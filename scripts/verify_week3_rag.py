#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""第 3 周 RAG 端到端验收（需要本地模型已下载完成）。

链路：上传资料 → Java 落盘 → Python 解析/分块/BGE-M3 向量化 → 写入 Milvus
     → 回写 MySQL kb_chunk → 问答时 Milvus 召回 → BGE-Reranker 重排
     → qwen3.7-flash 流式生成（SSE）→ 引用溯源

用法：
    python scripts/verify_week3_rag.py
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


def request(base, method, path, body=None, token=None, raw_body=None,
            content_type=None, timeout=300):
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


def _json(text):
    try:
        return json.loads(text)
    except Exception:  # noqa: BLE001
        return None


def multipart(field, filename, content, content_type):
    boundary = "----sa" + uuid.uuid4().hex
    parts = [
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'.encode("utf-8"),
        f"Content-Type: {content_type}\r\n\r\n".encode(),
        content,
        f"\r\n--{boundary}--\r\n".encode(),
    ]
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def sse_ask(base, token, question, session_id=None, timeout=300):
    """直接消费 SSE（不走 Java，便于校验帧协议）。返回 (事件列表, 纯文本)。"""
    payload = {"question": question}
    if session_id:
        payload["sessionId"] = session_id
    req = urllib.request.Request(
        base + "/chat/ask",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
            "Accept": "text/event-stream",
        },
        method="POST",
    )
    events: list[tuple[str, str]] = []
    first_at = None
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status != 200:
                return events, f"HTTP {resp.status}"
            buffer = ""
            for raw_line in resp:
                line = raw_line.decode("utf-8").rstrip("\n")
                if line == "":
                    if buffer:
                        event, data = _parse_frame(buffer)
                        events.append((event, data))
                        if first_at is None and data:
                            first_at = time.time() - started
                        buffer = ""
                    continue
                buffer += line + "\n"
    except Exception as exc:  # noqa: BLE001
        return events, f"{type(exc).__name__}: {exc}"
    return events, ""


def frame_text(data: str) -> str:
    """把 message / reasoning 帧的 data 解析出 text。

    坑：帧的 data 是 `{"text": "..."}` 这样的 JSON 字符串，
    调用方如果直接把它当正文拼接，就会得到一堆 `{"text": "**结论**\\n"}` ——
    判断"回答里有没有某个词"时全是假失败。统一走这个函数取值。
    """
    if not data:
        return ""
    try:
        parsed = json.loads(data)
    except Exception:  # noqa: BLE001
        return data
    if isinstance(parsed, dict):
        return str(parsed.get("text") or "")
    return ""


def collect_answer(events) -> str:
    """把 message 帧拼成完整回答。

    注意要跳过结束标记：SSE 的最后一条是裸的 `data: [DONE]`（没有 event 名，
    因此被解析成默认事件 "message"）。不过滤的话它会混进回答文本里，
    让"回答长度""有没有专业术语"这类断言出现莫名其妙的偏差。
    """
    return "".join(
        frame_text(data)
        for event, data in events
        if event == "message" and data.strip() != "[DONE]"
    )


def collect_citations(events) -> list:
    """取最后一个 citations 帧的内容（引用是一次性下发的）。"""
    frames = [data for event, data in events if event == "citations"]
    if not frames:
        return []
    try:
        parsed = json.loads(frames[-1])
    except Exception:  # noqa: BLE001
        return []
    return parsed if isinstance(parsed, list) else []


