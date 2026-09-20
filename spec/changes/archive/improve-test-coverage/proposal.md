# 提案：测试覆盖率提升与质量门禁（阶段 9，研发工程）

> ⚠️ **本提案的基线已过期，请勿据此执行**（核实见
> [`../../IMPLEMENTATION_STATUS.md`](../../IMPLEMENTATION_STATUS.md)）。
> 下述"覆盖率仅 14%"、"添加 JaCoCo 配置"、"新建 ExamSubmitServiceTest /
> application-test.yml"在当时均已完成：实施前实测 85.1% 行 / 66.3% 分支，
> pom.xml 已有 jacoco 0.8.12。真实缺口只有 ScoreService 一处，且已补齐。
> 另外本仓库 `git remote` 为空，文中 GitHub Actions + Codecov 交付项无从运行。

## Why

**当前问题**: 测试覆盖率仅 14%，关键路径缺少集成测试。

### 问题现状

| 指标 | 当前值 | 目标值 |
|------|--------|--------|
| 文件覆盖率 | 41/287 (~14%) | ≥80% |
| 行覆盖率 | ~30% | ≥70% |
| 分支覆盖率 | ~20% | ≥60% |
| 核心服务覆盖 | Service 层 50% | 100% |

**缺失能力**:
- 批量发布、Sweep 等关键流程无集成测试
- MQ 消费者路径未测试
- 并发场景无压力测试
- 无代码质量门禁（SonarQube）

**背景**:
- 项目已有 JUnit + Mockito 基础
- 但未定义覆盖率目标与门禁
- 面试价值：可讲"测试左移"实战经验

**期望状态**:
- 核心服务覆盖率达 80%+
- Maven 构建失败若覆盖率 < 70%
- SonarQube Quality Gate 通过

## What Changes

### 代码变更

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `pom.xml` | MODIFIED | 添加 JaCoCo 插件配置 |
| `ScoreServiceTest.java` | NEW | 新建批量发布集成测试 |
| `ExamSubmitServiceTest.java` | NEW | 并发压测用例 |
| `application-test.yml` | NEW | 测试环境配置 |
| `.github/workflows/ci.yml` | ADDED | CI/CD 流水线 |

### 规范变更

- `spec/specs/testing/spec.md` - **ADDED**: 新建测试质量规范

## Impact

### 受影响的规范
- `spec/specs/testing/spec.md` - 新建测试质量规范

### 受影响的代码
- 新增测试文件（41 → 80+）
- CI/CD 流水线配置

### 用户影响
- **质量提升**: Bug 发现更早，生产事故减少

### API 变更
- 无外部 API 变更

### 需要迁移
- [x] 数据库迁移（无）
- [ ] 配置变更（CI/CD）
- [x] 文档更新（规范 + 决策记录）
- [ ] 测试验证（覆盖率达标）

## 时间线评估

**中等**: 约 3-4 天（W14-W15）
- 编写核心服务测试：2 天
- 集成测试与压测：1 天
- CI/CD 配置：0.5 天
- 文档更新：0.25 天

## 风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 测试维护成本高 | 中 | 中 | 优先写高价值测试 |
| CI 构建时间延长 | 低 | 低 | 并行执行测试 |

## 验收标准

1. ✅ JaCoCo 覆盖率报告：整体 ≥70%，核心服务 ≥80%
2. ✅ Maven test 命令触发覆盖率检查
3. ✅ SonarQube Quality Gate 通过
4. ✅ 批量发布、Sweep 等关键流程有集成测试
5. ✅ CI/CD 流水线自动运行测试
6. ✅ 全量测试通过，无回归问题

## 备选方案

**方案 B（不推荐）**: 仅追求行覆盖率
- 缺点：可能写出无意义断言

**方案 C（折中）**: 手动运行覆盖率工具
- 优点：灵活
- 缺点：无法强制门禁

**推荐方案 A**: JaCoCo + Maven 插件 + SonarQube 门禁

## 参考资源

- [JaCoCo Documentation](https://www.jacoco.org/jacoco/trunk/doc/)
- [SonarQube Quality Gates](https://docs.sonarqube.org/latest/user-guide/quality-gates/)
