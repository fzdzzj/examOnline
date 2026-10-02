# survey-frontend-candidates 提案（前端只读深度巡查）

> 立项依据：2026-10-01 深夜复核，后端优化候选池已空（`docs/backend-optimization-candidates.md` 头注批注：候选 1–4 全部闭环）；同轮全仓巡查对前端仅覆盖三类模式级抽查（v-for key、echarts/lodash 整包引入、SSE 生成代码）。本卡以系统性只读巡查产生下一批前端候选。**零代码改动；候选 ≠ 已确认瓶颈 ≠ 已批准变更。**

## Why

1. 前端（46 个 .vue 与 api/components/constants/hooks/pages/router/store/utils）自骨架搭建（add-frontend-* 系列）后未做过系统性优化巡查；
2. 后端候选池已空，新候选只能由巡查产生，不得凭空立项或复用过期台账；
3. 巡查是后续实施卡的前置：任何候选实施前须按 performance 域「先归因、一次只改一类」纪律另行立卡，本卡不实施任何改动。

## What Changes

- 产出（**不入库**纪律文件）：`docs/frontend-optimization-candidates.md`——前端优化候选证据台账，格式镜像后端台账；
- 入库（单笔收口提交）：`spec/changes/survey-frontend-candidates/` 整体归档 + `spec/README.md` 归档行；
- **零改动承诺**：`src/`、`src/test/`、`frontend/`、`pom.xml` 一行不改；无规范变更（`specs/frontend-delta.md` 为零规范变更声明）。

## 巡查面（六面，普查不抽查）

1. **API 调用模式**（api/ + pages/）：每页请求数与串并行关系；未分页的全量拉取；重复拉取；搜索/输入无防抖；轮询间隔与页面可见性配合。
2. **生命周期与清理**（hooks/ + pages/）：定时器/interval、EventSource/SSE、事件监听器在 onUnmounted 的清理；长驻页面的内存持有；路由快速切换的响应竞态（旧响应覆盖新状态）。
3. **渲染与大列表**（pages/ + components/）：可变大的列表是否无虚拟化（监考学生列表、题目列表、成绩表）；深 watcher；整卷 JSON 等大对象全量 reactive；computed 误用。
4. **状态与重复提交**（store/ + pages/ + api/）：交卷/提交类按钮防重；与后端幂等/锁的配合；token 过期在轮询/SSE/普通请求各路径的处理。
5. **网络与包体**（router/ + 各 import）：路由级代码分割；按需引入复核（echarts/lodash 本轮已查，结论相同时不重复立项）；接口响应体大小与实际消费面。
6. **错误与边界路径**（api/ 拦截器 + pages/）：请求失败后 loading 是否永挂；空态/404 呈现；SSE 断线重连与事件去重；草稿保存冲突提示（DraftSyncBadge 措辞已裁决）。

## 已裁决不重复立项（登记候选前先比对 spec/specs/*/spec.md 与归档卡）

- `add-frontend-must-change-guard` / `add-auth-must-change-password`：access.ts mustChangePassword 守卫；change-password 页不本地持久化状态；
- DraftSyncBadge 组件措辞约束；
- 切屏事件归并为一条上报（含 durationMs）与交卷前/页面暂停/beforeunload 兜底；
- 草稿自动保存 30s 间隔 + 30s 防抖双路径引擎（节流优先修复已收口）；
- `fix-frontend-paper-table-render`、`accept-frontend-19-23` 等已收口前端卡覆盖的行为；
- 本轮模式级抽查已判干净的三类（v-for key、整包引入、SSE 生成代码）——可复核，结论相同不立项。

## Impact

- 运行时影响：零（纯静态只读；不启动 dev server、不跑构建、不碰 Docker/共享 dev）。
- 门禁：预期四计数与基线锚一致（350/0/0/1 @ `e14fd68`），本卡零改动、计数不变；收口前复跑为凭据。
- 规范：零合入；任何候选的后续实施各自立卡走独立 delta。

## 验收与停止条件

- **验收**（全部满足才算完成）：台账成文且头注绑定当次 revision；逐候选锚点可复验（优先 grep 锚点，行号仅辅助并绑定 revision）；六面各有「查了什么、怎么查」记录；零改动凭据 `git diff --name-only <开工HEAD> HEAD -- src pom.xml frontend` 输出为空；门禁四计数与基线一致；`evidence-sha256.txt` 覆盖全部证据文件；单笔收口提交；按固定清单回传（验收记录三要素：命令 + 当次真实输出 + 短 revision）。
- **停止条件**（触发即停、如实上报、不自行扩张范围）：
  1. 发现违反既有 spec Scenario 的**行为缺陷**——不属于优化候选，停止上报（缺陷由指导主 agent 另立修复卡）；
  2. 门禁复跑红，或四计数与基线不一致；
  3. 疑点需运行时/构建证据才能判定——如实登记「待测量」，不为此启动构建，更不得静态断言收益；
  4. 工作树出现本卡之外的意外改动。
- **回传固定清单**：① `git status` 终态原文；② 候选总数与六面分布；③ 台账路径与其 sha256；④ 逐条候选一行摘要（锚点 + 疑点 + 最小下一步）；⑤ 门禁当次完整四计数与退出码；⑥ 收口提交短 SHA 与提交信息；⑦ evidence 文件清单。回传后由指导主 agent 独立复核（子 agent 回报不是事实）。
