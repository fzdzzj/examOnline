# 规范增量：question-bank

## 新增需求：题库批量标签管理 (ADDED)

系统 SHALL 支持教师对多道题目执行批量追加（BIND）或批量解绑（UNBIND）标签操作，并对题目所有权执行严格校验。

### Scenario: 批量追加标签 (BIND) (ADDED)
- **WHEN**: 教师调用 `POST /api/questions/batch-tags`，入参包含 `questionIds`、`tagIds`，`action` 为 `BIND`
- **THEN**: 系统 SHALL 校验当前教师对目标题目的所有权，越权题目放入 `failed` 列表，合法题目放入 `succeeded`
- **AND**: 对于合法题目，系统 SHALL 计算其尚未绑定的标签差集，仅将差集插入 `question_tags` 关联表
- **AND**: 原题已有标签以及非指定标签 SHALL 原样保留，且重复追加具备幂等性

### Scenario: 批量解绑标签 (UNBIND) (ADDED)
- **WHEN**: 教师调用 `POST /api/questions/batch-tags`，入参包含 `questionIds`、`tagIds`，`action` 为 `UNBIND`
- **THEN**: 系统 SHALL 校验当前教师对目标题目的所有权，合法题目放入 `succeeded`
- **AND**: 系统 SHALL 从 `question_tags` 物理删除合法题目与所选 `tagIds` 的对应关联行
- **AND**: 原题关联的其他未选标签 SHALL 原样保留不受影响

### Scenario: 批量打标越权防护与参数校验 (ADDED)
- **WHEN**: 教师 A 试图对教师 B 创建的题目进行批量打标/解绑，且教师 A 非管理员
- **THEN**: 系统 SHALL 拦截对教师 B 题目的操作，将其记录于响应的 `failed` 列表并返回越权原因
- **AND**: 若入参 `questionIds` 或 `tagIds` 为空，系统 SHALL 返回 400 Bad Request

---

## 新增需求：试卷分值智能配平 (ADDED)

系统 SHALL 支持教师通过离散最大余数算法一键均分试卷题目分值或按题型配比分值，确保题目分值之和严格等于目标总分。

### Scenario: 离散最大余数一键均分保和 (ADDED)
- **WHEN**: 教师调用 `POST /api/papers/{id}/questions/scores/rebalance`，`mode` 为 `EVEN`
- **THEN**: 系统 SHALL 校验试卷处于未锁定（草稿）可编辑状态，锁定试卷拒绝修改并抛出 400 错误
- **AND**: 系统 SHALL 根据目标总分 $S_{target}$ 与题目总数 $N$，以 0.5 分为步长整数化（$T = S_{target} / 0.5$），计算整除商 $Q = \lfloor T / N \rfloor$ 与余数 $R = T \pmod N$
- **AND**: 系统 SHALL 将前 $R$ 道题分配分值 $(Q + 1) \times 0.5$，后 $N - R$ 道题分配 $Q \times 0.5$，使所有题目分值之和严格等于 $S_{target}$
- **AND**: 批量分值更新在单一事务内持久化至 `paper_questions` 表

### Scenario: 按题型智能配比配平 (ADDED)
- **WHEN**: 教师调用 `POST /api/papers/{id}/questions/scores/rebalance`，`mode` 为 `BY_TYPE`
- **THEN**: 系统 SHALL 将指定固定分值的题型按所设单题分值赋予，计算固定题型分值总和
- **AND**: 系统 SHALL 将剩余目标分值通过最大余数算法均匀分配给标记为自动配平的题型题目
- **AND**: 全卷所有题目分值之和 SHALL 严格等于目标总分

### Scenario: 单题最低分与合法性拦截 (ADDED)
- **WHEN**: 均分计算结果中任意题目分值低于 0.5 分，或待配平题目数为 0
- **THEN**: 系统 SHALL 拦截并拒绝配平，返回 400 业务错误提示，不执行任何数据更改
