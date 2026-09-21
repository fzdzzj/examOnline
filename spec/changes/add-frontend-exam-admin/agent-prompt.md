# 子 agent 提示词 —— `add-frontend-exam-admin`（阶段 21，前端方向③）

> 用法：整份复制发给子 agent。自包含。
> 先读 `spec/changes/add-frontend-exam-admin/proposal.md`、`tasks.json`、`specs/frontend/spec-delta.md`。

> **验收判据（不是常量，覆盖下文旧内容）**：本阶段对后端的唯一要求是「跑门禁命令后满足下列条件」，不是「用例数等于某个数」——历史文档里写死过的用例数都是过期副本，看到它按本判据办，**不要去凑它、更不要为了对上它去动测试**。
> 1. **开工时**跑一次后端全量门禁，把该次 `Tests run / Failures / Errors / Skipped` 连同**实际执行的命令**与当时的短 revision 记录在案（写进回报）；这个数字就是本阶段的基线。
> 2. **收尾时**再跑同一条命令，必须同时满足：`Failures=0` 且 `Errors=0`、`Skipped` 保持 1、用例总数**不得少于**开工时记录在案的数值。
> 3. `Skipped: 1` 是契约导出方法受 `exportContract` 开关控制，**属设计使然，不要试图消除**。
> 4. **门禁命令**：仓库自有的唯一命令待 `update-agent-gate-single-source`（E2）B-2 回填；回填前按 `docs/指导Agent交接文档.md` 六（§6.1 上下文）的工具链小节自行拼装并回报原始输出——该小节是**特定 shell / 本机环境下的历史绕行办法**，不是被指定的命令，换环境先自检再拼，且**必须带 `clean`**（残留报告会让计数虚高）。
> 5. **基线不绿就停下回报**（`Failures>0` / `Errors>0` / `Skipped≠1` / 总数少于开工记录值），不要在坏底座上继续。
>
> **本机 dev 环境只给指针**：宿主端口、凭据、要设哪些环境变量、容器名等事实一律现场读 `docs/指导Agent交接文档.md` §6.2，其真源是 `docker-compose.yml`（端口以其实际映射为准）与 `src/main/resources/application-dev.yml`；本提示词不复制这些值（旧版本里的宿主端口就与 compose 不一致）。不变的规则有三条：**不要改端口、不要停宿主已有的服务、不要起 compose 里的 Redis 容器**（宿主已有一套 Redis，会抢端口）。
>
> 启动日志中 `ExamSubmitSender` → `waitForConfirmsOrDie` 的 `IllegalStateException` 是**遗留 #10**，真 broker 下才暴露、非致命；`/actuator/health` 返回 UP 即视为启动成功，**不要顺手修**。

---

## 现状

- 仓库 `D:\code\examOnline`，分支 `feature/add-performance-deepening-readwrite`。
- **前置阶段 20 `add-frontend-teacher-authoring` 必须已合入**：`frontend/` 下题库 / 标签 / 组卷 / 试卷预览已可用，试卷已能产出。**开工前先跑门禁基线（`lint:check` / `type-check:check` / `vitest`）与后端全量门禁（判据见顶部：记录四数字 + 命令 + revision），把结果记录在案**；基线不绿就停下回报。
- 本阶段做**教师端考务**：班级管理、考试创建 / 发布 / 强制结束、考试列表与状态呈现、考试详情、监考进度与行为日志时间线、Grafana 只读入口。
- 对应后端：`clazz/ClassController`、`exam/ExamController`、`monitoring/MonitorController`、`anticheat/BehaviorLogController`。
- 最多修复尝试 **2 次**。第 3 次仍失败停下回报。
- **你必须自己 commit**（按任务组分次：班级 / 考试创建与发布 / 监考与行为日志 / 测试）。每次提交后立即 `git rev-parse HEAD` 与 `git status --short`；HEAD 若变 unborn，从 `.git/logs/HEAD` 取 sha 按仓库约定补 ref，**不要用 `git update-ref`**。
- **不要改 `spec/`**（含不要勾 `tasks.json`、不要动 `spec/README.md`）。

