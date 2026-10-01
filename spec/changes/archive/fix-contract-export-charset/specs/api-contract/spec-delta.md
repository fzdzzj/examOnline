# 规范差异：api-contract（OpenAPI 契约暴露·导出编码收口）

本文件包含对 `spec/specs/api-contract/spec.md` 的规范变更（修改既有 Requirement，补导出编码无损场景）。

## MODIFIED Requirements

### Requirement: OpenAPI 契约暴露

WHEN 前端或其他消费方需要与后端 API 集成,

系统 SHALL 暴露与代码实现同步生成的 OpenAPI 3 文档，且 SHALL 提供可入仓库的契约文件作为客户端生成的唯一来源，且导出路径 SHALL 编码无损。

#### Scenario: 文档覆盖全部端点

GIVEN 后端存在 14 个 Controller

WHEN 获取 /v3/api-docs

THEN 每个 Controller 的路径均出现在文档中

AND 新增端点后文档自动同步

#### Scenario: 鉴权语义标注

GIVEN 端点需要 Access Token

WHEN 消费方读取文档

THEN 能识别 Bearer 鉴权方案与免鉴权端点清单

AND 免鉴权端点在文档中显式声明为空 security 而非仅依赖运行时白名单

#### Scenario: 契约可导出复现

GIVEN 需要为前端生成类型化客户端

WHEN 按记录的命令导出契约

THEN 产出 openapi.yaml 且入仓库

AND 后端接口变更后可重新导出更新

#### Scenario: 契约冒烟护栏

GIVEN 文档端点可能因配置或依赖变化失效

WHEN 运行测试

THEN api-docs 可达性与路径规模下限由测试守住

AND 导出的契约文件非空且含 OpenAPI 版本声明

#### Scenario: 导出不污染常规测试运行

GIVEN 常规测试运行不应改写仓库文件

WHEN 执行全量测试

THEN 契约导出不发生

AND 仅在显式开启导出开关时才写入 openapi.yaml

#### Scenario: 导出编码无损

GIVEN 导出开关开启且文档含非 ASCII 字段

WHEN 执行测试内导出

THEN 产出文件按字节流写入，中文不乱码

AND servers.url 保持完整形态不退化

AND 与真 dev 实例直接拉取的契约语义一致
