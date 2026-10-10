#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""第 3 周联调加固验收（Day 20~21：把"正常路径之外"的场景补上）。

覆盖 4 类场景：

  A. 新用户空检索 + 跨用户数据隔离
     - 全新账号（资料库为空）提问：必须回答「资料里没有相关内容」且无引用；
     - start 帧 hasMaterial=False：证明 Milvus 检索按 user_id 过滤（看不到 demo 的资料）；
     - 全新账号拿 demo 的 sessionId 提问：403（会话级水平越权拦截）。

  B. 长文档多切片
     - 上传约 6000 字长文（多 chunk），校验「切片数 == 向量增量」；
     - 就资料里靠后的章节提问，验证跨切片召回 + 重排后引用正确。

  C. 并发问答（3 路同时）
     - 2 路资料内 + 1 路资料外并发打 SSE：全部正常结束；
     - 资料内两路都带引用且回答各自命中，资料外那路拒答且无引用；
     - 三个会话分别落库、互不串台（按会话逐条校验 messages）。

  D. SSE 中途断连（前端 AbortController 场景）
     - 流到一半直接掐断连接：双端 /health 不受影响；
     - 已生成的部分内容照常落库（user + assistant 两条，assistant 为部分内容）；
     - 断连之后新的完整问答仍然正常（服务没有被拖垮）。

用法（需先启动 Java 8080 与 Python 8000）：
    python scripts/verify_week3_hardening.py
"""

from __future__ import annotations

import argparse
import concurrent.futures as cf
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request
import uuid

# Windows 控制台默认 GBK：接口返回的原文里常带数学符号（∫₀¹ 的下标 ₀ 等），
# print 会抛 UnicodeEncodeError 并直接中断整个验收脚本。统一切 UTF-8 + replace。
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


def _parse_frame(raw):
    event = "message"
    data_lines = []
    for line in raw.split("\n"):
        if line.startswith("event:"):
            event = line[6:].strip()
        elif line.startswith("data:"):
            data_lines.append(line[5:].lstrip())
    return event, "\n".join(data_lines)


def frame_text(data: str) -> str:
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
    return "".join(
        frame_text(data)
        for event, data in events
        if event == "message" and data.strip() != "[DONE]"
    )


def collect_citations(events) -> list:
    frames = [data for event, data in events if event == "citations"]
    if not frames:
        return []
    try:
        parsed = json.loads(frames[-1])
    except Exception:  # noqa: BLE001
        return []
    return parsed if isinstance(parsed, list) else []


def _as_list(value) -> list:
    """citations 可能是 JSON 字符串（数据库存的形态）或已经是数组，统一成 list。"""
    if isinstance(value, list):
        return value
    if not value:
        return []
    try:
        parsed = json.loads(value)
    except Exception:  # noqa: BLE001
        return []
    return parsed if isinstance(parsed, list) else []


def start_meta(events) -> dict:
    """从 start 帧里取会话元信息（sessionId / hasMaterial）。"""
    for event, data in events:
        if event == "start":
            try:
                return json.loads(data) or {}
            except Exception:  # noqa: BLE001
                return {}
    return {}


def sse_ask(base, token, question, session_id=None, timeout=300):
    """完整消费一次 SSE 问答，返回 (事件列表, 错误信息)。"""
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
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status != 200:
                return events, f"HTTP {resp.status}"
            buffer = ""
            for raw_line in resp:
                line = raw_line.decode("utf-8").rstrip("\n")
                if line == "":
                    if buffer:
                        events.append(_parse_frame(buffer))
                        buffer = ""
                    continue
                buffer += line + "\n"
    except Exception as exc:  # noqa: BLE001
        return events, f"{type(exc).__name__}: {exc}"
    return events, ""


def sse_abort(base, token, question, timeout=120):
    """发起 SSE，读到第 1 个 message 帧后立即掐断连接（模拟前端 AbortController）。

    返回 (读到的帧数, sessionId, 错误信息)。
    """
    req = urllib.request.Request(
        base + "/chat/ask",
        data=json.dumps({"question": question}).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
            "Accept": "text/event-stream",
        },
        method="POST",
    )
    frames: list[tuple[str, str]] = []
    session_id = None
    started = time.time()
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
        buffer = ""
        for raw_line in resp:
            line = raw_line.decode("utf-8").rstrip("\n")
            if line == "":
                if buffer:
                    event, data = _parse_frame(buffer)
                    frames.append((event, data))
                    if event == "start":
                        session_id = (start_meta([(event, data)]) or {}).get("sessionId")
                    buffer = ""
                    if event == "message":
                        # 正文已经开始了 —— 此刻掐断，服务端应把已生成的部分落库
                        resp.close()
                        break
                    if len(frames) >= 60:
                        # 保险丝：极少数情况下 reasoning 特别长，读到 60 帧就直接掐，
                        # 此时 reasoning 也已累积，落库条件（answerText 或 reasoning 非空）同样满足
                        resp.close()
                        break
            else:
                buffer += line + "\n"
            if time.time() - started > timeout:
                break
    except Exception as exc:  # noqa: BLE001
        return frames, session_id, f"{type(exc).__name__}: {exc}"
    return frames, session_id, None


def wait_parse(base, token, document_id, timeout=600):
    """轮询文档解析状态直到「完成 / 失败」。"""
    deadline = time.time() + timeout
    final = None
    while time.time() < deadline:
        status, body, _ = request(base, "GET", f"/kb/{document_id}", token=token)
        d = (body or {}).get("data") or {}
        if d.get("parseStatus") in ("完成", "失败"):
            return d
        time.sleep(6)
    return final


def wait_messages(base, token, session_id, want=2, timeout=20):
    """轮询会话消息，直到至少有 want 条（断连后落库是异步的，要给它时间）。"""
    deadline = time.time() + timeout
    msgs: list = []
    while time.time() < deadline:
        status, body, _ = request(base, "GET", f"/chat/session/{session_id}/messages", token=token)
        msgs = (body or {}).get("data") or []
        if len(msgs) >= want:
            return msgs
        time.sleep(1.5)
    return msgs


def build_long_doc() -> str:
    """构造约 6000 字的高数笔记（7 节），确保切成多个 chunk。"""
    sections = [
        ("第一节 极限的计算", """
