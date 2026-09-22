# 规范差异：data-access（MySQL 8 初始化·真机验证收口）

本文件包含对 `spec/specs/data-access/spec.md` 的规范变更（修改两个既有 Requirement，各补真机执行场景）。

## MODIFIED Requirements

### Requirement: 新库建表可在 MySQL 8 执行

WHEN 用正式建表脚本在空的 MySQL 8 实例初始化数据库,

系统 SHALL 使脚本执行成功并建出全部业务表，且 SHALL 与测试所用建表脚本为同一份，并 SHALL 有真实 MySQL 8 实例上的执行证据。

#### Scenario: 空库可建全

GIVEN 一份空的 MySQL 8 库

WHEN 执行 schema.sql

THEN 全部表创建成功

AND 不因 AUTO_INCREMENT 未定义为 key 而失败

#### Scenario: 测试与新库同源

GIVEN 集成测试通过加载 schema.sql 建表

WHEN 新环境使用同一文件

THEN 不会出现「测试绿、MySQL 空库建不起来」

#### Scenario: 真机空库初始化实测

GIVEN 真实空 MySQL 8 实例

WHEN 人为执行 schema.sql

THEN 全部业务表建成且无报错

AND 证据含实际执行的命令、该次原始输出与当时短 revision

AND 该证据不得以 H2 结果或文本约定测试替代

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

#### Scenario: 存量迁移真机实测

GIVEN 按迁移前形态构造的真实 MySQL 8 存量库

WHEN 人为执行补主键迁移脚本

THEN 缺主键的表补上 PRIMARY KEY

AND 重复执行的可忽略失败行为与脚本头注声明一致

AND 证据含实际执行的命令与该次原始输出