## 先核实，不要照抄文档（重要）

`docs/` 是产品愿景，与已实现代码可能不一致。**下面每一项都必须先从代码核实**，核实结论写进回报：

1. **考试状态枚举与迁移**：读 `src/main/java/com/exam/exam/**`，确认状态取值（未开始 / 进行中 / 已结束 / 已批改 / 已发布 的真实编码）、`ExamStateMachineService.autoAdvance()` 的迁移规则、**哪些动作由后端触发而非前端可请求**；
2. **`anti_cheat_config` 的真实字段**：读实体与相关 Service，确认后端**实际支持**哪些防作弊配置项（切屏阈值等）。**只渲染代码里真存在的字段，一个都不许多**；
3. **force-end 的真实端点与副作用**：确认端点路径、入参、以及它是否触发缺考标记（阶段 12 修过这条路径，读 `absence` 相关代码确认）；
4. **发布考试是否生成试卷快照**：读 `exam_snapshots` / `ExamSnapshotService` 相关代码确认；
5. **班级与学生归属**：读 `clazz/ClassController` 与 `ClassService`，确认入班 / 转班 / 学生列表的真实端点与入参。注意项目硬约束：班级相关只调 `ClassService.listStudentIds(Long classId)`，**本阶段不改 `com.exam.clazz` 包**；
6. **监考数据源**：读 `monitoring/MonitorController`，确认它真提供什么（在线数 / 提交进度 / 其它）。**它不提供的，界面就不许出现**；
7. **行为日志查询**：读 `anticheat/BehaviorLogController`，确认查询维度（按考试 / 按学生）、事件类型枚举与 `severity` 取值；
8. **Grafana 面板地址**：读 `docker/observability/**` 与 `docs/observability-runtime-evidence.md`，取真实的面板 uid / 端口（证据文件里记的是 `uid=exam-online-overview`、Grafana 3000、数据源 `uid=prometheus`），**核实后再写死链接**；
9. **教师越权校验**：确认后端是否有 `assertTeacherOwnsExam` 同类校验，前端按后端返回处理，不自行放宽。

若 `openapi.yaml` 落后于后端代码，**停下回报**，不要在前端手写补丁类型。

## 硬约定（违反即返工）

1. **本阶段不改后端**：`src/main/**`、`src/test/**`、`pom.xml`、`openapi.yaml`、`docker/**` 零改动。**特别不改 `com.exam.clazz` 包**（项目硬约束）。发现接口缺口停下回报并说明缺什么。
2. **状态机权威在后端**：考试状态与「当前可执行哪些动作」**一律按接口返回渲染**。**禁止**前端自行推算「进行中 → 已结束」或按本地时间判断状态——后端有 `autoAdvance()` 定时迁移，前端推算必然漂移。代码处写注释说明这条取舍。
3. **force-end 必须知情确认**：确认弹窗**必须写明「该动作会触发缺考标记」**（若第 3 项核实成立），教师确认后才发请求。这是不可逆动作，不许做成一键无提示。
4. **监考不得声称「实时」**：v3 无 WebSocket / SSE 推送。用 `@tanstack/vue-query` 轮询，**刷新间隔写成常量并在界面上如实标注**（例如「每 10s 刷新」）。文案统一用「准实时轮询」。**禁止**自建 WebSocket、禁止引入 socket.io 等依赖。
5. **不重做观测图表**：Grafana 已有面板（阶段 11/16 交付并有动态证据）。前端**只给只读入口链接**，不用 `echarts` 重画同口径图表，避免两套口径不一致。**本阶段不引入 `echarts`**。
6. **防作弊配置只渲染后端已有字段**，不新增前端专有配置项，不在前端做「看起来更强」的假配置。
7. **越权不靠前端隐藏**：后端校验才是边界。前端守卫 / 按钮隐藏只为体验，代码注释写明。
8. **不夹带砍掉项与后续阶段**：不做人脸识别、设备指纹、IP 白名单、浏览器锁定、定时发布、补考缓考界面（补考属阶段 23）、不建学生端答题页、不建批改 / 成绩页。
9. **不引入新依赖**。确有必要停下回报说明理由。
10. **质量门禁不许放宽**（不得大面积 `any`、不得关严格项、不得批量 `eslint-disable`）。
11. **严禁提交** `node_modules/`、`dist/`、`test-results/`、`.env`。

