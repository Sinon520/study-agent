"""本地模型自检脚本：验证 BGE-M3 与 BGE-Reranker-v2-m3 能真实加载并出结果。

用法（在 server-ai 目录下）::

    .\\.venv\\Scripts\\python.exe scripts\\check_models.py            # 默认：未就绪则跳过，exit 0
    .\\.venv\\Scripts\\python.exe scripts\\check_models.py --strict   # 严格模式：任一项失败 exit 1

两种模式的区别：
- **默认（--skip-missing）**：模型还没下完时打印「未就绪，跳过」并 `exit 0`。
  这样在下模型期间把它挂进流水线不会误报失败，适合当「随时可跑的验收脚本」。
- **--strict**：任何一项不通过就 `exit 1`，适合作为发布前的强制门禁。

检查项：
1. `BGEM3FlagModel('BAAI/bge-m3', use_fp16=False)` 编码一句中文，打印向量维度（应为 1024）；
2. `FlagLLMReranker('BAAI/bge-reranker-v2-m3', use_fp16=False)` 对 1 个 query + 3 个 passage
   打分，打印分数列表（该类加载失败时自动回退 FlagReranker，见下方注释）。

踩坑记录：Windows 控制台默认 GBK，直接 print emoji 会抛 `UnicodeEncodeError`
中断脚本，因此这里强制把 stdout/stderr 重配为 UTF-8，并只用 ASCII 状态标记。
"""

from __future__ import annotations

import argparse
import os
import sys
import time
from pathlib import Path

# ---- 控制台编码：必须在任何输出之前处理 ----
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(  # type: ignore[union-attr]
            encoding="utf-8", errors="replace", line_buffering=True
        )
    except Exception:  # pragma: no cover
        pass

# 允许直接 `python scripts/check_models.py` 时导入 app 包
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
os.environ.setdefault("HF_HUB_DISABLE_XET", "1")
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "60")

EMBEDDING_MODEL = "BAAI/bge-m3"
RERANKER_MODEL = "BAAI/bge-reranker-v2-m3"

QUERY = "什么是向量数据库？"
PASSAGES = [
    "Milvus 是一款开源的向量数据库，支持十亿级向量检索。",
    "今天北京天气晴朗，气温 25 摄氏度。",
    "BGE-M3 支持稠密、稀疏与多向量三种检索方式。",
]


def preflight(model_name: str) -> bool:
    """打印本地缓存预检结果，返回是否就绪（不联网）。"""
    try:
        from app.services.model_cache import missing_report

        report = missing_report(model_name)
    except Exception as exc:  # noqa: BLE001
        print(f"  [WARN] 预检不可用: {type(exc).__name__}: {exc}")
        return True  # 预检不可用时不做拦截，交给真实加载去判断

    if report["ready"]:
        print(f"  [OK] 本地缓存完整 | 权重 = {report['weight']}")
        return True
    print(f"  [NOT READY] 缺少: {'、'.join(report['missing'])}")
    return False


def check_embedding() -> bool:
    """检查嵌入模型，返回是否通过。"""
    print("=" * 78)
    print(f"[1/2] 嵌入模型 {EMBEDDING_MODEL} (use_fp16=False)")
    if not preflight(EMBEDDING_MODEL):
        return False

    started = time.perf_counter()
    try:
        from FlagEmbedding import BGEM3FlagModel
    except Exception as exc:  # noqa: BLE001
        print(f"      [FAIL] 导入 FlagEmbedding 失败: {type(exc).__name__}: {exc}")
        return False

    try:
        model = BGEM3FlagModel(EMBEDDING_MODEL, use_fp16=False)
    except Exception as exc:  # noqa: BLE001
        print(f"      [FAIL] 加载失败: {type(exc).__name__}: {exc}")
        return False
    print(f"      加载耗时: {time.perf_counter() - started:.2f}s")

    try:
        out = model.encode(
            ["你好，这是一句用于验证嵌入模型的中文。"],
            return_dense=True,
            return_sparse=False,
            return_colbert_vecs=False,
        )
        dense = out["dense_vecs"]
        dim = len(dense[0])
        print(f"      [OK] 向量维度 = {dim} (期望 1024)")
        print(f"      前 5 维: {[round(float(x), 6) for x in dense[0][:5]]}")
        return dim == 1024
    except Exception as exc:  # noqa: BLE001
        print(f"      [FAIL] 编码失败: {type(exc).__name__}: {exc}")
        return False


