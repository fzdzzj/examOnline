# deployment 规范

> 能力域：生产部署栈——应用镜像化 + Nginx TLS 终结 + 独立生产编排（生产化 P3，add-deployment-stack）。
> 来源：`spec/changes/archive/add-deployment-stack` 合入。
> 实施注记：
> - 新增根 `Dockerfile`（后端多阶段：Temurin 17 JDK 构建层 / JRE 运行层；`.mvn`/`maven-settings.xml` 随带、
>   CRLF `mvnw` 转 LF 赋执行位、直接 `package` 按需拉依赖、不打包 `.m2-repo`）、根 `.dockerignore` 与
>   `frontend/.dockerignore`（build context 瘦身）、`frontend/Dockerfile`（node22 + corepack 对齐
>   `packageManager` 锁定 pnpm `--frozen-lockfile` + vite build → nginx stable-alpine）、
>   `frontend/nginx.conf`（HTTP→HTTPS 301、TLS 证书挂载点、SPA `try_files` 回退、`/api/` 同源反代 `app:8080`、
>   `client_max_body_size` 对齐后端 multipart 50MB）、`docker/certs/generate-selfsigned.sh`（openssl 自签 SAN localhost，
>   产物落 `docker/certs/out/` 已 gitignore）、`docker-compose.prod.yml`（web/app/redis/mysql 主从/rabbitmq，
>   凭据全 `${VAR:?err}` fail-fast，app 与中间件默认不映射宿主端口、web 唯一对外 8081/8443 可覆盖，
>   mysql init 单文件挂载既有 repl-user 脚本 + `src/main/resources/schema.sql` 物理引用零副本，逐服务 healthcheck）。
> - 对既有 `docker-compose.yml`、`docker/observability/**`、`docker/mysql/**`、`application*.yml`、`vite.config.ts` 零触碰。
> - 前端 `baseURL=''` 已有同源反向代理预声明（`frontend/src/api/config.ts`），前端源码零改动即工作。

## Requirements

### Requirement: 生产部署栈

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