## 写入边界

允许新增 / 修改：

- `frontend/src/pages/**`（新增班级、考试、监考相关页面）
- `frontend/src/components/**`（新增考试表单、状态标签、确认弹窗、行为日志时间线等组件）
- `frontend/src/hooks/**`、`frontend/src/utils/**`、`frontend/src/constants/**`（新增本阶段所需；状态与事件类型的展示映射放 constants）
- `frontend/src/router/**` 或文件路由所需改动（**仅**新增考务路由与菜单项，不动守卫逻辑本身）
- `frontend/src/store/**`（仅确有需要时新增，不动会话/角色状态）
- `frontend/src/api/**`（**仅**当后端契约已更新时重新 `gen:api`；不许手写）
- `frontend/src/**/__tests__/**` 或 `frontend/tests/**`（新增 vitest 用例）

禁止其它路径。**特别禁止**：`src/main/**`、`src/test/**`、`pom.xml`、`openapi.yaml`、`schema.sql`、`docker/**`、`spec/**`、`com.exam.clazz` 相关任何文件、阶段 19/20 已交付的拦截器/守卫/认证页/题库组卷页（需复用请 import，不要改写）。

## 实施

1. **基线自检**：门禁三项 + 后端全量门禁（命令见「后端门禁命令」小节），把结果**记录在案**（后端记 `Tests run / Failures / Errors / Skipped` + 实际命令 + 短 revision）。
2. **核实后端契约**（见上），结论写进回报。
3. **班级管理**：班级列表 + CRUD；班级学生列表（分页）；入班 / 转班操作（按核实到的真实端点）。
4. **考试创建表单**：试卷（选择阶段 20 产出的试卷）+ 班级 + 时间窗（`start_time` / `end_time`）+ 个人时长（`duration_minutes`）+ 迟到允许分钟 + 防作弊配置（**仅**核实到的字段）。表单校验：结束时间晚于开始时间、时长为正、必选项齐备。
5. **考试列表**：状态以标签呈现（映射放 constants）；**可用操作按钮完全由接口返回决定**；分页与筛选。
6. **发布考试**：二次确认，弹窗说明「发布后生成试卷快照、学生侧可见」。
7. **force-end**：二次确认，弹窗**明示缺考标记后果**；成功后刷新列表状态。
8. **考试详情**：试卷快照只读预览（复用阶段 20 的预览组件，不要重写）、考生名单、提交进度。
9. **监考视图**：提交进度 + 在线情况（**仅** `MonitorController` 真提供的数据）；`vue-query` 轮询，间隔常量化并在界面标注「准实时轮询，每 Ns 刷新」。
10. **行为日志时间线**：按学生查看事件类型、`severity`、时间；严重度用颜色区分但**不改变后端语义**。
11. **观测入口**：只读链接到核实到的 Grafana 面板地址（新开标签页）。链接旁注明「面板由阶段 11/16 交付，前端不重做同口径图表」。
12. **测试**（vitest，必须有）：
    - 状态 → 标签文案 / 颜色的映射纯函数（覆盖全部状态取值 + 未知值兜底）；
    - 「可用动作由后端决定」：给定不同接口返回，渲染出的按钮集合正确（含**后端未返回某动作时前端绝不显示**）；
    - force-end 确认弹窗：未确认不发请求、确认后才发、弹窗文案含缺考后果关键词；
    - 考试创建表单校验（时间窗反向、时长非正、必填缺失）；
    - 轮询间隔常量与「准实时」文案断言（防止有人日后改成声称实时）；
    - 行为日志 severity 映射纯函数。
