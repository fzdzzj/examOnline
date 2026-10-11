# spec-delta：add-deployment-stack

> 新立能力域 `deployment`（第 20 域）+ ADDED 1 Requirement（六 Scenario）；既有 19 个能力域零改动。合入目标文件：`spec/specs/deployment/spec.md`（新建）。

## ADDED Requirement: 生产部署栈

WHEN 应用以前述部署栈形态交付,

系统 SHALL 以可独立构建的容器镜像承载前后端、SHALL 以同源反向代理暴露 API、SHALL 强制 HTTPS 终结、SHALL 使生产凭据缺变量即失败, AND SHALL 保持建表唯一入口与存量迁移人工台账语义不变。

#### Scenario: 镜像可构建且机器中立

GIVEN 干净克隆与可用的容器构建环境

WHEN 分别构建后端与前端镜像

THEN 多阶段构建成功产出可运行镜像

AND 构建不依赖仓库外机器状态（不打包 `.m2-repo`、不含机器绝对路径）

#### Scenario: 同源反代与 SPA 托管

GIVEN 生产编排起栈且前端静态资源就绪

WHEN 浏览器请求 `/api/**`

THEN 由 Nginx 同源反向代理至应用容器

AND 非 API 路径回退 SPA 入口文件

AND 前端代码零改动（`baseURL` 为空串的既有预声明即成立）

#### Scenario: HTTP 强制重定向与 TLS 终结

GIVEN TLS 证书经挂载点注入

WHEN 以 HTTP 明文请求任意路径

THEN 返回重定向至 HTTPS

AND HTTPS 下真实 API 调用（登录）成功

AND 自签证书仅用于本地验证，真实证书替换为人工动作不入库

#### Scenario: 生产凭据缺变量即失败

GIVEN 生产编排或 prod profile 的任一必需凭据环境变量未设置

WHEN 编排解析或应用启动

THEN 明确失败且不回退任何默认凭据

AND 生产凭据不以默认值形态入版本库

#### Scenario: 建表唯一入口与迁移台账语义不变

GIVEN 生产编排以全新数据卷首起

WHEN MySQL 容器初始化

THEN 建表仅经 `src/main/resources/schema.sql` 单一事实源的物理引用执行（幂等，零副本）

AND 起栈后登录链路可用即证明建表生效

AND 迁移脚本无任何自动执行者（编排 init 仅挂 schema.sql 与复制用户脚本），存量库人工台账纪律不变

#### Scenario: 上传上限两侧对齐

GIVEN Nginx 反代与后端 multipart 限制并存

WHEN 上传超过后端 `max-request-size` 口径的请求经反代

THEN Nginx `client_max_body_size` 与后端限制对齐（由后端口径驱动，不弱于后端拒绝阈值）

AND 上传类接口行为与直连后端一致
