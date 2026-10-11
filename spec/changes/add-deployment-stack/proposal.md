# 提案：生产部署栈（应用镜像化 + Nginx TLS 终结 + 独立生产编排，生产化 P3 重定义）

> 状态：已立项待执行。阶段 0 查证基于 main `46cac1d`（2026-10-11）现场重核；开工基线在任务 1 以 `git rev-parse --short HEAD` 实测记录。台账候选 P3 四要素经现场重核大幅修正（防「台账滞后致范围失真」先例），范围按用户裁决定版为**一揽子**（后端镜像 + 前端 Nginx 托管反代 + 独立生产编排 + HTTPS 自签全链路验证），台账项②经查证**过期剔除**（如实登记，见 Why）。

## Why

- **台账 P3 四要素现场重核（`46cac1d` 实测）**：
  1. **应用镜像化与多阶段构建**——真缺口：全仓零 Dockerfile（`**/Dockerfile*` 零命中）；`docker-compose.yml` 仅中间件编排（MySQL 主从/Redis/RabbitMQ/Jaeger/ES），应用本体裸跑 `java -jar`（pom 已有 spring-boot 插件 + `finalName=exam-online`，镜像化前置齐备）；
  2. **生产 profile 固化压测口径**——**过期剔除**：`application-prod.yml` 早已存在且完整（datasource 主从双源全环境变量化、hikari 池 master 50 / slave 20 已固化、redis/mail 外置、`sql.init.mode: never`、prod 关 swagger-ui）；且压测结论是提交 P99 **如实未达标**且「无配置杠杆可再用」（`docs/submit-capacity-tuning-report.md`，spec/README 遗留事项第 1 条）——**无达标容量数字可固化**，强行「固化口径」违反「不得改口径迎合目标」纪律。本案对 prod.yml 零改动；
  3. **HTTPS 终结与 cookie secure 标记**——cookie 部分查证**无对象**：认证是 JWT + localStorage + `Authorization: Bearer` 头（`frontend/src/utils/token.ts` / `apiClient.ts`），后端 Java 零 cookie 代码——无 cookie 可标 secure；HTTPS 真缺口（应用侧与部署侧均无 TLS 层），与要素④合并为同一 Nginx 终结层；
  4. **前端 Nginx 托管与 API 反代**——真缺口：frontend 零 nginx 配置、零部署形态；`frontend/src/api/config.ts` 注释已预留「生产走同源反向代理」（`baseURL=''`，路径契约本身带 `/api`）——反代语义已在前端预声明，缺的只是载体。
- **形态先例与依赖**：`docker/observability/docker-compose.observability.yml` 已确立「独立 compose 文件承载独立栈」先例；`docker-compose.yml` 凭据全 `${ENV:-default}` 参数化（dev-config 能力域）、`application-prod.yml` 生产侧刻意无兜底（缺变量即启动失败，README「要查什么去哪儿查」表口径）——容器环境变量注入形态与既有 fail-fast 哲学天然对齐。
- **HTTPS 深度（用户定版）**：自签全链路验证——Nginx TLS 配置 + 自签证书生成脚本 + 证书挂载点，本地以 HTTPS 起栈冒烟真实链路（登录/静态资源/反代），证明 TLS 配置真实性；**真实证书替换属后续人工动作**（依赖真实域名/环境），本案如实登记为非目标。

## What Changes

