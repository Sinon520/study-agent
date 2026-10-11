#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""第 4 周验收：Redis 缓存加固（Day 27 Cache Aside）。

验证范围：
  A. 基础设施：Redis 可达
  B. 计划详情缓存：
     首次查询回填缓存（key 存在 + TTL 正确 + 值与接口一致）；
     再次查询走缓存（响应一致）；
     修改计划 → 缓存被删（失效）；再查 → 回填新值；
     改回原值复原数据。
  C. 错题标签统计缓存：
     查询回填；新增带新标签的错题 → 失效；再查新标签出现且回填；
     删除错题 → 失效；再查缓存回填（标签行保留是 LEFT JOIN 设计，计数归零）。
  D. 用户信息缓存：
     /auth/me 回填（值/ TTL 正确）；
     微信登录改昵称（唯一写路径）→ 缓存被删；再查回填新昵称。
  E. 隔离：B 账号越权读 demo 计划 → 403，且 B 名下不会产生缓存条目
     （证明"缓存命中"没有绕过归属校验）。

实现说明：直接连 Redis 校验 key 状态（EXISTS/GET/TTL），而不是只看接口返回
—— 接口对"缓存命中"和"缓存未命中"的返回是一样的，"验缓存生效"必须看到
Redis 侧的真实状态，否则测试会在缓存全挂时依然全绿。

用法（需先启动 Java 8080 与 Redis；MySQL 正常）：
    python scripts/verify_week4_cache.py
退出码：失败项数量（0 = 全部通过）。
"""

from __future__ import annotations

import argparse
import json
import pathlib
import random
import socket
import string
import sys
import time
import urllib.error
import urllib.request

# Windows 控制台默认 GBK，统一 UTF-8 + replace（详见 scripts/_console.py）
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


# ---------------------------------------------------------------------- HTTP


def call(base: str, method: str, path: str, body=None, token: str | None = None,
         query: dict | None = None, timeout: int = 30):
    url = base + path
    if query:
        from urllib.parse import urlencode
        url += "?" + urlencode(query, encoding="utf-8")
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            return exc.code, json.loads(raw)
        except Exception:  # noqa: BLE001
            return exc.code, None
    except Exception as exc:  # noqa: BLE001
        return -1, f"{type(exc).__name__}: {exc}"


def rand(prefix: str) -> str:
    return prefix + "".join(random.choices(string.ascii_lowercase + string.digits, k=6))


def login(java: str, username: str, password: str) -> tuple[str | None, int | None]:
    status, body = call(java, "POST", "/auth/login", {"username": username, "password": password})
    data = (body or {}).get("data") or {}
    return data.get("accessToken"), (data.get("userInfo") or {}).get("id")


# ------------------------------------------------------------------ MiniRedis
# 极简 RESP 客户端（只为 EXISTS / GET / TTL / DEL / PING，避免引第三方库）


class MiniRedis:
    def __init__(self, host: str = "127.0.0.1", port: int = 6379, timeout: float = 5):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.buf = b""

    def cmd(self, *args: str):
        parts = [f"*{len(args)}\r\n".encode()]
        for a in args:
            raw = a.encode("utf-8")
            parts.append(f"${len(raw)}\r\n".encode() + raw + b"\r\n")
        self.sock.sendall(b"".join(parts))
        return self._read()

    def _readline(self) -> bytes:
        while b"\r\n" not in self.buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("Redis 连接被关闭")
            self.buf += chunk
        line, self.buf = self.buf.split(b"\r\n", 1)
        return line

    def _read(self):
        line = self._readline()
        kind, rest = line[:1], line[1:]
        if kind == b"+":
            return rest.decode()
        if kind == b"-":
            raise RuntimeError(rest.decode())
        if kind == b":":
            return int(rest)
        if kind == b"$":
            n = int(rest)
            if n == -1:
                return None
            while len(self.buf) < n + 2:
                self.buf += self.sock.recv(4096)
            data, self.buf = self.buf[:n], self.buf[n + 2:]
            return data.decode("utf-8")
        if kind == b"*":
            n = int(rest)
            return [self._read() for _ in range(n if n >= 0 else 0)]
        raise RuntimeError(f"未知 RESP 类型: {kind!r}")

    def exists(self, key: str) -> bool:
        return int(self.cmd("EXISTS", key)) == 1

    def get_json(self, key: str):
        raw = self.cmd("GET", key)
        return json.loads(raw) if raw is not None else None

    def ttl(self, key: str) -> int:
        return int(self.cmd("TTL", key))

    def delete(self, *keys: str) -> None:
        self.cmd("DEL", *keys)

    def close(self) -> None:
        self.sock.close()


def wait_key(redis: MiniRedis, key: str, want: bool, timeout: float = 8.0) -> bool:
    """轮询 key 状态（防异步失效如 AI 打标并发删缓存导致的时序抖动）。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if redis.exists(key) == want:
            return True
        time.sleep(0.3)
    return redis.exists(key) == want


