# 规范差异：testing（测试质量）

本文件包含对 `spec/specs/testing/spec.md` 的规范变更。

## ADDED Requirements

### Requirement: 覆盖率目标

```markdown
### Requirement: 代码覆盖率基线
WHEN 提交代码，
系统 SHALL 满足以下覆盖率要求:
- 整体行覆盖率 ≥ 70%
- 核心服务（Exam、Score、Paper）行覆盖率 ≥ 80%
- 分支覆盖率 ≥ 60%
- 方法覆盖率 ≥ 75%

#### Scenario: 覆盖率检查
GIVEN 开发者运行 mvn test
WHEN JaCoCo 插件执行
THEN 若整体覆盖率 < 70%，构建失败
AND 输出详细报告至 target/site/jacoco/index.html
AND PR 无法合并（GitHub Quality Gate）

#### Scenario: 核心服务豁免
GIVEN 新模块开发初期
WHEN 覆盖率暂时未达标
THEN 需提交技术债务任务单
AND 设定改进期限（≤2 周）
```

---

### Requirement: 测试分层策略

```markdown
### Requirement: 单元测试优先
WHEN 编写测试，
系统 SHALL 按优先级顺序覆盖:
1. 单元测试（Unit Test）: 每个 Service 方法
2. 集成测试（Integration Test）: Controller + Service + DB
3. 端到端测试（E2E）: 完整业务流程

#### Scenario: 单元测试规范
GIVEN 一个 Service 方法
WHEN 编写单元测试
THEN 使用 Mockito 模拟依赖
AND 测试时间 < 100ms/用例
AND 无外部依赖（DB、MQ、Redis）

#### Scenario: 集成测试范围
GIVEN 关键业务流程（批量发布、Sweep）
WHEN 编写集成测试
THEN 使用 @SpringBootTest + Testcontainers
AND 测试真实 DB/MQ/Redis 行为
AND 测试时间 < 1 分钟/用例
```

---

### Requirement: 并发测试

```markdown
### Requirement: 并发场景验证
WHEN 实现并发控制逻辑（如分布式锁），
系统 SHALL 编写并发压力测试用例。

#### Scenario: 考试提交并发
GIVEN 同一考试多用户同时提交
WHEN 执行压测（100 并发）
THEN 验证 Redis 锁正确生效
AND 去重表唯一索引拦截重复请求
AND CAS 乐观锁处理最终冲突
AND P95 耗时 < 500ms

#### Scenario: Sweep 多实例
GIVEN 两个 ExamSweepService 实例同时运行
WHEN 执行并发扫描
THEN 重复处理率 < 5%
AND 无数据丢失或重复评分
```

---

### Requirement: CI/CD门禁

```markdown
### Requirement: 自动化测试流水线
WHEN 提交 Pull Request，
系统 SHALL 自动触发以下流程:
1. mvn clean test（单元测试）
2. jacoco:report（覆盖率生成）
3. sonarqube:analyze（代码质量扫描）
4. Quality Gate 判断（覆盖率 + 漏洞）

#### Scenario: PR 合并条件
GIVEN 开发者提交 PR
WHEN GitHub Actions 执行完成
THEN 必须满足:
  - 所有测试通过
  - 覆盖率 ≥ 70%
  - SonarQube Quality Gate = PASSED
  - 无新增 Blocker/Critical 漏洞
AND PR 方可合并
```

---

### Requirement: 测试数据管理

```markdown
### Requirement: 测试数据隔离
WHEN 运行集成测试，
系统 SHALL 使用独立数据库 Schema 或容器化环境。

#### Scenario: Testcontainers 使用
GIVEN MySQL 集成测试
WHEN @BeforeEach 执行
THEN 启动临时 MySQL 容器
AND 执行 Flyway 迁移
AND 测试结束后自动销毁容器

#### Scenario: 数据清理
GIVEN 测试方法执行完成
WHEN @AfterEach 执行
THEN TRUNCATE 所有测试表
AND 释放数据库连接
AND 确保下一个测试干净环境
```

---

## MODIFIED Requirements

### Requirement: 异常测试

**原需求文本**:
```markdown
WHEN 业务抛出异常，
系统 SHALL 编写对应异常测试用例。
```

**新需求文本**:
```markdown
WHEN 业务抛出异常，
系统 SHALL:
1. 编写异常路径单元测试（@Test(expected = ...)）
2. 验证异常消息与错误码正确
3. 验证事务回滚行为
4. 验证日志记录完整性
```

#### Scenario: 事务回滚验证
GIVEN 事务方法抛出 BusinessException
WHEN 测试执行
THEN 使用 @Rollback(true)（默认）
AND 验证数据库无脏数据
AND 使用 @Mock Bean 验证外部调用未执行

---

## REMOVED Requirements

无移除项。

---

## 规范变更总结

| 类型 | 需求名称 | 变更说明 |
|------|---------|----------|
| ADDED | 覆盖率目标 | 整体≥70%，核心服务≥80% |
| ADDED | 测试分层策略 | 单元 → 集成 → E2E 优先级 |
| ADDED | 并发测试 | 分布式锁与多实例场景 |
| ADDED | CI/CD 门禁 | PR 合并前自动验证 |
| ADDED | 测试数据管理 | Testcontainers 隔离 |
| MODIFIED | 异常测试 | 新增事务回滚验证要求 |

---

## 验证清单

- [ ] `mvn test jacoco:report` 生成覆盖率报告
- [ ] 整体覆盖率 ≥ 70%，核心服务 ≥ 80%
- [ ] GitHub Actions PR 自动触发测试
- [ ] SonarQube Quality Gate = PASSED
- [ ] 并发压测：100 用户提交无冲突
- [ ] 全量测试通过，无回归问题

---

## 参考示例

### ✅ 正确示例：单元测试

```java
@SpringBootTest
class ScoreServiceTest {
    
    @Autowired
    private ScoreService scoreService;
    
    @Test
    void testPublishPartialFailure() {
        // GIVEN
        List<Long> examIds = Arrays.asList(1L, 2L, 3L);
        
        // WHEN
        List<ScoreActionItem> results = scoreService.publish(examIds);
        
        // THEN
        assertThat(results).hasSize(3);
        assertThat(results.get(0).isSuccess()).isTrue();
        assertThat(results.get(1).isSuccess()).isFalse(); // 第 2 个失败
        assertThat(results.get(2).isSuccess()).isTrue();   // 第 3 个不受影响
    }
}
```

### ✅ 正确示例：并发测试

```java
@SpringBootTest
class ExamSubmitServiceTest {
    
    @Autowired
    private ExamSubmitService submitService;
    
    @Test
    void testConcurrentSubmission() throws InterruptedException {
        // GIVEN
        CountDownLatch latch = new CountDownLatch(100);
        AtomicInteger successCount = new AtomicInteger(0);
        
        // WHEN
        for (int i = 0; i < 100; i++) {
            Executors.newSingleThreadExecutor().submit(() -> {
                try {
                    submitService.submit(123L, request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // 重复提交被拒绝
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        
        // THEN
        assertThat(successCount).isEqualTo(1); // 仅第一个成功
    }
}
```

### ❌ 错误示例：无并发测试

```java
// ❌ 禁止：未验证并发场景
@Test
void testSubmission() {
    submitService.submit(123L, request);
}
```