1. **根 `Dockerfile`（后端多阶段构建）**：构建层 temurin 17 JDK + `./mvnw package`（wrapper 依赖 `.mvn/` 全套，仓库自足；依赖层与源码层分层缓存；**不打包仓库内 `.m2-repo`**——容器构建走公共仓库拉取，零机器绑定）；运行层 temurin 17 JRE + `exam-online.jar`；`JAVA_OPTS` 环境变量挂载点（默认不调优——压测口径不动）。
2. **根 `.dockerignore` 与 `frontend/.dockerignore`**：build context 瘦身（`.git`、`target`、`.m2-repo`、`node_modules`、`dist`、`docs`、`spec`、`loadtest` 证据目录等——`.m2-repo` 可达 GB 级，不 ignore 会拖死构建）。
3. **`frontend/Dockerfile`（多阶段）**：构建层 node 22 + corepack 启用 pnpm（版本对齐 `packageManager` 字段锁定值）+ `--frozen-lockfile` + `vite build`；运行层 nginx stable-alpine 托管 dist。
4. **`frontend/nginx.conf`**：HTTP→HTTPS 301 重定向；HTTPS server 段（证书走挂载点注入）；SPA 托管（`try_files` 回退 index.html）；`/api/` 同源反代应用 8080；`client_max_body_size` 对齐后端 multipart 上限（`max-request-size: 50MB`——nginx 默认 1M 会挡上传，必须显式对齐）；gzip。
5. **`docker/certs/generate-selfsigned.sh`**：openssl 自签证书脚本（SAN 含 localhost），产物落 `docker/certs/out/`；`.gitignore` 追加该产物目录（证书不入库）。
6. **`docker-compose.prod.yml`（根目录独立文件，零触碰既有 `docker-compose.yml`）**：
   - services：`web`（前端镜像 + 证书挂载，唯一对外端口，默认值避开 dev 编排已占端口，全可环境变量覆盖）、`app`（后端镜像，**不映射宿主端口**——仅经容器网络对 web 提供服务）、`redis`（生产形态直起，无 dev 的 profile 门）、`mysql-master`/`mysql-slave`（GTID 主从，**默认不映射宿主端口**）、`rabbitmq`（同 dev 定义语义、凭据注入）；
   - 凭据全部 `${VAR:?error}` required 形态（缺变量 compose 解析即失败——fail-fast，对齐 dev-config「生产凭据不带默认值」Scenario）；
   - mysql init：复制用户脚本沿用 `docker/mysql/master/init/` 既有文件**单文件挂载**；建表经 `src/main/resources/schema.sql` **单文件挂载**（新库首起建表唯一入口的物理引用，**零副本**——绝不复制 schema.sql 内容到新位置）；
   - healthcheck 逐服务配置（命令用基础镜像自带工具域，不引入额外安装义务）；文件头注释详尽自述（对齐 `docker-compose.yml` 头注释先例）。
7. **文档登记**：`README.md`「要查什么去哪儿查」表 +1 行部署栈指针（不写端口等易变事实，事实在 compose 头注释）；`spec/README.md` 三处常规登记（进行中变更行 / 能力域表新增行 / 收口时改收口句与归档表行）。
8. **spec 基线**：新立 `spec/specs/deployment/spec.md` 能力域（第 20 域）+ Requirement「生产部署栈」（六 Scenario，见 spec-delta）；既有 19 域零改动。
9. **三件套随实施笔一并入库**（对齐 add-ci-gate-pipeline O-1 教训，使收口笔归档形态为 R100）。

## Impact