# ---------------------------------------------------------------------- main


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", default="http://127.0.0.1:8080")
    args = parser.parse_args()
    java = args.java

    print("=" * 64)
    print(" 第 4 周验收：Redis 缓存加固（Day 27 Cache Aside）")
    print(f" Java = {java}  Redis = 127.0.0.1:6379")
    print("=" * 64)

    # ---------------------------------------------------------- A. 基础设施
    print()
    print("--- A. 基础设施 ---")
    try:
        redis = MiniRedis()
        check(redis.cmd("PING") == "PONG", "Redis 可达（PING）")
    except Exception as exc:  # noqa: BLE001
        check(False, "Redis 可达（PING）", f"连接失败：{exc}")
        return summary()

    jwt, demo_id = login(java, "demo", "123456")
    check(bool(jwt and demo_id), "demo 登录成功", f"userId={demo_id}")
    if not jwt:
        return summary()

    # ------------------------------------------------------ B. 计划详情缓存
    print()
    print("--- B. 计划详情缓存（读回填 / 写失效 / 再回填）---")
    _s, body = call(java, "GET", "/plan/page", token=jwt, query={"pageNum": 1, "pageSize": 1})
    records = ((body or {}).get("data") or {}).get("records") or []
    created_plan = False
    if records:
        plan = records[0]
    else:
        today = time.strftime("%Y-%m-%d")
        target = time.strftime("%Y-%m-%d", time.localtime(time.time() + 90 * 86400))
        _s, body = call(java, "POST", "/plan", {
            "title": "缓存验收计划", "examType": "考研", "targetDate": target,
            "startDate": today, "dailyMinutes": 60,
        }, token=jwt)
        plan = (body or {}).get("data") or {}
        created_plan = True
    plan_id = plan.get("id")
    check(bool(plan_id), "取到测试用计划", f"planId={plan_id} title={plan.get('title')}")
    if not plan_id:
        return summary()

    plan_key = f"plan:detail:{demo_id}:{plan_id}"
    redis.delete(plan_key)  # 清场：确保从 miss 开始

    s1, b1 = call(java, "GET", f"/plan/{plan_id}", token=jwt)
    v1 = (b1 or {}).get("data") or {}
    check(s1 == 200 and redis.exists(plan_key), "首次查询回源并回填缓存",
          f"key={plan_key} ttl={redis.ttl(plan_key)}s")
    check(570 <= redis.ttl(plan_key) <= 600, "计划缓存 TTL ≈ 10 分钟",
          f"ttl={redis.ttl(plan_key)}s")
    cached1 = redis.get_json(plan_key) or {}
    check(cached1.get("title") == v1.get("title") and cached1.get("id") == plan_id,
          "缓存内容与接口返回一致", f"cached.title={cached1.get('title')!r}")

    s2, b2 = call(java, "GET", f"/plan/{plan_id}", token=jwt)
    v2 = (b2 or {}).get("data") or {}
    check(s2 == 200 and (v2.get("title"), v2.get("taskTotal")) == (v1.get("title"), v1.get("taskTotal")),
          "再次查询命中缓存（响应一致）", f"title={v2.get('title')!r} taskTotal={v2.get('taskTotal')}")

    # 修改计划标题 → 缓存应被删除
    new_title = (v1.get("title") or "") + "（缓存验收）"
    s3, b3 = call(java, "PUT", f"/plan/{plan_id}", {
        "title": new_title, "examType": v1.get("examType"), "targetDate": v1.get("targetDate"),
        "startDate": v1.get("startDate"), "dailyMinutes": v1.get("dailyMinutes"),
    }, token=jwt)
    check(s3 == 200 and not redis.exists(plan_key), "修改计划 → 缓存被删（写失效）",
          f"PUT status={s3} key存在={redis.exists(plan_key)}")

    s4, b4 = call(java, "GET", f"/plan/{plan_id}", token=jwt)
    v4 = (b4 or {}).get("data") or {}
    cached4 = redis.get_json(plan_key) or {}
    check(v4.get("title") == new_title and cached4.get("title") == new_title,
          "再查得到新值并回填缓存", f"title={v4.get('title')!r}")

    # 复原数据（改回原标题）→ 顺带验证恢复路径同样失效并回填
    s5, b5 = call(java, "PUT", f"/plan/{plan_id}", {
        "title": v1.get("title"), "examType": v1.get("examType"), "targetDate": v1.get("targetDate"),
        "startDate": v1.get("startDate"), "dailyMinutes": v1.get("dailyMinutes"),
    }, token=jwt)
    s6, b6 = call(java, "GET", f"/plan/{plan_id}", token=jwt)
    v6 = (b6 or {}).get("data") or {}
    check(s5 == 200 and s6 == 200 and v6.get("title") == v1.get("title"),
          "计划标题已复原（不影响演示数据）", f"title={v6.get('title')!r}")

    if created_plan:
        call(java, "DELETE", f"/plan/{plan_id}", token=jwt)
        print("         （验收临时计划已删除）")

    # -------------------------------------------------- C. 错题标签统计缓存
    print()
    print("--- C. 错题标签统计缓存 ---")
    tags_key = f"wrong:tags:{demo_id}"
    redis.delete(tags_key)

    s1, b1 = call(java, "GET", "/wrong-question/tags", token=jwt)
    tags1 = (b1 or {}).get("data") or []
    check(s1 == 200 and redis.exists(tags_key), "标签查询回源并回填缓存",
          f"key={tags_key} ttl={redis.ttl(tags_key)}s count={len(tags1)}")
    check(0 < redis.ttl(tags_key) <= 300, "标签缓存 TTL ≈ 5 分钟", f"ttl={redis.ttl(tags_key)}s")

    new_tag = "缓存验收标签"  # 固定名：重复运行复用同一标签行，不产生垃圾数据
    _s, body = call(java, "POST", "/wrong-question", {
        "subject": "数学", "content": f"缓存验收错题 {rand('q')}", "answer": "A",
        "difficulty": 1, "mastery": "未掌握", "tags": [new_tag],
    }, token=jwt)
    q_id = ((body or {}).get("data") or {}).get("id")
    check(bool(q_id), "新增带新标签的错题", f"questionId={q_id} tag={new_tag}")
    check(not redis.exists(tags_key), "新增错题 → 标签缓存被删（写失效）")

    s3, b3 = call(java, "GET", "/wrong-question/tags", token=jwt)
    tags3 = (b3 or {}).get("data") or []
    check(new_tag in tags3 and wait_key(redis, tags_key, True),
          "再查新标签可见且缓存回填", f"包含新标签={new_tag in tags3}")

    call(java, "DELETE", f"/wrong-question/{q_id}", token=jwt)
    check(not redis.exists(tags_key), "删除错题 → 标签缓存被删（写失效）")

    s5, b5 = call(java, "GET", "/wrong-question/tags", token=jwt)
    tags5 = (b5 or {}).get("data") or []
    # 注意：标签名**依然在**是设计使然 —— listTagsWithCount 用 LEFT JOIN，
    # "用户建过但暂时没有错题引用的标签"也保留在筛选栏（否则用户建了标签却找不到），
    # 删除错题只清理引用关系（计数归 0、排序靠后）。这里断言的是"缓存被正确回填"，
    # 而不是"标签消失"—— 曾经的错误预期已在首次运行中修正。
    check(new_tag in tags5 and wait_key(redis, tags_key, True),
          "删除后缓存回填（标签行保留是 LEFT JOIN 设计，计数归零）",
          f"包含新标签={new_tag in tags5}")

    # ----------------------------------------------------- D. 用户信息缓存
    print()
    print("--- D. 用户信息缓存（/auth/me 回填 + 微信登录写路径失效）---")
    code = rand("cache-wx-")
    nick_a = rand("缓存验收用户A-")
    _s, body = call(java, "POST", "/auth/wx-login", {"code": code, "nickname": nick_a})
    wx_user = ((body or {}).get("data") or {}).get("userInfo") or {}
    wx_jwt = ((body or {}).get("data") or {}).get("accessToken")
    wx_id = wx_user.get("id")
    check(bool(wx_id and wx_jwt), "mock 微信登录创建测试账号", f"userId={wx_id}")
    if not wx_id:
        return summary()

    user_key = f"user:info:{wx_id}"
    redis.delete(user_key)

    s1, b1 = call(java, "GET", "/auth/me", token=wx_jwt)
    me1 = (b1 or {}).get("data") or {}
    check(s1 == 200 and redis.exists(user_key), "查询用户信息回源并回填缓存",
          f"key={user_key} ttl={redis.ttl(user_key)}s")
    check(1740 <= redis.ttl(user_key) <= 1800, "用户缓存 TTL ≈ 30 分钟", f"ttl={redis.ttl(user_key)}s")
    cached_user = redis.get_json(user_key) or {}
    check(cached_user.get("id") == wx_id and cached_user.get("nickname") == me1.get("nickname"),
          "缓存内容与用户信息一致", f"nickname={cached_user.get('nickname')!r}")

    nick_b = rand("缓存验收用户B-")
    call(java, "POST", "/auth/wx-login", {"code": code, "nickname": nick_b})
    check(not redis.exists(user_key), "微信登录改昵称 → 用户缓存被删（写失效）")

    s3, b3 = call(java, "GET", "/auth/me", token=wx_jwt)
    me3 = (b3 or {}).get("data") or {}
    cached3 = redis.get_json(user_key) or {}
    check(me3.get("nickname") == nick_b and cached3.get("nickname") == nick_b,
          "新昵称立即可见并回填缓存", f"nickname={me3.get('nickname')!r}")

    # ------------------------------------------------------- E. 越权与隔离
    print()
    print("--- E. 越权不走缓存 ---")
    other = rand("cache_b_")
    call(java, "POST", "/auth/register",
         {"username": other, "password": "verify123456", "nickname": other})
    other_jwt, other_id = login(java, other, "verify123456")
    check(bool(other_jwt and other_id), "注册并登录 B 账号", f"userId={other_id}")
    if other_jwt:
        # B 请求 demo 的计划详情：必须 403，且不能产出任何缓存条目
        demo_plan_id = plan_id if not created_plan else None
        if demo_plan_id:
            s, b = call(java, "GET", f"/plan/{demo_plan_id}", token=other_jwt)
            code_ = (b or {}).get("code") if isinstance(b, dict) else None
            b_key = f"plan:detail:{other_id}:{demo_plan_id}"
            check(s == 403 or code_ == 403, "B 读 demo 的计划详情 → 403",
                  f"status={s} code={code_}")
            check(not redis.exists(b_key), "越权请求未写入任何缓存条目", f"key={b_key}")

    redis.close()
    return summary()


def summary() -> int:
    total = PASS + FAIL
    print()
    print("=" * 64)
    if FAIL == 0:
        print(f" 缓存验收：全部通过 ({PASS}/{total})")
    else:
        print(f" 缓存验收：{PASS} 通过 / {FAIL} 失败（共 {total}）")
    for name in FAILED:
        print(f"   - 失败: {name}")
    print("=" * 64)
    return FAIL


if __name__ == "__main__":
    sys.exit(main())
