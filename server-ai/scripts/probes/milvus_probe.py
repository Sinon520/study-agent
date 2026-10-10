"""Milvus Lite 检索连通性自检（不需要启动 AI 服务）。

为什么单独一个脚本：Milvus Lite 是**单进程独占**的本地文件库
（同一数据文件只能被一个进程持有锁），所以没法在服务运行时用另一个进程去查。
排查检索问题时必须先停掉服务再跑这个脚本。

用法：
    # 先停掉 8000 端口的 AI 服务
    .\.venv\Scripts\python.exe scripts/probes/milvus_probe.py
"""

from __future__ import annotations

import os
import sys
import traceback
from pathlib import Path

# Windows 控制台默认 GBK，打印数学符号（如下标 ₀）会抛 UnicodeEncodeError
# 直接中断脚本 —— 详见 docs/踩坑记录.md 里"emoji/特殊字符杀脚本"那条
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
    except Exception:  # noqa: BLE001
        pass

# 必须在 import pymilvus 之前把 MILVUS_URI 设成合法 URI 形态
# （pymilvus 在 import 期就会解析它，详见 app/core/config.py 的说明）
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from app.core.config import BASE_DIR, get_settings  # noqa: E402

os.environ.setdefault("MILVUS_URI", "http://127.0.0.1:19530")

from pymilvus import DataType, MilvusClient  # noqa: E402


def main() -> int:
    settings = get_settings()
    uri = str((BASE_DIR / settings.milvus_uri).resolve()) if not settings.milvus_uri.startswith(
        ("http://", "https://", "tcp://")) else settings.milvus_uri
    name = settings.milvus_collection

    print("=" * 68)
    print("Milvus Lite 检索自检")
    print(f"  uri        = {uri}")
    print(f"  collection = {name}")
    print("=" * 68)

    try:
        client = MilvusClient(uri=uri)
    except Exception:
        print("[FAIL] 打不开数据文件（大概率是 AI 服务还在运行、持有文件锁）")
        traceback.print_exc()
        return 1

    print()
    print(f"has_collection({name}) = {client.has_collection(name)}")
    if not client.has_collection(name):
        print("[!] collection 不存在，先跑一次 /rag/ingest 或 /rag/stats 创建它")
        return 1

    try:
        stats = client.get_collection_stats(name)
        print("stats =", stats)
        print("describe =", str(client.describe_collection(name))[:300])
    except Exception:
        traceback.print_exc()

    # 用一个固定向量探两条路径：带 filter 与不带 filter
    probe_vector = [0.01] * 1024

    # 关键：先看加载状态并 load()。
    # Milvus 里"建好 collection + 建好索引 + 数据已写入"仍然可能是 released 状态，
    # 此时 search 会报 (code=101) call load() before search/get/query。
    print()
    print("--- load_state / load_collection ---")
    try:
        print("load_state(before) =", client.get_load_state(collection_name=name))
    except Exception:
        traceback.print_exc()
    try:
        client.load_collection(collection_name=name)
        print("[OK] load_collection 已调用")
        print("load_state(after)  =", client.get_load_state(collection_name=name))
    except Exception:
        print("[FAIL] load_collection")
        traceback.print_exc()

    print()
    print("--- search 无 filter ---")
    try:
        result = client.search(collection_name=name, data=[probe_vector], limit=5,
                               output_fields=["content"])
        print("[OK] hits =", len(result[0]) if result else 0)
    except Exception:
        print("[FAIL]")
        traceback.print_exc()

    print()
    print("--- search 带 filter='user_id == 1' ---")
    try:
        result = client.search(collection_name=name, data=[probe_vector], limit=5,
                               filter="user_id == 1", output_fields=["content"])
        print("[OK] hits =", len(result[0]) if result else 0)
    except Exception:
        print("[FAIL]")
        traceback.print_exc()

    print()
    print("--- search 带 search_params（与服务一致）---")
    try:
        result = client.search(
            collection_name=name,
            data=[probe_vector],
            limit=20,
            filter="user_id == 1",
            output_fields=["document_id", "chunk_index", "page_no", "content"],
            search_params={"metric_type": "COSINE", "params": {"ef": 128}},
        )
        print("[OK] hits =", len(result[0]) if result else 0)
        for hit in (result[0] if result else [])[:3]:
            print("   ", hit.get("distance"), str((hit.get("entity") or {}).get("content"))[:60])
    except Exception:
        print("[FAIL]")
        traceback.print_exc()

    return 0


if __name__ == "__main__":
    sys.exit(main())
