# dev-config 规范

> 能力域：开发环境配置（提案⑫ 新建）。
> 来源：`spec/changes/archive/parameterize-dev-credentials` 合入（dev 凭据参数化，2026-09-21）。
> 实施注记：
> - `docker-compose.yml` 主/从 `MYSQL_ROOT_PASSWORD`（含两处 healthcheck 口令）与 `RABBITMQ_DEFAULT_USER/PASS` 共六处均为 `${ENV:-default}` 形态，默认值与参数化前逐字一致（开箱行为不变）。
> - `application*.yml` 各凭据（DB 主/从、Redis、SMTP、JWT）立项复核时已是 `${ENV:fallback}` 形态，本变更未改动；生产侧多数无兜底默认值 = fail-fast，语义未动。
> - 覆盖验证口径：不设变量时 `docker compose config` 解析值与原字面量逐字一致；设变量后解析为覆盖值且 healthcheck 口令同步。
> - 自查命令（bash；输出须为空，非空即存在裸凭据）：
>
> ```
> grep -inE '(password|passwd|secret|_pass)[[:space:]]*:' docker-compose.yml docker/observability/docker-compose.observability.yml src/main/resources/application*.yml | grep -v '\${'
> ```
>
>   已做过变异验证：注入 `password: bare123` / `rabbit_pass: exam999` 均可检出；`${...}` 形态与 healthcheck 内嵌口令不误报。

## Requirements

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
