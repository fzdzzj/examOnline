# add-redis-dependency-runbook

## 一、背景与问题（B.2 横切首扫 H2 残留面 · Redis 依赖裁决 B2+C2 卡，2026-10-17 立案）

**裁决背景（用户已拍板，2026-10-17）**：Redis 故障口径统一为「**正确性由 DB 幂等保证，Redis 降级保可用性，草稿为已声明的硬依赖面**」。A1（登录守卫降级）、D1（交卷锁两类失败区分）已闭环；本卡（B2+C2）把口径落成**可运维的 canonical 文档**并收口告警面：

- 派工背景：H2 卡在案时的「观察项落运维 runbook 页」建议 + 裁决 ④「鉴权/限流/监考三套口径取舍」已由裁决统一，需要一处 canonical 事实源承载口径、键族矩阵、告警映射与恢复流程；
- 告警盘点缺口（本卡实盘点结论）：D1 新增的 `exam.submit.lock.degraded` 计数器**没有对应告警规则**——降级等于没人知道，违背「降级必须可观测」的同源哲学；本卡补齐 `SubmitLockDegraded`（照抄 AuthDegraded 形态）+ `AlertAssetsTest` EXPECTED_ALERTS 守护同笔更新。

## 二、方案（纯文档 + 告警缺口补齐；硬依赖面如实登记不冒领）

- 新建 `docs/redis-dependency-runbook.md`：①依赖口径声明（裁决原文 + 单一降级哲学 + 硬依赖面声明）；②键族降级矩阵（逐族：承载方/职责/故障行为/计数指标与告警/恢复方式——降级行与硬依赖行分列）；③监控告警现状盘点（三降级计数器 ↔ 三告警规则映射完整；无计数指标的既有降级面与硬依赖面的信号来源如实说明）；④快速恢复流程 + 故障期学生端行为指引；
- **矩阵以代码直读为准**（2026-10-17 全景盘点）：降级面=拦截器黑名单/会话版本、登录守卫六触点（A1）、接口限流（既有）、交卷锁（D1）、行为计数/在线状态/错题判定缓存（既有降级）；硬依赖面=草稿暂存（裁决声明无兜底）、令牌签发与续期（AuthService.issueTokens/refresh/logout + TokenStoreService，故障期新登录 500——**残留观察面，消除需单独立案**）、Spring Cache 快照/详情（无 CacheErrorHandler 异常裸穿，拉卷/详情/写路径失效故障期 500——即 H2 在案「RedisCacheManager 直连」残留观察面）、找回密码验证码；
- 告警补齐：`SubmitLockDegraded` 规则（`sum(increase(exam_submit_lock_degraded_total[5m])) > 0`，for 1m，warning，照抄 AuthDegraded）；`AlertAssetsTest` EXPECTED_ALERTS 同笔更新（集合断言无用例数增量，gate-baseline 零上调合法持平）；
- `AGENTS.md` 按工作流第 7 条加指针（只加指针不加正文）：Redis 调用点改动先读 runbook + 新降级计数指标必须有告警并过守护；
- 不动：任何业务代码、告警规则既有十条、CacheConfig/TokenStoreService/ExamDraftService（硬依赖面与残留观察面的消除不在本卡——本卡只如实登记）。

## 三、白名单

- `docs/redis-dependency-runbook.md`（新建，交付物）
- `docker/observability/prometheus/rules/exam-online-alerts.yml`（SubmitLockDegraded 规则 + 头注计数表述勘正）
- `src/test/java/com/exam/observability/AlertAssetsTest.java`（EXPECTED_ALERTS + 注释计数表述勘正）
- `AGENTS.md`（指针一行）
- `.github/ci/gate-baseline.json`（核对不动）、`spec/changes/add-redis-dependency-runbook/**`、
  `spec/README.md`、`docs/功能待办清单.md`
- **禁触**：全部 src/main（含 CacheConfig/TokenStoreService/ExamDraftService——硬依赖面消除另案）、
  schema.sql、docker/mysql/migrations/、openapi.yaml、pom.xml、frontend/、MQ 发布路径。

## 四、验收要点

- runbook 四节齐备且与代码对齐：矩阵每行可对应到真实类与键前缀（盘点过程留痕 tasks.json）；「降级」行与「硬依赖」行不混淆——硬依赖面不得写成已降级
- SubmitLockDegraded 规则：AlertAssetsTest 全绿（EXPECTED_ALERTS 集合相等 + 结构断言）；变异击红——删除该规则 → AlertAssetsTest 确定性失败 → 复原回绿，留证（本卡守护红形态；文档卡无业务用例数增量，gate-baseline 651 核对不动）
- 范围外零改动核对（src/main 全域 + schema.sql + docker/mysql + openapi.yaml + pom + frontend）
- 门禁 `mvnw.cmd clean test` 全绿（仓库根、带 clean、禁 -o）；backendTests 基线 651 只增不减（本卡零新增用例，同值持平）
- 文档卡级理由：纯文档 + 一条告警规则照抄先例 + 集合断言更新；无业务代码、无契约、无 DDL
