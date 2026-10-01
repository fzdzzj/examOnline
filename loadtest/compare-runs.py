#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""多轮压测对比：把 target/loadtest/ 下各轮产物汇总成表，并按「臂」取中位数。

为什么单独成脚本：
  P99 只是「客户端观测值」，它同时受服务端服务时长与排队影响。要判断某轮变慢
  是服务端变慢还是排队变长，必须把两侧放在一起看——只用 P99 会把二者混为一谈。
  本脚本把五类口径并排输出：
    ① 客户端逐笔（P50/P90/P95/P99/max、失败数）
    ② 服务端 MVC 计时（sum/count ⇒ 平均处理时长）——应用自己记的账。
       tune-submit-capacity 起 prom-before/prom-after 成对快照可用时就取**本轮净增量**
       （精确），旧轮次没有这对文件时退回「扣减前一累计轮」的启发式（保留以兼容旧产物）。
    ③ 隐含并发度 = 服务端 sum ÷ 外部墙钟；它是「平均有多少请求同时占着 Tomcat
       工作线程」的下界（外部墙钟 ⊇ 应用活跃窗口 ⇒ 该比值只会低估）。把它与
       Tomcat 线程上限（现场来自 sample-*.csv 的 tomcat_max 峰值）对照，就能判断
       请求是否卡在「拿不到线程」上。
    ④ DB 侧硬指标（丢单、落库尾巴时长）
    ⑤ 臂级中位数（G4）：每臂 ≥3 轮，取各硬指标的中位数做判定——单轮不得作为容量结论。

用法：
  python loadtest/compare-runs.py run1 run2 run3 [run4 ...]
  python loadtest/compare-runs.py default=d1,d2,d3 tuned=t1,t2,t3   # 臂分组，附加中位数表
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


