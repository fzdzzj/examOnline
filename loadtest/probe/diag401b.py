#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对 p100 臂的失败请求做时序与线程分布定位。

用法: python loadtest/probe/diag401b.py <臂名> <轮>
"""
import csv
import os
import sys
from collections import Counter

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "target", "loadtest")


def main():
    arm, rnd = sys.argv[1], sys.argv[2]
    with open(os.path.join(BASE, f"submit-results-{arm}-{rnd}.csv"), newline="", encoding="utf-8", errors="replace") as fh:
        rs = list(csv.DictReader(fh))
    t0 = min(int(r["timeStamp"]) for r in rs)
    t1 = max(int(r["timeStamp"]) for r in rs)
    print(f"{arm}-{rnd}: 窗口 {t1 - t0}ms  请求 {len(rs)}")
    bad = [r for r in rs if r["success"] != "true"]
    print(f"失败 {len(bad)} 笔; 线程名前缀 {Counter(r['threadName'].rsplit('-', 1)[0] for r in bad)}")
    for r in sorted(bad, key=lambda x: int(x["timeStamp"])):
        off = int(r["timeStamp"]) - t0
        print(f"  +{off / 1000:8.2f}s  {r['label']:<14} {r['responseCode']:<8} {int(float(r['elapsed'])):>6}ms  {r['threadName']}")
    ok = sorted(int(r["timeStamp"]) - t0 for r in rs if r["success"] == "true" and r["responseCode"] == "200")
    print(f"200 笔时间戳范围 +{ok[0] / 1000:.2f}s .. +{ok[-1] / 1000:.2f}s")


if __name__ == "__main__":
    main()
