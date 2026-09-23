#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断 p100 臂新出现的 HTTP 401：与登录阶段失败（token 行缺失）的对应关系。

用法: python loadtest/probe/diag401.py <臂名>
读 target/loadtest/{submit-results,tokens}-<臂>-<轮>.csv。
"""
import csv
import os
import sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "target", "loadtest")


def rows(path):
    with open(path, newline="", encoding="utf-8", errors="replace") as fh:
        return list(csv.DictReader(fh))


def main():
    arm = sys.argv[1] if len(sys.argv) > 1 else "tuned-t400-p100"
    total_tokens_expected = 5000
    print(f"臂 = {arm}")
    for rnd in ("r1", "r2", "r3"):
        sub = os.path.join(BASE, f"submit-results-{arm}-{rnd}.csv")
        tok = os.path.join(BASE, f"tokens-{arm}-{rnd}.csv")
        if not os.path.exists(sub):
            continue
        n_tok = sum(1 for _ in open(tok, encoding="utf-8", errors="replace")) if os.path.exists(tok) else -1
        rs = rows(sub)
        by_code = {}
        for r in rs:
            code = r["responseCode"] or ("<" + (r["failureMessage"] or "empty")[:40] + ">")
            by_code.setdefault(code, []).append(int(float(r["elapsed"])))
        print(f"\n--- {rnd} ---  tokens 行数 {n_tok} (缺 {total_tokens_expected - n_tok})  请求 {len(rs)}")
        for code in sorted(by_code, key=lambda c: -len(by_code[c])):
            els = sorted(by_code[code])
            n = len(els)
            print(f"  {code:<52} n={n:<5} 耗时 中位 {els[n // 2]:>6}ms  最大 {els[-1]:>6}ms  合计 {sum(els) / 1000:>8.1f}s")


if __name__ == "__main__":
    main()
