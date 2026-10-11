# spec-delta：add-backup-restore-drill

> 合入目标文件：`spec/specs/reliability/spec.md` 末尾追加。ADDED 1 Requirement（六 Scenario）；既有 Requirement 零改动。

## ADDED Requirement: 数据库备份与恢复演练

WHEN 业务数据库需要备份或恢复,

系统 SHALL 提供参数化双环境（dev 主库 / prod 编排库）的备份与恢复脚本，SHALL 以人工执行台账登记每次备份，AND SHALL 提供经真实演练验证的恢复步骤。

#### Scenario: 双环境全量备份

GIVEN dev 主库或 prod 编排库任一环境

WHEN 以环境参数执行备份脚本

THEN 产出含 GTID 一致性（--single-transaction --source-data）的全量 dump、gzip 压缩并附 sha256

AND 落于不入库的产物目录，凭据经环境变量注入、脚本零硬编码口令

#### Scenario: 隔离恢复演练

GIVEN 一次真实备份产物

WHEN 在一次性隔离 MySQL 容器内执行恢复脚本

THEN 恢复后表数与 dump 表数一致且关键行抽样逐值一致

AND 演练不向任何共享环境写入

#### Scenario: 恢复防护

WHEN 执行恢复脚本

THEN 目标容器与库名须显式指定且恢复前打印 dump 元信息并要求显式确认

AND 缺确认即拒绝执行（防误恢复生产库）

#### Scenario: 轮转与幂等

GIVEN 备份产物目录内已存在超过保留份数的旧 dump

WHEN 再次执行备份

THEN 过期文件按保留份数删除且脚本可被宿主 cron 幂等调用

AND 仓库不含任何自动执行者（定时属宿主 crontab 文档化示例）

#### Scenario: 人工执行台账

WHEN 每次真实备份或恢复演练完成

THEN 于 BACKUPS.md 台账登记日期、环境、文件名、sha256 与演练结论

AND 该登记为人工义务（对齐 migrations APPLIED.md 纪律，脚本入库不等于备份已运行）

#### Scenario: 丢失边界文档化

WHEN 维护者查看备份 README

THEN Redis 草稿、RabbitMQ 持久化消息、binlog 三者的丢失边界与既有事实（appendonly/durable/log-bin）如实陈述并对齐 data-consistency 域既有结论

AND 不新造任何新的持久化承诺、异地容灾与 PITR 显式排除