def _parse_frame(raw):
    event = "message"
    data_lines = []
    for line in raw.split("\n"):
        if line.startswith("event:"):
            event = line[6:].strip()
        elif line.startswith("data:"):
            data_lines.append(line[5:].lstrip())
    return event, "\n".join(data_lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", default="http://127.0.0.1:8080")
    parser.add_argument("--python", default="http://127.0.0.1:8000")
    args = parser.parse_args()
    java, py = args.java, args.python

    print("=" * 64)
    print(" 第 3 周 RAG 端到端验收")
    print(f" Java   = {java}")
    print(f" Python = {py}")
    print("=" * 64)

    # ---------------------------------------------------------- 模型与向量库
    print()
    print("--- A. 本地模型与向量库 ---")
    status, body, _ = request(py, "GET", "/health")
    check(status == 200, "AI 服务 /health 200")
    check((body or {}).get("llm_configured") is True, "DASHSCOPE_API_KEY 已配置")

    status, body, _ = request(py, "POST", "/debug/embed", {"texts": ["定积分的几何意义"]}, timeout=600)
    vec = body or {}
    check(status == 200 and vec.get("dimension") == 1024,
          "BGE-M3 输出 1024 维向量（模型真实加载成功）",
          f"dimension={vec.get('dimension')} count={vec.get('count')}")

    status, body, _ = request(py, "POST", "/debug/rerank",
                              {"query": "定积分", "passages": ["定积分求面积", "今天天气很好"], "top_k": 2},
                              timeout=600)
    results = (body or {}).get("results") or []
    check(status == 200 and len(results) == 2, "BGE-Reranker 打分成功（FlagReranker）",
          f"results={[(r.get('index'), round(r.get('score', 0), 3)) for r in results]}")
    check(bool(results) and results[0].get("index") == 0,
          "重排把相关段落排在第一（语义正确）",
          f"top1={results[0].get('passage') if results else None}")

    # ---------------------------------------------------------- 登录
    print()
    print("--- B. 上传资料并入库 ---")
    status, body, _ = request(java, "POST", "/auth/login",
                              {"username": "demo", "password": "123456"})
    token = ((body or {}).get("data") or {}).get("accessToken")
    check(bool(token), "demo 登录成功")

    content = """定积分的几何意义

定积分 ∫₀¹ x² dx 的几何意义是曲线 y = x² 与 x 轴以及直线 x=0、x=1 所围成的曲边梯形的面积。
当被积函数在区间上为负时，定积分的值为负，表示面积取负号。

牛顿-莱布尼茨公式

若函数 f(x) 在区间 [a, b] 上连续，且 F(x) 是 f(x) 的一个原函数，
则 ∫ₐᵇ f(x) dx = F(b) - F(a)。这条公式把定积分的计算归结为求原函数，
是微积分基本定理的直接结论，也是考研数学中计算定积分最常用的方法。

定积分的常见题型

第一类是直接利用牛顿-莱布尼茨公式计算定积分。
第二类是用定积分求平面图形的面积，关键是画出草图并确定积分上下限。
第三类是变限积分求导，需要用到 (∫ₐˣ f(t) dt)' = f(x)。
第四类是把定积分与数列极限结合起来，通常用定积分的定义把和式化为积分。

换元积分法与分部积分法

定积分换元时一定要注意同时更换积分上下限，换元后不必再把变量换回去。
分部积分公式为 ∫ₐᵇ u dv = [uv]ₐᵇ - ∫ₐᵇ v du，选择 u 与 dv 的原则与不定积分一致。
""".encode("utf-8")

    # 记录入库前的向量总数，后面用"增量"判断本次入库是否写全
    status, body, _ = request(py, "GET", "/rag/stats")
    vector_before = (body or {}).get("vectorCount") or 0

    payload, ctype = multipart("file", "定积分复习要点.txt", content, "text/plain")
    status, body, _ = request(java, "POST", "/kb/upload", token=token,
                              raw_body=payload, content_type=ctype, timeout=120)
    doc = (body or {}).get("data") or {}
    document_id = doc.get("id")
    check(bool(document_id), "资料上传成功", f"documentId={document_id} status={doc.get('parseStatus')}")

    final = None
    if document_id:
        deadline = time.time() + 600
        while time.time() < deadline:
            status, body, _ = request(java, "GET", f"/kb/{document_id}", token=token)
            d = (body or {}).get("data") or {}
            if d.get("parseStatus") in ("完成", "失败"):
                final = d
                break
            time.sleep(6)

    check(final is not None and final.get("parseStatus") == "完成",
          "文档解析完成（解析→分块→向量化→写 Milvus→回写 MySQL）",
          f"status={final.get('parseStatus') if final else '超时'} chunkCount={final.get('chunkCount') if final else '-'} err={final.get('errorMsg') if final else ''}")
    if final:
        check((final.get("chunkCount") or 0) > 0, "切片数 > 0", f"chunkCount={final.get('chunkCount')}")

    # 向量数应对应"本次入库新增的切片数"。
    #
    # 注意：不能直接比较"向量总数 == 本文档切片数"——Milvus 是跨文档共享的
    # 一个 collection，库里会留有历史测试数据（以及被删除文档的残留）。
    # 那种写法会得到"明明入库成功却报不一致"的假失败（本项目实际踩过）。
    # 正确做法：比较**入库前后向量数的增量**与本文档的切片数。
    status, body, _ = request(py, "GET", "/rag/stats")
    kb = body or {}
    vector_after = kb.get("vectorCount") or 0
    check(vector_after > 0, "Milvus 里有向量", f"vectorCount={vector_after}")

    if final and final.get("chunkCount"):
        delta = vector_after - vector_before
        check(delta == final.get("chunkCount"),
              "本次入库新增的向量数 == 本文档切片数（双向定位对得上）",
              f"入库前={vector_before} 入库后={vector_after} 增量={delta} "
              f"chunkCount={final.get('chunkCount')}")

    # ---------------------------------------------------------- 问答
    print()
    print("--- C. RAG 流式问答与引用溯源 ---")
    question = "定积分的几何意义是什么？牛顿-莱布尼茨公式怎么用？"
    events, err = sse_ask(java, token, question)
    check(not err, "SSE 请求成功", err or "")

    names = [e for e, _ in events]
    check("start" in names, "收到 event: start")
    check("reasoning" in names or "message" in names, "收到内容帧",
          f"事件序列前 8 个={names[:8]}")

    answer = collect_answer(events)
    check(len(answer) > 20, "生成了有内容的回答", f"回答长度={len(answer)} 字")
    check("曲边梯形" in answer or "面积" in answer,
          "回答基于资料内容（提到「曲边梯形/面积」）",
          f"回答前 120 字：{answer[:120]}")

    citations = collect_citations(events)
    check(len(citations) > 0, "收到 event: citations（引用溯源）",
          f"引用条数={len(citations)}")
    if citations:
        first = citations[0]
        check(bool(first.get("documentName")), "引用带文档名", f"documentName={first.get('documentName')}")
        check(first.get("pageNo") is not None, "引用带页码", f"pageNo={first.get('pageNo')}")
        check(bool(first.get("snippet")), "引用带原文片段", f"snippet={str(first.get('snippet'))[:60]}")
    check(any(d == "[DONE]" for _, d in events), "以 data: [DONE] 正常结束")

    # 资料外的问题：应明确说"资料里没有"，而不是凭自己的知识编造。
    #
    # 判定要比"回答里有没有'没有'两个字"严格得多：模型很擅长先说一句
    # "资料里没有相关内容"，然后**接着凭记忆长篇大论**——这恰好违背了 RAG 的
    # 初衷（用户会误以为内容是来自他自己的资料）。所以这里同时检查：
    #   1) 明确声明没有资料；
    #   2) 回答足够短（没有展开讲）；
    #   3) 没有出现该话题的专业术语。
    print()
    print("--- D. 资料外的问题（防幻觉）---")
    events2, err2 = sse_ask(java, token, "请介绍一下量子纠缠在量子计算中的具体应用。")
    answer2 = collect_answer(events2)
    check(not err2, "第二个问题请求成功", err2 or "")

    declared = ("资料里没有相关内容" in answer2) or ("没有相关" in answer2)
    concise = len(answer2) <= 120
    no_expert_terms = not any(
        term in answer2 for term in ("纠缠态", "量子比特", "叠加态", "退相干", "qubit")
    )
    check(declared, "明确声明「资料里没有相关内容」", f"回答={answer2[:120]!r}")
    check(concise, "没有展开作答（避免前半句拒答、后半句凭记忆长篇输出）",
          f"回答长度={len(answer2)} 字（阈值 120）")
    check(no_expert_terms, "没有沿用该话题的专业术语（说明确实没在编造）",
          f"回答={answer2[:120]!r}")

    # 引用本就不该有：没召回资料时 citations 必须是空的
    citations2 = collect_citations(events2)
    check(len(citations2) == 0, "资料外的问题不产生引用来源",
          f"citations={len(citations2)}")

    # ---------------------------------------------------------- 落库
    print()
    print("--- E. 会话与消息落库 ---")
    status, body, _ = request(java, "GET", "/chat/session/page", token=token)
    sessions = ((body or {}).get("data") or {}).get("records") or []
    check(len(sessions) > 0, "会话已落库", f"会话数={len(sessions)}")

    # 注意：不能只查"最新那个会话"。
    # 上面第 D 步刚问了一个**资料外**的问题，它自己也是一个新会话，
    # 而且按 lastTime 倒序排在最前面 —— 那个会话的 assistant 消息
    # citations 本来就该是空的（这正是正确行为）。
    # 所以这里遍历会话，找"确实带引用的那条 assistant 消息"来验证落库。
    status, body, _ = request(java, "GET", "/chat/session/page", token=token)
    all_sessions = ((body or {}).get("data") or {}).get("records") or []

    checked_messages = False
    found_citations = False
    for session in all_sessions[:10]:
        status, body, _ = request(java, "GET", f"/chat/session/{session['id']}/messages", token=token)
        msgs = (body or {}).get("data") or []
        if len(msgs) >= 2:
            checked_messages = True
        for msg in msgs:
            if msg.get("role") == "assistant" and msg.get("citations"):
                found_citations = True
                break
        if found_citations:
            break

    check(checked_messages, "存在一问一答都落库的会话（user + assistant）",
          f"检查了 {min(len(all_sessions), 10)} 个会话")
    check(found_citations, "带引用的回答在落库时保存了 citations JSON",
          "（资料外的那个会话 citations 为空是正确行为，这里验证的是有引用的那条）")

    # ---------------------------------------------------------- 清理
    print()
    print("--- F. 清理 ---")
    if document_id:
        status, body, _ = request(java, "DELETE", f"/kb/{document_id}", token=token, timeout=120)
        check((body or {}).get("code") == 200, "删除资料（含向量与切片）",
              f"msg={(body or {}).get('msg')}")
        status, body, _ = request(py, "GET", "/rag/stats")
        check((body or {}).get("vectorCount", -1) >= 0, "向量库统计仍可访问",
              f"剩余向量={((body or {}).get('vectorCount'))}")

    total = PASS + FAIL
    print()
    print("=" * 64)
    if FAIL == 0:
        print(f" RAG 端到端验收：全部通过 ({PASS}/{total})")
    else:
        print(f" RAG 端到端验收：{PASS} 通过 / {FAIL} 失败（共 {total}）")
        for name in FAILED:
            print(f"   - 失败: {name}")
    print("=" * 64)
    return FAIL


if __name__ == "__main__":
    sys.exit(main())
