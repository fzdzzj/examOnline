# spec-delta：add-user-import-export

涉及域：authentication、class-management、frontend。

## 域：authentication

### Requirement: 用户 Excel 批量导入

WHEN 管理员上传学生名单 xlsx,

系统 SHALL 提供 `POST /api/admin/users/import`（multipart，权限 ADMIN + user:manage）：按固定列模板（学号/姓名/邮箱可选/初始密码/班级名称）逐行独立事务导入，创建角色固定为 STUDENT 的账号（初始密码 BCrypt 入库）；学号已存在（库内或文件内先出现者）、班级名称不存在或歧义、必填缺失或格式非法的行 SHALL 跳过并逐行报告错误，正确行照常入库；数据行数超过 1000 SHALL 文件级拒绝。返回 {imported, failed, errors}。

#### Scenario: 合法行入库与班级挂接

GIVEN 模板含合法学生行且班级名称在 classes 表存在

WHEN 管理员上传

THEN 账号创建（角色 STUDENT、密码 BCrypt）、user_class 挂接正确

AND 该学生能以初始密码登录

#### Scenario: 重复学号跳过不覆盖

GIVEN 某行学号已存在（库内或文件内先出现）

WHEN 导入执行

THEN 该行跳过并报告错误，既有账号的密码/姓名/角色不被改动

#### Scenario: 班级不存在跳过不自动建班

GIVEN 某行班级名称在 classes 表不存在或存在同名歧义

WHEN 导入执行

THEN 该行跳过并报告错误，不自动创建班级，其余行不受影响

#### Scenario: 角色固定学生

GIVEN 模板不含角色列且导入服务固定 STUDENT

WHEN 导入完成

THEN 全部成功行账号角色为 STUDENT，不存在批量创建教师/管理员的路径

#### Scenario: 行数上限与坏格式

GIVEN 数据行超过 1000，或存在坏格式文件（非 xlsx/表头不符）

WHEN 上传

THEN 文件级拒绝并返回可观察错误，不产生任何入库

#### Scenario: 权限门控

GIVEN 非 ADMIN token 或缺 user:manage 权限的请求方

WHEN 请求导入/模板/导出任一端点

THEN 返回 403

---

### Requirement: 用户导入模板下载

WHEN 管理员下载导入模板,

系统 SHALL 提供 `GET /api/admin/users/import-template`（权限同上）返回 xlsx 模板，表头列序与导入解析、导出输出严格一致（护栏用例锁定三向一致）。

#### Scenario: 模板表头三向一致

GIVEN 模板文件、导入解析列定义、导出列定义

WHEN 三者表头逐列比对

THEN 列序与文案完全一致

---

### Requirement: 用户 Excel 导出

WHEN 管理员导出用户名单,

系统 SHALL 提供 `GET /api/admin/users/export`（权限同上，参数 scope=student|teacher|all，可选 classId 过滤学生）以 SXSSF 流式输出 xlsx：scope=student 输出学生（含班级归属列，classId 指定时仅该班）、scope=teacher 输出教师、scope=all 输出全部用户（含角色列）；软删/状态口径 SHALL 与管理侧既有列表口径一致；列口径与模板一致（学生域列）。

#### Scenario: scope 三态与班级过滤

GIVEN 库内存在学生（分属两班）、教师、管理员

WHEN 分别以 scope=student、scope=teacher、scope=all、scope=student&classId=X 导出

THEN 各导出仅含对应人群，classId 过滤仅输出该班学生

#### Scenario: 流式不溢出

GIVEN 大量用户记录

WHEN 导出

THEN SXSSF 流式写入，不因内存不足失败

#### Scenario: 导出口径与列表一致

GIVEN 管理侧列表查询口径（软删/状态过滤）

WHEN 导出执行

THEN 导出行集合与列表口径一致，不输出列表不可见的用户

## 域：class-management

### Requirement: 导入班级挂接语义

WHEN 批量导入学生行携带班级名称,

系统 SHALL 以 classes 表按名称解析班级并写入 user_class（语义等价既有 joinStudent，不改其实现行）；班级名称不存在或同名歧义时 SHALL 行级跳过，SHALL NOT 自动创建班级。

#### Scenario: 挂接语义等价单建路径

GIVEN 导入行班级名称唯一存在

WHEN 导入完成

THEN user_class 记录与既有一对一入班路径产生的记录语义一致（转班/成绩随人语义不变）

## 域：frontend

### Requirement: 用户导入导出管理界面

WHEN 管理员在用户管理页操作批量导入导出,

系统 SHALL 提供导入上传（文件选择+提交）、结果反馈（成功/失败计数与逐行错误列表）、模板下载入口、导出入口（scope 选择与学生班级过滤选择）；零新依赖，对齐既有管理页风格。

#### Scenario: 导入结果反馈

GIVEN 一次含成功行与错误行的导入

WHEN 导入完成

THEN 页面展示成功/失败计数与逐行错误原因

#### Scenario: 导出入口与过滤

WHEN 管理员选择导出

THEN 可选 scope（学生/教师/全部）与学生班级过滤，触发下载

#### Scenario: 模板下载入口

WHEN 管理员点击下载模板

THEN 获得 xlsx 模板文件
