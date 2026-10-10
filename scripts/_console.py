"""验收脚本共用的输出编码保护。

为什么需要它（来自 docs/踩坑记录.md 的真实事故）：
    Windows 控制台默认是 GBK。验收脚本会把**接口返回的原文**打进 detail 信息，
    而那些原文里经常含数学符号（∫₀¹ 的下标 ₀、∫ₐᵇ 等）与 emoji。
    `print()` 遇到 GBK 编不出的字符会抛 `UnicodeEncodeError`，
    **直接中断整个验收脚本** —— 表现是"跑到一半突然退出、退出码还不是失败数"，
    让人误以为是被测服务挂了。

    实测就踩过：verify_week3_rag.py 在打印 citations 的 snippet 时崩掉，
    前面 18 项已经通过，但因为进程异常退出，结果无法汇总。

解决：在任何输出之前把 stdout/stderr 切成 UTF-8 + errors="replace"。
`errors="replace"` 是关键 —— 宁可把极少数生僻字符显示成 `?`，
也不能因为一个字符让整轮验收白跑。
"""

from __future__ import annotations

import sys


def enable_utf8_output() -> None:
    """把标准输出/错误切成 UTF-8（幂等，可多次调用）。"""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
        except Exception:  # noqa: BLE001 - 非标准流（如被重定向成对象）时忽略
            pass


__all__ = ["enable_utf8_output"]
