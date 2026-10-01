# 规范差异：data-access（SQL 性能与安全）

本文件包含对 `spec/specs/data-access/spec.md` 的规范变更。

## MODIFIED Requirements

### Requirement: 查询编写规范

**原需求文本**:
```markdown
WHEN 编写数据库查询，
系统 SHALL 使用参数化查询防止 SQL 注入。
```

**新需求文本**:
```markdown
WHEN 编写数据库查询，
系统 SHALL:
1. 使用参数化查询防止 SQL 注入
2. 避免 OR 条件导致索引失效，改用 UNION ALL
3. 禁止使用 MyBatis Plus inSql() 等危险方法
4. 所有查询需有 EXPLAIN 验证索引使用
```

#### Scenario: 参数化查询
GIVEN 一个带条件的查询
WHEN 开发者编写查询
THEN 使用 `#{param}` 或 `<foreach>` 参数化
AND 禁止字符串拼接 SQL

#### Scenario: OR 条件处理
GIVEN 一个包含 OR 条件的复杂查询
WHEN 开发者编写查询
THEN 拆分为 UNION ALL 多个简单查询
AND 每个查询单独使用索引
AND 添加 DISTINCT 去重

#### Scenario: IN 查询安全
GIVEN 一个动态 IN 条件
WHEN 开发者编写查询
THEN 使用 MyBatis XML + <foreach>
AND 禁止 wrapper.inSql()
AND 添加输入长度校验

---

### Requirement: 索引策略

**新增需求**:

```markdown
### Requirement: 复合索引设计
WHEN 表存在多条件组合查询，
系统 SHALL 创建复合索引覆盖常用查询模式。

#### Scenario: Sweep 查询索引
GIVEN exam_submissions 表
WHEN 执行 sweep 任务查询
THEN 使用复合索引 (status, deadline_time)
AND 查询计划显示 type=range 而非 ALL

#### Scenario: 索引顺序优化
GIVEN 复合索引 (col1, col2, col3)
WHEN 查询条件为 col1 AND col3
THEN 索引可部分使用（前缀匹配）
AND 建议在 col1 后添加 col3 形成更优索引
```

```markdown
### Requirement: 索引维护
WHEN 创建或删除索引，
系统 SHALL:
- 记录索引用途与预期性能提升
- 定期运行 SHOW INDEX 验证命中率
- 删除未使用的冗余索引
```

---

## ADDED Requirements

### Requirement: 慢 SQL 监控

```markdown
### Requirement: 慢查询日志
WHEN 应用启动，
系统 SHALL 配置慢 SQL 日志阈值（如 200ms）。

#### Scenario: 慢查询告警
GIVEN 一个执行超过阈值的查询
WHEN 查询完成
THEN 记录到慢查询日志
AND 上报 Micrometer 指标 db_query_slow_total
AND 触发 Grafana 告警（如 P99 > 500ms）

#### Scenario: 查询审计
GIVEN 生产环境
WHEN 启用慢查询日志
THEN application.yml 包含:
  spring:
    jpa:
      properties:
        hibernate:
          format_sql: true
          show_sql: false  # 生产关闭，仅保留慢查询
```

---

### Requirement: 查询性能基准

```markdown
### Requirement: 性能基线
WHEN 定义核心查询，
系统 SHALL 设定性能目标（如 P95 < 100ms）。

#### Scenario: Sweep 查询性能
GIVEN 10 万条考试提交记录
WHEN 执行 sweep 任务查询
THEN P95 < 200ms
AND P99 < 500ms
AND 全量扫描率 < 5%

#### Scenario: 回归测试
WHEN 修改查询逻辑
THEN 必须通过性能对比测试
AND 性能下降 > 10% 需重新评审
```

---

### Requirement: SQL 代码审查

```markdown
### Requirement: 审查清单
WHEN 进行代码审查，
审查者 SHALL 检查以下项目：
- [ ] 是否使用参数化查询
- [ ] OR/IN 条件是否有索引支持
- [ ] 是否避免 SELECT *
- [ ] 分页查询是否限制最大 size
- [ ] 复杂查询是否有 EXPLAIN 验证

#### Scenario: 危险方法检测
GIVEN 代码中包含 inSql()/eqSql() 等方法
WHEN 代码审查
THEN 标记为高危
AND 要求改为参数化实现
```

---

## REMOVED Requirements

无移除项。

---

## 规范变更总结

| 类型 | 需求名称 | 变更说明 |
|------|---------|----------|
| MODIFIED | 查询编写规范 | 新增 OR 转 UNION、禁止 inSql |
| ADDED | 复合索引设计 | 指定常用查询的索引策略 |
| ADDED | 慢 SQL 监控 | 配置阈值与告警机制 |
| ADDED | 性能基线 | 定义 P95/P99 目标值 |
| ADDED | SQL 审查清单 | 代码审查检查项 |

---

## 验证清单

- [ ] `EXPLAIN SELECT ...` 显示 Sweep 查询使用 `idx_sweep_candidate`
- [ ] `grep 'inSql' *.java` 结果为空
- [ ] `application.yml` 包含慢 SQL 配置
- [ ] Prometheus 可见 `db_query_duration_seconds` 直方图
- [ ] 压测报告：10 万记录查询 P95 < 200ms
- [ ] 代码审查通过，无 SQL 注入风险

---

## 参考示例

### ✅ 正确示例：UNION ALL

```xml
<!-- ExamSubmissionMapper.xml -->
<select id="findSweepCandidates" resultType="ExamSubmission">
  SELECT s.* FROM exam_submissions s
  JOIN exams e ON e.id = s.exam_id
  WHERE s.status = 1 
    AND s.deadline_time &lt; #{now}
    AND e.is_deleted = 0
  
  UNION ALL
  
  SELECT s.* FROM exam_submissions s
  JOIN exams e ON e.id = s.exam_id
  WHERE s.status = 1
    AND e.status IN (2, 3)
    AND e.is_deleted = 0
  
  LIMIT #{limit}
</select>
```

### ❌ 错误示例：OR 条件

```java
// ❌ 索引失效
WHERE status = 1 AND (deadline_time < now OR e.status IN (2,3))
```

### ✅ 正确示例：参数化 IN

```java
// PaperService.java
List<Long> questionIds = questionTagRepository
    .findQuestionIdsByTagIds(tagIdList);  // 参数化子查询
wrapper.in(Question::getId, questionIds);
```

### ❌ 错误示例：IN 拼接

```java
// ❌ SQL 注入风险
wrapper.inSql(Question::getId,
    "SELECT question_id FROM question_tags WHERE tag_id IN (" + tagIdList + ")");
```
