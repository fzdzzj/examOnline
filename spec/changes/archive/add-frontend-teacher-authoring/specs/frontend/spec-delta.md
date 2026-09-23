# 规范差异：frontend（题库与组卷界面）

本文件包含对 `spec/specs/frontend/spec.md` 的规范变更（新增）。

## ADDED Requirements

### Requirement: 题库管理界面

WHEN 教师需要维护题目,

系统 SHALL 提供题目检索、录入、修改与软删除的界面，且 SHALL 按题型提供对应的录入校验。

#### Scenario: 按条件检索题目

GIVEN 题库中存在多道题目

WHEN 教师按题型、标签或关键词筛选

THEN 列表按分页呈现匹配结果

#### Scenario: 题型差异化录入

GIVEN 教师选择某一题型

WHEN 填写题目表单

THEN 仅呈现该题型适用的字段

AND 该题型的必要校验在提交前生效

#### Scenario: 软删除语义以后端为准

GIVEN 一道题目已被软删除

WHEN 教师查看题库

THEN 其可见性与可操作性由后端返回决定

AND 前端不将已删题目呈现为可用

### Requirement: 组卷界面

WHEN 教师需要产出试卷,

系统 SHALL 提供手动选题与标签随机抽题两条路径，且 SHALL 支持逐题分值覆盖与总分汇总。

#### Scenario: 手动组卷可排序与改分

GIVEN 教师已选入若干题目

WHEN 调整题号顺序或单题分值

THEN 试卷内容与总分实时更新

#### Scenario: 随机抽题结果可确认

GIVEN 教师配置了标签、题型与数量

WHEN 触发抽题

THEN 前端呈现抽中结果

AND 教师可重抽或确认入卷

#### Scenario: 随机算法不在前端

GIVEN 需要按标签抽题

WHEN 前端发起抽题

THEN 由后端决定抽中题目

AND 前端不复现随机算法

#### Scenario: 试卷可只读预览

GIVEN 一份已组好的试卷

WHEN 教师查看试卷详情

THEN 呈现题目内容与分值分布

### Requirement: 前端不复现判分口径

WHEN 前端处理题目答案录入,

系统 SHALL 仅做格式校验，且 SHALL NOT 在前端实现答案归一化或判分规则。

#### Scenario: 归一化归后端

GIVEN 教师录入判断题答案

WHEN 前端提交

THEN 按后端约定的原始格式提交

AND 归一化由后端完成

#### Scenario: 两端不漂移

GIVEN 判分口径需要调整

WHEN 修改发生

THEN 仅后端变更

AND 前端无需同步修改判分逻辑
