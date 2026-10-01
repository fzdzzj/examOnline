# 测量与控制台原件转录（reattribute-monitor-overview-projection）

> 原始控制台产物直写仓库外 `D:\code\examOnline-measure\monitor-projection\`；逐轮 JSON、字节账、
> 捕获原文、裁决书与容器日志已拷入本目录 `evidence/`（在库件见 `evidence-sha256.txt` 清单）。
> 60MB 级捕获 IT 控制台全文 `it-run.log` 与两只废弃轮日志留在库外，位置与 sha256 登记于本文件
> 第 5 节（不入清单，保证清单在新克隆上可复验）。

## 1. 阶段 2 轮次（单次 IT 调用跑齐三形状三臂）

命令（仓库根；口令经 `MONITOR_MYSQL_PWD` 注入、日志中记 `***`，不落盘）：

```
mvnw.cmd test -Dtest=MonitorOverviewProjectionMeasureIT -DfailIfNoTests=false \
  -Dmeasure.rev=bede6c8 -Dmeasure.label=run1 \
  -Dmeasure.out=D:\code\examOnline-measure\monitor-projection\raw \
  -Dmeasure.mysql.url=jdbc:mysql://127.0.0.1:13319/monitor_projection_measure?... -Dmeasure.mysql.user=root
```

IT 控制台结尾（`it-run.log` 尾部）：

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 155.5 s -- in com.exam.monitoring.measure.MonitorOverviewProjectionMeasureIT
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  02:45 min
[INFO] Finished at: 2026-09-29T21:08:23+08:00
```

runner 汇总与冻结哈希核对（`capture-manifest.json` 在库）：

```
rounds: itExit=0 problemsEmpty=true shapesDone=true out=D:\code\examOnline-measure\monitor-projection\raw
preregMatch=true（recorded=recomputed=1481d739c8e830969c139e2d124ebf17219231503a076ab4ff5f6ef097e59fcb）
problems=[]   redisKeysDeleted=4494
```

控制台含期望内噪声：测试上下文无 broker，IT 造数触发的交卷 MQ 发送失败按既有兜底落草稿
（`ExamSubmitService - 交卷消息发送失败，答案暂存草稿等待补发`）与 Rabbit 容器
`Failed to check/redeclare auto-delete queue(s)`——不影响捕获与计时，权威记录以逐轮 JSON 与
runner 门（`problemsEmpty`）为准。

## 2. 字节相位（容器侧机械 SUM(LENGTH)）

runner 输出（`bytes-n*.json`/`bytes-n*.log` 在库）：

```
bytes n=200:  OLD=4281440  PROJ=2000+20480  ratio=190.46  checksOk=true
bytes n=1000: OLD=21407200 PROJ=10000+20480 ratio=702.34  checksOk=true
bytes n=3000: OLD=64221600 PROJ=30000+20480 ratio=1272.22 checksOk=true
```

checks 全 true：`oldSelectContainsLongFields`（OLD SELECT 列表含 paper_json+answers）、
`projSelectEqualsFrozen`（PROJ=student_id,status）、`narrowSelectOk`（窄读 selectList=paper_json
且 LIMIT 1）、`oldrepSameTextAsOld`/`oldrepSameTextAsRoundsOld`（副本臂语句逐字相同）、
`mainRowsOk`、`narrowRowsOk`（1 行）、`verifyRowsOk`（paper_json 逐行 20480）、
`verifyAnswersOk`（answers 逐行 2048）、`verifyLogsOk`、`exitsOk`。

## 3. 容器（`container-*.log` 全文在库）

- `container-start.log`：`docker run` exit=0、ready=true、镜像 `mysql:8.0`（digest 与容器 Id 全文在库）、
  端口 `127.0.0.1:13319`、库 `monitor_projection_measure`、tmpfs `/var/lib/mysql`；
  Redis 一次性容器 `redis:7.2-alpine` 端口 6390、db15（全文在同文件）。
- `container-setup.log`：`SELECT VERSION()` = 8.0.46；整库执行仓库 `src/main/resources/schema.sql`
  exit=0（表数 26）；`SHOW CREATE TABLE exam_submissions\G` 与 `SHOW INDEX` 全文落盘。
- `container-stop.log`：`docker rm -f` mysql=0 redis=0；`docker ps -a --filter name=…`
  零匹配证据（`stdout=[]`）全文在库。

## 4. 机械裁决（`analyze-monitor-projection.cjs`；`adjudication.json`/`verdict-console.txt` 在库）

```
裁决：GO（n=200:GO，n=1000:GO，n=3000:GO）
n=200: M1=P M2=P M3=P S1=P S2=P S3=P S4=P
  S2 bytes: OLD=4281440 PROJ=22480(main=2000+narrow=20480) ratio=190.46 threshold=5
  S3 ns: noiseUpper=104226200 proj=[33338900,36135700,25443800,55012000,31707300]
n=1000: S2 ratio=702.34；S3 noiseUpper=512574300 proj=[72814900,72089800,82309700,51512600,60767400]
n=3000: S2 ratio=1272.22；S3 noiseUpper=899997000 proj=[63530700,106902100,79503300,115851500,77596900]
S3 额外往返是否抵消字段节省：未抵消（S2 ∧ S3 均通过）
S4 账目：每形状主语句两臂均 1 条/n 行；窄读 PROJ 1 条/1 行（同轮记账）
边界：一次性本地容器、单机、空并发、MockMvc 测试上下文；结论不外推生产 MySQL/Tomcat，
不构成交卷或监考 P99 结论。
```

## 5. 库外原件登记（不在入库清单内）

| 库外路径（`D:\code\examOnline-measure\monitor-projection\raw\`） | 字节数 | sha256 |
| --- | --- | --- |
| `it-run.log`（捕获 IT 控制台全文，含阶段 2 轮次） | 60861180 | `9ef52cb3932899264dff7d0964639c7cb68997253eb0461be8fbc5b0db82a667` |
| `it-run-run1-void.log`（废弃轮 1，夹具算式勘误） | — | `794e90031e4fa22ab1611a4e189f6045ef59729f827d4e588b31f80afb7bcbaa` |
| `it-run-run2-void.log`（废弃轮 2，窄读参数勘误） | — | `49855974706a47dc0fd091b5449de7336cef20bd81ee8c553d6c6c27c6ee7a78` |
| `void-run2\`（废弃轮 2 的产物副本 10 件） | — | 见 `apparatus-correction-note.md` |
| `red-green\guard-red.log`、`guard-green.log` | 17537 / 15466 | 与入库副本一致（`f4b4befc…` / `741d1cc6…`） |

废弃轮的原因、时间戳与"判据未改、未挑轮"声明见 `apparatus-correction-note.md`。
