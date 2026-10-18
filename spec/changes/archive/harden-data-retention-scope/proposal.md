# Proposal: 历史未清理表数据保留清理闭环与运维 Runbook (harden-data-retention-scope)

> 阶段：S 级生产运维治理与数据生命周期闭环（纯后端 + 规范与运维文档，零 DDL）  
> 目标能力域：`data-access`  
> 分支：`feature/harden-data-retention-scope`  
> 关联待办：`docs/功能待办清单.md:104` 观察 12「user_messages/invite_codes/exam_dlq_messages/practice_attempt 永不清理；retention prod 默认关无 runbook」  

---

## 1. 为什么做（背景与动机）

- **背景**：在前期变更 `add-data-retention`（阶段 15）中，系统落地了以**考试生命周期**（按 `exam_id` 驱动）清理 `exam_behavior_logs`、`exam_submit_dedups`、`score_audit_logs` 三张辅助表，并在后续追加了按**自身年龄**（`created_time` + 索引 `idx_audit_time`）清理 `audit_log` 安全审计表。
- **现状与缺口**（观察 12）：
  1. **已重放死信记录长期累积**：`exam_dlq_messages` 表已有复合索引 `idx_dlq_status_time (status, created_time)`，此前因「按 exam_id 清理」未纳入；对于已成功重放（`status = 'REPLAYED'`）的死信消息，历史记录长期无界滞留；
  2. **生产保留配置缺乏 Runbook**：数据保留任务在 `application.yml` 中默认关闭（`enabled: false`）且默认只试算（`dry-run: true`），生产环境缺乏一份权威、可操作的运维启动 Runbook（对齐 `docs/redis-dependency-runbook.md` 规范），缺乏全表清理矩阵与合规裁决依据；
  3. **未清理表的边界未显式固化**：系统内如 `user_messages`、`invite_codes`、`practice_attempt` 缺少权威的保留/排除裁决说明（例如 `practice_attempt` 属于学生错题消灭事实绝不可删；`user_messages` 与 `invite_codes` 缺少时间索引，按规范严禁发起全表扫描 DELETE）。

---

## 2. 改什么（范围与设计）

### 2.1 后端实施范围

1. **`ExamDlqMessageMapper.java` 扩充有界清理方法**：
   - `@Select("SELECT COUNT(*) FROM exam_dlq_messages WHERE status = #{status} AND created_time < #{cutoff}")`；
   - `@Delete("DELETE FROM exam_dlq_messages WHERE status = #{status} AND created_time < #{cutoff} LIMIT #{limit}")`；
   - 充分利用既有复合索引 `idx_dlq_status_time (status, created_time)` 最左前缀与时间范围，**零 DDL，严格杜绝全表扫描**。
2. **`RetentionService.java` 扩展已重放死信清理**：
   - 新增配置项 `@Value("${exam.retention.dlq.retention-days:30}") int dlqRetentionDays`；
   - 仅清理 `status = 'REPLAYED'` 的历史死信，待人工排查的 `status = 'PARKED'` **严格保留，绝不自动清理**；
   - 走短事务分批删除（复用 `batchSize` 与 `maxBatchesPerRun`），异常单独 try-catch 隔离不阻断其他表；
   - 记录 `BusinessMetrics.countRetentionRows("exam_dlq_messages", "candidate"/"deleted", count)`；
   - 更新 `RetentionReport` 记录 `dlqCandidates` 与 `dlqDeleted`。
3. **配置补全** (`src/main/resources/application.yml`):
   - 在 `exam.retention` 下显式补齐 `dlq.retention-days: 30` 默认值与中文注释说明。

### 2.2 运维治理与文档

1. **编写生产数据保留 Runbook** (`docs/data-retention-runbook.md`):
   - 生产环境启用 Retention 的前置检查单与确认流程；
   - `dry-run` 试算结果核验步骤；
   - 生产真删切换步骤与报警指标联动；
   - **全表保留与清理矩阵**：明确清理表（5 张）、业务事实不清理表（15+ 张）与无索引未纳入表（`user_messages`, `invite_codes`）的取舍论证。

### 2.3 契约与测试

1. **集成测试扩展** (`DataRetentionIntegrationTest.java`):
   - 验证 `exam_dlq_messages` 在 dry-run 模式下仅统计候选、不执行物理删除；
   - 验证真删模式下 `REPLAYED` 超窗消息被按批删除，而近期 `REPLAYED` 消息与任何 `PARKED` 消息均不受影响；
   - 验证分批上限限制与短事务控制。
2. **双端门禁与变异验证**：
   - 全量门禁 `./mvnw clean test` 通过（预期新增单测/集成测试单调上调）；
   - 注入变异并完成断言级击红留证。

---

## 3. 质量红线与禁忌

1. **零 DDL**：严禁修改 `src/main/resources/schema.sql`，严禁新增 `migrations/` 脚本。
2. **禁止全表扫描式删除**：所有删除条件必须完全命中既有索引最左前缀。
3. **安全第一**：默认 `enabled=false`、默认 `dry-run=true` 双闸门不变；`PARKED` 状态死信绝对不删。
4. **测试严禁 `@Sql` 自建表**。
