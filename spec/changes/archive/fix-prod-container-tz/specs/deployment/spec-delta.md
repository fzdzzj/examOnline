# spec-delta：deployment（生产容器时区与业务墙钟一致）

## 变更类型

ADDED（新增 Requirement「生产容器时区与业务墙钟一致」，四 Scenario；既有 Requirement 与 Scenario 零改动）

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
