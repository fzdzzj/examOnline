# 规范差异：dev-config（开发环境配置）

本文件包含对 `spec/specs/dev-config/spec.md` 的规范变更（新增能力域）。

## ADDED Requirements

### Requirement: 开发环境凭据参数化

WHEN dev 相关配置中出现连接凭据或密钥,

系统 SHALL 使其可被环境变量覆盖，且 SHALL 保证未设置环境变量时本地开箱体验不变。

#### Scenario: 未设环境变量走默认

GIVEN 干净克隆且未设置任何环境变量

WHEN dev 配置加载

THEN 凭据取配置文件内保留的本地默认值

AND 行为与参数化前完全一致

#### Scenario: 环境变量可覆盖

GIVEN 设置了对应环境变量

WHEN dev 配置加载

THEN 凭据取环境变量值

#### Scenario: 新增凭据不得裸写

GIVEN 后续任何 dev 配置改动

WHEN 引入新的凭据或密钥项

THEN 必须以环境变量可覆盖的形态写入

AND 该性质由可机械执行的自查命令守住

#### Scenario: 生产凭据不带默认值

GIVEN prod profile 的凭据配置

WHEN 检查其形态

THEN 生产凭据不携带回退默认值，缺变量即启动失败

AND 本变更不改动生产 fail-fast 语义
