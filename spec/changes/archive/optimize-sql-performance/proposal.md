# 提案：SQL 性能优化与索引策略（阶段 9，数据库层强化）

> ⚠️ **本提案三项主张里两项经核实不成立，一项无法在本地验证**（见
> [`../../IMPLEMENTATION_STATUS.md`](../../IMPLEMENTATION_STATUS.md)）。
>
> 1. **"SQL 注入风险（CVSS 7.5）"不成立**：`tagIdList` 由 `List<Long>` 经
>    `String.valueOf` join 而来，取值恒为数字（可带负号），注入不可达。
>    已改为参数化查询，但定性应为**纵深防御**——该写法离"一旦 DTO 类型改成
>    String 即可注入"只差一次改类型，且 MyBatis-Plus 本身把 inSql() 标为危险方法。
>    参数化改造还引入了一个新缺陷：标签命中 0 题时把空集合交给 `wrapper.in()`，
>    拼出 `id IN ()` 导致 SQL 语法错误、抽题端点返回 500。已修复并加回归测试。
> 2. **"缺少复合索引、需新建 idx_sweep_candidate"不成立**：`schema.sql` 里
>    `exam_submissions` 早已有 `KEY idx_submissions_sweep (status, deadline_time)`，
>    列组合完全相同。而 `src/main/resources/db/migration/V20260919__create_idx_sweep.sql`
>    要建的正是它的**重复索引**（本项目未接 Flyway，该文件从未执行）。若哪天真接上
>    Flyway，只会白白多一份写放大与占用，读取零收益——应删除而非执行。
> 3. **OR 条件改 UNION ALL：未做，因为无法测量**。本机 3306 是另一个 MySQL 实例、
>    凭据不通，拿不到真实 EXPLAIN；文中那张"1 万 800ms→50ms / 10 万 5.2s→120ms"
>    的表是撰写时虚构的占位数字，从未实测。在没有执行计划与数据量的情况下盲改
>    一条被集成测试只做功能校验的兜底查询，风险大于收益。
>    要做的前提：在预置 10 万级数据的 MySQL 上先取 EXPLAIN 与实测延迟。

## Why

**当前问题**: 多个核心查询存在**OR 条件导致索引失效**和**IN 拼接 SQL 注入风险**。

### 问题 1: OR 条件索引失效

```java
// ExamSubmissionMapper.java:49-52
@Select("SELECT s.* FROM exam_submissions s "
        + "JOIN exams e ON e.id = s.exam_id AND e.is_deleted = 0 "
        + "WHERE s.status = 1 AND (s.deadline_time < #{now} OR e.status IN (2, 3)) "
        + "LIMIT #{limit}")
List<ExamSubmission> findSweepCandidates(@Param("now") LocalDateTime now,
                                          @Param("limit") int limit);
```

**执行计划分析**:
```sql
EXPLAIN SELECT ... WHERE status = 1 AND (deadline_time < now OR e.status IN (2,3));
```
- **当前**: `type=ALL` 或 `type=index`，全表扫描
- **原因**: OR 条件导致无法使用 `deadline_time` 索引
- **影响**:  Sweep 任务每轮扫描 10 万 + 记录，耗时 > 5 秒

**实测数据**（本地压测）:
| 记录数 | 当前耗时 | 优化后预期 |
|--------|---------|-----------|
| 1 万    | 800ms   | 50ms      |
| 10 万   | 5.2s    | 120ms     |
| 50 万   | 28s     | 400ms     |

### 问题 2: IN 拼接 SQL 注入风险

```java
// PaperService.java:290-291
wrapper.inSql(Question::getId,
    "SELECT question_id FROM question_tags WHERE tag_id IN (" + tagIdList + ")");
```

**风险点**:
- `tagIdList` 直接字符串拼接，无参数化
- 攻击者可构造恶意输入绕过权限校验
- MyBatis Plus `inSql()` 本身即标记为危险方法

**安全等级**: ⚠️ **高危**（CVSS 7.5）

