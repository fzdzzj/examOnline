# harden-scheduler-pool-config

## 一、背景与问题（B.2 横切首扫中危 M2 卡，2026-10-17 立案）

`SchedulingConfig` 裸 `@EnableScheduling`——Spring 默认给 `@Scheduled` **单线程**调度器。当前四个定时任务共用这一个线程串行执行：考试状态机扫表（`ExamStateMachineService`，fixed-delay 10s）、开考提醒（`ReservationRemindService`，fixed-delay 10s）、交卷兜底对账（`ExamSweepService`，fixed-delay 10s，单轮最多 10 轮 × 500 批，每批等待 MQ confirm 至 5s）、数据保留（`RetentionService`，cron 每日 3:30）。

**单线程阻塞风险（缓解论证）**：broker 故障/拥塞时 sweep 的 MQ confirm 等待可拖到分钟级——单线程调度器下，定时发布/自然结束的状态机流转与开考提醒整体排在 sweep 之后被阻塞，表现为「考试到点不开、到点不结束、提醒迟到」而应用本身健康（无异常无告警），是最难排查的静默停摆形态。调度池 ≥2 后，sweep 与状态机/提醒分属不同线程，任一任务慢不再阻塞其余任务。**边界如实登记**：fixed-delay 语义按任务自互斥，同任务不会因池扩而自我重叠；多实例 sweep 幂等安全性（既有测试钉住）不受线程池影响；本卡只缓解「任务间排队阻塞」，不改变 sweep 单轮的自身耗时。

## 二、方案（配置化 + 护栏，零代码行为改动）

- `application.yml` 增加 `spring.task.scheduling.pool.size: ${SCHEDULING_POOL_SIZE:2}`（env 可覆盖，缺省 2）；
- **「三 yml 一致」的落法**：以 base `application.yml` 单点声明实现——dev/prod 零覆盖即结构上不可能漂移（标量键无 SMTP properties map 那种分档覆盖需求；重复声明反而制造第三份副本漂移风险，违背 AGENTS.md 单一事实源纪律）。护栏测试机械钉住：base 必须声明且缺省值 ≥2；dev/prod 若未来出现同名覆盖则其值也必须 ≥2（防「dev 2 / prod 1」式分歧）；
- `SchedulingConfig` javadoc 补记单线程默认值风险与池配置出处（代码可自解释，文档只在指针层）；
- 零代码行为改动：不引入 ThreadPoolTaskScheduler 定制 Bean、不动任务本身。

## 三、白名单

- `src/main/resources/application.yml`（spring.task.scheduling.pool.size 声明 + 注释）
- `src/main/java/com/exam/exam/config/SchedulingConfig.java`（javadoc 补记）
- `src/test/java/com/exam/deployment/SchedulingPoolConfigGuardTest.java`（新文件：配置护栏两断言）
- `.github/ci/gate-baseline.json`、`spec/changes/harden-scheduler-pool-config/**`、`spec/README.md`、`docs/功能待办清单.md`
- **禁触**：四个 @Scheduled 任务本体、application-dev.yml / application-prod.yml、schema.sql、docker/、openapi.yaml、pom.xml、frontend/、docker/observability/

## 四、验收要点

- 护栏测试两断言：base 声明 spring.task.scheduling.pool.size 且解析缺省值 ≥2；dev/prod 若出现同名覆盖其值同样 ≥2（当前零覆盖 → 一致性成立）
- 变异击红：把 base 值改为 1（或删声明）→ 护栏确定性失败 → 复原回绿留证；等价变异如实登记
- 范围外零改动核对（任务本体/dev/prod yml + schema.sql + docker + openapi.yaml + pom + frontend）
- 门禁 `mvnw.cmd clean test` 全绿（仓库根、带 clean、禁 -o）；backendTests 基线在 665 上只增不减，收口笔同笔上调（+2）
- S 级理由：单配置键 + 护栏；行为变化仅「调度池 1→2」，无业务语义改动；阻塞风险论证与边界落 proposal