求极限是高等数学的基础题型，常用方法有以下几类。
第一，利用等价无穷小替换。当 x→0 时，sin x ~ x，tan x ~ x，1-cos x ~ x²/2，
ln(1+x) ~ x，e^x-1 ~ x，这些替换只能用于乘除结构，加减结构中替换要谨慎，
必要时用泰勒展开到足够阶数再计算。
第二，利用两个重要极限：lim(x→0) sin x / x = 1，lim(x→∞) (1+1/x)^x = e。
凡是能化成这两种结构的极限，都可以直接套用。
第三，洛必达法则。使用条件是 0/0 或 ∞/∞ 型未定式，且分子分母在去心邻域内可导、
分母导数不为零、导数之比的极限存在（或为无穷）。注意洛必达法则是"后验"的：
如果导数之比的极限不存在，不能说明原极限不存在，只能说明洛必达失效，需要换方法。
第四，夹逼准则与单调有界准则，主要用于数列极限与递推数列的证明题。
计算极限时还要注意左右极限：分段函数在分界点、含绝对值的函数、
e^(1/x) 与 arctan(1/x) 这类在 x→0 时左右行为不同的函数，都必须分开讨论。
"""),
        ("第二节 导数与微分", """
导数的定义是函数增量与自变量增量之比的极限，几何意义是切线斜率。
高频计算类型：
（1）复合函数求导：链式法则逐层展开，先对外层函数求导，再乘以内层函数的导数。
（2）隐函数求导：方程两边同时对 x 求导，把 y 看成 x 的函数，遇到 y 就乘 y'，
最后解出 y'。求二阶导时把 y' 的表达式再对 x 求导（仍含 y'）。
（3）参数方程求导：dy/dx = (dy/dt)/(dx/dt)，二阶导 d²y/dx² = d(dy/dx)/dt ÷ (dx/dt)。
（4）对数求导法：适用于幂指函数 u(x)^v(x) 与多因子连乘除的函数，
先取对数把乘除化成加减，再两边求导。
可导与连续的关系：可导一定连续，连续不一定可导；判断分段函数在分界点是否可导，
必须用定义分别算左右导数并且相等。
微分的表达式是 dy = f'(x)dx，常用于近似计算与误差估计。
"""),
        ("第三节 微分中值定理", """