13. **联调验证**：后端以 dev profile 跑起来（端口与环境事实见顶部指针：`docs/指导Agent交接文档.md` §6.2 + `docker-compose.yml` + `application-dev.yml`；**不要改端口、不要停宿主已有的服务**；起不来就停下回报，不要 mock 冒充）。**走通一条真实链路**：登录（教师）→ 建班级 → 学生入班 → 建考试（绑阶段 20 的试卷）→ 发布 → 查看快照与考生名单 → 查看监考进度 → force-end → 确认状态变更与缺考名单出现。每步真实请求路径 + HTTP 状态记进回报。
14. **门禁全绿 + 后端回归满足顶部判据**（`Failures=0`、`Errors=0`、`Skipped=1`、总数不少于开工记录值）再提交。

## 后端门禁命令

仓库自有的**唯一门禁命令待 `update-agent-gate-single-source`（E2）B-2 回填**——仓库里对「哪条命令算门禁」现有互斥说法，本提示词不再新增一种，也不再照抄任何机器绑定的命令行。

回填前按 `docs/指导Agent交接文档.md` 六（§6.1 上下文）的工具链小节自行拼装：那是**特定 shell / 本机环境下的历史绕行办法**（含仓库外 JDK/Maven 绝对路径），不是被指定的唯一命令，也不意味着别的 shell 非这么拼不可——换环境先自检 `mvn -version` 用的是哪个 JDK，再决定怎么拼，并在回报里给出实际执行的命令与原始输出。**必须带 `clean`**，否则 `target/surefire-reports` 的残留会让计数虚高。

前端命令在 `frontend/` 下用 pnpm 跑。

## Commit

按任务组分次提交，中文描述、前缀 `feat(frontend)` / `test(frontend)`，例如：

```
feat(frontend): 班级管理与学生入班转班界面
feat(frontend): 考试创建发布与 force-end 知情确认
feat(frontend): 监考进度轮询与行为日志时间线
test(frontend): 补状态映射、动作可用性与确认弹窗单测
```

## 回报格式（按此七段，不要写散文）

1. **开工基线**：门禁三项 + 后端全量（改动前）——后端那项必须给出**实际执行的命令 + 原始输出四数字 + 当时短 revision**，这就是收尾时比较的记录值
2. **后端契约核实结论**：考试状态真实取值与迁移规则；`anti_cheat_config` **实际字段清单**；force-end 端点与**是否触发缺考标记**（依据文件行）；发布是否生成快照；班级端点；`MonitorController` **真提供的数据项**；行为日志查询维度与 severity 取值；Grafana 面板真实地址；越权校验有无
3. **新增页面与组件清单**：路径 + 职责一句话；轮询间隔常量值
4. **真实联调证据**：那条端到端链路每步的请求路径 + HTTP 状态（含 force-end 后缺考名单出现的证据），不得用 mock 冒充
5. **单测清单**：用例名 + 断言什么；`vitest` 收尾三数字
6. **收尾**：门禁三项结果、`vitest` 三数字、**后端全量的命令 + 原始输出四数字**并按判据自评（`Failures=0`、`Errors=0`、`Skipped=1`、总数不少于第 1 项记录值；不满足即不通过，不得改测试凑数）、每个 commit 的 `git rev-parse HEAD`、`git status --short`、`git diff --stat`（证明后端与 `com.exam.clazz` 零改动）
7. **意外发现 / 接口缺口**：缺什么、你**没有**怎么绕过；文档与代码不一致处

## 禁止

- 禁止改后端任何文件（含 `openapi.yaml`、`com.exam.clazz`）；
- 禁止前端自行推算考试状态或按本地时间判定状态迁移；
- 禁止 force-end 无知情确认；
- 禁止声称监考「实时」，禁止自建 WebSocket / SSE / 引入 socket 依赖；
- 禁止引入 `echarts` 重做 Grafana 同口径图表；
- 禁止渲染后端不存在的防作弊配置字段或监考数据项；
- 禁止夹带人脸 / 设备指纹 / IP 白名单 / 浏览器锁定 / 定时发布 / 补考界面 / 学生端答题页 / 批改成绩页；
- 禁止擅自新增依赖；
- 禁止用 mock server 冒充联调通过；
- 禁止放宽 lint / type-check / 断言来转绿；
- 禁止勾 `tasks.json` 或归档 `spec/`。
