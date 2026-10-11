# examOnline —— 在线考试系统（Spring Boot 单体 + Vue3 前端）

> **本文件只做路由，不做事实源。**
> 端口、口令、环境变量默认值、用例数、覆盖率、表清单、"当前进行到哪一步"——这些会随提交过期的东西，本文件一个都不写，
> 只写**要查 X 去读哪个文件**。
> 为什么：同一事实一旦有多个副本，副本必然漂移，而被读到最多的那份最新就会把读者带偏。
> 本仓库为此记录过两次真实失败——照着旧版本文件给的连接信息去连库，连不上，然后把"容器没起"误判成"环境坏了 / 凭据不对"
> （事故记录见 `docs/指导Agent交接文档.md` 的 dev 启动环境小节，规约见 `AGENTS.md`）。
> 想给本文件补一个数值，请先读 `AGENTS.md` 末尾「本文件与 README 不承载易变事实」。

## 新成员 / 新 agent 阅读顺序

1. `docs/project-overview-for-agents.md` —— 这个项目是什么、每类事实的真源在哪、怎么验证（一份读完）
2. `AGENTS.md`（仓库根）——四条硬约定、禁忌清单指针、以及"哪些东西不许写进根入口"
3. `spec/README.md` —— 当前状态（进行中的变更、已合入的能力域）
4. `docs/指导Agent交接文档.md` —— 本机环境实况、踩过的坑、明确不建议做的事
5. 目标能力域的规格：`spec/specs/<capability>/spec.md`

## 要查什么，去哪儿查

| 你想知道 | 唯一出处 | 备注 |
|---|---|---|
| dev 环境连哪个 MySQL / Redis / RabbitMQ（地址、端口、口令默认值） | `src/main/resources/application-dev.yml` | 变量名与兜底默认值写在同一行 `${VAR:default}`；**本文件不复制其字面值** |
| 生产环境配置 | `src/main/resources/application-prod.yml` + `src/main/resources/application.yml` | 生产侧刻意不给兜底默认值（缺变量就该启动失败） |
| 启动需要哪些环境变量 | 上面两个文件里的 `${...}` 表达式 | 别在本文件维护变量清单——它已经过期过一次 |
| 依赖服务的编排与宿主端口映射 | `docker-compose.yml`（顶部注释解释了宿主端口为何不是各组件的默认端口） | 实际生效的映射以 `docker ps --format "{{.Names}} {{.Ports}}"` 为准 |
| 本机 dev 该怎么起、有哪些坑 | `docs/指导Agent交接文档.md` 的「本机 dev 启动环境」小节 | 含"哪个容器故意不要起"及其原因 |
| 指导主 Agent 如何低成本给子 Agent 派工与验收 | `docs/主Agent执行指南.md` | 角色专用；新成员按需阅读，不属于必读顺序 |
| 表结构 / 建表脚本 | `src/main/resources/schema.sql` | 建表唯一入口，见 `AGENTS.md` 约定 1 |
| 存量库改表 | `docker/mysql/migrations/README.md` | **放进该目录 ≠ 变更已生效**，该目录无自动执行者 |
| 后端接口契约 | 仓库根 `openapi.yaml` | 如何再生成（离线导出为推荐路径，护栏断言防编码回归）：见 `spec/specs/api-contract/spec.md` |
| 项目分成哪些能力域 / 某能力的验收标准 | `spec/README.md` 能力地图 + `spec/specs/*/spec.md` | 条数以目录实际内容为准，本文件不写数 |
| 为什么这样设计（决策依据） | `docs/需求决策记录.md` | 按主题分节，含被推翻的旧方案 |
| 产品愿景原文 | `docs/examOnline需求规格说明书.md` | |
| 前端工程 | `frontend/`；进行中的前端变更见 `spec/README.md` 进行中表 | 前端与后端测试基线的关系见 `spec/README.md` 的「前端系列纪律」，本文件不复述 |
| 跑哪一条命令算门禁通过 | `AGENTS.md`「唯一门禁命令」（`./mvnw clean test`，已由 `update-agent-gate-single-source` B-2 回填） | 验收判据见 `spec/specs/agent-harness/spec.md`，不在本文件复述 |
| 远端 CI 双端门禁跑什么、机检基线在哪 | `.github/workflows/ci.yml` + `.github/ci/gate-baseline.json` | push/PR 自动复跑双端门禁并比对基线（用例数与契约 paths 只增不减）；收口笔同步上调基线的纪律见 `spec/README.md` 工作流；CI 是第二防线，不改变本地唯一门禁命令的地位 |
| 当前进行到哪一步 / 下一步做什么 | `spec/README.md`「当前状态」的进行中变更表 | 本文件不再写"下一变更是 X"——它过期在阶段 2 |

## 快速开始（只给入口，数值一律去上面那张表查）

1. **起依赖**：`docker compose up -d`（服务清单与宿主映射见 `docker-compose.yml`；本机 Redis 用宿主实例，哪个容器故意不要起见交接文档小节）
2. **构建与测试**：门禁命令已定稿——后端 `./mvnw clean test`（见 `AGENTS.md`「唯一门禁命令」）；前端 `npm.cmd --prefix frontend run lint:check` / `type-check:check` / `test` 三门禁。远端 push/PR 由 GitHub Actions 双 job 机检化复跑（见上表 CI 行）
3. **起应用**：dev profile；连接与凭据的默认值全部来自 `src/main/resources/application-dev.yml`
4. **自检**：`GET /actuator/health` 返回 `UP`（HTTP 端口见 `application.yml` 的 `server.port`）
5. **验证完停掉实例并回报**——残留实例会让下一次"连不上"变成互踩

## 整体形态（一句话，细节去看规格）

单体 Spring Boot 应用 + 同仓库 Vue3 前端；统一响应 / 全局异常 / RequestId 透传，MyBatis-Plus 数据层，
MySQL 主从 + Redis + RabbitMQ 依赖编排。**每个子系统的实际行为以 `spec/specs/<capability>/spec.md` 为准**，
本文件不描述实现细节——它上一次这样写的时候，项目还只有两个包。
