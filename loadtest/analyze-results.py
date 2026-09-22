#!/usr/bin/env python3
"""从 JMeter 逐笔结果 CSV 计算交卷硬指标（add-submit-loadtest）。

为什么要自己算而不是只看 JMeter 的 summariser 汇总表：
  - 汇总表给的是「整体」P99，会把 setUp 阶段的登录样本混进来；交卷指标必须只取
    label = "POST /submit" 的子集；
  - 汇总表不区分「HTTP 200 但业务码非 0」「断言失败」这两类失败，逐笔 CSV 才能分辨；
  - P99 需要逐笔延迟，汇总表只给百分比行、无法复核。

用法：python loadtest/analyze-results.py <jmeter-results.csv> [label]
默认 label = "POST /submit"。
退出码：硬指标全部达标 0；任一项不达标 1（便于脚本化验收）。
"""
import csv
import sys
from collections import Counter

# JMeter 结果 CSV 的标准列序（jmeter.save.saveservice.* 全开时的 17 列）。
# 当文件缺字段名表头时按此列序解析——JMeter 只在「文件不存在」时写表头，
# 用截断方式清空文件会得到「有数据无表头」的 CSV，这里必须能兜住。
DEFAULT_COLUMNS = [
    "timeStamp", "elapsed", "label", "responseCode", "responseMessage",
    "threadName", "dataType", "success", "failureMessage", "bytes",
    "sentBytes", "grpThreads", "allThreads", "URL", "Latency", "IdleTime", "Connect",
]


def load(path: str):
    with open(path, "r", encoding="utf-8-sig", newline="") as fh:
        first = fh.readline()
        fh.seek(0)
        has_header = "timeStamp" in first and "elapsed" in first
        if has_header:
            return list(csv.DictReader(fh)), True
        # 无表头：按标准列序给名字，多出来的列名兜底为 col_n
        return list(csv.DictReader(fh, fieldnames=DEFAULT_COLUMNS)), False


def pct(sorted_vals, q: float) -> float:
    """线性插值分位数（与 JMeter 的分位算法不同，但同样可复算；这里取最近秩）。"""
    if not sorted_vals:
        return float("nan")
    idx = min(len(sorted_vals) - 1, int(round(q * (len(sorted_vals) - 1))))
    return sorted_vals[idx]


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    path = sys.argv[1]
    label = sys.argv[2] if len(sys.argv) > 2 else "POST /submit"

    rows, has_header = load(path)
    if not rows:
        print(f"结果文件为空：{path}")
        return 1

    labels = Counter(r["label"] for r in rows)
    print(f"结果文件: {path}")
    print(f"表头: {'有' if has_header else '无（按标准列序解析）'}")
    print("样本分布: " + ", ".join(f"{k}={v}" for k, v in labels.most_common()))

    target = [r for r in rows if r["label"] == label]
    if not target:
        print(f"未找到 label={label} 的样本")
        return 1

    ok = [r for r in target if r.get("success", "").lower() == "true"]
    bad = [r for r in target if r.get("success", "").lower() != "true"]
    lat = sorted(float(r["elapsed"]) for r in ok)

    print()
    print(f"--- {label} ---")
    print(f"总请求        {len(target)}")
    print(f"成功          {len(ok)}")
    print(f"失败          {len(bad)}")
    if bad:
        codes = Counter(f"HTTP {r.get('responseCode')}: {r.get('responseMessage')}" for r in bad)
        for k, v in codes.most_common():
            print(f"  失败明细    {v} × {k}")
        assert_msgs = Counter(r.get("failureMessage", "") for r in bad if r.get("failureMessage"))
        for k, v in assert_msgs.most_common(5):
            print(f"  断言消息    {v} × {k[:120]}")

    if lat:
        print(f"平均延迟      {sum(lat) / len(lat):.1f} ms")
        print(f"P50           {pct(lat, 0.50):.0f} ms")
        print(f"P90           {pct(lat, 0.90):.0f} ms")
        print(f"P95           {pct(lat, 0.95):.0f} ms")
        print(f"★P99          {pct(lat, 0.99):.0f} ms")
        print(f"最大          {lat[-1]:.0f} ms")
        ts = [float(r["timeStamp"]) for r in target]
        dur_s = (max(ts) - min(ts)) / 1000.0
        print(f"到达窗口      {dur_s:.1f} s（逐笔 timeStamp 跨度）")

    print()
    print("--- 硬指标判定（P99 口径）---")
    p99 = pct(lat, 0.99) if lat else float("nan")
    verdict = []
    verdict.append(("提交 P99 < 2000ms", bool(lat) and p99 < 2000))
    print("\n".join(f"  [{'达标' if v else '不达标'}] {n}" for n, v in verdict))
    print("  注：0 丢单 / 批量落库 < 30s 由 db/02-metrics.sql 与 mq-depth CSV 判定，不在此脚本。")
    return 0 if all(v for _, v in verdict) else 1


if __name__ == "__main__":
    sys.exit(main())
