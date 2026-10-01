# 提案：schema.sql 为 AUTO_INCREMENT 补主键（阶段 17）

## Why

阶段 16 动态验证时空 MySQL 执行 `schema.sql` **失败**：MySQL 8 要求 `AUTO_INCREMENT` 列必须是 key（错误 1075），而本仓库 `schema.sql` **25 张表**全部是 `id BIGINT NOT NULL AUTO_INCREMENT` **没有 `PRIMARY KEY`**。

H2 `MODE=MySQL` 能建表，集成测试全绿——又是「测试绿、新库建不起来」。与阶段 12（`score_review` 缺列）同类：护栏没挡住方言差异。

**已核实（2026-09-16 grep）**：

- `src/main/resources/schema.sql`：`CREATE TABLE` 25 张，均有 `AUTO_INCREMENT`，**零处 `PRIMARY KEY`**。
- 测试：`src/test/resources/application-test.yml` → `schema-locations: classpath:schema.sql`（H2）。
- 迁移例外：`docker/mysql/migrations/2026-W15-add-dlq-messages.sql` **有** `PRIMARY KEY (id)`，与 `schema.sql` 里同表不一致。
- 既有表风格长期如此，不是某一次笔误。

**期望状态**：新库用 `schema.sql` 能在 **MySQL 8 与 H2** 上建全；存量库有可手工执行的补主键脚本；用测试锁住「凡 AUTO_INCREMENT 必有 PRIMARY KEY」，防止再漂。

**本提案不是性能优化，是新环境可启动。不加业务列、不改 Java 映射。**

## What Changes

1. `schema.sql`：每张表补 `PRIMARY KEY (id)`（与 UNIQUE/KEY 并列，不删既有唯一约束）。
2. `docker/mysql/migrations/2026-W16-add-primary-keys.sql`：存量库 `ALTER TABLE … ADD PRIMARY KEY (id)`；脚本头写明 Multiple primary key / Duplicate 可忽略（`CREATE TABLE IF NOT EXISTS` 不会给已存在表补主键）。
3. 测试：新增对 `schema.sql` 文本的约定测试（每个含 `AUTO_INCREMENT` 的建表块必须出现 `PRIMARY KEY`）；全量 H2 回归必须仍绿。
4. **不改**实体、Mapper、业务 SQL。

## Impact

### 受影响的规范
- `spec/specs/data-access/spec.md` — ADDED：新库建表可在 MySQL 8 执行；AUTO_INCREMENT 必须有主键。

### 受影响的文件
- `src/main/resources/schema.sql`
- `docker/mysql/migrations/2026-W16-add-primary-keys.sql`（新增）
- `src/test/java/…/SchemaSqlMysqlCompatibilityTest.java`（路径实施自定，建议 `com.exam.support` 或 `com.exam.observability` 旁的 support）

### 需要迁移
- [x] 存量 MySQL 手工跑 W16 脚本（已能启动的库可能已经手工加过 PK，重复执行报错可忽略）
- [ ] 业务 API

## 时间线评估

小：约 0.5 天。

## 风险

- **H2 与 MySQL 对 PRIMARY KEY 语法**：禁止 `COMMENT`、禁止 MySQL 专属语法；`PRIMARY KEY (id)` 两边都能吃。若 H2 红，停下来回报，不要用 `@Sql` 绕。
- **已有主键的存量表**：ALTER 失败是预期，脚本注释写清楚，不要写成可静默成功的存储过程。
- **不要**趁机加索引、改列类型、合并约束名。
