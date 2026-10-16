# fix-prod-container-tz

## 一、背景与问题（B.2 横切首扫 H1，2026-10-17 立案）

Dockerfile 运行层（`eclipse-temurin:17-jre`）未声明 `TZ`，prod 编排（docker-compose.prod.yml
的 app 服务）亦无 TZ 环境变量 → JVM 默认时区跟随容器系统时区 = UTC。而考试时间全按上海
墙钟 DATETIME 存储、以 `LocalDateTime.now()` 直接比较（全仓 22 文件），prod 容器内全部
时间比较整体漂 8 小时：状态机流转、缺考锚定、开考提醒、交卷门控、草稿 TTL 全部受影响。
dev 环境跑宿主机（TZ=Asia/Shanghai）从未暴露。修复前确证记录见 evidence/（prod 容器
`docker exec exam-online-prod-app date` → UTC）。

## 二、方案

- Dockerfile 运行层加 `ENV TZ=Asia/Shanghai` 一行（基础镜像 `eclipse-temurin:17-jre`
  自带 tzdata，无需装包；JVM 默认时区、logback 时间戳、@Scheduled 调度随 TZ 对齐）；
- build 层零改动：构建 `-DskipTests package` 与时区无关，构建产物与修复前等价；
- 词法护栏单测（先例 `PublisherConfirmScopeGuardTest` 模式）锚定运行层段落断言 TZ 声明
  （防未来 build 层出现同形行造成误判合规）——CI 无 docker 环境，部署文件变更无其他
  机检防线，护栏是唯一机检防线；
- 不动：docker-compose.prod.yml、schema.sql、应用代码（业务全链路按上海墙钟是既定
  口径，本案修复的是容器与该口径的偏差，不引入新时区语义、不改任何时间比较代码）。

## 三、白名单

- `Dockerfile`（运行层 +1 行）
- `src/test/java/com/exam/deployment/**`（新增护栏单测）
- `.github/ci/gate-baseline.json`（收口笔同笔上调 backendTests，只增不减）
- `spec/changes/fix-prod-container-tz/**`、`spec/README.md`、`docs/功能待办清单.md`（台账随立项笔入库）
- 禁触：`src/main/resources/schema.sql`、`docker/mysql/migrations/`、`openapi.yaml`、
  `pom.xml`、`frontend/`、`src/main/java`（零 DDL、零契约、零应用代码）

## 四、验收要点

- 修复前确证：prod 演练环境 `docker exec exam-online-prod-app date` → UTC 留证（命令 + 当次输出 + 短 revision）
- 修复后对齐：重建镜像起容器 `date` → CST (+0800)，10s 级定时扫描日志时间戳与上海墙钟对齐
- 护栏变异：删 TZ 行 → 护栏确定性失败 → 恢复回绿，先红后绿留证
- 门禁 `mvnw.cmd clean test` 全绿（仓库根、带 clean、禁 -o）；backendTests 基线只增不减，收口笔同笔上调
- S 级理由：单行修复 + 一个护栏单测、无契约变化、无设计权衡
