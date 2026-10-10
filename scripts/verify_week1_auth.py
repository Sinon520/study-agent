#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
第 1 周验收脚本：登录认证全流程（服务端 HTTP 黑盒测试）

为什么用 Python 而不是 PowerShell：
    本机 shell 宿主是 Windows PowerShell 5.1，它的 Invoke-WebRequest
    在 401 场景下会把响应体吞掉、Headers 访问还偶发 "无法对 Null 数组进行索引"，
    导致"服务明明正常但验收脚本全红"。Python 的 http.client 行为确定，适合做验收。

用法：
    1) 先启动服务
         java -jar server-java/target/server-java-1.0.0-SNAPSHOT.jar
    2) 运行
         python scripts/verify_week1_auth.py
         python scripts/verify_week1_auth.py --base-url http://127.0.0.1:8080

退出码：失败项数量（0 表示全部通过），便于接进 CI。
"""

from __future__ import annotations

import argparse
import json
import random
import string
import sys
import urllib.error
import urllib.request

PASS = 0
FAIL = 0


def step(title: str) -> None:
    print()
    print(f"--- {title}")


def check(cond: bool, desc: str, detail: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {desc}")
    else:
        FAIL += 1
        print(f"  [FAIL] {desc}")
    if detail:
        print(f"         {detail}")


class Api:
    """极简 HTTP 客户端：不抛异常，把状态码 + body + 头一起返回。"""

    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")

    def __call__(self, method: str, path: str, body=None, token: str | None = None) -> dict:
        url = self.base_url + path
        data = None
        headers = {"Content-Type": "application/json; charset=utf-8"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        if token:
            headers["Authorization"] = f"Bearer {token}"

        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read().decode("utf-8", errors="replace")
                return {
                    "status": resp.status,
                    "headers": {k.lower(): v for k, v in resp.headers.items()},
                    "text": raw,
                    "json": self._parse(raw),
                }
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", errors="replace")
            return {
                "status": e.code,
                "headers": {k.lower(): v for k, v in (e.headers or {}).items()},
                "text": raw,
                "json": self._parse(raw),
            }
        except Exception as e:  # 连接被拒、超时等
            return {"status": -1, "headers": {}, "text": f"{type(e).__name__}: {e}", "json": None}

    @staticmethod
    def _parse(raw: str):
        try:
            return json.loads(raw)
        except Exception:
            return None


def rand_username() -> str:
    return "verify_" + "".join(random.choices(string.digits, k=5))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--username", default=None)
    parser.add_argument("--password", default="verify123456")
    args = parser.parse_args()

    api = Api(args.base_url)
    username = args.username or rand_username()
    password = args.password

    print("=" * 64)
    print(" 第 1 周验收 · 登录认证全流程")
    print(f" 目标服务: {args.base_url}")
    print(f" 测试账号: {username} / {password}")
    print("=" * 64)

    # ---------------------------------------------------------------- A
    step("A. 健康检查与 traceId")
    health = api("GET", "/health")
    check(health["status"] == 200, "GET /health 返回 200", f"实际: {health['status']} {health['text'][:120]}")
    check((health["json"] or {}).get("code") == 200, "body.code = 200",
          f"data.status = {(health['json'] or {}).get('data', {}).get('status')}")
    trace_header = health["headers"].get("x-trace-id")
    check(bool(trace_header), "响应头存在 X-Trace-Id", f"X-Trace-Id = {trace_header}")
    check((health["json"] or {}).get("traceId") == trace_header,
          "body.traceId 与响应头一致", f"body = {(health['json'] or {}).get('traceId')}")

    # traceId 透传：带自定义头应原样返回
    req = urllib.request.Request(
        args.base_url.rstrip("/") + "/health",
        headers={"X-Trace-Id": "FIXEDTRACE0000111122223333AAAA"},
        method="GET",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        echoed = resp.headers.get("X-Trace-Id")
    check(echoed == "FIXEDTRACE0000111122223333AAAA", "调用方传入的 X-Trace-Id 被透传回响应头",
          f"echoed = {echoed}")

    # ---------------------------------------------------------------- B
    step("B. 接口文档")
    docs = api("GET", "/v3/api-docs")
    check(docs["status"] == 200, "GET /v3/api-docs 返回 200", f"实际: {docs['status']}")
    check("/health" in docs["text"], "文档包含 /health")
    check("/auth/login" in docs["text"], "文档包含 /auth/login")

    # ---------------------------------------------------------------- C
    step("C. 注册与登录")
    reg = api("POST", "/auth/register",
              {"username": username, "password": password, "nickname": "验收同学", "examType": "考研"})
    check(reg["status"] == 200 and (reg["json"] or {}).get("code") == 200,
          "POST /auth/register 注册成功",
          f"code={(reg['json'] or {}).get('code')} msg={(reg['json'] or {}).get('msg')}")

    # 重复注册同一账号必须被拒绝
    dup = api("POST", "/auth/register", {"username": username, "password": password})
    check((dup["json"] or {}).get("code") == 400, "重复用户名被拒绝",
          f"code={(dup['json'] or {}).get('code')} msg={(dup['json'] or {}).get('msg')}")

    login = api("POST", "/auth/login", {"username": username, "password": password})
    lj = login["json"] or {}
    check(lj.get("code") == 200, "POST /auth/login 登录成功", f"code={lj.get('code')} msg={lj.get('msg')}")
    data = lj.get("data") or {}
    access_token = data.get("accessToken")
    refresh_token = data.get("refreshToken")
    check(bool(access_token), "拿到 accessToken", f"长度 = {len(access_token or '')}")
    check(bool(refresh_token), "拿到 refreshToken", f"长度 = {len(refresh_token or '')}")
    check(data.get("expiresIn") == 7200, "accessToken 有效期 2h", f"expiresIn = {data.get('expiresIn')}")
    check((data.get("userInfo") or {}).get("username") == username, "返回 userInfo",
          f"username = {(data.get('userInfo') or {}).get('username')}")
    check("password" not in json.dumps(data, ensure_ascii=False), "返回体不含 password 字段")

    bad = api("POST", "/auth/login", {"username": username, "password": "wrong_password"})
    check((bad["json"] or {}).get("code") == 400, "错误密码被拒绝",
          f"code={(bad['json'] or {}).get('code')} msg={(bad['json'] or {}).get('msg')}")

    # 超长密码必须被参数校验挡在 BCrypt 之前（防 CPU 放大）
    huge = api("POST", "/auth/login", {"username": username, "password": "x" * 5000})
    check((huge["json"] or {}).get("code") == 400, "超长密码被参数校验拦截",
          f"code={(huge['json'] or {}).get('code')} msg={(huge['json'] or {}).get('msg')}")

    # 不存在的用户名与错误密码返回相同提示（防账号枚举）
    ghost = api("POST", "/auth/login", {"username": "no_such_user_zzz", "password": "whatever123"})
    check((ghost["json"] or {}).get("msg") == (bad["json"] or {}).get("msg"),
          "用户不存在与密码错误返回同一提示", f"ghost={(ghost['json'] or {}).get('msg')}")

    # ---------------------------------------------------------------- D
    step("D. 带 token 访问受保护接口")
    profile = api("GET", "/user/profile", token=access_token)
    check(profile["status"] == 200 and (profile["json"] or {}).get("code") == 200,
          "GET /user/profile 返回 200", f"实际: {profile['status']}")
    check((((profile["json"] or {}).get("data") or {}).get("username")) == username,
          "返回 token 对应的用户", f"username = {(((profile['json'] or {}).get('data') or {}).get('username'))}")

    # ---------------------------------------------------------------- E
    step("E. 未认证访问")
    no_token = api("GET", "/user/profile")
    check(no_token["status"] == 401, "不带 token → HTTP 401", f"实际: {no_token['status']}")
    check((no_token["json"] or {}).get("code") == 401, "body.code = 401",
          f"msg = {(no_token['json'] or {}).get('msg')}")

    forged = api("GET", "/user/profile", token="eyJhbGciOiJIUzI1NiJ9.fake.signature")
    check(forged["status"] == 401, "伪造 token → HTTP 401", f"实际: {forged['status']}")

    # refresh token 不能当 access token 用（类型隔离）
    misuse = api("GET", "/user/profile", token=refresh_token)
    check(misuse["status"] == 401, "refreshToken 不能当 accessToken 用", f"实际: {misuse['status']}")

    # ---------------------------------------------------------------- F
    step("F. 刷新令牌（轮换）")
    refresh = api("POST", "/auth/refresh", {"refreshToken": refresh_token})
    rj = refresh["json"] or {}
    check(rj.get("code") == 200, "POST /auth/refresh 成功", f"code={rj.get('code')} msg={rj.get('msg')}")
    rdata = rj.get("data") or {}
    new_access = rdata.get("accessToken")
    new_refresh = rdata.get("refreshToken")
    check(bool(new_access), "拿到新的 accessToken")
    check(new_refresh and new_refresh != refresh_token, "refreshToken 已轮换（新旧不同）")

    profile2 = api("GET", "/user/profile", token=new_access)
    check((profile2["json"] or {}).get("code") == 200, "新 accessToken 可用",
          f"username = {(((profile2['json'] or {}).get('data') or {}).get('username'))}")

    # ---------------------------------------------------------------- G
    step("G. 旧 refreshToken 二次使用（应失败）")
    reuse = api("POST", "/auth/refresh", {"refreshToken": refresh_token})
    check((reuse["json"] or {}).get("code") == 401, "旧 refreshToken 已失效",
          f"code={(reuse['json'] or {}).get('code')} msg={(reuse['json'] or {}).get('msg')}")

    # ---------------------------------------------------------------- H
    step("H. 登出后令牌失效")
    logout = api("POST", "/auth/logout", {"refreshToken": new_refresh}, token=new_access)
    check((logout["json"] or {}).get("code") == 200, "POST /auth/logout 成功",
          f"msg = {(logout['json'] or {}).get('msg')}")
    after = api("POST", "/auth/refresh", {"refreshToken": new_refresh})
    check((after["json"] or {}).get("code") == 401, "登出后 refreshToken 失效",
          f"code={(after['json'] or {}).get('code')} msg={(after['json'] or {}).get('msg')}")

    # ---------------------------------------------------------------- I
    step("I. 小程序 mock 登录")
    wx_code = f"test_code_{username}"
    wx = api("POST", "/auth/wx-login", {"code": wx_code, "nickname": "小程序验收"})
    wj = wx["json"] or {}
    check(wj.get("code") == 200, "POST /auth/wx-login 成功", f"code={wj.get('code')} msg={wj.get('msg')}")
    wdata = wj.get("data") or {}
    wx_id = (wdata.get("userInfo") or {}).get("id")
    check(bool(wdata.get("accessToken")), "拿到 accessToken（自动建号）", f"userId = {wx_id}")

    wx2 = api("POST", "/auth/wx-login", {"code": wx_code})
    wx_id2 = (((wx2["json"] or {}).get("data") or {}).get("userInfo") or {}).get("id")
    check(wx_id is not None and wx_id == wx_id2, "同一 code 二次登录命中同一用户（mock openid 稳定）",
          f"第一次 userId = {wx_id}，第二次 userId = {wx_id2}")

    # 不同 code 必须是不同用户
    wx3 = api("POST", "/auth/wx-login", {"code": wx_code + "_other"})
    wx_id3 = (((wx3["json"] or {}).get("data") or {}).get("userInfo") or {}).get("id")
    check(wx_id3 is not None and wx_id3 != wx_id, "不同 code 建出不同用户",
          f"另一个 userId = {wx_id3}")

    # 小程序 token 能访问受保护接口
    wx_profile = api("GET", "/user/profile", token=wdata.get("accessToken"))
    check((wx_profile["json"] or {}).get("code") == 200, "小程序 token 可访问受保护接口",
          f"username = {(((wx_profile['json'] or {}).get('data') or {}).get('username'))}")

    # ---------------------------------------------------------------- J
    step("J. 登出（全端下线）")
    login_a = api("POST", "/auth/login", {"username": username, "password": password})
    login_b = api("POST", "/auth/login", {"username": username, "password": password})
    rt_a = ((login_a["json"] or {}).get("data") or {}).get("refreshToken")
    rt_b = ((login_b["json"] or {}).get("data") or {}).get("refreshToken")
    at_a = ((login_a["json"] or {}).get("data") or {}).get("accessToken")
    check(rt_a != rt_b, "两次登录产生不同的 refreshToken（多端并存）")

    # 不带 refreshToken 登出 → 清理该用户全部 refreshToken
    allout = api("POST", "/auth/logout", {}, token=at_a)
    check((allout["json"] or {}).get("code") == 200, "不带 refreshToken 的登出成功",
          f"msg = {(allout['json'] or {}).get('msg')}")
    dead_a = api("POST", "/auth/refresh", {"refreshToken": rt_a})
    dead_b = api("POST", "/auth/refresh", {"refreshToken": rt_b})
    check((dead_a["json"] or {}).get("code") == 401 and (dead_b["json"] or {}).get("code") == 401,
          "全端下线后两个 refreshToken 都失效",
          f"a={(dead_a['json'] or {}).get('code')} b={(dead_b['json'] or {}).get('code')}")

    # ---------------------------------------------------------------- 汇总
    total = PASS + FAIL
    print()
    print("=" * 64)
    if FAIL == 0:
        print(f" 验收结果：全部通过 ({PASS}/{total})")
    else:
        print(f" 验收结果：{PASS} 通过 / {FAIL} 失败（共 {total}）")
    print("=" * 64)
    return FAIL


if __name__ == "__main__":
    sys.exit(main())
