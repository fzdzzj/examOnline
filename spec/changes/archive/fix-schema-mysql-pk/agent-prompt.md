# 子 agent 提示词 —— `fix-schema-mysql-pk`（阶段 17）

> 用法：整份复制发给子 agent。自包含。
> 先读 `spec/changes/fix-schema-mysql-pk/proposal.md` 与 `tasks.json`。

---

## 现状

- 分支 `feature/add-performance-deepening-readwrite`。
- 阶段 16 归档可能并行：**不要改** `docs/observability-runtime-evidence.md` / 告警 YAML / 观测 README。
- `schema.sql` **25 张表**均 `id … AUTO_INCREMENT` 且 **无 PRIMARY KEY**（指导 agent 已 grep）。MySQL 8 空库会 1075；H2 测试全绿。
- 最多修复尝试 **1 次**。
- **你必须自己 commit**（一次 `fix` 或 `fix(data-access)`）。提交后 `git rev-parse HEAD`；HEAD 变 unborn 则补 ref。
- **不要改 spec/**（含不要勾 tasks.json）。

## 硬约定

1. 只补 `PRIMARY KEY (id)`，不改列类型、不加业务列、不删 UNIQUE。
2. 禁止 MySQL 专属 `COMMENT`。
3. 禁止 `@Sql` 自建表。
4. 不改实体 / Mapper / 业务 SQL / `pom.xml`。
5. 存量迁移必须同时落盘：`CREATE TABLE IF NOT EXISTS` **不会**给已存在表补主键。

## 写入边界

允许改：
- `src/main/resources/schema.sql`
- `docker/mysql/migrations/2026-W16-add-primary-keys.sql`（新增）
- 新增测试类（建议 `src/test/java/com/exam/support/SchemaSqlMysqlCompatibilityTest.java`）

禁止其它路径。

## 25 张表（必须全覆盖）

users, roles, permissions, user_roles, role_permissions, invite_codes, questions, tags, question_tags, papers, paper_questions, paper_snapshots, exams, exam_snapshots, exam_submissions, subjective_grades, score_audit_logs, exam_submit_dedups, exam_behavior_logs, classes, user_class, exam_absence, exam_candidates, score_review, exam_dlq_messages

## 实施

1. 每张表建表块加 `PRIMARY KEY (id)`（放在约束区，与 UNIQUE/KEY 并列）。
2. 写 W16 迁移：25 条 `ALTER TABLE … ADD PRIMARY KEY (id)`；头注释写新库走 schema.sql、存量手工跑、重复执行报错可忽略。
3. 测试：读 `schema.sql` 文本，按 `CREATE TABLE` 切块，凡含 AUTO_INCREMENT 的块必须含 PRIMARY KEY，缺一块 assert 失败并打出表名。
4. 全量回归（直调 launcher，`-o test`）。基线先记下来，只增不减全绿。

本机 Maven 必须直调 jdk177 launcher（见交接文档），不要用 PATH 里的 mvn。

Commit 建议：
```
fix(data-access): schema.sql 为 AUTO_INCREMENT 补主键以兼容 MySQL 8
```

## 回报

1. 基线三数字
2. 改了哪些表 / 迁移文件
3. 约定测试类名与它断言什么
4. 收尾三数字 + `git rev-parse HEAD`
5. 意外发现（H2 若红，停）

禁止：为过测试改 H2 模式、放宽断言、改 Java 业务。
