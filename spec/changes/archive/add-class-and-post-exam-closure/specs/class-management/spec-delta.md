# 规范差异：class-management

本文件包含对 `spec/specs/class-management/spec.md` 的规范变更（班级管理，全部为新增）。

## ADDED Requirements

### Requirement: 班级实体
WHEN 教师管理班级,
系统 SHALL 支持创建、查询、更新、删除班级，班级 SHALL 归属教师。

#### Scenario: 创建班级
GIVEN 教师登录
WHEN 教师创建班级
THEN 系统创建班级并记录创建教师
AND 教师可管理该班级

### Requirement: 学生入班与转班
WHEN 学生加入或变更班级,
系统 SHALL 维护学生-班级关联，转班 SHALL 更新关联且成绩随人。

#### Scenario: 学生入班
GIVEN 管理员或教师指定学生
WHEN 学生被加入班级
THEN 建立学生-班级关联

#### Scenario: 转班成绩随人
GIVEN 学生从 A 班转至 B 班
WHEN 更新班级关联
THEN 历史成绩仍跟随学生个人
AND 不受班级变更影响

### Requirement: 班级学生列表
WHEN 查询班级学生,
系统 SHALL 返回该班级当前学生列表，供应考名单推导。

#### Scenario: 查询班级学生
GIVEN 一个班级含多名学生
WHEN 查询班级学生列表
THEN 返回该班级当前全部学生