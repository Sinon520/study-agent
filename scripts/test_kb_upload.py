#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""第 3 周资料上传链路测试（不依赖本地模型是否已下载完）。

验证链路：前端上传 → Java 校验/落盘/写 kb_document → 异步调 Python /rag/ingest
        → 失败时把状态置为「失败」并带上可读原因（不允许一直停在「解析中」）。

用法：
    python scripts/test_kb_upload.py
"""

from __future__ import annotations

import argparse
import pathlib
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

# Windows 控制台默认 GBK：接口返回的原文里常带数学符号（∫₀¹ 的下标 ₀ 等），
# print 会抛 UnicodeEncodeError 并**直接中断整个验收脚本**，表现成"跑到一半突然退出"。
# 这里在任何输出之前把 stdout/stderr 切成 UTF-8 + errors=replace。
# 详见 docs/踩坑记录.md 与 scripts/_console.py。
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from _console import enable_utf8_output  # noqa: E402

enable_utf8_output()

PASS = 0
FAIL = 0
FAILED: list[str] = []


def check(cond: bool, desc: str, detail: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {desc}")
    else:
        FAIL += 1
        FAILED.append(desc)
        print(f"  [FAIL] {desc}")
    if detail:
        print(f"         {detail}")


def request(base: str, method: str, path: str, body=None, token=None, raw_body=None,
            content_type=None, timeout=120):
    data = None
    headers = {}
    if raw_body is not None:
        data = raw_body
        headers["Content-Type"] = content_type
    elif body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode("utf-8")
            return resp.status, _json(text), text
    except urllib.error.HTTPError as exc:
        text = exc.read().decode("utf-8")
        return exc.code, _json(text), text
    except Exception as exc:  # noqa: BLE001
        return -1, None, f"{type(exc).__name__}: {exc}"


def _json(text: str):
    try:
        return json.loads(text)
    except Exception:  # noqa: BLE001
        return None


def multipart(field: str, filename: str, content: bytes, content_type: str):
    """手工拼 multipart/form-data（不引入第三方库）。"""
    boundary = "----studyagent" + uuid.uuid4().hex
    parts = [
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'.encode("utf-8"),
        f"Content-Type: {content_type}\r\n\r\n".encode(),
        content,
        f"\r\n--{boundary}--\r\n".encode(),
    ]
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", default="http://127.0.0.1:8080")
    args = parser.parse_args()
    base = args.java

    print("=" * 64)
    print(" 第 3 周资料上传链路测试")
    print(f" Java = {base}")
    print("=" * 64)

    # 登录
    status, body, _ = request(base, "POST", "/auth/login",
                              {"username": "demo", "password": "123456"})
    token = ((body or {}).get("data") or {}).get("accessToken")
    print()
    print("--- A. 前置 ---")
    check(bool(token), "demo 登录成功")

    # 非法类型必须被拒
    print()
    print("--- B. 上传校验 ---")
    payload, ctype = multipart("file", "evil.exe", b"MZ\x90\x00", "application/octet-stream")
    status, body, _ = request(base, "POST", "/kb/upload", token=token,
                              raw_body=payload, content_type=ctype)
    check(status == 200 and (body or {}).get("code") == 400,
          "不支持的文件类型被拒（.exe）",
          f"code={(body or {}).get('code')} msg={(body or {}).get('msg')}")

    # 正常文本文件
    print()
    print("--- C. 正常上传与异步解析 ---")
    content = (
        "定积分的几何意义\n\n"
        "定积分 ∫₀¹ x² dx 的几何意义是曲线 y = x² 与 x 轴在区间 [0,1] 上围成的曲边梯形的面积。\n\n"
        "牛顿-莱布尼茨公式\n\n"
        "若 F(x) 是 f(x) 的一个原函数，则 ∫ₐᵇ f(x) dx = F(b) - F(a)。"
        "这条公式把定积分的计算归结为求原函数，是微积分基本定理的直接结论。\n\n"
        "常见题型\n\n"
        "1. 直接计算定积分；2. 用定积分求面积；3. 变限积分求导；4. 定积分与极限结合。\n"
    ).encode("utf-8")
    payload, ctype = multipart("file", "定积分复习要点.txt", content, "text/plain")
    status, body, _ = request(base, "POST", "/kb/upload", token=token,
                              raw_body=payload, content_type=ctype)
    doc = (body or {}).get("data") or {}
    check(status == 200 and (body or {}).get("code") == 200 and doc.get("id"),
          "上传成功并返回文档记录", f"documentId={doc.get('id')} status={doc.get('parseStatus')}")
    check(doc.get("fileSizeText") not in (None, "-"), "返回可读文件大小", f"size={doc.get('fileSizeText')}")
    check((body or {}).get("code") == 200, "响应是统一返回体", f"msg={(body or {}).get('msg')}")

    document_id = doc.get("id")

    # 轮询状态：解析是异步的，等它落到「完成」或「失败」。
    #
    # 超时给 600 秒而不是 180 秒：解析任务在 Java 的线程池里排队，
    # 而每个任务要同步等 Python 跑完 BGE-M3 向量化（首次还要加载 2.2GB 模型）。
    # 实测池子偏小时一条任务排到 267 秒 —— 那是**服务端的问题**
    # （已把池子 2 → 4 并加了排队告警），但验收脚本也不该用一个刚好卡在
    # 边界上的超时，否则会把"服务端慢"误报成"状态卡住不落定"。
    final = None
    if document_id:
        deadline = time.time() + 600
        while time.time() < deadline:
            status, body, _ = request(base, "GET", f"/kb/{document_id}", token=token)
            d = (body or {}).get("data") or {}
            if d.get("parseStatus") in ("完成", "失败"):
                final = d
                break
            time.sleep(5)

    check(final is not None, "解析状态在 600 秒内落定为「完成」或「失败」（不会一直卡在解析中）",
          f"最终状态={final.get('parseStatus') if final else '超时'}")

    if final:
        if final.get("parseStatus") == "完成":
            check(final.get("chunkCount", 0) > 0, "解析成功且切片数 > 0",
                  f"chunkCount={final.get('chunkCount')}")
        else:
            reason = final.get("errorMsg") or ""
            check("模型" in reason or "下载" in reason, "失败原因可读且指向模型未就绪",
                  f"errorMsg={reason[:160]}")

    # 越权：另一个账号不能读这份文档
    print()
    print("--- D. 越权防护 ---")
    other = "kbtest_" + uuid.uuid4().hex[:6]
    request(base, "POST", "/auth/register", {"username": other, "password": "verify123456"})
    status, body, _ = request(base, "POST", "/auth/login", {"username": other, "password": "verify123456"})
    other_token = ((body or {}).get("data") or {}).get("accessToken")
    if document_id and other_token:
        status, body, _ = request(base, "GET", f"/kb/{document_id}", token=other_token)
        check((body or {}).get("code") == 403, "他人读取该文档 → 403",
              f"code={(body or {}).get('code')}")

    # 清理：删掉测试文档
    print()
    print("--- E. 清理 ---")
    if document_id:
        status, body, _ = request(base, "DELETE", f"/kb/{document_id}", token=token)
        check((body or {}).get("code") == 200, "删除文档成功（含向量/切片清理）",
              f"msg={(body or {}).get('msg')}")
        status, body, _ = request(base, "GET", f"/kb/{document_id}", token=token)
        check((body or {}).get("code") == 400, "删除后详情返回业务错误",
              f"code={(body or {}).get('code')}")

    total = PASS + FAIL
    print()
    print("=" * 64)
    if FAIL == 0:
        print(f" 上传链路测试：全部通过 ({PASS}/{total})")
    else:
        print(f" 上传链路测试：{PASS} 通过 / {FAIL} 失败（共 {total}）")
        for name in FAILED:
            print(f"   - 失败: {name}")
    print("=" * 64)
    return FAIL


if __name__ == "__main__":
    sys.exit(main())