def check_reranker() -> bool:
    """检查重排模型，返回是否通过。

    `BAAI/bge-reranker-v2-m3` 是 XLM-RoBERTa 结构的 cross-encoder，
    需求指定用 FlagLLMReranker，若该类加载失败则自动回退 FlagReranker。
    """
    print("=" * 78)
    print(f"[2/2] 重排模型 {RERANKER_MODEL} (use_fp16=False)")
    if not preflight(RERANKER_MODEL):
        return False

    try:
        import FlagEmbedding  # noqa: F401
    except Exception as exc:  # noqa: BLE001
        print(f"      [FAIL] 导入 FlagEmbedding 失败: {type(exc).__name__}: {exc}")
        return False

    model = None
    kind = ""
    started = time.perf_counter()
    try:
        from FlagEmbedding import FlagLLMReranker

        print("      尝试 FlagLLMReranker ...")
        model = FlagLLMReranker(RERANKER_MODEL, use_fp16=False)
        kind = "llm"
    except Exception as exc:  # noqa: BLE001
        print(f"      FlagLLMReranker 失败（{type(exc).__name__}: {exc}），回退 FlagReranker ...")

    if model is None:
        try:
            from FlagEmbedding import FlagReranker

            model = FlagReranker(RERANKER_MODEL, use_fp16=False)
            kind = "cross"
        except Exception as exc:  # noqa: BLE001
            print(f"      [FAIL] 加载失败: {type(exc).__name__}: {exc}")
            return False

    print(f"      加载耗时: {time.perf_counter() - started:.2f}s, 实现类={kind}")

    try:
        if kind == "llm":
            scores = model.compute_score(QUERY, PASSAGES)
        else:
            scores = model.compute_score([[QUERY, p] for p in PASSAGES])
        if hasattr(scores, "tolist"):
            scores = scores.tolist()
        if isinstance(scores, (int, float)):
            scores = [scores]
        scores = [round(float(s), 6) for s in scores]
        print(f"      [OK] query = {QUERY!r}")
        for i, passage in enumerate(PASSAGES):
            print(f"      passage[{i}] score={scores[i]}  {passage}")
        print(f"      分数列表 = {scores}")
        return len(scores) == len(PASSAGES)
    except Exception as exc:  # noqa: BLE001
        print(f"      [FAIL] 打分失败: {type(exc).__name__}: {exc}")
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description="本地模型自检")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="严格模式：任一项不通过则 exit 1（默认未就绪时跳过并 exit 0）",
    )
    parser.add_argument(
        "--skip-missing",
        action="store_true",
        default=True,
        help="模型未就绪时跳过并 exit 0（默认行为）",
    )
    args = parser.parse_args()

    print("=" * 78)
    print(f"HF_ENDPOINT = {os.environ.get('HF_ENDPOINT')}")
    print(f"Python      = {sys.version.split()[0]}")
    print(f"模式        = {'严格(--strict)' if args.strict else '宽松(--skip-missing，默认)'}")
    print("=" * 78)

    ok_embed = check_embedding()
    ok_rerank = check_reranker()
    all_ok = ok_embed and ok_rerank

    print("=" * 78)
    if all_ok:
        print("结果: 嵌入=通过  重排=通过")
    elif args.strict:
        print(f"结果: 嵌入={'通过' if ok_embed else '未通过'}  重排={'通过' if ok_rerank else '未通过'}")
        print("[FAIL] 严格模式：存在未通过的检查项")
    else:
        print("结果: 模型未就绪，跳过（宽松模式，exit 0）")
        print("      提示：先运行 scripts/download_models.py 下载模型；加 --strict 可强制失败")
    print("=" * 78)

    if args.strict:
        return 0 if all_ok else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
