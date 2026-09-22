#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""多轮压测对比：把 target/loadtest/ 下各轮产物汇总成一张表。

为什么单独成脚本：
  P99 只是「客户端观测值」，它同时受服务端服务时长与排队影响。要判断某轮变慢
  是服务端变慢还是排队变长，必须把两侧放在一起看——只用 P99 会把二者混为一谈。
  本脚本把四类口径并排输出：
    ① 客户端逐笔（P50/P90/P95/P99/max、失败数）
    ② 服务端 MVC 计时（sum/count ⇒ 平均处理时长）——应用自己记的账
    ③ 隐含并发度 = 服务端 sum ÷ 外部墙钟；它是「平均有多少请求同时占着 Tomcat
       工作线程」的下界（外部墙钟 ⊇ 应用活跃窗口 ⇒ 该比值只会低估）。把它与
       Tomcat 线程上限对照，就能判断请求是否卡在「拿不到线程」上。
    ④ DB 侧硬指标（丢单、落库尾巴时长）

用法：python loadtest/compare-runs.py run1 run2 run3 [run4 ...]
"""
import csv
import re
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "target" / "loadtest"
SUBMIT_LABEL = "POST /submit"


def pct(vals, p):
    if not vals:
        return None
    s = sorted(vals)
    idx = min(len(s) - 1, max(0, int(round(p / 100.0 * len(s) + 0.5)) - 1))
    return s[idx]


def read_client(tag):
    path = OUT / f"submit-results-{tag}.csv"
    if not path.exists():
        return None
    with path.open(newline="", encoding="utf-8", errors="replace") as fh:
        rows = [r for r in csv.DictReader(fh) if r.get("label") == SUBMIT_LABEL]
    if not rows:
        return None
    ts = [int(r["timeStamp"]) for r in rows]
    el = [int(r["elapsed"]) for r in rows]
    done = [t + e for t, e in zip(ts, el)]
    ok = sum(1 for r in rows if r.get("success") == "true")
    return {
        "n": len(rows),
        "ok": ok,
        "fail": len(rows) - ok,
        "avg": sum(el) / len(el),
        "p50": pct(el, 50),
        "p90": pct(el, 90),
        "p95": pct(el, 95),
        "p99": pct(el, 99),
        "max": max(el),
        "arrival_s": (max(ts) - min(ts)) / 1000.0,
        "wall_s": (max(done) - min(ts)) / 1000.0,
    }


def read_server(tag):
    path = OUT / f"prom-after-{tag}.txt"
    if not path.exists():
        return None
    text = path.read_text(encoding="utf-8", errors="replace")
    pat = r'^http_server_requests_seconds_(sum|count)\{[^}]*status="200"[^}]*uri="/api/exam-taking/exams/\{examId\}/submit"\}\s+([0-9.eE+-]+)'
    found = {}
    for m in re.finditer(pat, text, re.M):
        found[m.group(1)] = float(m.group(2))
    if "sum" not in found or "count" not in found or found["count"] == 0:
        return None
    return {"sum": found["sum"], "count": int(found["count"]), "avg_ms": found["sum"] / found["count"] * 1000}


def read_db(tag):
    """解析 mysql -t 的两张表。表 A 是「指标|值」两列；表 B 是四个时效列。"""
    path = OUT / f"metrics-{tag}.txt"
    if not path.exists():
        return None
    text = path.read_text(encoding="utf-8", errors="replace")
    got = {}
    # 表 B：先定位表头行，再取紧随其后的第一条数据行（跳过 +---+ 分隔线）
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if "persist_tail_ms" in line:
            for nxt in lines[i + 1:]:
                cells = [c.strip() for c in nxt.strip().strip("|").split("|")]
                if len(cells) == 4:
                    try:
                        got["persist_tail_ms"] = float(cells[3])
                    except ValueError:
                        continue
                    break
            break
    # 表 A
    for line in lines:
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) != 2:
            continue
        key, val = cells
        if not key or key.startswith("+") or key == "metric":
            continue
        try:
            num = float(val)
        except ValueError:
            continue
        if "丢单" in key and "IS NULL" in key:
            got["lost"] = num
        elif key.startswith("已交卷"):
            got["submitted"] = num
        elif key.startswith("进行中"):
            got["in_progress"] = num
    return got or None


def main(tags):
    print("| 轮次 | 客户端 n | 失败 | 均值 | P50 | P90 | P95 | **P99** | max | 到达跨度 | 全程墙钟 |")
    print("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    data = {}
    for tag in tags:
        c = read_client(tag)
        if not c:
            print(f"| {tag} | （缺 submit-results-{tag}.csv） |")
            continue
        data[tag] = {"client": c}
        print(
            f"| {tag} | {c['n']} | {c['fail']} | {c['avg']:.0f}ms | {c['p50']}ms | {c['p90']}ms | "
            f"{c['p95']}ms | **{c['p99']}ms** | {c['max']}ms | {c['arrival_s']:.1f}s | {c['wall_s']:.1f}s |"
        )

    print()
    print("| 轮次 | 服务端 count | 服务端 sum | 服务端均值 | 隐含并发度=sum/墙钟 | 占 200 线程 | DB 丢单 | 落库尾巴 |")
    print("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    prev = None
    for tag in tags:
        s = read_server(tag)
        d = data.get(tag, {})
        db = read_db(tag) or {}
        if not (s and "client" in d):
            print(f"| {tag} | 缺数据（prom-after-{tag}.txt / submit-results） |")
            continue
        count, total, note = s["count"], s["sum"], ""
        # 同一实例连跑两轮时 Prometheus 计数器是累计值，须扣减前一轮，
        # 否则「服务端均值」会被前一轮稀释（实测 run4 未扣减时算出 483ms，扣减后 447ms）。
        if prev and count > d["client"]["n"] and abs((count - prev[0]) - d["client"]["n"]) <= 1:
            count, total = count - prev[0], total - prev[1]
            note = "（已扣减前轮）"
        prev = (s["count"], s["sum"])
        implied = total / d["client"]["wall_s"]
        print(
            f"| {tag} | {count} | {total:.2f}s | {total / count * 1000:.0f}ms | "
            f"≥{implied:.1f} | {implied / 200 * 100:.1f}% | {db.get('lost', '?')} | "
            f"{db.get('persist_tail_ms', '?')}ms {note} |"
        )


if __name__ == "__main__":
    argv = sys.argv[1:]
    if not argv:
        argv = ["run1", "run2", "run3", "run4"]
    main(argv)