- **规范**：新立 deployment 能力域 1 个（ADDED 1 Requirement 六 Scenario）；既有能力域零改动。
- **代码**：零业务代码改动（`src/`、`frontend/src/`、`pom.xml`、`schema.sql`、`openapi.yaml`、`.github/` 零触碰）；新增根 `Dockerfile`、`.dockerignore`、`frontend/Dockerfile`、`frontend/.dockerignore`、`frontend/nginx.conf`、`docker/certs/generate-selfsigned.sh`、`docker-compose.prod.yml`；既有文件仅 `.gitignore` 追加（证书产物目录）与 README/spec-README 登记行；`docker-compose.yml` 与 `docker/observability/**` 与 `docker/mysql/**` 零触碰。
- **用户/API**：无任何可见变化（API 契约、错误码、前端行为零改动；`baseURL=''` 已预留同源反代，前端源码零改动即工作）。
- **数据与部署**：零 schema 改动；prod 编排新库首起经 schema.sql 幂等建表（`CREATE TABLE IF NOT EXISTS`）；存量库零影响（编排不自动触碰任何既有库；`docker/mysql/migrations/` 人工台账纪律不变——**无自动执行者**语义保持，编排 init 仅挂 schema.sql 与复制用户脚本，不挂 migrations）。
- **CI**：零触碰（`.github/` 零改动；零用例变化，`gate-baseline.json` 合法持平）。
- **外发动作授权**：本案验收必然包含 push（feature 分支、main）——用户派发本卡即视为授权本案范围内的 push 动作。
- **非目标（显式排除）**：真实证书/DNS/域名接入（自签仅为验证 TLS 配置真实性，真实证书替换属后续人工动作）；CD 流水线（镜像推送 registry、部署自动化）；K8s；可观测性栈（prometheus/grafana/jaeger）进 prod 编排（已有独立编排，compose 层面可用 `-f` 叠加）；备份容灾（候选 P5）；告警通道（候选 P6）；压测复跑与任何容量口径调整（P99 未达标遗留不动，prod.yml 参数零改动）；admin 账号密码轮换等运维流程。

## 验收与停止条件

- **本地可验证部分（Docker Desktop，三要素留证：命令 + 当次输出 + 短 revision）**：
  ① 双镜像构建成功：后端多阶段 `docker build`、前端多阶段 `docker build` 各自产出镜像（构建日志与镜像清单留证）；
  ② 自签证书脚本在宿主 Git Bash 跑通，产物挂载起栈：`docker compose -f docker-compose.prod.yml config` 解析成功（required 变量缺失时明确报错留证——fail-fast 实证）；`up -d` 后全栈 healthy（逐服务 healthcheck 状态留证）；
  ③ HTTPS 全链路冒烟（真实 API 调用，非 curl -k 敷衍单点）：HTTP→HTTPS 301 重定向实测；HTTPS 下登录接口真实调用成功（AdminInitializer 注入账号经环境变量）；静态资源 200；`/api/` 反代链路 web→app 实证；SPA 路由回退实测（直接访问前端路由路径返回 index.html）；
  ④ 新库首起建表实证：全新 volume 起栈后登录冒烟成功即证明 schema.sql init 生效（建表唯一入口经单一事实源物理引用）；
  ⑤ 实施笔后双端门禁持平全绿（零业务代码，预期持平基线；后端仓库根 `mvnw.cmd clean test` + 前端 `npm.cmd` 三门禁，防 System32 空 npm 假绿）；
  ⑥ 既有编排零影响自证：`docker-compose.yml` 与 `docker/observability/**` 零 diff；dev 起栈不受 prod 编排存在影响。
- **远端验证**：push feature → CI 三 job 绿（部署文件不影响 CI）→ `--no-ff` 合入 main → push main → main CI 三 job 绿（三要素留证）。
- **停止条件**：① Docker Desktop 不可用或基础镜像持续拉取失败（网络）→ 停止上报；② 构建或编排被要求引入机器绝对路径、私有凭证，或必须修改既有文件（`docker-compose.yml`、`application*.yml`、`vite.config.ts`、`docker/mysql/**`）才能工作 → 停止上报；③ prod 起栈与 dev 编排/宿主服务端口冲突且环境变量覆盖仍无法解决 → 停止上报，不擅改既有编排；④ 自签脚本在 Windows 宿主跑不通且容器内替代不可行 → 停止上报；⑤ mysql 主从复制在 prod 首起建立失败、两轮调整仍失败 → 停止上报；⑥ 发现必须改动 `src/` 或 `frontend/src/` 业务代码才能让反代/镜像工作 → 停止上报（前端 `baseURL=''` 已预留同源反代，理论上零改动；若真需要改，属新变更裁决）。
