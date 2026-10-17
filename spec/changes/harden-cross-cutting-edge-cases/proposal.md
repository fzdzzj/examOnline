# Proposal: 横切边缘与运维盲区收尾治理 (harden-cross-cutting-edge-cases)

## 1. 为什么（Why）

本项目此前已完成多轮核心业务加固与 Redis 依赖韧性治理。在系统横切两轮巡查（基线 8e7f766）中登记的「低 8」项均为长期积累的边缘防御薄弱点、架构死配置与运维观测盲区，具体包括：

1. **L-01 删班级无考试引用检查（`exam.class_id` 悬挂防护）**：`ClassService.delete` 仅清理了 `user_class` 关联并软删班级，未检查是否存在考试引用该班级。被引用的班级一旦删除，`exams.class_id` 悬挂，且 `AbsenceService` 在流转状态机或计算缺考时无法推导应考名单；
2. **L-02 RabbitMQ listener 死配置整块残留**：`application.yml` 中保留 `spring.rabbitmq.listener.simple.*`，但交卷消费由手工 `@Bean` 声明的 `batchContainerFactory`（`exam.taking.mq.*` 控制）驱动，Boot 自动配置 listener 从未生效，环境变量 `RABBIT_CONCURRENCY` 误导运维；
3. **L-03 慢 SQL 仅 WARN 无指标告警闭环**：`SlowSqlInterceptor` 在 SQL 超阈值时仅打印 WARN 日志，未暴露 Prometheus 指标及告警规则，无法触发监控报警；
4. **L-04 Prometheus 观测拓扑与 prod 容器网络不接**：`prometheus.yml` 默认 `host.docker.internal:8080` 仅适用于宿主机开发环境，在 Docker 生产拓扑中无法抓取容器内 `exam-online-prod-app:8080`，套用会导致 `up==0` 假告警常鸣；
5. **L-05 成绩复核处理 check-then-act 无 CAS**：`ScoreReviewService.handle` 仅在内存检查状态，直接调用 `updateById`，高并发下存在 last-write-wins 覆盖风险；
6. **L-06 dev/prod 默认 REDIS_DATABASE:0 共实例互串**：`application-dev.yml` 与 `application-prod.yml` 默认分库均为 0，若单机共用 Redis 实例会导致测试与生产数据互相污染；
7. **L-07 代码引用未落 yml 的旋钮 9 组运维不可见**：代码中通过 `@Value` 引用的 9 组配置（如预约提前提醒、死信重放参数、导入行数上限、题干图片配置等）未在 `application.yml` 中声明；
8. **L-08 题干图片命名卷不在备份指引范围**：`docker/mysql/backup/` 仅含 MySQL 备份，缺少 `question-images-data` 数据卷的冷备与恢复指南。

本提案将上述 8 项集中打包为一案彻底收尾。

---

## 2. 改什么（What）

### 2.1 班级删除前置考试引用检查 (`ClassService`)
- `ClassService` 注入 `ExamMapper`；
- 在 `delete(Long id)` 前置检查：`examMapper.selectCount(Wrappers.<Exam>lambdaQuery().eq(Exam::getClassId, id)) > 0`；
- 若存在关联考试，抛出 `BusinessException(ResponseCode.BAD_REQUEST, "该班级已被考试引用，不允许删除")`；
- 编写 `ClassServiceTest` 单元测试验证阻断与放行。

### 2.2 清理 RabbitMQ listener 死配置 (`application.yml`)
- 移除 `spring.rabbitmq.listener.simple.*` 死配置块；
- 保留并维护注释，明确交卷消费者容量完全由 `exam.taking.mq.*` 调控。

### 2.3 慢 SQL 指标打点与告警规则闭环 (`BusinessMetrics`, `SlowSqlInterceptor`, `alerts.yml`)
- `BusinessMetrics` 增加 `exam.database.slow.sql` Counter 指标及 `countSlowSql()` 方法；
- `SlowSqlInterceptor` 注入 `BusinessMetrics`，执行超阈值时调用打点；
- `docker/observability/prometheus/rules/exam-online-alerts.yml` 增加告警规则 `DatabaseSlowSqlDetected`；
- `AlertAssetsTest` 的 `EXPECTED_ALERTS` 集合增加守护断言；
- `SlowSqlInterceptorTest` 补充打点断言。

