# 规范差异：frontend（试卷详情题目表渲染）

本变更补充 `spec/specs/frontend/spec.md` 既有「组卷界面」Requirement 的可观察场景；直到实际验收完成前，基线中的 D1 缺陷注记仍然有效。

## MODIFIED Requirements

### Requirement: 组卷界面

WHEN 教师需要产出试卷,

系统 SHALL 提供手动选题与标签随机抽题两条路径，且 SHALL 支持逐题分值覆盖与总分汇总；已有题目的试卷详情 SHALL 实际呈现题目表。

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

#### Scenario: 详情页题目表真实可见

GIVEN 试卷中已有题目

WHEN 教师打开试卷详情

THEN 页面实际呈现题目表及题目内容，而非仅显示题目计数或分值分布

AND 试卷未锁定时呈现改分、排序与移出入口

AND 试卷已锁定时呈现只读题目内容