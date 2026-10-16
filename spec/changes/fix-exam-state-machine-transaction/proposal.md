# 变更提案：fix-exam-state-machine-transaction

## 为什么（Why）

2026-10-16 三轮后端巡查高危发现（主 Agent 源码亲验）：

- `ExamStateMachineService.scheduledAdvance()`（L173-175）与 `advanceOnStartup()`（L179-184）经 `this.autoAdvance()` **自调用**——Spring AOP 代理被绕过，L111 的 `@Transactional(rollbackFor = Exception.class)` 在生产定时路径**不生效**（单测经注入代理调用反而生效，恰好掩盖缺陷）。
- 后果：当轮 CAS 状态迁移与 `absenceService.markAbsence()` 各自独立提交。`markAbsence` 瞬时失败（DB 抖动等）时，考试已置 `ENDED` 而 `exam_absence` 无缺考行；`autoAdvance` 只扫 `IN_PROGRESS`，ENDED 后无自愈路径（`ExamService` L271-274 注释明载「漏标不可自愈」）→ **缺考名单永久失真**，直接影响缺考统计与补考名单口径（absence-makeup 基线：缺考锚定「进行中→已结束」，两条结束路径都必须 markAbsence）。
- 次生缺陷：H-04 详情缓存清除位于方法体内 = 事务提交**前**执行，clear 与 commit 之间存在并发读回填旧状态的窗口。

## 改什么（What）

1. `autoAdvance()` 改**编程式事务**：构造注入 `TransactionTemplate`（Boot 自动装配 bean，`QuestionImportExportService` 既有使用先例），方法体包入 `execute`；**移除 `@Transactional` 注解**并在 javadoc 注明原因（`@Scheduled`/`@EventListener` 入口同类自调用绕代理，注解形态在生产静默失效——编程式事务不依赖调用路径）。
2. 事务边界：**整轮一个事务**（与原注解意图一致）——CAS 批量推进 + `markAbsence` 同事务；任一 `markAbsence` 失败当轮全部回滚，受影响考试保持原状态，下一轮扫表重试补偿（CAS 乐观锁幂等 + `exam_absence` 唯一索引 INSERT IGNORE 幂等，重试不产生重复数据）。
3. 缓存清除（`clearExamDetailCache`）移至**事务提交后**执行，关闭 clear→commit 脏读回填窗口。
4. `scheduledAdvance`/`advanceOnStartup` 调用点零改动；对外行为（返回值、日志、指标计数）不变。

## 影响面（Impact）

- 单类（`ExamStateMachineService`）+ 测试；零 DDL（schema.sql 零 diff）、零契约变更（openapi 97 不变）、零前端（frontend 零 diff）。
- 既有不变式保全：缺考锚定「进行中→已结束」不变（absence-makeup 基线）；「并发流转仅一次」CAS 语义不变；缓存清除时机从「事务内」改为「提交后」（语义增强）。
- 门禁基线：仅 backendTests 预期增长；S 级，双岗隔离。

## 技术注记

- `TransactionTemplate` 默认仅 RuntimeException/Error 回滚；本路径无受检异常（CAS/markAbsence/缓存清除均为运行时异常），与原 `rollbackFor = Exception.class` 语义等价。
- 修复选型否决「自注入代理」与「拆独立 bean」：前者引入自引用坏味道，后者重构面更大；编程式事务对齐仓内既有惯例且永久免疫调用路径问题。

## 任务阶段

- 阶段 0 & 1：立项三件套与在途总账登记。
- 阶段 2：实施 + 单测先红后绿（定时入口路径 markAbsence 失败须回滚——实现前该用例红即缺陷存在性证明）+ 变异击红留证。
- 阶段 3：全量门禁实测、基线上调、规范合入、归档、`--no-ff` 合并入 main。
