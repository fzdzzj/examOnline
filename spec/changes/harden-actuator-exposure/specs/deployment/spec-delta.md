# spec-delta：deployment 域（harden-actuator-exposure）

## ADDED Requirements

### Requirement: Actuator 暴露面最小化且观测链路不断 (harden-actuator-exposure)

WHEN prod 环境运行,

系统 SHALL 仅暴露观测必需端点（health 与 prometheus）且健康细节不外露（show-details never）, AND 鉴权豁免 SHALL 收窄为观测端点白名单（health/**、prometheus、metrics）而非 /actuator/** 整体豁免, AND SHALL NOT 断 compose healthcheck 与 prometheus 抓取两条观测链路。

#### Scenario: prod 暴露面收敛 (harden-actuator-exposure)

GIVEN base 档保持 health,info,metrics,prometheus（开发便利与测试观测）

THEN prod 覆盖 exposure 为 health,prometheus（info 默认空载零价值剔除、metrics 聚合出口由 prometheus 承载）

AND prod show-details 为 never（healthcheck 只依 HTTP 状态码判定，组件名与连接状态不再未认证可读）

#### Scenario: 鉴权豁免白名单式收窄 (harden-actuator-exposure)

GIVEN /actuator/** 整体豁免使全部 actuator 端点未认证可读

THEN 豁免收窄为 /actuator/health/**、/actuator/prometheus、/actuator/metrics/** 白名单

AND 其余 actuator 端点（含未来新增）一律要求认证（默认拒绝）

AND metrics 保留豁免不影响 prod 实际暴露面（prod 未暴露该端点=404）

#### Scenario: 观测链路保全 (harden-actuator-exposure)

GIVEN prometheus 经 /actuator/prometheus 抓取（AlertAssetsTest 守护 up 指标）、prod healthcheck 打 /actuator/health/db、既有集成测试打 /actuator/metrics/{name} 现场留证

THEN 三条观测链路在收敛后照常工作（豁免白名单全覆盖 + show-details never 不改变 200/503 判定）

#### Scenario: 收敛面受护栏钉住 (harden-actuator-exposure)

GIVEN 暴露面与豁免清单是易回退的部署面配置

THEN 词法护栏测试钉住 prod exposure 恰为 health,prometheus、show-details 恰为 never、WebMvcConfig 白名单三模式在场且 /actuator/** 整体豁免不在场

#### Scenario: 变异击红 (harden-actuator-exposure)

GIVEN WebMvcConfig 恢复 /actuator/** 整体豁免

WHEN 运行暴露面护栏测试

THEN 以确定性断言失败（先红后绿留证；等价变异如实登记不强杀）
