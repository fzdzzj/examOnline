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
> - fix-prod-container-tz（2026-10-17 归档）：运行层声明 `ENV TZ=Asia/Shanghai`（基础镜像自带 tzdata 不装包），
>   修复 prod 容器 JVM 走基础镜像默认 UTC 致考试定时链路整体漂 8 小时；词法护栏
>   `ProdContainerTzGuardTest` 锚定运行层段落断言 TZ 声明（CI 无 docker，部署文件变更的唯一机检防线）。

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

### Requirement: 生产容器时区与业务墙钟一致

WHEN 考试定时链路（状态推进、缺考标记、开考提醒、交卷门控、草稿 TTL）在 prod 容器内执行,

系统 SHALL 以业务约定的上海墙钟（Asia/Shanghai）运行, AND SHALL NOT 因容器基础镜像默认 UTC 使全部时间比较漂移。

#### Scenario: 修复前确证

GIVEN prod 演练容器以当前镜像运行

WHEN 执行 `docker exec exam-online-prod-app date`

THEN 输出为 UTC 时区（留证：命令 + 当次输出 + 短 revision）

AND 若容器未在跑，先以 `docker compose -f docker-compose.prod.yml` 起环境再确证

#### Scenario: 修复后对齐

GIVEN Dockerfile 运行层声明 `ENV TZ=Asia/Shanghai` 且镜像重建

WHEN 执行 `docker exec exam-online-prod-app date`

THEN 输出显示 CST (+0800)

AND 定时任务（10s 级扫描）日志时间戳与上海墙钟对齐

#### Scenario: 护栏防回退

GIVEN CI 环境不跑 docker、部署文件变更无其他机检防线

WHEN Dockerfile 的 TZ 声明被删除

THEN 词法护栏单测以确定性断言失败（先红后绿留证，先例 `PublisherConfirmScopeGuardTest` 词法护栏模式）

#### Scenario: 构建行为不受影响

GIVEN build 层（`-DskipTests package`）与时区无关

WHEN 仅运行层声明 TZ

THEN 构建产物与修复前等价（build 层零改动）

---

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

### Requirement: 生产环境安全强制项 (add-prod-profile-enforcements)

WHEN prod 环境启动,

系统 SHALL 检测默认管理员账号是否仍为公开已知默认口令并 fail-fast 拒绝启动（显式豁免除外）,
AND SHALL NOT 在 SMTP 未配置时将找回密码验证码明文落日志（改为功能明确报错不可用）,
AND SHALL NOT 影响 dev/test 档的本地便利行为。

#### Scenario: 默认口令启动闸 fail-fast (add-prod-profile-enforcements)

GIVEN prod 编排强制要求 ADMIN_PASSWORD 环境变量（新库初始创建即强口令）

WHEN 启动时检测到配置的管理员账号口令哈希仍匹配公开已知默认口令

THEN 以明确异常中止启动（含账号与两条处置路径的启动日志）

AND 检查时序晚于 AdminInitializer（@Order(3)，检查创建后最终状态）

#### Scenario: 显式豁免仅限存量库过渡 (add-prod-profile-enforcements)

GIVEN 存量库的管理员口令改密需先登录完成

THEN exam.auth.admin.allow-default-password=true 显式豁免可放行启动（打 WARN）

AND 豁免是过渡形态：登录改密后必须移除豁免并重启复核（处置路径写进日志与 javadoc）

#### Scenario: SMTP 未配置时验证码绝不明文落日志 (add-prod-profile-enforcements)

GIVEN 验证码明文落日志意味着 5 分钟 TTL 内有日志读权限者可接管任意账号

THEN base 档 allow-log-fallback 默认 false（安全缺省），prod 继承 false

WHEN SMTP 未配置且兜底关闭时请求找回密码验证码

THEN 返回明确业务报错（功能暂不可用），任何日志行不含验证码明文

AND 未注册邮箱静默成功的防枚举语义不变

#### Scenario: dev/test 档便利保留 (add-prod-profile-enforcements)

GIVEN 本地开发与测试依赖日志兜底跑通找回流程

THEN dev/test 档显式声明 allow-log-fallback=true，行为与本卡之前零变化

AND 启动闸仅 prod profile 装配，dev/test 启动零影响

#### Scenario: 变异击红 (add-prod-profile-enforcements)

GIVEN 摘除启动闸的抛出（改为仅 WARN）

WHEN 运行启动闸单测

THEN 以确定性断言失败（先红后绿留证；等价变异如实登记不强杀）