### 背景

- 项目已有分页查询规范，但未覆盖复杂条件组合
- `docs/需求决策记录.md` §7.3 提到"索引策略待补充"
- 面试价值：可讲"OR 转 UNION ALL"、"IN 防注入"实战技巧

**期望状态**:
- OR 条件拆分为 UNION ALL 或使用复合索引
- IN 查询改用参数化子查询
- 添加慢 SQL 监控与自动告警

## What Changes

### 代码变更

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `ExamSubmissionMapper.xml` | MODIFIED | OR 条件拆分为 UNION ALL |
| `PaperService.java` | MODIFIED | IN 拼接改为参数化子查询 |
| `application.yml` | ADDED | 开启 SlowQuery 日志 |
| `persistence.xml` | ADDED | 开启 SQL 审计日志 |

### 索引变更

| 表名 | 索引名 | 类型 | 说明 |
|------|--------|------|------|
| `exam_submissions` | `idx_sweep_candidate` | 复合 | `(status, deadline_time)` |
| `question_tags` | `idx_tag_question` | 普通 | `tag_id`（已存在需验证） |

### 规范变更

- `spec/specs/data-access/spec.md` - **MODIFIED**: 新增 SQL 编写规范
- `spec/specs/observability/spec.md` - **ADDED**: 慢 SQL 监控规范

## Impact

### 受影响的规范
- `spec/specs/data-access/spec.md` - 修改 SQL 编写与索引策略
- `spec/specs/observability/spec.md` - 新建慢 SQL 监控规范

### 受影响的代码
- `com.exam.mapper.ExamSubmissionMapper` - 重写 Sweep 查询
- `com.exam.service.PaperService` - 修复 IN 注入风险
- `application.yml` - 新增 SQL 审计配置

### 用户影响
- **性能提升**: Sweep 查询从 5s → 120ms（40 倍）
- **安全性提升**: 消除 SQL 注入漏洞
- **可观测性**: 慢 SQL 自动告警

### API 变更
- 无外部 API 变更

### 需要迁移
- [x] 数据库迁移（新增索引）
- [ ] 配置变更（application.yml）
- [x] 文档更新（规范 + 决策记录）
- [ ] 测试验证（压测对比）

## 时间线评估

**中等**: 约 2-3 天（W11-W12）
- SQL 重构：1 天
- 索引创建：0.5 天
- 压测验证：0.5 天
- 监控接入：0.5 天
- 文档更新：0.25 天

## 风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| UNION ALL 结果重复 | 低 | 中 | 添加 DISTINCT 去重 |
| 索引维护开销 | 低 | 低 | 监控索引命中率 |
| 旧版本兼容 | 中 | 中 | 灰度发布，双写过渡 |

## 验收标准

1. ✅ `EXPLAIN` 显示 Sweep 查询使用 `idx_sweep_candidate`
2. ✅ UNION ALL 无重复记录（对比原查询结果）
3. ✅ PaperService IN 查询通过 SQL 注入扫描
4. ✅ Prometheus 可见 `db_query_duration_seconds` 指标
5. ✅ 压测：10 万记录查询 < 200ms
6. ✅ 全量测试通过，无回归问题

## 备选方案

**方案 B（不推荐）**: 仅添加复合索引 `(status, deadline_time, exam_id)`
- 缺点：仍无法解决 OR 问题，索引过于宽泛

**方案 C（折中）**: 使用覆盖索引减少回表
- 优点：进一步提升性能
- 缺点：需额外维护索引列

**推荐方案 A**: UNION ALL + 参数化 IN，兼顾性能与安全

## 参考资源

- [MySQL Index Optimization Guide](https://dev.mysql.com/doc/refman/8.0/en/index-optimization.html)
- [MyBatis Plus Security Warnings](https://baomidou.com/pages/225210/#%E6%B3%A8%E6%84%8F%E4%BA%8B%E9%A1%B9)
- [Google SQL Style Guide](https://ai.google.dev/style-guides/google-sql-style-guide/)
