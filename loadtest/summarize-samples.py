#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""压测期间持续采样（G5）的峰值汇总：把采样时间线压成一张可入报告的峰值表。

为什么必须有它：
  原报告只有「突发结束后的瞬时快照」，gauge 类型的连接池/线程水位**无法回看峰值**，
  于是「池未成为约束」只能是弱结论。本脚本消费的是 run-loadtest.sh 在压测窗口内
  持续写入的时间线，输出每个口径的**采样峰值**、中位数与「窗口内是否取到该口径」。

口径说明（诚实声明要写进报告）：
  - 峰值 = 采样点上的最大值。真实峰值只能逼近：采样周期 ≈ 间隔 + 单次取数耗时
    （应用侧 ~0.25s+0.3s，DB/MQ 侧 ~1s+1.5s），故本表是**下界**而非精确峰值。
  - NA 表示该次采样没取到（端点不可用/瞬时失败），不计入统计；整列全 NA 时会显式
    标注「该口径不可用」，**不得当作「值为 0」**。
  - 列名到中文口径的映射见 LABELS；未登记的列按原名输出。

用法：python loadtest/summarize-samples.py <csv> [<csv> ...]
      py loadtest/summarize-samples.py target/loadtest/sample-<TAG>.csv target/loadtest/mq-depth-<TAG>.csv
"""
import csv
import sys
from pathlib import Path

LABELS = {
    "tomcat_busy": "Tomcat 忙线程数（正被占用的工作线程）",
    "tomcat_current": "Tomcat 当前线程数（含空闲）",
    "tomcat_max": "Tomcat 线程上限（= server.tomcat.threads.max）",
    "hikari_pending": "Hikari 等待连接的线程数",
    "hikari_active": "Hikari 活跃连接数",
    "hikari_max": "Hikari 池上限",
    "proc_cpu": "应用进程 CPU 使用率（占单核比例，1.0 = 满一核）",
    "sys_cpu": "系统 CPU 使用率（占全部逻辑核比例）",
    "mysql_connected": "MySQL Threads_connected（服务端侧连接数）",
    "mysql_running": "MySQL Threads_running",
    "q_ready": "交卷队列 ready 深度",
    "q_unack": "交卷队列 unacked 深度",
    "not_persisted": "未落库计数（status=2 且 answers IS NULL）",
}


def to_floats(rows, key):
    vals = []
    for r in rows:
        v = (r.get(key) or "").strip()
        if v in ("", "NA"):
            continue
        try:
            vals.append(float(v))
        except ValueError:
            continue
    return vals


def summarize(path: Path) -> None:
    if not path.exists():
        print(f"（采样文件不存在，跳过：{path}）")
        print()
        return
    with path.open(newline="", encoding="utf-8", errors="replace") as fh:
        rows = list(csv.DictReader(fh))
    if not rows:
        print(f"（采样文件为空，跳过：{path}）")
        print()
        return

    ts = to_floats(rows, "ts")
    span = (max(ts) - min(ts)) / 1000.0 if len(ts) > 1 else 0.0
    print(f"采样文件: {path.name}")
    print(f"样本数: {len(rows)}（观测跨度 {span:.1f}s）")
    print()
    print("| 口径 | 有效样本 | 峰值 | 中位数 |")
    print("| --- | ---: | ---: | ---: |")
    for key in rows[0].keys():
        if key == "ts":
            continue
        label = LABELS.get(key, key)
        vals = to_floats(rows, key)
        if not vals:
            print(f"| {label} | 0 | 该口径不可用（全 NA） | — |")
            continue
        s = sorted(vals)
        median = s[len(s) // 2]
        print(f"| {label} | {len(vals)} | **{max(vals):.3f}** | {median:.3f} |")

    # 「线程上限在整个窗口内没变过」= 注入值确实作用于运行期（而不是只写进了命令行）
    mx = to_floats(rows, "tomcat_max")
    if mx and len(set(mx)) == 1:
        print()
        print(f"窗口内 Tomcat 线程上限恒为 {mx[0]:.0f}（运行期读取值，非命令行自述）")
    print()


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    for arg in sys.argv[1:]:
        summarize(Path(arg))
    return 0


if __name__ == "__main__":
    sys.exit(main())
