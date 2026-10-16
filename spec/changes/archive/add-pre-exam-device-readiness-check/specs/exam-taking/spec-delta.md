# exam-taking spec-delta —— add-pre-exam-device-readiness-check

> 变更提案：spec/changes/add-pre-exam-device-readiness-check/proposal.md
> 基线：spec/specs/exam-taking/spec.md
> 形态：ADDED Scenario（三条，合入基线时逐条并入「学生进入考试」相关 Requirement）

## ADDED Scenario：进入考试前置设备就绪门控

- **WHEN**: 学生在线进入考试详情页（`/student/exams/:id`）且本考试会话未完成设备就绪自检
- **THEN**: 系统 SHALL 先呈现设备就绪自检面板，SHALL NOT 在自检完成前发起 `POST /exam-taking/exams/{examId}/enter`（起表请求被 enabled 门控拦截）
- **AND**: 自检 SHALL 覆盖网络往返时延、屏幕分辨率/视口、全屏支持、浏览器内核与关键 API（Fullscreen、localStorage）四类检查项，全部为浏览器本地检测，SHALL NOT 新增任何后端调用，SHALL NOT 以 enter 端点作探测目标（起表副作用）

## ADDED Scenario：就绪报告知情门控（非绝对阻断）

- **WHEN**: 自检执行完毕
- **THEN**: 系统 SHALL 呈现就绪报告并放行进入考试（「跑自检」是硬门控，检查结果是知情层）
- **AND**: RTT 超阈值或分辨率过小 SHALL 仅呈现警示徽标不阻断；浏览器内核不兼容或 localStorage 不可用 SHALL 强警示并要求「仍要进入」二次确认，SHALL NOT 永久阻断开考（考试可用性优先于环境理想性）
- **AND**: 自检面板自身执行异常 SHALL 按「已完成 + 全部警示」兜底放行，SHALL NOT 因自检故障阻断开考

## ADDED Scenario：离线重入豁免与会话级留存

- **WHEN**: 学生断网状态下重入考试（`navigator.onLine === false`，进入离线快照兜底路径）
- **THEN**: 设备自检 SHALL 整场跳过直放行，SHALL NOT 阻断既有「enter 失败 + 本地快照」降级重入流程
- **AND**: 自检完成结果 SHALL 会话级留存（sessionStorage），同一考试会话内刷新或返回再进 SHALL NOT 重复触发自检面板；路由复用切换 examId SHALL 视为新考试重新门控