### 2.4 Prometheus 观测拓扑容器对齐与指引 (`prometheus.yml`, `README.md`)
- `prometheus.yml` 增加生产容器拓扑注释说明（`exam-online-prod-app:8080`）；
- `docker/observability/README.md` 明确多环境（dev 宿主 vs prod 容器）抓取目标配置切换指引。

### 2.5 成绩复核处理 CAS 原子更新 (`ScoreReviewService`)
- 在 `handle(Long reviewId, ReviewHandleRequest request)` 中，将 `updateById(review)` 改为条件更新：
  ```java
  int updated = reviewMapper.update(review, Wrappers.<ScoreReview>lambdaUpdate()
          .eq(ScoreReview::getId, reviewId)
          .in(ScoreReview::getStatus, ScoreReview.STATUS_PENDING, ScoreReview.STATUS_PROCESSING));
  if (updated == 0) {
      throw new BusinessException(ResponseCode.BAD_REQUEST, "该复核申请已被其他操作处理");
  }
  ```
- `ScoreReviewServiceTest` 补充并发或已被其他线程处理时更新 0 行抛出异常的断言。

### 2.6 Redis 默认分库环境隔离 (`application-prod.yml`)
- `application-prod.yml` 默认分库调整为 `database: ${REDIS_DATABASE:1}`（dev 保持 0），消除共用实例串库隐患。

### 2.7 隐式 @Value 配置旋钮集中显式化 (`application.yml`)
- 集中补齐以下 9 组 `@Value` 旋钮及中文注释说明：
  - `exam.reservation.remind-ahead-minutes: 30`
  - `exam.mq.dlq.max-replay: 3`
  - `exam.mq.dlq.replay-max: 100`
  - `exam.mq.dlq.receive-timeout-ms: 2000`
  - `exam.question-import.max-rows: 1000`
  - `exam.user-import.max-rows: 1000`
  - `exam.question-image.dir: ./data/question-images`
  - `exam.question-image.max-size-mb: 5`
  - `exam.grading.multiple-partial-ratio: 1.0`

### 2.8 题干图片命名卷备份与恢复指引 (`docker/mysql/backup/README.md`)
- 在备份文档中追加 `question-images-data` 数据卷冷备（`docker run ... tar czf`）与恢复命令，形成全数据资产备份闭环。

---

## 3. 影响能力域与规范

- `class-management`：班级删除前置关联考试引用检查规范；
- `score-review`：复核处理原子 CAS 更新与并发冲突防守规范；
- `observability`：慢 SQL 指标打点与告警规则闭环规范；
- `dev-config`：配置树清理、隐式旋钮显式化与 Redis 默认环境隔离规范。

---

## 4. 零 DDL 与质量红线

- **零 DDL**：未新增、修改任何表结构，未改动 `schema.sql`，不添加任何 `docker/mysql/migrations/` 脚本；
- **契约稳定**：`openapi.yaml` 既有 97 个 paths 与 schema 保持稳定，无需修改契约；
- **测试零 @Sql**：新测用例一律基于已有数据库基础设施与 Mockito 单测；
- **gate-baseline 只增不减**：全量后端用例数基线锁定上调。

---

## 5. 变异验证计划

- **m1（删班级引用检查）**：注释掉 `ClassService.delete` 中的考试引用检查逻辑，验证阻断测试变红（期望抛 400 但返回成功）；
- **m2（复核处理 CAS）**：移除 `update` 的 `in(STATUS_PENDING, STATUS_PROCESSING)` 条件，改回无条件 `updateById`，验证并发/冲突阻断测试变红（期望抛 400 但无异常）；
- **m3（慢 SQL 打点）**：注释掉 `SlowSqlInterceptor` 中的 `businessMetrics.countSlowSql()`，验证单测变红。
