"""文档解析与文本分块。

支持 PDF / Word(docx) / 纯文本。刻意**不使用 unstructured**：
它会把 detectron2、paddle 这类重依赖拉进来，个人项目上完全没必要；
pypdf + python-docx 加起来不到 1MB，覆盖考研/考公资料的全部常见格式。

分块策略采用「先按结构切段、再按长度合并」：
1. 先按段落切（`\\n\\n` 与中文特有的缩进），保留语义边界；
2. 段太长就按句子切（中文标点 + 英文句号），再按 chunk_size 合并；
3. 段与段之间保留 `overlap` 个字符的重叠，避免"答案被切在两块中间"导致检索不到。

页码保留：每个 chunk 都带 `page_no`，这是引用溯源（"文档名 + 第 N 页"）的前提。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

from app.core.exceptions import BizException
from app.core.logger import logger

#: 默认分块参数（与执行计划一致：chunk_size=500、overlap=50）
DEFAULT_CHUNK_SIZE = 500
DEFAULT_OVERLAP = 50

#: 支持的扩展名
SUPPORTED_SUFFIXES = {".pdf", ".docx", ".doc", ".txt", ".md"}


@dataclass
class PageText:
    """一页（或一个逻辑段）的文本及其来源页码。"""

    text: str
    page_no: int | None = None


@dataclass
class Chunk:
    """一个待向量化的分块。"""

    index: int
    content: str
    page_no: int | None = None
    char_count: int = 0
    metadata: dict = field(default_factory=dict)


# --------------------------------------------------------------------- 解析


def parse_document(file_path: str | Path) -> list[PageText]:
    """解析文档为「页文本」列表。

    Args:
        file_path: 本地文件路径。

    Returns:
        `list[PageText]`，PDF 按页、docx 按段落块、纯文本按整篇。

    Raises:
        BizException: 文件不存在或格式不支持。
    """
    path = Path(file_path)
    if not path.exists():
        raise BizException(code=4004, msg=f"文件不存在：{path}")
    if not path.is_file():
        raise BizException(code=4004, msg=f"不是文件：{path}")

    suffix = path.suffix.lower()
    if suffix not in SUPPORTED_SUFFIXES:
        raise BizException(
            code=4004,
            msg=f"不支持的文件类型 {suffix}，仅支持 {'/'.join(sorted(SUPPORTED_SUFFIXES))}",
        )

    if suffix == ".pdf":
        pages = _parse_pdf(path)
    elif suffix in {".docx", ".doc"}:
        pages = _parse_docx(path)
    else:
        pages = _parse_text(path)

    pages = [p for p in pages if p.text and p.text.strip()]
    logger.info(
        "文档解析完成: {} | 类型={} | 页/段数={} | 总字符={}",
        path.name,
        suffix,
        len(pages),
        sum(len(p.text) for p in pages),
    )
    return pages


def _parse_pdf(path: Path) -> list[PageText]:
    """用 pypdf 逐页抽取文本。"""
    try:
        from pypdf import PdfReader
    except Exception as exc:  # noqa: BLE001
        raise BizException(code=5001, msg=f"pypdf 不可用，无法解析 PDF：{exc!r}") from exc

    reader = PdfReader(str(path))
    if getattr(reader, "is_encrypted", False):
        # 有些资料带空密码加密，尝试解一次；失败才报错
        try:
            reader.decrypt("")
        except Exception as exc:  # noqa: BLE001
            raise BizException(code=4004, msg="PDF 已加密，无法解析") from exc

    pages: list[PageText] = []
    for page_index, page in enumerate(reader.pages, start=1):
        try:
            text = page.extract_text() or ""
        except Exception as exc:  # noqa: BLE001 - 单页失败不影响整篇
            logger.warning("PDF 第 {} 页解析失败，跳过: {!r}", page_index, exc)
            continue
        pages.append(PageText(text=_clean(text), page_no=page_index))
    return pages


def _parse_docx(path: Path) -> list[PageText]:
    """用 python-docx 抽取段落（docx 没有稳定的页码概念，page_no 置空）。"""
    try:
        import docx
    except Exception as exc:  # noqa: BLE001
        raise BizException(code=5001, msg=f"python-docx 不可用，无法解析 Word：{exc!r}") from exc

    document = docx.Document(str(path))
    blocks: list[str] = []

    for paragraph in document.paragraphs:
        text = _clean(paragraph.text)
        if text:
            blocks.append(text)

    # 表格里的内容也常是考点，一并抽出来
    for table in document.tables:
        for row in table.rows:
            cells = [_clean(cell.text) for cell in row.cells]
            line = " | ".join(c for c in cells if c)
            if line:
                blocks.append(line)

    # docx 没有页码，按"每 30 个段落算一个逻辑页"给一个粗略的定位号，
    # 让引用溯源至少能说出"大致位置"，而不是完全为空
    pages: list[PageText] = []
    for start in range(0, len(blocks), 30):
        group = blocks[start:start + 30]
        pages.append(PageText("\n".join(group), page_no=start // 30 + 1))
    return pages


def _parse_text(path: Path) -> list[PageText]:
    """纯文本：按空行分段，每 30 段算一个逻辑页。"""
    raw = _read_text_with_fallback(path)
    blocks = [b for b in (_clean(b) for b in re.split(r"\n\s*\n", raw)) if b]

    pages: list[PageText] = []
    for start in range(0, len(blocks), 30):
        group = blocks[start:start + 30]
        pages.append(PageText("\n".join(group), page_no=start // 30 + 1))
    if not pages and raw.strip():
        pages = [PageText(_clean(raw), page_no=1)]
    return pages


def _read_text_with_fallback(path: Path) -> str:
    """按 UTF-8 → GBK → 忽略错误 的顺序读文本。

    国内的资料 txt 有很多是 GBK 编码，直接 UTF-8 读会抛异常或全是乱码。
    """
    data = path.read_bytes()
    for encoding in ("utf-8", "utf-8-sig", "gbk", "gb18030"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    logger.warning("文本文件 {} 编码无法识别，按 UTF-8 忽略错误解码", path.name)
    return data.decode("utf-8", errors="ignore")


def _clean(text: str) -> str:
    """清洗文本：统一空白、去掉页眉页脚类的孤立短行噪声。"""
    if not text:
        return ""
    # 全角空格、制表符统一成普通空格；连续空行压成一个
    text = text.replace("\u3000", " ").replace("\xa0", " ").replace("\t", " ")
    text = re.sub(r"[ ]{2,}", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    # 去掉行内多余空格（PDF 抽取常见 "定 积 分" 这种断字）
    text = re.sub(r"(?<=[\u4e00-\u9fff]) (?=[\u4e00-\u9fff])", "", text)
    return text.strip()


# --------------------------------------------------------------------- 分块


def split_into_chunks(
    pages: list[PageText],
    chunk_size: int = DEFAULT_CHUNK_SIZE,
    overlap: int = DEFAULT_OVERLAP,
) -> list[Chunk]:
    """把页文本切成带重叠的分块。

    Args:
        pages: 解析结果。
        chunk_size: 目标分块字符数。
        overlap: 相邻分块的重叠字符数（必须小于 chunk_size）。

    Returns:
        `list[Chunk]`，`index` 从 0 连续递增。
    """
    if chunk_size <= 0:
        raise BizException(code=4000, msg="chunk_size 必须大于 0")
    overlap = max(0, min(overlap, chunk_size // 2))  # 重叠不能超过一半，否则会死循环

    chunks: list[Chunk] = []
    for page in pages:
        for piece in _split_page(page.text, chunk_size, overlap):
            chunks.append(
                Chunk(
                    index=len(chunks),
                    content=piece,
                    page_no=page.page_no,
                    char_count=len(piece),
                )
            )

    # 丢掉过短的碎片：可能是页眉、页码、"目录"这种噪声，
    # 保留它们只会污染向量库并让检索结果全是无意义的片段
    min_length = max(20, chunk_size // 10)
    before = len(chunks)
    chunks = [c for c in chunks if c.char_count >= min_length]
    for i, chunk in enumerate(chunks):
        chunk.index = i

    if before != len(chunks):
        logger.debug("分块后过滤掉 {} 个过短碎片（< {} 字符）", before - len(chunks), min_length)

    logger.info(
        "分块完成: {} 个 chunk | 平均 {} 字符 | chunk_size={} overlap={}",
        len(chunks),
        (sum(c.char_count for c in chunks) // len(chunks)) if chunks else 0,
        chunk_size,
        overlap,
    )
    return chunks


def _split_page(text: str, chunk_size: int, overlap: int) -> list[str]:
    """先把一页切成"段"，再按长度贪婪合并成不超过 chunk_size 的块。"""
    if not text.strip():
        return []

    paragraphs = [p.strip() for p in re.split(r"\n\s*\n|\n", text) if p.strip()]

    # 段太长（常见于 PDF：整页被抽成一个段）就按句子再切
    units: list[str] = []
    for para in paragraphs:
        if len(para) <= chunk_size:
            units.append(para)
        else:
            units.extend(_split_sentences(para, chunk_size))

    pieces: list[str] = []
    current = ""
    for unit in units:
        if not current:
            current = unit
            continue
        if len(current) + 1 + len(unit) <= chunk_size:
            current = f"{current}\n{unit}"
        else:
            pieces.append(current)
            # 带重叠：把上一块的尾部接到新块前面
            tail = current[-overlap:] if overlap else ""
            current = f"{tail}\n{unit}".strip() if tail else unit

    if current:
        pieces.append(current)
    return pieces


def _split_sentences(text: str, chunk_size: int) -> list[str]:
    """按句子切分超长段落（兼容中英文标点）。"""
    parts = re.split(r"(?<=[。！？；!?;])|(?<=\.)\s+", text)
    sentences = [p.strip() for p in parts if p and p.strip()]

    # 句子本身还超长（比如没有标点的表格行）就只能硬切
    result: list[str] = []
    for sentence in sentences:
        if len(sentence) <= chunk_size:
            result.append(sentence)
        else:
            for i in range(0, len(sentence), chunk_size):
                result.append(sentence[i:i + chunk_size])
    return result


__all__ = ["PageText", "Chunk", "parse_document", "split_into_chunks",
           "DEFAULT_CHUNK_SIZE", "DEFAULT_OVERLAP", "SUPPORTED_SUFFIXES"]
