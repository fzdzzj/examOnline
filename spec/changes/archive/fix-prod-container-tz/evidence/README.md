# evidence/ —— fix-prod-container-tz 证据索引（证据三要素：命令 + 当次输出 + 短 revision）

原始全量日志（未裁剪）留存于仓库外 D:\code\examOnline-measure\prod-tz-drill\（环境坑先例：
target/measure 曾整体消失，测量证据一律写仓库外）。本目录 03–06 为护栏四相完整日志
（定向运行，各 46–90 行原样入库）；07 为全量门禁判定行裁剪（先例：fix-exam-state-machine-transaction
8.6MB 全量日志收口裁剪为判定行）。

## 01 修复前确证（UTC 现状）

-  revision：6161815（立案笔 HEAD，分支 feature/fix-prod-container-tz）
-  前置：pre-fix 镜像按 6161815 的 Dockerfile 现场重建（本地旧镜像建于 2026-10-11 19:21、
   Dockerfile 最后提交 19:50，防陈旧镜像误证）；`docker compose -f docker-compose.prod.yml
   --env-file <演练env> up -d app` 起栈（app/mysql-master/mysql-slave/redis/rabbitmq 全 healthy，
   演练凭据为一次性生成不入库）。
-  命令与当次输出：
  - `date`（宿主）→ `Sat Oct 17 00:52:13 2026`（上海墙钟，UTC+8）
  - `docker exec exam-online-prod-app date` → `Fri Oct 16 04:51:39 PM UTC 2026`
  - `docker exec exam-online-prod-app date +"%Y-%m-%dT%H:%M:%S%z"` → `2026-10-16T16:52:13+0000`
    （与宿主同一秒对拍：恰漂 8 小时；`echo $TZ` → 空）
-  app 日志时间戳同期为 UTC 形态（`2026-10-16 16:49:33.002`，boot 行）。

## 02 修复后对齐（CST + 定时扫描日志时间戳）

-  revision：b34b23e（实施笔 HEAD）
-  重建镜像（同 Dockerfile+ENV TZ=Asia/Shanghai）并 `compose up -d app` 重建容器。
-  命令与当次输出：
  - `docker exec exam-online-prod-app date` → `Sat Oct 17 01:01:34 AM CST 2026`
  - `date`（宿主，同一秒对拍）→ `Sat Oct 17 01:01:34 2026`；`echo $TZ` → `Asia/Shanghai`
  - 10s 级定时扫描真实日志行（向主库插一行 published=1、start_time 已过 1 分钟的可迁移考试，
    等待一轮扫描）：`docker logs --since 30s` →
    `2026-10-17 01:04:29.698 [scheduling-1] INFO c.e.exam.service.ExamStateMachineService - 考试 1 定时迁移 0 -> 1`
    （时间戳 CST=上海墙钟；DB 复核 status 0→1 已迁移——证明 LocalDateTime.now() 比较语义
    在正确时区下工作：start_time 01:03:23 CST 后约 66s 内迁移，修复前该比较要晚 8 小时才成立）
-  同期周期性日志（OTLP exporter 连 localhost:4318 被拒的既有现象，观测栈收集器未启动所致，
  与本变更无关）时间戳亦为 CST 且严格 15s 周期（01:01:58→01:02:13→01:02:28→…）。

## 03 护栏红相（TDD 先红：pre-fix Dockerfile 上护栏确定性失败）

-  revision：6161815（护栏文件已写、Dockerfile 尚未加 TZ）
-  命令：`./mvnw test -Dtest=ProdContainerTzGuardTest`
-  当次输出（03-guard-red-prefix.log）：`Tests run: 1, Failures: 1` —
  `AssertionFailedError: Dockerfile 运行层缺少 ENV TZ=Asia/Shanghai…` BUILD FAILURE（EXIT=1）

## 04 护栏绿相（加 ENV TZ 后回绿）

-  revision：b34b23e（实施笔内容就绪、Dockerfile 已加 TZ）
-  命令：`./mvnw test -Dtest=ProdContainerTzGuardTest`
-  当次输出（04-guard-green-fixed.log）：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` BUILD SUCCESS（EXIT=0）

## 05 变异击红（删 TZ 行 → 护栏红）

-  变异：`sed -i '/^ENV TZ=Asia\/Shanghai$/d' Dockerfile`（`grep -c "ENV TZ"` → 0）
-  命令：`./mvnw test -Dtest=ProdContainerTzGuardTest`
-  当次输出（05-guard-red-mutation-deleted.log）：`Tests run: 1, Failures: 1` BUILD FAILURE（EXIT=1）

## 06 复原回绿（git checkout HEAD -- Dockerfile → 护栏绿）

-  还原后 `grep -c "ENV TZ"` → 1
-  命令：`./mvnw test -Dtest=ProdContainerTzGuardTest`
-  当次输出（06-guard-green-restored.log）：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` BUILD SUCCESS（EXIT=0）

## 07 全量门禁

-  revision：b34b23e（工作树=实施笔干净态）
-  命令：仓库根 `./mvnw clean test`（Git Bash；Windows cmd 等价 `mvnw.cmd clean test`；带 clean、未用 -o）
-  当次输出（07-full-gate-h1-summary.log）：`Tests run: 631, Failures: 0, Errors: 0, Skipped: 1`，
  BUILD SUCCESS，GATE_EXIT=0（Skipped 1 为既有 OpenApiContractTest 基线跳过；
  631 = 收口前基线 630 + 本案护栏 1）