罗尔定理：如果函数 f(x) 在闭区间 [a,b] 上连续，在开区间 (a,b) 内可导，
且端点值相等 f(a)=f(b)，则在开区间内至少存在一点 ξ，使得 f'(ξ)=0。
拉格朗日中值定理：如果函数 f(x) 在闭区间 [a,b] 上连续，在开区间 (a,b) 内可导，
则在开区间内至少存在一点 ξ，使得 f(b)-f(a)=f'(ξ)(b-a)。
它的几何意义是：曲线上至少存在一点，该点处的切线平行于连接两个端点的弦。
拉格朗日中值定理是证明不等式的主力工具：把 f(b)-f(a) 换成 f'(ξ)(b-a) 后，
用 f'(ξ) 的取值范围就能夹出要证的不等式。
柯西中值定理：如果 f(x) 与 g(x) 都在闭区间上连续、开区间内可导，且 g'(x)≠0，
则存在 ξ 使得 [f(b)-f(a)]/[g(b)-g(a)] = f'(ξ)/g'(ξ)。
它与拉格朗日中值定理的关系是：取 g(x)=x 时柯西定理就退化为拉格朗日定理。
泰勒中值定理是这三个定理的推广：用高阶导数把函数展成多项式加余项，
带拉格朗日余项的泰勒公式在证明题中使用最多。
"""),
        ("第四节 不定积分", """
不定积分是求导的逆运算，基本积分表要背熟，其余技巧都是把式子化成表内形式。
第一换元法（凑微分）：把被积表达式凑成 f(φ(x))φ'(x)dx 的形式，
令 u=φ(x) 化为 ∫f(u)du。常见的凑法有：x dx = ½d(x²)，dx/x = d(ln x)，
e^x dx = d(e^x)，dx/√(1-x²) = d(arcsin x)。
第二换元法：适用于含根式的积分。含 √(a²-x²) 令 x=a sin t；
含 √(a²+x²) 令 x=a tan t；含 √(x²-a²) 令 x=a sec t。
换元后最后一定要把变量换回 x。
分部积分法：∫u dv = uv - ∫v du。选择 u 的口诀是"反、对、幂、指、三"，
排在前面的取作 u。幂函数与指数函数、幂函数与三角函数相乘时经常需要连续分部两次。
有理函数的积分：先做部分分式分解，再逐项积分；
假分式要先做多项式除法化为"多项式 + 真分式"。
三角有理式的积分一般用万能代换 t=tan(x/2)，但计算量大，能用其它方法就优先用其它方法。
"""),
        ("第五节 定积分及其应用", """
