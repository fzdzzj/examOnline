# 提案：告警投递链路（Alertmanager webhook 本地闭环，生产化 P6 重定义）

> 状态：已立项待执行。阶段 0 查证基于 main `783ef28`（2026-10-11）现场重核。台账候选 P6 经现场重核**半过期**：「按 observability spec 既有指标清单接告警」半已闭环——`docker/observability/prometheus/rules/exam-online-alerts.yml` 9 条规则（down/交卷失败率/P99/MQ 积压/限流降级/5xx/防作弊/DLQ/重试耗尽）早已落地，阈值按 spec 判据设定且有词法护栏 `src/test/java/com/exam/observability/AlertAssetsTest.java` 守护；真缺口是**投递链路**：prometheus 无 alerting 段、编排无 Alertmanager——9 条规则触发后无处投递、无人看见。范围按用户裁决定版为 **Alertmanager 接入 + webhook 通道本地闭环验收**（邮件/飞书/钉钉等真实平台对接显式排除）。

## Why

- **现状（`783ef28` 实测）**：`docker/observability/prometheus/prometheus.yml` 无 alerting 段（规则触发只会在 Prometheus UI 静默变红）；`docker-compose.observability.yml` 仅 prometheus + grafana 两服务，无 Alertmanager；`AlertAssetsTest` 断言对象为 alerts.yml 规则内容（9 条名称/expr/for/severity/summary/description）+ 资产存在性，**不锁 prometheus.yml 结构**——加 alerting 段不撞护栏；改规则则必撞护栏（EXPECTED_ALERTS 集合断言），故规则零碰是护栏强制的硬约束。
- **通道形态（用户定版）**：webhook 本地闭环——Alertmanager webhook receiver 指向本地临时接收容器，验收以真实触发（不起应用制造 `up==0` → `ExamOnlineDown` for 1m pending→firing）→ receiver 收到 POST 落日志留证；零外部依赖、零真实平台凭证；生产对接任意平台（飞书/钉钉/企业微信）时仅需以环境变量覆盖 webhook URL，不换代码。
- **镜像通道前置**：prom/alertmanager 基础镜像拉取走 add-deployment-stack 已解锁的本机 registry-mirror 通道（仓库层坐标零改动的环境层配置），无新增网络风险面。

## What Changes

1. **`docker/observability/prometheus/prometheus.yml`**：新增 `alerting` 段（alertmanagers 指向 compose 网内 `alertmanager:9093`）；若既有配置未挂载规则文件则同笔补 `rule_files`（任务 1 K3 现场查证后按实况定，如实登记）。
2. **`docker/observability/docker-compose.observability.yml`**：
   - 新增 `alertmanager` 服务：prom/alertmanager **pinned 具体版本号（禁 latest，对齐本文件头注释既有纪律）**，配置文件挂载只读；
   - 新增 `alert-receiver` 服务：本地验收专用临时 POST 接收器，**profile 门控**（先例：redis 的 `host-redis-unavailable` profile 形态）——默认不随栈启动，验收时 `--profile` 显式拉起，最小实现（如 python:alpine 数行 http server 收 POST 落日志），零业务绑定。
3. **`docker/observability/alertmanager/alertmanager.yml`（新增）**：
   - `route` 分层：`severity=critical` → 立即投递（group_wait 0s）；`severity=warning` → 分组聚合（group_wait 适度窗口）；`severity=info` → 不路由至任何 receiver（只留观）；
   - `inhibit`：critical 活跃时抑制同级 warning 噪音（形态实施时按 Alertmanager 语义定）；
   - `receivers`：webhook receiver，URL 默认指向 compose 网内 alert-receiver，**经环境变量可覆盖**（真实平台 URL 含 token 时走环境变量注入，绝不入库）。
4. **文档登记**：`spec/README.md` 三处常规登记（「进行中变更」行 / observability 能力域行来源列追加 / 收口时改收口句与归档表行）；`README.md`「要查什么去哪儿查」表 observability 行按需补告警投递指针（不写易变事实）。
5. **spec 基线**：`spec/specs/observability/spec.md` 末尾 ADDED「告警投递链路」Requirement（五 Scenario，见 spec-delta）。
6. **三件套随实施笔一并入库**（对齐先例，使收口笔归档形态为 R100）。

## Impact

- **规范**：observability 能力域 ADDED 1 Requirement（五 Scenario）；既有 Requirement 零改动。
- **代码**：零业务代码改动（`src/main`、`frontend/`、`pom.xml`、`schema.sql`、`openapi.yaml`、`.github/` 零触碰）；**`exam-online-alerts.yml`（9 条规则与阈值）与 `AlertAssetsTest.java` 零触碰**（护栏硬约束：改规则必撞 EXPECTED_ALERTS 断言）；既有文件受控修改仅两处——prometheus.yml（+alerting 段）与 compose.observability（+两服务）；新增 alertmanager 配置目录；spec/README 登记行。
- **用户/API**：无任何可见变化。
- **数据与部署**：无 schema、零应用配置改动；observability 栈为可选依赖（不随业务栈启动），本案不触碰业务编排与共享 dev 资源。
- **CI**：零触碰；`gate-baseline.json` 合法持平（零用例变化）。
- **外发动作授权**：本案验收含 push（feature、main）——派发本卡即视为授权本案范围内 push。
- **非目标（显式排除）**：真实平台对接（飞书/钉钉/企业微信 webhook、短信、电话）；邮件 SMTP receiver；告警规则与阈值的任何调整（台账「阈值须按 spec 判据」已由既有规则满足，本案零碰）；值班/升级策略；业务代码告警埋点；Grafana 告警面板改造。

## 验收与停止条件

- **本地可验证部分（三要素留证）**：
  ① 配置解析：`docker compose -f docker/observability/docker-compose.observability.yml config` 通过（默认不启动 alert-receiver，profile 门控自证）；prometheus.yml 与 alertmanager.yml 结构自查（alerting 指向、route 分层、inhibit 存在性）；
  ② 真实触发闭环：起 observability 栈（含 `--profile` 拉 receiver）而**不启动应用** → Prometheus 抓取失败 `up{job="exam-online"}==0` → `ExamOnlineDown` pending→firing（for 1m）→ Alertmanager 投递 → **receiver 日志收到 POST（含 alertname/severity/summary）留证** + Alertmanager UI 与 Prometheus UI 告警状态留证；
  ③ 路由分层核验：critical 立发语义（配置级 + 实测到达时距）、warning 聚合窗口、info 不投递（AntiCheatEventSpike 类 info 告警无法低成本制造时以配置形态核验 + amtool/UI 辅助，如实登记核验层级）；
  ④ 护栏自证：实施笔后后端门禁中 AlertAssetsTest 全绿（规则零碰的执行层证据）；双端门禁持平基线；
  ⑤ 清理：验收后 `down` 全栈，无残留容器/卷污染共享环境。
- **停止条件**：① prom/alertmanager 无法 pinned 到具体稳定版本（仅 latest）→ 停止上报；② 基础镜像经既有 mirror 通道仍持续拉取失败 → 停止上报（停止条件同 add-deployment-stack 解锁路径，不引入新通道方案）；③ 发现必须修改 `exam-online-alerts.yml`、`AlertAssetsTest.java` 或任何 `src/` 文件才能工作 → 停止上报（护栏与业务红线）；④ webhook 验收链路在本地两轮调整仍无法真实触发（ExamOnlineDown 制造失败）→ 停止上报；⑤ 被要求将任何真实平台 URL/token 以明文形态写入仓库文件 → 停止上报。
