# 提案：事务回滚策略统一（阶段 8.2，防御性规范）

## Why

代码证据核实（`grep 裸 @Transactional`）显示：项目共 27 处 `@Transactional`，其中 **26 处使用裸 `@Transactional`（不带 rollbackFor），仅 1 处 `AuthService.registerWithRole` 使用了 `@Transactional(rollbackFor = Exception.class)`。

**本提案的性质是「防御性规范统一」，不是「bug 修复」**——这点必须如实说明，避免误判：

- **当前不会出错**：全项目业务异常统一抛 `BusinessException`（继承 RuntimeException）；`JsonProcessingException`/`IOException`/`MessagingException`/`InterruptedException` 等受检异常均已在 catch 块内显式转换成 `BusinessException` 重新抛出。因此没有任何受检异常穿过事务边界，裸 `@Transactional` 默认的「RuntimeException/Error 回滚」语义在当前代码下**已能正确回滚**。
- **但存在暴露面**：Spring 的 `@Transactional` 默认仅在 `RuntimeException` 与 `Error`（即 `unchecked`）时回滚，`checked Exception` 不回滚。一旦未来有人直接 `throws SomeCheckedException` 而不转换，事务会**静默提交而非回滚**，造成数据半提交——这是隐蔽的高危坑。显式 `rollbackFor = Exception.class` 能把这个坑从「依赖个人自觉」变成「框架强制」。
- **一致性**：`AuthService` 已写了 `rollbackFor`，其余 26 处没写，属历史不一致（认证最早实现时写了，后续模块统一约定「业务异常都是 RuntimeException」后就没再写）。统一可消除「同一团队的两种写法」带来的误读。

**背景**：
- 面试价值：可讲「为什么显式声明 rollbackFor=Exception.class 是大厂规范」——(1) 受检异常的静默不回滚是隐蔽风险；(2) 显式声明让回滚语义不依赖默认值、自文档化；(3) 读代码时一眼看出「本方法出错一定回滚」。
- 数据一致性相关决策（`docs/需求决策记录.md` §10.x）：乐观锁 version、状态机 CAS、幂等交卷均已落地，本提案是事务原子性这一层的收尾补齐。

**当前状态**：26 处裸 `@Transactional`，仅 1 处显式 rollbackFor。

**期望状态**：所有 `@Transactional` 统一显式 `rollbackFor = Exception.class`，回滚语义不依赖 Spring 默认值，自文档化。

## What Changes

- 将 26 处裸 `@Transactional` 统一改为 `@Transactional(rollbackFor = Exception.class)`。
- 不引入新依赖、不改业务逻辑、不改事务传播/隔离级别/只读等既有属性（当前均无额外属性）。

### 涉及文件（26 处，9 类）

| 类 | 处数 | 行号 |
|---|---|---|
| `ExamStateMachineService` | 2 | 54, 88 |
| `ExamSnapshotService` | 1 | 75 |
| `ExamService` | 5 | 65, 139, 183, 199, 222 |
| `PaperSnapshotService` | 1 | 82 |
| `PaperService` | 8 | 77, 118, 145, 161, 171, 188, 207, 246 |
| `TagService` | 2 | 40, 81 |
| `QuestionService` | 3 | 65, 91, 120 |
| `ScoreService` | 3 | 94, 288, 330 |
| `SubjectiveGradingService` | 1 | 104 |

## Impact

### 受影响的规范
- `spec/specs/data-consistency/spec.md` - 新建（`ADDED`）：事务回滚策略。

### 受影响的代码
- 仅上述 9 个 Service 类的注解行（26 处），无方法体改动。

### 用户影响
- 无功能变化（纯防御性注解统一）。

### API 变更
- 无。

### 需要迁移
- [ ] 数据库迁移（无）
- [ ] 配置变更（无）
- [x] 文档更新（本提案 + 规范 + 决策记录补「事务回滚策略」注记）

## 时间线评估

小：约半天（W10，机械性注解统一 + 回归测试）。

## 风险

- **误引入副作用**：`rollbackFor = Exception.class` 会让受检异常也回滚。当前代码受检异常均已转换，行为不变；但需用既有全量测试回归，确认无行为差异。
- **遗漏排查**：用 `grep` 精确锁定 26 处，改完后再次 `grep '^\s*@Transactional\s*$'` 确认零残留。
- **与 Spring 默认值语义差异**：仅当未来出现「希望受检异常不回滚」的场景时才有差异；本系统无此场景，统一更安全。