牛顿-莱布尼茨公式：若 F(x) 是 f(x) 的一个原函数，则 ∫ₐᵇ f(x)dx = F(b)-F(a)。
它把定积分的计算归结为求原函数，是计算定积分最常用的方法。
定积分的几何意义是曲边梯形的面积：f(x)≥0 时等于曲线与 x 轴之间的面积，
f(x)<0 的部分要取负号。
定积分换元时一定要同时更换积分上下限，换元后不必再把变量换回去；
分部积分公式为 ∫ₐᵇ u dv = [uv]ₐᵇ - ∫ₐᵇ v du。
变限积分求导：(∫ₐˣ f(t)dt)' = f(x)；(∫_{φ(x)}^{ψ(x)} f(t)dt)' = f(ψ(x))ψ'(x) - f(φ(x))φ'(x)。
定积分的应用主要是求面积、旋转体体积、弧长：平面图形面积用上下曲线之差积分；
旋转体体积用圆盘法 ∫π[f(x)]²dx 或柱壳法 ∫2πx·f(x)dx；
弧长用 ∫√(1+[f'(x)]²)dx。
反常积分要先判断敛散性再计算；注意瑕点处的极限是否存在。
"""),
        ("第六节 一阶线性微分方程", """
一阶线性微分方程的标准形式是 y' + P(x)y = Q(x)。
当 Q(x)≡0 时称为一阶齐次线性方程，分离变量即可求得通解 y = C·e^{-∫P(x)dx}。
当 Q(x)≠0 时（非齐次），用常数变易法：把齐次通解中的常数 C 换成待定函数 C(x)，
代回原方程求出 C(x)，最终得到通解公式：
y = e^{-∫P(x)dx} · ( ∫ Q(x)·e^{∫P(x)dx} dx + C )。
记忆要点：两个指数因子互为相反数，指数里都是 ∫P(x)dx，
括号里是非齐次项 Q(x) 乘上 e^{∫P(x)dx} 再积分，最后别忘了加任意常数 C。
可分离变量的方程要先化成 g(y)dy = f(x)dx 再两边积分；
齐次方程 y' = f(y/x) 用代换 u = y/x 化为可分离变量方程。
一阶线性方程的初值问题把初始条件代入通解定出 C 即可。
"""),
        ("第七节 无穷级数", """
常数项级数收敛的必要条件是通项趋于零，但通项趋于零并不保证级数收敛，
典型例子是调和级数发散。
正项级数的判别法：比较判别法（放大缩小找参照级数）、比值判别法、
根值判别法；p 级数当 p>1 时收敛，p≤1 时发散，是最常用的参照。
交错级数用莱布尼茨判别法：通项单调递减且趋于零则收敛。
任意项级数要先判断绝对收敛还是条件收敛。
幂级数 ∑aₙxⁿ 的收敛半径 R = lim|aₙ/aₙ₊₁|，在收敛区间内可以逐项求导、
逐项积分，这两个操作是求幂级数和函数的主要工具。
把函数展开成幂级数：直接法（按泰勒公式算系数）与间接法（利用 e^x、sin x、
1/(1-x) 等已知展开式做代换、逐项求导或积分）。
理解展开式时重点关注收敛域端点的行为，它决定了等式成立的范围。
"""),
    ]
    parts = ["高等数学核心考点笔记\n"]
    for title, body in sections:
        parts.append(title + body)
    return "\n".join(parts)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", default="http://127.0.0.1:8080")
    parser.add_argument("--python", default="http://127.0.0.1:8000")
    args = parser.parse_args()
    java, py = args.java, args.python

    print("=" * 64)
    print(" 第 3 周联调加固验收（并发 / 断连 / 长文档 / 空检索）")
    print(f" Java   = {java}")
    print(f" Python = {py}")
    print("=" * 64)

    status, body, _ = request(java, "POST", "/auth/login", {"username": "demo", "password": "123456"})
    demo_token = ((body or {}).get("data") or {}).get("accessToken")
    check(bool(demo_token), "demo 登录成功")

    # =================================================== A. 空检索 + 数据隔离
    print()
    print("--- A. 新用户空检索 + 跨用户数据隔离 ---")
    username = f"hard_{int(time.time())}_{uuid.uuid4().hex[:4]}"
    status, body, _ = request(java, "POST", "/auth/register",
                              {"username": username, "password": "123456",
                               "nickname": "加固测试", "examType": "考研"})
    check((body or {}).get("code") == 200, "注册全新账号（资料库为空）", f"username={username}")

    fresh_token = None
    status, body, _ = request(java, "POST", "/auth/login", {"username": username, "password": "123456"})
    fresh_token = ((body or {}).get("data") or {}).get("accessToken")
    check(bool(fresh_token), "新账号登录成功")

    if fresh_token:
        events, err = sse_ask(java, fresh_token, "定积分的几何意义是什么？")
        answer = collect_answer(events)
        meta = start_meta(events)
        check(not err, "新用户提问请求成功", err or "")
        check(meta.get("hasMaterial") is False,
              "start 帧 hasMaterial=False（检索按 user_id 过滤，看不到 demo 的资料）",
              f"hasMaterial={meta.get('hasMaterial')} chunkCount={meta.get('chunkCount')}")
        check("资料里没有相关内容" in answer,
              "新用户（无资料）提问 → 明确说资料里没有", f"回答={answer[:100]!r}")
        check("曲边梯形" not in answer and "牛顿" not in answer,
              "回答没有泄漏 demo 资料里的内容（用户级隔离生效）")
        check(len(collect_citations(events)) == 0, "空检索不产生引用来源",
              f"citations={len(collect_citations(events))}")

    # 会话级越权：新用户不能借 demo 的 sessionId 提问
    status, body, _ = request(java, "GET", "/chat/session/page", token=demo_token)
    demo_sessions = ((body or {}).get("data") or {}).get("records") or []
    demo_session_id = demo_sessions[0]["id"] if demo_sessions else None
    check(demo_session_id is not None, "demo 存在历史会话（用于越权用例）",
          f"sessionId={demo_session_id}")
    if demo_session_id and fresh_token:
        status, body, raw = request(java, "POST", "/chat/ask",
                                    {"question": "越权测试", "sessionId": demo_session_id},
                                    token=fresh_token)
        code = (body or {}).get("code") if isinstance(body, dict) else None
        check(status == 403 or code == 403, "新用户使用 demo 的 sessionId → 403 拦截",
              f"http={status} code={code}")

    # ============================================================ B. 长文档
    print()
    print("--- B. 长文档多切片（约 6000 字 / 7 节）---")
    long_name = "高数核心考点笔记.txt"
    long_text = build_long_doc()
    status, body, _ = request(py, "GET", "/rag/stats")
    vector_before = (body or {}).get("vectorCount") or 0

    payload, ctype = multipart("file", long_name, long_text.encode("utf-8"), "text/plain")
    status, body, _ = request(java, "POST", "/kb/upload", token=demo_token,
                              raw_body=payload, content_type=ctype, timeout=120)
    doc = (body or {}).get("data") or {}
    document_id = doc.get("id")
    check(bool(document_id), "长文档上传成功",
          f"documentId={document_id} 字数={len(long_text)}")

    final = wait_parse(java, demo_token, document_id) if document_id else None
    check(final is not None and final.get("parseStatus") == "完成",
          "长文档解析完成",
          f"status={final.get('parseStatus') if final else '超时'} "
          f"chunkCount={final.get('chunkCount') if final else '-'} "
          f"err={final.get('errorMsg') if final else ''}")

    chunk_count = (final or {}).get("chunkCount") or 0
    check(chunk_count >= 5, "长文档被切成多个 chunk（>=5，证明分块在长文档上工作）",
          f"chunkCount={chunk_count} 字数={len(long_text)}")

    status, body, _ = request(py, "GET", "/rag/stats")
    vector_after = (body or {}).get("vectorCount") or 0
    check(vector_after - vector_before == chunk_count,
          "本次入库向量增量 == 切片数",
          f"入库前={vector_before} 入库后={vector_after} 增量={vector_after - vector_before}")

    events, err = sse_ask(java, demo_token, "拉格朗日中值定理的条件、结论和几何意义是什么？")
    answer = collect_answer(events)
    citations = collect_citations(events)
    check(not err and len(answer) > 20, "长文档材料内提问有回答", f"回答长度={len(answer)}")
    # 断言用「定理的特征内容」而不是「定理的名字」：
    # 实测模型可能整篇不写"拉格朗日"四个字（问题里已带名字，回答直接给条件/结论/几何意义），
    # 拿名字当断言会得到"内容完全正确却报失败"的假阴性。这里要求同时出现公式特征与几何意义。
    has_formula = ("f(b)" in answer) or ("f(\\xi)" in answer)
    has_geometry = ("切线" in answer) or ("几何意义" in answer)
    check(has_formula and has_geometry,
          "回答命中资料中段章节（跨切片召回 + 重排正确）", f"回答前 100 字={answer[:100]!r}")
    check(any((c.get("documentName") or "") == long_name for c in citations),
          "引用来源包含本次上传的长文档",
          f"引用文档={[c.get('documentName') for c in citations]}")

    # ========================================================== C. 并发问答
    print()
    print("--- C. 并发问答（3 路同时打 SSE）---")
    q1 = "拉格朗日中值定理的条件和结论是什么？"
    q2 = "一阶线性微分方程的通解公式是什么？"
    q3 = "请介绍一下量子纠缠在量子计算中的具体应用。"

    started = time.time()
    with cf.ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(sse_ask, java, demo_token, q) for q in (q1, q2, q3)]
        results = [f.result() for f in futures]
    wall = time.time() - started

    (e1, err1), (e2, err2), (e3, err3) = results
    a1, a2, a3 = collect_answer(e1), collect_answer(e2), collect_answer(e3)
    c1, c2, c3 = collect_citations(e1), collect_citations(e2), collect_citations(e3)

    check(not (err1 or err2 or err3),
          "3 路并发 SSE 无网络层错误", f"errs={[err1, err2, err3]} 并发总耗时={wall:.1f}s")
    check(all(any(d == "[DONE]" for _, d in e) for e in (e1, e2, e3)),
          "3 路并发 SSE 全部正常收尾（[DONE]）")
    # 注意：模型不一定会在回答里写出"拉格朗日"四个字（实测并发下只给出公式），
    # 所以断言放宽到"定理名 或 公式特征"，只要求内容确实命中资料，不要求措辞。
    check(("拉格朗日" in a1) or ("中值定理" in a1) or ("f(b)" in a1),
          "并发·问题1 回答命中资料（中值定理）", f"回答前 80 字={a1[:80]!r}")
    check("P(x)" in a2 or "常数变易" in a2 or "∫" in a2,
          "并发·问题2 回答命中资料（微分方程通解）", f"回答前 80 字={a2[:80]!r}")
    check("资料里没有相关内容" in a3 and len(c3) == 0,
          "并发·问题3（资料外）仍然拒答且无引用",
          f"回答={a3[:80]!r} citations={len(c3)}")
    check(len(c1) > 0 and len(c2) > 0, "并发·资料内两路都带引用",
          f"citations: q1={len(c1)} q2={len(c2)} q3={len(c3)}")

    # 三个会话分别落库、互不串台：按标题找到各自的会话，逐条校验
    status, body, _ = request(java, "GET", "/chat/session/page", token=demo_token)
    all_sessions = ((body or {}).get("data") or {}).get("records") or []
    pair_ok = {}
    for question, answer, cits in ((q1, a1, c1), (q2, a2, c2), (q3, a3, c3)):
        sid = None
        for s in all_sessions:
            if (s.get("title") or "").startswith(question[:20]):
                sid = s["id"]
                break
        if sid is None:
            pair_ok[question] = False
            continue
        status, body, _ = request(java, "GET", f"/chat/session/{sid}/messages", token=demo_token)
        msgs = (body or {}).get("data") or []
        roles = [m.get("role") for m in msgs]
        assistant = next((m for m in msgs if m.get("role") == "assistant"), None)
        assistant_cits = _as_list(assistant.get("citations") if assistant else None)
        ok = (roles.count("user") == 1 and roles.count("assistant") == 1
              and (len(assistant_cits) > 0) == (len(cits) > 0))
        pair_ok[question] = ok
        print(f"         会话 {sid}「{question[:12]}…」roles={roles} "
              f"assistant_citations={len(assistant_cits)}")

    check(all(pair_ok.values()), "3 个并发会话分别落库、消息与引用互不串台",
          f"per-session={list(pair_ok.values())}")

    # ====================================================== D. SSE 中途断连
    print()
    print("--- D. SSE 中途断连（AbortController 场景）---")
    frames, aborted_sid, abort_err = sse_abort(java, demo_token, "柯西中值定理的公式是什么？")
    check(abort_err is None and len(frames) >= 2,
          "读到若干帧后主动掐断连接", f"读到 {len(frames)} 帧 err={abort_err}")
    check(bool(aborted_sid), "断连前从 start 帧拿到 sessionId", f"sessionId={aborted_sid}")

    status, _, _ = request(java, "GET", "/health", timeout=10)
    check(status == 200, "断连后 Java /health 仍 200")
    status, _, _ = request(py, "GET", "/health", timeout=10)
    check(status == 200, "断连后 Python /health 仍 200")

    if aborted_sid:
        msgs = wait_messages(java, demo_token, aborted_sid, want=2, timeout=20)
        roles = [m.get("role") for m in msgs]
        assistant = next((m for m in msgs if m.get("role") == "assistant"), None)
        partial_len = len((assistant or {}).get("content") or "")
        # 断连点可能落在"思考阶段"（此时 content=0、reasoning 已有内容），
        # 两者只要有一个非空就满足落库条件 —— 明细里两个都打出来，避免误读
        partial_reasoning = len((assistant or {}).get("reasoningContent") or "")
        check("user" in roles and "assistant" in roles,
              "断连会话仍落库 user + assistant（已生成的部分内容照常保存）",
              f"roles={roles} 部分正文={partial_len} 字 部分思考={partial_reasoning} 字")

    events, err = sse_ask(java, demo_token, "拉格朗日中值定理的几何意义是什么？")
    final_answer = collect_answer(events)
    check(not err and any(d == "[DONE]" for _, d in events) and len(final_answer) > 20,
          "断连之后新的完整问答正常（服务没有被拖垮）",
          f"回答长度={len(final_answer)}")

    # ============================================================== E. 清理
    print()
    print("--- E. 清理 ---")
    if document_id:
        status, body, _ = request(java, "DELETE", f"/kb/{document_id}", token=demo_token, timeout=120)
        check((body or {}).get("code") == 200, "删除长文档（含向量与切片）",
              f"msg={(body or {}).get('msg')}")
        status, body, _ = request(py, "GET", "/rag/stats")
        check((body or {}).get("vectorCount", -1) >= 0, "向量库统计仍可访问",
              f"剩余向量={((body or {}).get('vectorCount'))}")

    total = PASS + FAIL
    print()
    print("=" * 64)
    if FAIL == 0:
        print(f" 加固验收：全部通过 ({PASS}/{total})")
    else:
        print(f" 加固验收：{PASS} 通过 / {FAIL} 失败（共 {total}）")
        for name in FAILED:
            print(f"   - 失败: {name}")
    print("=" * 64)
    return FAIL


if __name__ == "__main__":
    sys.exit(main())
