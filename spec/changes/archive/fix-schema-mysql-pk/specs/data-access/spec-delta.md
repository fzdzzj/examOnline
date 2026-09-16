# 规范差异：data-access（新库建表与 MySQL 8 兼容）

本文件包含对 `spec/specs/data-access/spec.md` 的规范变更（新库建表可在 MySQL 8 执行，新增）。

## ADDED Requirements

### Requirement: 新库建表可在 MySQL 8 执行

WHEN 用正式建表脚本在空的 MySQL 8 实例初始化数据库,

系统 SHALL 使脚本执行成功并建出全部业务表，且 SHALL 与测试所用建表脚本为同一份。

#### Scenario: 空库可建全

GIVEN 一份空的 MySQL 8 库

WHEN 执行 schema.sql

THEN 全部表创建成功

AND 不因 AUTO_INCREMENT 未定义为 key 而失败

#### Scenario: 测试与新库同源

GIVEN 集成测试通过加载 schema.sql 建表

WHEN 新环境使用同一文件

THEN 不会出现「测试绿、MySQL 空库建不起来」

### Requirement: AUTO_INCREMENT 列必须有主键

WHEN 在建表定义中使用 AUTO_INCREMENT,

系统 SHALL 同时声明 PRIMARY KEY（或等价的 key），且 SHALL 用自动化手段防止回归。

#### Scenario: 每张自增表都有主键

GIVEN schema.sql 中任意一张含 AUTO_INCREMENT 的表

WHEN 检查其建表块

THEN 同一块内存在 PRIMARY KEY

AND 该性质由测试守住

#### Scenario: 存量库可补主键

GIVEN 已存在但缺少主键的表

WHEN 执行对应迁移脚本

THEN 为 id 补上 PRIMARY KEY

AND 若主键已存在，重复执行失败可忽略且不改业务数据
