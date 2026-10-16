# 提案：add-pre-exam-device-readiness-check——考前设备与运行环境就绪度自检

- **目标能力域**：exam-taking、frontend
- **级别**：M
- **零 DDL**：是（纯前端变更：后端零改动、契约零改动、建表零触碰）
- **来源**：docs/功能待办清单.md 候选表「考前设备与运行环境就绪度自检」+ B.1 备忘第 6 条；愿景 §3.3「考前设备检测」

## 一、WHY（问题）

学生进入考试后才暴露环境问题，全部在起表开考后暴雷：

1. **弱网学生**：进入考试后频繁断线，草稿自动保存与行为上报队列堆积，答卷连续性差，还可能触发离线降级；
2. **不兼容/老旧浏览器**：进入后全屏守卫反复弹出（FullScreenGuardOverlay），localStorage 不可用致草稿与本地快照无法留存（createStudentDraftStorage/createStudentExamSnapshotStorage 全部失效）；
3. **低分辨率/高缩放设备**：答题卡与写字板排版挤压，作答体验崩坏。

现状：`student/exams/[id].page.vue` 的 enter useQuery **挂载即触发** `POST /exam-taking/exams/{examId}/enter`（起表副作用），学生没有任何环境自检与知情通道。

## 二、WHAT（方案）

进入考试前新增「设备就绪自检」门控面板，纯前端实现：

1. **门控位置**：`[id].page.vue` 的 enter useQuery 增加 `enabled` 门控（readinessGate 未通过前不发起 enter），详情页首屏先呈现自检面板；enter 请求链路与幂等语义零改动。
2. **检查项**（全部浏览器本地能力，零新增后端调用；绝不用 enter 端点当探测目标——它有起表副作用）：
   - 网络往返时延（RTT）：对同源轻量资源 HEAD 采样，丢弃首样本（DNS/握手冷启动）取中位数；
   - 屏幕与视口：分辨率/有效视口低于建议阈值 → 警示；
   - 全屏支持：`document.fullscreenEnabled`（与既有全屏守卫同语义）；
   - 浏览器兼容：userAgent 内核白名单（Chrome/Edge/Firefox/Safari）+ 关键 API 特性检测（Fullscreen API、localStorage——草稿与快照留存依赖）。
3. **门控语义（知情门控，非绝对阻断）**——考试可用性优先：
   - 「跑一遍自检」是硬门控：检查完成呈现就绪报告前，「进入考试」按钮禁用；
   - 检查结果是知情层：RTT 超阈值/分辨率过小仅警示徽标不阻断；内核不兼容或 localStorage 不可用强警示 + 「仍要进入」二次确认；
   - **任何检查项不构成永久阻断**；自检逻辑自身异常按「已完成 + 全部警示」兜底放行；
   - **离线重入豁免**：`navigator.onLine === false` 时整场自检跳过直放行——断网重入（enter 失败 + 本地快照兜底，offlineExamReentry 既有流程）是救场路径，绝不能被自检阻断。
4. **报告留存**：自检完成结果 sessionStorage 会话级留存，同一考试会话内刷新/返回再进不重复跑；路由复用切换 examId 视为新考试重新门控。
5. **组件化**：新增 `components/student/DeviceReadinessCheckModal.vue` + 检查逻辑 hook `hooks/useDeviceReadiness.ts`（与 UI 分离可单测），页面接线最小化。

## 三、范围（白名单）

- `frontend/src/components/student/DeviceReadinessCheckModal.vue`（新增）
- `frontend/src/components/student/__tests__/`（新增测试）
- `frontend/src/hooks/useDeviceReadiness.ts`（新增）
- `frontend/src/hooks/__tests__/`（新增测试）
- `frontend/src/pages/(dashboard)/student/exams/[id].page.vue`（enabled 门控接线）
- `frontend/src/pages/(dashboard)/student/exams/__tests__/`（新增测试）
- `spec/changes/add-pre-exam-device-readiness-check/**`

白名单外零 diff；后端 `src/main`/`src/test` 零触碰；`schema.sql`/`docker/mysql/migrations/` 零触碰；`openapi.yaml`/`pom.xml` 零触碰。

## 四、非目标

- 不上报就绪报告到后端（无新端点、无新表、无新契约）；
- 不做绝对阻断（任何检查不过都不永久挡考，最多二次确认）；
- 不做设备指纹/环境取证（anti-cheat 域另行立项）；
- 不改进入考试契约、状态机与幂等语义（enter 语义零改动）；
- 不做移动端适配改造（仅提示分辨率过小）。

## 五、风险与对策

1. **自检误判阻断考试** → 知情门控不阻断 + 离线豁免 + 强警示仅二次确认 + 自检异常兜底放行；
2. **刷新/重入重复自检破坏考试连续性** → sessionStorage 会话级留存；examId 切换视为新考试重新门控；
3. **RTT 采样误报** → 丢弃首样本取中位数 + 阈值宽松仅警示；
4. **jsdom 环境 API 缺失** → 检查逻辑 hook 与 UI 分离，特性检测 mock 化可单测。

## 六、验收

- 前端门禁：`frontend/` vitest 全量跑全绿，新增用例数以实测为准，`frontendTests` 基线收口笔同笔上调（只增不减）；
- 单测先红后绿 + 变异击红留证 `evidence/`（enabled 门控移除→红、离线豁免移除→红、二次确认跳过→红、sessionStorage 留存移除→重跑）；
- 后端零 diff 论证：白名单外零触碰（离线门禁分级政策：纯前端变更收窄为前端门禁 + 零 diff 论证，后端门禁免跑须复核确认论证成立）。