def median(vals):
    vals = sorted(v for v in vals if v is not None)
    if not vals:
        return None
    n = len(vals)
    return vals[n // 2] if n % 2 else (vals[n // 2 - 1] + vals[n // 2]) / 2.0


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


def _prom_counts(path):
    """从 Prometheus 裸文本取 submit 接口 status=200 的 (count, sum)。

    标签实测形态（别猜）：uri 里的大括号是**字面**的，不做转义 ——
      http_server_requests_seconds_count{error="none",...,status="200",
        uri="/api/exam-taking/exams/{examId}/submit"} 20
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    pat = (r'^http_server_requests_seconds_(sum|count)\{[^}]*status="200"[^}]*'
           r'uri="/api/exam-taking/exams/\{examId\}/submit"\}\s+([0-9.eE+-]+)')
    found = {}
    for m in re.finditer(pat, text, re.M):
        found[m.group(1)] = float(m.group(2))
    if "sum" not in found or "count" not in found or found["count"] == 0:
        return None
    return {"sum": found["sum"], "count": int(found["count"])}


def read_server(tag):
    """优先用 prom-before/prom-after 净增量（G4-b）；缺这对文件时返回 after 原值 + 标记。

    第三种情况必须与上一种区分开：**prom-before 存在、但里面没有该 meter**。
    Micrometer 的 meter 是首次请求时才注册的，所以每个臂的第 1 轮 before 快照里
    根本没有 submit 这一条 —— 那不是「取数失败」，而是「本轮开始时计数确实是 0」。
    """
    after = OUT / f"prom-after-{tag}.txt"
    if not after.exists():
        return None
    a = _prom_counts(after)
    if not a:
        return None
    before = OUT / f"prom-before-{tag}.txt"
    if before.exists():
        b = _prom_counts(before) or {"count": 0, "sum": 0.0}   # before 无该 meter ⇒ 基线 0
        dc, ds = a["count"] - b["count"], a["sum"] - b["sum"]
        if dc > 0:
            return {"count": dc, "sum": ds, "avg_ms": ds / dc * 1000, "delta": True}
    return {"count": a["count"], "sum": a["sum"], "avg_ms": a["sum"] / a["count"] * 1000, "delta": False}


def read_samples(tag):
    """读持续采样时间线（G5），返回各列峰值；文件不存在返回 None。"""
    path = OUT / f"sample-{tag}.csv"
    if not path.exists():
        return None
    with path.open(newline="", encoding="utf-8", errors="replace") as fh:
        rows = list(csv.DictReader(fh))
    if not rows:
        return None
    peaks, counts = {}, {}
    for key in (rows[0] or {}).keys():
        vals = []
        for r in rows:
            v = (r.get(key) or "").strip()
            if v in ("", "NA"):
                continue
            try:
                vals.append(float(v))
            except ValueError:
                continue
        if vals:
            peaks[key] = max(vals)
            counts[key] = len(vals)
    peaks["_rows"] = len(rows)
    peaks["_counts"] = counts
    return peaks


# ---- G5 的第二条证据线：连接池的**累计量净增量**（不依赖采样，因此不受采样周期限制）----
# 为什么需要它：采样周期在本机被「进程 spawn ~0.43s/次」顶到 ~3s，10s 突发窗口只能采到
# 3~5 个点，**采样峰值可能漏掉尖峰**。而下面这些量是从 prom-before/prom-after 直接做差的：
#   acquire_count/sum → 本轮取连接次数与平均等待；acquire_max/usage_max → 2 分钟衰减窗口内的
#   最差值（只要紧跟突发取，就覆盖了整个突发窗口）；timeout_total → 硬失败次数。
# 三个量合起来能直接回答「池是不是约束」，比「采样到的 pending 峰值」强得多。
POOL_METRICS = {
    "acquire_count": ("hikaricp_connections_acquire_seconds_count", "Δ取连接次数", "sum"),
    "acquire_sum": ("hikaricp_connections_acquire_seconds_sum", "Δ取连接总耗时", "sum"),
    "acquire_max": ("hikaricp_connections_acquire_seconds_max", "取连接最差(窗口max)", "max"),
    "usage_max": ("hikaricp_connections_usage_seconds_max", "连接持有最差(窗口max)", "max"),
    "timeout_total": ("hikaricp_connections_timeout_total", "Δ连接超时次数", "sum"),
    "pool_max": ("hikaricp_connections_max", "池上限", "max"),
}


def _prom_gauge(text, name, pool="master"):
    """取带 pool 标签的指标值；不存在返回 None。"""
    pat = re.compile(r'^' + re.escape(name) + r'\{[^}]*pool="' + re.escape(pool) + r'"[^}]*\}\s+([0-9.eE+-]+)', re.M)
    m = pat.search(text)
    return float(m.group(1)) if m else None


def read_pool(tag):
    """返回本轮连接池证据：累计量取 before→after 的净增量，窗口 max 取 after 值。"""
    after_p = OUT / f"prom-after-{tag}.txt"
    if not after_p.exists():
        return None
    after = after_p.read_text(encoding="utf-8", errors="replace")
    before_p = OUT / f"prom-before-{tag}.txt"
    before = before_p.read_text(encoding="utf-8", errors="replace") if before_p.exists() else ""
    got = {}
    for key, (name, _label, kind) in POOL_METRICS.items():
        a = _prom_gauge(after, name)
        if a is None:
            continue
        if kind == "sum":
            b = _prom_gauge(before, name) or 0.0
            got[key] = a - b
        else:
            got[key] = a
    return got or None


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


def parse_argv(argv):
    """返回 (tags_in_order, arms)，arms = [(arm_name, [tags]), ...]（无 '=' 时为空）。"""
    tags, arms = [], []
    for a in argv:
        if "=" in a:
            name, rhs = a.split("=", 1)
            arm_tags = [t for t in rhs.split(",") if t]
            arms.append((name, arm_tags))
            tags.extend(arm_tags)
        else:
            tags.append(a)
    return tags, arms


def main(argv):
    tags, arms = parse_argv(argv)
    if not tags:
        tags, arms = ["run1", "run2", "run3", "run4"], []

    print("## ① 客户端逐笔（P99 的唯一数据源）")
    print()
    print("| 轮次 | n | 失败 | 均值 | P50 | P90 | P95 | **P99** | max | 到达跨度 | 全程墙钟 |")
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
    print("## ② 服务端逐轮（sum/count 取本轮净增量，回退口径会标注）")
    print()
    print("| 轮次 | 服务端 count | 服务端 sum | 服务端均值 | 口径 | 隐含并发度=sum/墙钟 | 线程上限 | 占用率 | DB 丢单 | 落库尾巴 |")
    print("| --- | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |")
    prev = None
    for tag in tags:
        s = read_server(tag)
        d = data.get(tag, {})
        db = read_db(tag) or {}
        smp = read_samples(tag) or {}
        if not (s and "client" in d):
            print(f"| {tag} | 缺数据（prom-after-{tag}.txt / submit-results） |")
            continue
        count, total, note = s["count"], s["sum"], "净增量" if s.get("delta") else "累计值"
        # 旧产物兜底：同一实例连跑两轮时 Prometheus 计数器是累计值，须扣减前一轮
        if not s.get("delta") and prev and count > d["client"]["n"] and abs((count - prev[0]) - d["client"]["n"]) <= 1:
            count, total = count - prev[0], total - prev[1]
            note = "已扣减前轮"
        prev = (s["count"], s["sum"])
        implied = total / d["client"]["wall_s"]
        tmax = smp.get("tomcat_max") or 200.0
        print(
            f"| {tag} | {count} | {total:.2f}s | {total / count * 1000:.0f}ms | {note} | "
            f"≥{implied:.1f} | {tmax:.0f} | {implied / tmax * 100:.1f}% | {db.get('lost', '?')} | "
            f"{db.get('persist_tail_ms', '?')}ms |"
        )

    print()
    print("## ③ 连接池证据（G5：累计量净增量，不受采样周期限制）")
    print()
    print("| 轮次 | Δ取连接次数 | Δ取连接均值 | 取连接最差(窗口max) | 连接持有最差(窗口max) | Δ连接超时次数 | 池上限 |")
    print("| --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    for tag in tags:
        p = read_pool(tag)
        if not p:
            print(f"| {tag} | 缺数据（prom-after-{tag}.txt 里无 hikaricp 指标） |")
            continue
        cnt, tot = p.get("acquire_count"), p.get("acquire_sum")
        avg = f"{tot / cnt * 1000:.2f}ms" if cnt and tot is not None and cnt > 0 else "—"
        amax = p.get("acquire_max")
        umax = p.get("usage_max")
        print(
            f"| {tag} | {cnt if cnt is not None else '—'} | {avg} | "
            f"{f'{amax * 1000:.2f}ms' if amax is not None else '—'} | "
            f"{f'{umax * 1000:.2f}ms' if umax is not None else '—'} | "
            f"{p.get('timeout_total', '—')} | {p.get('pool_max', '—')} |"
        )

    if arms:
        print()
        print("## ④ 臂级中位数（G4：每臂 ≥3 轮，判定看中位数不看单轮）")
        print()
        print("| 臂 | 轮数 | P99 中位数 | 客户端均值中位数 | 服务端均值中位数 | 隐含并发度中位数 | 丢单合计 | 落库尾巴中位数 | Tomcat忙线程峰值中位数 | 池 pending 峰值中位数 | 取连接最差(臂内最大) | P99 判定 |")
        print("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |")
        for name, arm_tags in arms:
            p99s, avgs, savgs, imps, losts, tails, busy, hp, amax = [], [], [], [], [], [], [], [], []
            wall_ok = 0
            for tag in arm_tags:
                c = read_client(tag)
                s = read_server(tag)
                db = read_db(tag) or {}
                smp = read_samples(tag) or {}
                pool = read_pool(tag) or {}
                if c:
                    p99s.append(c["p99"])
                    avgs.append(c["avg"])
                    wall_ok += 1
                if s and c:
                    savgs.append(s["avg_ms"])
                    imps.append(s["sum"] / c["wall_s"])
                if db:
                    losts.append(db.get("lost", 0))
                    tails.append(db.get("persist_tail_ms"))
                if smp:
                    if "tomcat_busy" in smp:
                        busy.append(smp["tomcat_busy"])
                    if "hikari_pending" in smp:
                        hp.append(smp["hikari_pending"])
                if pool.get("acquire_max") is not None:
                    amax.append(pool["acquire_max"] * 1000)
            m_p99 = median(p99s)
            verdict = "—" if m_p99 is None else ("**达标**" if m_p99 < 2000 else "**不达标**")
            fmt = lambda v, f="{:,.0f}": "—" if v is None else f.format(v)  # noqa: E731
            print(
                f"| {name} | {wall_ok} | {fmt(m_p99)}ms | {fmt(median(avgs))}ms | {fmt(median(savgs))}ms | "
                f"≥{fmt(median(imps), '{:.1f}')} | {fmt(sum(losts)) if losts else '—'} | "
                f"{fmt(median(tails))}ms | {fmt(median(busy))} | {fmt(median(hp))} | "
                f"{fmt(max(amax), '{:,.2f}ms') if amax else '—'} | {verdict} |"
            )


if __name__ == "__main__":
    main(sys.argv[1:])
