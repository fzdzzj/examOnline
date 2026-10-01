# 测量原件登记（库外 raw）

> 裁决输入＝本目录 `adjudication.json`（`analyze-grading-score-projection.cjs` 按冻结 M1–M5/S1–S4 算子机械输出）。
> 下列库外原件的绝对路径与 sha256 供独立复核；关键件同时拷贝入 `evidence/`。
> 验收边界：一次性本地 MySQL 8 容器（tmpfs、127.0.0.1 高位端口、独立库，用毕销毁）+ 单机 + 空并发 + 进程内测试上下文。

| 文件 | 说明 | 字节 | sha256 |
|---|---|---:|---|
| `rounds-n200.json` | raw rounds JSON n=200 | 1867253 | `34586fe2fc5b484209a81caed62d17919aa2ba32129b771ed125e593ad7be17b` |
| `rounds-n1000.json` | raw rounds JSON n=1000 | 8857307 | `d2eaba51102954848f0a11473c56485ff55eaf0346f34766b3a0216c20c27798` |
| `rounds-n3000.json` | raw rounds JSON n=3000 (26MB-class; kept outside repo per card discipline) | 26437240 | `6a662dbe934597bd03cf0ba7a72f89924ba2c184881e82b10e02953561a9367c` |
| `bytes-n200.json` | container-side byte accounting n=200 | 8270 | `d0110ac78908079e56a6fe64cce1ec37f24d7ee78a04b0d309bc5732339dcc14` |
| `bytes-n1000.json` | container-side byte accounting n=1000 | 8367 | `760d5bcd4ca7c9627f6451c352d6f256b67b6dd1b29bfd5382af93a769600871` |
| `bytes-n3000.json` | container-side byte accounting n=3000 | 8410 | `fc7fc91ed4a19d0c6a10478fddb8bb965dc55892567fcf61725568c2a10f85e0` |
| `seed-counts-n200.json` | V1 seed counts n=200 | 550 | `0797570fc281c0e9ca2ca59216f09b3d87821474b7c2838bc97c8d453a2a9cd0` |
| `seed-counts-n1000.json` | V1 seed counts n=1000 | 563 | `fa4cb2babe8b0074c0236337b19c99522037677cd8797c126c57850e960919e4` |
| `seed-counts-n3000.json` | V1 seed counts n=3000 | 564 | `fd9aff49628736b3d3d728d83372ea0050c01963f8cc0a05796cab2f39c99a5d` |
| `capture-manifest.json` | IT manifest (rev/prereg sha256/canary/problems) | 1438 | `a82012eef4405fedd967dfc81442fe9058f245a9e1e04128bab3683b8dc74176` |
| `it-run.log` | IT console original (attempt-9 rounds) | 669952 | `bd002e3ea4124e705c4855eb69992a15271646d76f8d6053a9e9087ba612a8b8` |
| `adjudication.json` | machine adjudication (copied into evidence/) | 80027 | `0d47d16b52974b5ff08f6b7dfe298372ff77f1ccec2973c5961079e32f6e846b` |
| `diag-histogram-atcap.txt` | jcmd GC.class_histogram at -Xmx6g cap (attempt-8) | 783241 | `ab224f26e8e012946ccb01aa7f219de8b3d18de37ced084f1899b6d0b1eb8f65` |
| `diag-histogram-t1.txt` | jcmd GC.class_histogram early sample (attempt-8) | 793533 | `db64114b172af333b1b1f2dce30d454100d7d4fa3d061a3c4e212e2ba27a3afc` |
| `it-run-n3000-oom-repro.log` | attempt-8 OOM console original (copied into evidence/) | 209062 | `155e1eb4a4f6d1aabfa5907a9bfc307abae01b019367a3c43591228a4550b904` |
| `gate-6a33faf.log` | gate log (committed 6a33faf) | 1881224 | `d017541c86d393d6b6554c71803464cadc4f04e08180554d237fd9c46ae286db` |
| `gate-after-impl.log` | gate log (working tree, pre-commit) | 1895498 | `e69772e21118352d3b066325c0ef36dc5111564a3869213dd6d0c78417baa644` |
