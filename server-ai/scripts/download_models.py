"""本地开源模型下载脚本（**逐文件**断点续传，可无人值守反复重跑）。

用法（在 server-ai 目录下，使用 venv 的 python）::

    $env:HF_ENDPOINT = "https://hf-mirror.com"
    .\\.venv\\Scripts\\python.exe scripts\\download_models.py
    .\\.venv\\Scripts\\python.exe scripts\\download_models.py --only bge-m3
    .\\.venv\\Scripts\\python.exe scripts\\download_models.py --include-onnx

设计要点（为什么不用裸的 snapshot_download）：
- 先列仓库文件清单（含大小），**逐文件**调 `hf_hub_download`：
  已下载的文件秒过（断点续传），缺失的文件单独重试（最多 --retries 次），
  **单个文件失败不影响其它文件**；
- 每行打印「文件序号 / 总体百分比 / 已下载字节 / 文件名」，便于无人值守时看日志判断进度；
- 结束时打印**逐个文件的本地路径与大小**作为验收依据，并汇总缺失清单；
- 默认跳过 `onnx/`（bge-m3 的 onnx 权重是同一份模型的重复副本，多占 ~2.2GB 带宽）。

踩坑记录（重要）：
1. hf-mirror.com 对仓库里的 `imgs/.DS_Store` 返回 **403 Forbidden**，
   会让整个 snapshot 失败 → 这里直接跳过 `.DS_Store`；
2. 大文件请求会 302 跳到 `cas-bridge.xethub.hf.co`（Xet），
   而 `hf-xet` 客户端直连 `cas-server.xethub.hf.co` 会返回 **401 Unauthorized**，
   报 "File reconstruction error: CAS Client Error 401" → 必须 `HF_HUB_DISABLE_XET=1`；
3. 本机到镜像带宽有限（实测约 0.2 MB/s 量级），2.2GB 的权重需要数小时，
   因此本脚本设计为**可随时中断、随时重跑**；
4. Windows 控制台默认 GBK，print emoji 会抛 `UnicodeEncodeError` 并中断重试循环
   → 强制 stdout/stderr 为 UTF-8，且只用 ASCII 状态标记。
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

# ---- 控制台编码：必须在任何输出之前处理，否则 GBK 控制台会直接崩 ----
# line_buffering=True 很关键：日志经 Tee-Object 管道重定向时默认是块缓冲，
# 不加这个参数时进度要等缓冲区满才落盘，无人值守时看不到实时进度。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(  # type: ignore[union-attr]
            encoding="utf-8", errors="replace", line_buffering=True
        )
    except Exception:  # pragma: no cover - 非标准流
        pass

# ---- 必须在导入 huggingface_hub 之前设置镜像，否则会走 huggingface.co ----
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
os.environ.setdefault("HF_HUB_ENABLE_HF_TRANSFER", "0")
# 关键：禁用 Xet，否则会绕开镜像直连 cas-server.xethub.hf.co 并 401
os.environ.setdefault("HF_HUB_DISABLE_XET", "1")
# 本脚本就是要下载，绝不能进离线模式
os.environ.pop("HF_HUB_OFFLINE", None)
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "60")
os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")

MODELS: dict[str, str] = {
    "bge-m3": "BAAI/bge-m3",
    "bge-reranker-v2-m3": "BAAI/bge-reranker-v2-m3",
}

#: 直接跳过的文件/目录（.DS_Store 会让镜像返回 403）
SKIP_SUFFIXES = (".DS_Store",)
#: 默认跳过的目录前缀（onnx 权重是重复副本，很占带宽）
SKIP_PREFIXES = ("imgs/",)


def _fmt(size: float | None) -> str:
    if size is None:
        return "?"
    value = float(size)
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024 or unit == "GB":
            return f"{value:.1f} {unit}"
        value /= 1024
    return f"{value:.1f} GB"


def _should_skip(path: str, include_onnx: bool) -> bool:
    if path.endswith(SKIP_SUFFIXES):
        return True
    if path.startswith(SKIP_PREFIXES):
        return True
    if not include_onnx and path.startswith("onnx/"):
        return True
    return False


def list_repo_files(repo_id: str) -> list[tuple[str, int | None]]:
    """列出仓库文件与大小；失败时退化为仅有文件名（大小为 None）。"""
    from huggingface_hub import HfApi

    api = HfApi()
    try:
        entries = list(api.list_repo_tree(repo_id, recursive=True))
        out: list[tuple[str, int | None]] = []
        for item in entries:
            # 只保留 RepoFile；RepoFolder（如 1_Pooling / imgs / onnx）会被当作
            # 文件去下载并 404，白白重试 3 次污染日志
            if type(item).__name__ != "RepoFile":
                continue
            path = getattr(item, "path", None)
            if not path:
                continue
            size = getattr(item, "size", None)
            out.append((path, int(size) if size is not None else None))
        if out:
            return out
    except Exception as exc:  # noqa: BLE001
        print(f"[WARN] list_repo_tree 失败（{type(exc).__name__}: {exc}），改用 list_repo_files")

    try:
        files = api.list_repo_files(repo_id)
        return [(f, None) for f in files]
    except Exception as exc:  # noqa: BLE001
        print(f"[FAIL] 无法列出 {repo_id} 的文件清单: {type(exc).__name__}: {exc}")
        return []


def download_one_file(
    repo_id: str, filename: str, retries: int, cache_dir: str | None, timeout: int
) -> str | None:
    """下载单个文件，失败重试；返回本地路径或 None。"""
    from huggingface_hub import hf_hub_download

    os.environ["HF_HUB_DOWNLOAD_TIMEOUT"] = str(timeout)
    for attempt in range(1, retries + 1):
        try:
            return hf_hub_download(
                repo_id=repo_id,
                filename=filename,
                cache_dir=cache_dir,
                force_download=False,
            )
        except Exception as exc:  # noqa: BLE001
            msg = f"{type(exc).__name__}: {exc}"
            if attempt < retries:
                print(f"      [RETRY {attempt}/{retries - 1}] {filename}: {msg[:160]}")
                time.sleep(3 * attempt)
            else:
                print(f"      [FAIL] {filename}: {msg[:200]}")
    return None


def download_repo(
    name: str,
    repo_id: str,
    *,
    retries: int,
    cache_dir: str | None,
    include_onnx: bool,
    timeout: int,
) -> dict:
    """下载单个仓库，返回统计信息。"""
    print("=" * 78)
    print(f"仓库: {repo_id}  ({name})")
    files = [
        (p, s) for (p, s) in list_repo_files(repo_id) if not _should_skip(p, include_onnx)
    ]
    if not files:
        print("[FAIL] 文件清单为空")
        return {"repo": repo_id, "files": [], "failed": [], "ok": False}

    total_bytes = sum(s for _, s in files if s)
    total_files = len(files)
    print(f"待下载文件数: {total_files}   总体积(已知部分): {_fmt(total_bytes)}")

    done: list[tuple[str, str, int]] = []
    failed: list[str] = []
    done_bytes = 0
    started_all = time.perf_counter()

    for idx, (path, size) in enumerate(files, start=1):
        pct = (done_bytes / total_bytes * 100) if total_bytes else (idx - 1) / total_files * 100
        print(
            f"[{idx:>3}/{total_files}] 总体 {pct:5.1f}% | "
            f"已下载 {_fmt(done_bytes)}/{_fmt(total_bytes)} | {path}"
        )
        t0 = time.perf_counter()
        local = download_one_file(repo_id, path, retries, cache_dir, timeout)
        if local:
            try:
                real = Path(local).stat().st_size
            except OSError:
                real = size or 0
            done_bytes += real
            done.append((path, local, real))
            print(f"      [OK] {_fmt(real)}  用时 {time.perf_counter() - t0:.1f}s")
        else:
            failed.append(path)

    # ---------------- 逐文件验收清单 ----------------
    print("-" * 78)
    print(f"[{repo_id}] 已就绪文件清单（本地路径 + 大小）：")
    for path, local, real in done:
        print(f"  {_fmt(real):>10}  {local}")
    if failed:
        print(f"[{repo_id}] 缺失/失败文件（{len(failed)} 个，可重跑本脚本续传）：")
        for path in failed:
            print(f"  MISSING    {path}")
    print(
        f"[{repo_id}] 结果: {len(done)}/{total_files} 个文件就绪，"
        f"共 {_fmt(done_bytes)}，总耗时 {time.perf_counter() - started_all:.1f}s"
    )
    print("=" * 78)
    return {"repo": repo_id, "files": done, "failed": failed, "ok": not failed}


# ==========================================================================
# ModelScope（魔搭）后端 —— 国内强烈推荐，默认下载源
# --------------------------------------------------------------------------
# 为什么加这个：hf-mirror.com 实测只有 **0.167 MB/s** 且会挂死，
# 而 ModelScope 实测 **28.6 MB/s（快约 170 倍）**，2.2GB 权重 1~2 分钟就下完。
# 两个模型在 ModelScope 上的字节数与 HF 完全一致
# （bge-m3 pytorch_model.bin = 2,271,145,830 B，逐一核对过）。
# 下载到**本地目录** server-ai/models/<name>/（该目录已在 .gitignore 中），
# 由 app/core/config.py 自动解析并优先使用本地目录，.env 无需任何改动。
# ==========================================================================

MODELSCOPE_FILE_LIST = (
    "https://modelscope.cn/api/v1/models/{repo}/repo/files?Revision=master&Recursive=true"
)
MODELSCOPE_FILE_URL = (
    "https://modelscope.cn/api/v1/models/{repo}/repo?Revision=master&FilePath={path}"
)

#: 跳过图片与重复的 onnx 权重（onnx/model.onnx_data 是与 pytorch_model.bin 等大的重复副本）
MS_SKIP_PREFIXES = ("onnx/", "imgs/", "assets/")
MS_SKIP_SUFFIXES = (
    ".jpg", ".jpeg", ".png", ".webp", ".gif", ".gitattributes", ".ds_store",
)


def _ms_should_skip(path: str) -> bool:
    if path.startswith(MS_SKIP_PREFIXES):
        return True
    return path.lower().endswith(MS_SKIP_SUFFIXES)


def ms_list_files(repo_id: str) -> list[tuple[str, int]]:
    """列出 ModelScope 仓库文件与大小（跳过图片/onnx）。"""
    import urllib.request

    url = MODELSCOPE_FILE_LIST.format(repo=repo_id)
    try:
        with urllib.request.urlopen(url, timeout=60) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except Exception as exc:  # noqa: BLE001
        print(f"[FAIL] 无法获取 {repo_id} 文件清单: {type(exc).__name__}: {exc}")
        return []

    files = ((payload.get("Data") or {}).get("Files")) or []
    out: list[tuple[str, int]] = []
    for item in files:
        if item.get("Type") != "blob":
            continue
        path = item.get("Path")
        if not path or _ms_should_skip(path):
            continue
        out.append((path, int(item.get("Size") or 0)))
    return out


def ms_download_file(
    repo_id: str, path: str, dest_root: Path, expected_size: int, retries: int, timeout: int
) -> Path | None:
    """下载单个文件到本地目录，**严格校验字节数**后才落地。

    踩坑记录（重要）：早先的版本用 HTTP Range 做 append 断点续传，
    结果 ModelScope 的 redirect/CDN 在重试时语义不一致，
    导致 model.safetensors 被追加成了 2,382,220,908 字节
    （比正确的 2,271,071,852 多出整整 106 MB），
    transformers 加载时报 `SafetensorError: incomplete metadata, file not fully covered`。
    现在改为：每次尝试都从零写入 `.part`，**校验字节数完全一致**才 rename 成正式文件名；
    不一致就删掉重来。代价是失去断点续传，但换取「绝不产出损坏文件」——
    在 20+ MB/s 下重来一次只要一分多钟，完全值得。
    """
    import requests

    target = dest_root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    part = target.with_name(target.name + ".part")
    url = MODELSCOPE_FILE_URL.format(repo=repo_id, path=path)

    for attempt in range(1, retries + 1):
        if part.exists():
            try:
                part.unlink()
            except OSError:
                pass
        try:
            # 绝不复用已存在的字节（避免 append 语义造成的重复/截断）
            with requests.get(url, stream=True, timeout=timeout) as resp:
                if resp.status_code != 200:
                    raise RuntimeError(f"HTTP {resp.status_code}")
                written = 0
                with open(part, "wb") as fh:
                    for chunk in resp.iter_content(chunk_size=1 << 20):
                        if chunk:
                            fh.write(chunk)
                            written += len(chunk)

            actual = part.stat().st_size
            if expected_size and actual != expected_size:
                print(
                    f"      [SIZE MISMATCH] 第 {attempt}/{retries} 次: 实际 {actual:,} B "
                    f"!= 期望 {expected_size:,} B（差 {actual - expected_size:+,} B），丢弃重下"
                )
                try:
                    part.unlink()
                except OSError:
                    pass
                continue

            part.replace(target)  # 校验通过才原子改名
            return target
        except Exception as exc:  # noqa: BLE001
            if attempt < retries:
                print(f"      [RETRY {attempt}/{retries - 1}] {path}: {type(exc).__name__}: {exc}")
                time.sleep(2 * attempt)
            else:
                print(f"      [FAIL] {path}: {type(exc).__name__}: {exc}")
    return None


def download_repo_modelscope(name: str, repo_id: str, dest_root: Path, args) -> dict:
    """从 ModelScope 下载整个仓库到本地目录，返回统计信息。"""
    print("=" * 78)
    print(f"仓库: {repo_id}  ({name})  [来源: ModelScope 魔搭]")
    files = ms_list_files(repo_id)
    if not files:
        return {"repo": repo_id, "files": [], "failed": [], "ok": False}

    total_bytes = sum(s for _, s in files)
    total_files = len(files)
    target_dir = dest_root / name
    print(f"待下载文件数: {total_files}   总体积: {_fmt(total_bytes)}")
    print(f"目标目录: {target_dir}")

    done: list[tuple[str, str, int]] = []
    failed: list[str] = []
    done_bytes = 0
    started_all = time.perf_counter()

    for idx, (path, size) in enumerate(files, start=1):
        pct = (done_bytes / total_bytes * 100) if total_bytes else 0.0
        pre = target_dir / path
        complete = pre.exists() and (size == 0 or pre.stat().st_size == size)
        print(
            f"[{idx:>3}/{total_files}] 总体 {pct:5.1f}% | "
            f"已下载 {_fmt(done_bytes)}/{_fmt(total_bytes)} | {path}"
            f"{'  [已存在，跳过]' if complete else ''}"
        )
        if complete:
            done_bytes += pre.stat().st_size
            done.append((path, str(pre), pre.stat().st_size))
            continue

        t0 = time.perf_counter()
        local = ms_download_file(repo_id, path, target_dir, size, args.retries, args.timeout)
        if local:
            real = local.stat().st_size
            done_bytes += real
            done.append((path, str(local), real))
            speed = real / max(time.perf_counter() - t0, 1e-6) / 1048576
            print(
                f"      [OK] {_fmt(real)}  用时 {time.perf_counter() - t0:.1f}s ({speed:.1f} MB/s)"
            )
        else:
            failed.append(path)

    print("-" * 78)
    print(f"[{repo_id}] 已就绪文件清单（本地路径 + 大小）：")
    for path, local, real in done:
        print(f"  {_fmt(real):>10}  {local}")
    if failed:
        print(f"[{repo_id}] 缺失/失败文件（{len(failed)} 个，可重跑续传）：")
        for path in failed:
            print(f"  MISSING    {path}")
    print(
        f"[{repo_id}] 结果: {len(done)}/{total_files} 个文件就绪，"
        f"共 {_fmt(done_bytes)}，总耗时 {time.perf_counter() - started_all:.1f}s"
    )
    print("=" * 78)
    return {"repo": repo_id, "files": done, "failed": failed, "ok": not failed}


def cache_bytes(repo_ids: list[str]) -> int:
    """统计目标仓库在 HF 缓存里已占用的总字节数（含 .incomplete 分片）。"""
    try:
        from huggingface_hub.constants import HF_HUB_CACHE

        base = Path(HF_HUB_CACHE)
    except Exception:  # noqa: BLE001
        base = Path.home() / ".cache" / "huggingface" / "hub"

    total = 0
    for repo_id in repo_ids:
        repo_dir = base / ("models--" + repo_id.replace("/", "--"))
        if not repo_dir.exists():
            continue
        for item in repo_dir.rglob("*"):
            if item.is_file():
                try:
                    total += item.stat().st_size
                except OSError:
                    pass
    return total


def supervise(args, targets: dict[str, str]) -> int:
    """看门狗：守护下载子进程，检测到「长时间零字节增长」就杀掉并重启（断点续传）。

    为什么要这个：实测 hf-mirror 在下载 2.2GB 大文件时会**挂死**——
    socket 不关也不给数据，`HF_HUB_DOWNLOAD_TIMEOUT` 也没能触发，
    日志停在 pytorch_model.bin 一行不动，无人值守会永远卡住。
    看门狗把「挂死」变成「重启后续传」，这样挂机一整晚也能自己爬完。
    """
    repo_ids = list(targets.values())
    restarts = 0
    while True:
        cmd = [
            sys.executable,
            str(Path(__file__).resolve()),
            "--_worker",
            "--retries",
            str(args.retries),
            "--timeout",
            str(args.timeout),
        ]
        if args.only:
            for name in args.only:
                cmd += ["--only", name]
        if args.cache_dir:
            cmd += ["--cache-dir", args.cache_dir]
        if args.include_onnx:
            cmd += ["--include-onnx"]

        print("=" * 78)
        print(f"[WATCHDOG] 启动下载子进程（第 {restarts + 1} 次），PID 见下")
        print(f"[WATCHDOG] 停滞判定阈值 = {args.stall_timeout}s，轮询间隔 = {args.poll_interval}s")
        print(f"[WATCHDOG] 命令: {' '.join(cmd)}")
        print("=" * 78, flush=True)

        child = subprocess.Popen(cmd)  # 继承 stdout/stderr，直接写进同一份日志
        print(f"[WATCHDOG] 子进程 PID = {child.pid}", flush=True)

        last_bytes = cache_bytes(repo_ids)
        last_change = time.time()
        while child.poll() is None:
            time.sleep(args.poll_interval)
            current = cache_bytes(repo_ids)
            if current > last_bytes:
                last_bytes = current
                last_change = time.time()
                continue
            idle = time.time() - last_change
            if idle >= args.stall_timeout:
                print(
                    f"[WATCHDOG] 检测到停滞：已 {idle:.0f}s 无字节增长"
                    f"（当前缓存 {last_bytes / 1048576:.1f} MB）-> 杀掉子进程并续传",
                    flush=True,
                )
                child.kill()
                try:
                    child.wait(timeout=30)
                except Exception:  # noqa: BLE001
                    pass
                break

        returncode = child.wait()
        if returncode == 0:
            print("[WATCHDOG] 子进程正常结束，全部文件就绪", flush=True)
            return 0

        restarts += 1
        if restarts > args.max_restarts:
            print(
                f"[WATCHDOG] 已达最大重启次数 {args.max_restarts}，放弃。"
                "可稍后重跑本脚本继续断点续传。",
                flush=True,
            )
            return 1
        print(f"[WATCHDOG] 子进程退出码 {returncode}，5 秒后第 {restarts + 1} 次重启 ...", flush=True)
        time.sleep(5)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="逐文件下载 BGE-M3 / BGE-Reranker-v2-m3（可重复执行、断点续传）"
    )
    parser.add_argument(
        "--only",
        choices=list(MODELS.keys()),
        action="append",
        help="只下载指定模型（可重复指定），默认全部",
    )
    parser.add_argument("--retries", type=int, default=3, help="单个文件最大尝试次数")
    parser.add_argument("--cache-dir", default=None, help="自定义缓存目录，默认 HF 默认缓存")
    parser.add_argument("--timeout", type=int, default=60, help="单请求超时秒数")
    parser.add_argument(
        "--include-onnx", action="store_true", help="同时下载 onnx/ 目录（重复权重，默认跳过）"
    )
    parser.add_argument(
        "--source",
        choices=("modelscope", "hf"),
        default="modelscope",
        help="下载源：modelscope=魔搭（国内快，默认，实测 28.6MB/s）；hf=hf-mirror（慢且会挂死）",
    )
    parser.add_argument(
        "--dest",
        default=None,
        help="ModelScope 源的目标目录，默认 server-ai/models",
    )
    parser.add_argument(
        "--supervise",
        action="store_true",
        help="开启看门狗：下载进程挂死时自动杀掉并续传（仅 hf 源需要）",
    )
    parser.add_argument(
        "--stall-timeout", type=int, default=180, help="看门狗判定停滞的秒数，默认 180"
    )
    parser.add_argument("--poll-interval", type=int, default=20, help="看门狗轮询间隔秒数")
    parser.add_argument("--max-restarts", type=int, default=50, help="看门狗最大重启次数")
    parser.add_argument("--_worker", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()

    targets = {n: MODELS[n] for n in args.only} if args.only else dict(MODELS)

    if args.supervise and not args._worker and args.source == "hf":
        return supervise(args, targets)

    # ---------------- ModelScope 源（默认） ----------------
    if args.source == "modelscope":
        dest_root = Path(args.dest) if args.dest else Path(__file__).resolve().parents[1] / "models"
        print("=" * 78)
        print("下载源             = ModelScope 魔搭（国内镜像，实测 28.6 MB/s）")
        print(f"待下载模型         = {list(targets.values())}")
        print(f"目标目录           = {dest_root}")
        print(f"单文件重试次数      = {args.retries}   超时 = {args.timeout}s")
        print(f"开始时间           = {time.strftime('%Y-%m-%d %H:%M:%S')}")
        print("=" * 78, flush=True)

        results = []
        overall = time.perf_counter()
        for name, repo_id in targets.items():
            results.append(download_repo_modelscope(name, repo_id, dest_root, args))

        print("=" * 78)
        print(f"全部结束，总耗时 {time.perf_counter() - overall:.1f}s")
        all_ok = True
        for res in results:
            status = "完整" if res["ok"] else f"未完成（缺 {len(res['failed'])} 个文件）"
            total = sum(s for _, _, s in res["files"])
            print(f"  {res['repo']:<28} {status:<24} 已就绪 {len(res['files'])} 个文件 / {_fmt(total)}")
            all_ok = all_ok and res["ok"]
        print("=" * 78)
        return 0 if all_ok else 1

    # ---------------- HF 源（hf-mirror，慢） ----------------
    print("=" * 78)
    print(f"HF_ENDPOINT        = {os.environ.get('HF_ENDPOINT')}")
    print(f"HF_HUB_DISABLE_XET = {os.environ.get('HF_HUB_DISABLE_XET')}")
    print(f"待下载模型         = {list(targets.values())}")
    print(f"缓存目录           = {args.cache_dir or '(HF 默认缓存)'}")
    print(f"单文件重试次数      = {args.retries}   跳过 onnx = {not args.include_onnx}")
    print(f"开始时间           = {time.strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 78, flush=True)

    results = []
    overall = time.perf_counter()
    for name, repo_id in targets.items():
        results.append(
            download_repo(
                name,
                repo_id,
                retries=args.retries,
                cache_dir=args.cache_dir,
                include_onnx=args.include_onnx,
                timeout=args.timeout,
            )
        )

    print("=" * 78)
    print(f"全部结束，总耗时 {time.perf_counter() - overall:.1f}s")
    all_ok = True
    for res in results:
        status = "完整" if res["ok"] else f"未完成（缺 {len(res['failed'])} 个文件）"
        total = sum(s for _, _, s in res["files"])
        print(f"  {res['repo']:<28} {status:<28} 已就绪 {len(res['files'])} 个文件 / {_fmt(total)}")
        all_ok = all_ok and res["ok"]
    print("=" * 78)
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
