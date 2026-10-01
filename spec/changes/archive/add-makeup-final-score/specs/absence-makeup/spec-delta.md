# 规范差异：absence-makeup（补考成绩规则·接线收口）

本文件包含对 `spec/specs/absence-makeup/spec.md` 的规范变更（修改既有 Requirement，补接口可达场景）。

## MODIFIED Requirements

### Requirement: 补考成绩规则

WHEN 计算补考最终成绩,

系统 SHALL 按考试配置的规则（取最高分/取最近一次/取平均分）合并，历史成绩 SHALL 保留不覆盖，且最终成绩 SHALL 可经接口查询。

#### Scenario: 取最高分

GIVEN 补考成绩规则为取最高分

AND 学生主考与补考均有成绩

WHEN 计算最终成绩

THEN 取两者较高分

AND 历史成绩均保留

#### Scenario: 历史成绩保留

GIVEN 学生多次补考

WHEN 计算最终成绩

THEN 各次成绩记录均保留

AND 不覆盖删除

#### Scenario: 最终成绩经接口可查

GIVEN 学生主考与补考均有成绩

WHEN 经补考最终成绩接口查询

THEN 返回按考试配置规则合并的结果

AND 历史成绩仍保留

#### Scenario: 合并规则有真实调用者

GIVEN 代码库中 MakeupScoreService 的最终成绩计算入口

WHEN 检查其调用点

THEN 存在接口层真实调用

AND 不再是零调用死代码
