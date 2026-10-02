# 变更提案：监考总览轮询按页签门控（gate-monitor-polling-by-tab）

## 背景与动机

前端巡查台账（`docs/frontend-optimization-candidates.md`，快照 `bd5c645`）候选 1：考试详情页的监考总览查询（`useQuery` + `refetchInterval=10s`）`enabled` 只校验 examId 合法性、不读 `activeTab`——教师停留在概览/试卷快照/行为日志页签时仍持续 10s 轮询，属**无谓请求**（行为代码可证，非增长类瓶颈，无需负载测量即可定性）。同页行为日志查询已有页签门控先例（`enabled: … && activeTab.value === 'behavior'`）。

台账纪律 #1 将本候选列为优先方向；本提案为其实施卡。

## 指导侧冻结核实结论（子 agent 阶段 0 复核即可，不必重走全量巡查）

以下结论由指导侧在 `f8ea846` 亲自核实，行号绑定该 revision、漂移时以锚点文本为准：

1. **消费面（关键修正）**：monitor overview 数据被**两个**页签消费——
   - `monitor` 页签：`ExamMonitorPanel` 直接绑定 `overview`（`[id].page.vue` L129-135）；
   - `roster` 页签：`rosterRows` computed 读 `overview.value?.students` 左连实考状态/进度，「班级之外但已进入考试的学生」也来自 `overview`（L437-470）；L81 提示文案「…的计数见『监考』页」亦佐证依赖。
   - **门控集合必须是 `{monitor, roster}`**。台账候选 1 初估的 `{monitor}` 不完整——只门控 monitor 会让 roster 页签的实考状态列冻结。此为本卡对台账初估的修正。
2. `overview` / `snapshot` / `behavior` 页签不消费 overview 数据。behavior 页签的学生下拉候选（`logStudentOptions`）间接依赖 `rosterRows`，但：监考面板「行为轨迹」跳转（`openStudentLog`）直接设值不依赖下拉；访问过 monitor/roster 后 vue-query 缓存仍在，切到 behavior 候选完整。仅「直接落在 behavior 且从未访问 monitor/roster」时下拉缺班级外学生——边缘场景，**接受该取舍，不得为此把 behavior 加入门控集合**。
3. **实现落位**：`useExamMonitor.ts` 文件头自述「enabled 条件必须可被单测直接断言，写在页面里就只能靠 mount + 真 QueryClient」——门控组合进 `createMonitorQueryOptions`（可选第三参注入页签谓词）是**延续既有设计**，不是新抽象。
4. **框架行为依据**：tanstack vue-query 在 `enabled=false` 时不发起请求（含 `refetchInterval` 触发）；`enabled` 恢复 true 且数据 stale 时自动 refetch——「切回消费页签即恢复轮询并刷新」由框架保证，无需本地计时器补数。

## 改动内容（全部在 `frontend/src`，共 4 文件）

1. `constants/monitor.ts`：新增 `MONITOR_CONSUMER_TABS = ['monitor', 'roster'] as const` 与 `isMonitorConsumerTab(tab: string): boolean`；**注释写明 roster 在集合内的原因**（rosterRows 左连 overview.students，去掉 roster 会使考生名单页签实考列冻结）。
2. `hooks/useExamMonitor.ts`：`createMonitorQueryOptions` 增可选第三参 `{ isConsumerTabActive?: () => boolean }`（缺省 `() => true`，既有 4 条调用不破坏）；`enabled` 组合为 `examId 合法 && isConsumerTabActive()`；文件头不变式清单追加第 4 条（页签门控）。
3. `pages/(dashboard)/teacher/exams/[id].page.vue`：调用处注入 `() => isMonitorConsumerTab(activeTab.value)`；页面 `enabled` 不再单独写字面量页签比较。
4. `hooks/__tests__/useExamMonitor.spec.ts`：新增两个 describe——
   - 「页签门控」：`isMonitorConsumerTab` 三真（monitor/roster）两假（overview/snapshot/behavior）；`createMonitorQueryOptions` 第三参注入后 enabled 的 AND 语义（examId 非法时即使 monitor 页签也 false；examId 合法 + 非消费页签 false）；**缺省第三参保持旧行为 true**（向后兼容）。
   - 「页面接线词法护栏」：读页面源码断言 `enabled` 表达式引用了 `isMonitorConsumerTab`（参照后端 `PublisherConfirmScopeGuardTest` 的词法护栏先例，防门控被悄悄移除后测试仍全绿）。

## 不变式（全部不得动）

1. 轮询间隔仍取 `constants/monitor.ts`（10s）：本卡**不动间隔、不动 `MONITOR_POLLING_HINT` 措辞**。
2. `queryFn` 仍原样透传后端返回；`queryKey` 仍 `['exam', examId, 'monitor']`。
3. `refetchOnWindowFocus` 仍为 `false`；「轮询属后台刷新、失败不弹重试」取舍不变。
4. 人数/进度全部来自后端 overview，前端不合计不推算。
5. 行为日志两个查询的既有门控（L521-525/557）不动。
6. **零后端改动**：`src/`、`pom.xml` 零触碰；不启动 dev server、不跑前端构建、不碰 Docker/共享 dev。

## 验收与停止条件

**门禁**（两道，全绿才算过）：

- 前端：按 `frontend/package.json` scripts 实跑 lint / type-check / test（vitest），三项退出码 0；vitest 用例计数**恰增新用例数、不减**（开工时先跑一次记基线）。
- 后端：仓库根 `$env:JAVA_HOME='D:\develop1\jdk21'; .\mvnw.cmd clean test` → 四计数 **350/0/0/1** 不变（基线锚 `e14fd68`；`f8ea846` 起代码态零差异）+ BUILD SUCCESS。

**提交**（两笔，中文信息经仓库内临时文件 `-F`，UTF-8 无 BOM，提交后删）：

- 实施笔：`frontend/src` 4 文件 + 本变更目录三件套（立项记录随首个提交入库）。
- 收口笔：delta 合入 `spec/specs/frontend/spec.md` + 变更目录移入 `spec/changes/archive/` + `spec/README.md` 归档行 + `evidence/` + `evidence-sha256.txt`（LF 无 BOM）。零改动凭据：`git diff --name-only <实施笔SHA> HEAD -- src pom.xml frontend` 输出为空。

**停止条件**（触发即停、原样回传，不得自行扩大卡面）：

1. 阶段 0 锚点复核发现消费面与本提案不符（出现第三个消费 overview 的页签，或 roster 消费已被移除）。
2. vitest 或后端基线红（基线修复不属本卡）。
3. 词法护栏与单测无法同时成立（说明本方案形态在当前代码态不成立）。
4. 实施中发现必须动「不变式」清单任一条。
5. 验证需要 dev server / 构建 / 真实环境（本卡纯静态 + 单测可证）。

**回传固定清单（七项）**：① `git status` 终态原文；② 前端门禁三项命令与输出摘要（vitest 基线数 → 终态数）；③ 后端门禁当次四计数与退出码；④ 改动文件清单（每文件一行意图）+ 词法护栏先红后绿两次运行证据；⑤ 两笔提交短 SHA 与提交信息 + 零改动凭据；⑥ evidence 清单与 sha256；⑦ 停止条件复核声明（逐条）。
