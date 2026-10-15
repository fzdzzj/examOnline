# add-anti-cheat-restrictions：高级防作弊轻量面（IP 白名单准入、作答安全模式与行为采集增强）

## 背景与必要性

在系统演进到当前阶段后，基础切屏/失焦采集与监考大屏已稳定运行，但对比产品愿景（§7.5「IP/设备限制 + 浏览器锁定」、Phase 2 P0「高级防作弊」）与 `docs/功能待办清单.md` 候选池 B，系统仍存在三处关键安全面缺口：
1. **IP 范围准入硬缺口**：机房考试或特定局域网考试场景下，缺乏基于 IP 白名单/网段的考试准入限制，无法防御异地代考；
2. **作答端防作弊配置脱节**：`exams.anti_cheat_config` 已保存在库中，但学生进入考试接口 `EnterExamResponse` 尚未下发该配置，前端作答端无法感知本场考试开启了哪些安全模式；
3. **作答安全与行为事件覆盖不足**：作答端尚未提供禁用复制粘贴（防外部答案直接灌入）与全屏作答监控能力，行为事件库中缺少剪贴板违规与全屏退出事件。

## 现场核证基础与零 DDL 前提

1. **零 DDL、零迁移脚本**：
   - `exams` 表已具有 `anti_cheat_config TEXT` 字段，且 `ExamCreateRequest` / `ExamDetailResponse` 已支持该 JSON 配置（如 `{"ipWhitelist":["192.168.1.*"],"forbidCopy":true,"fullScreen":true}`）；
   - `exam_behavior_logs` 表采用可扩展的 `(event_type, event_data)` 动态结构；
   - 本变更 100% 零 DDL、零表结构修改，零迁移脚本，严格符合 `AGENTS.md` 硬约定 1 & 4。
2. **决策红线**：
   - 严格遵循 `docs/需求决策记录.md` §3.1：防作弊守卫**只警告 + 记录，绝不自动强制交卷**；作答安全拦截与行为采集属于旁路保障，网络抖动或上报失败不得阻断学生正常答题。

## 设计裁决

1. **后端 IP 准入匹配（IpMatcher）**：
   - 提取客户端真实 IP：优先从 `X-Forwarded-For` 首项提取有效 IPv4/IPv6，无效或不存在时回退至 `request.getRemoteAddr()`；
   - 白名单格式支持：支持单个精确 IP（如 `192.168.1.100`）、通配符（如 `192.168.1.*`）、CIDR 网段（如 `192.168.1.0/24`）以及本地回环（`127.0.0.1` / `0:0:0:0:0:0:0:1`）；
   - 在 `ExamTakingService.enter` 准入方法（`assertAdmission`）中，若 `antiCheatConfig` 包含非空 `ipWhitelist`，校验当前 IP；若不匹配则抛出 `403 FORBIDDEN`，错误文案对齐「当前 IP ({ip}) 不在本次考试允许的网段范围内」。
2. **行为事件类型扩展（可插拔 SPI）**：
   - 在 `BehaviorEventTypes` 扩充常量 `COPY_PASTE`（复制/粘贴尝试）与 `FULLSCREEN_EXIT`（退出全屏）；
   - 新增 `CopyPasteEventCollector` 与 `FullScreenExitEventCollector`，默认严重度为 `MEDIUM`，自动注册入 `BehaviorEventRegistry`（核心采集逻辑零修改）。
3. **防作弊配置契约下发**：
   - `EnterExamResponse` 增加字段 `antiCheatConfig`（`JsonNode`），进入考试和断线恢复查询时下发，保证只增不减。
4. **前端作答端安全守卫**：
   - 在 `TakingBoard.vue` / `useBehaviorReport.ts` 中解析 `antiCheatConfig`：
     - 若 `forbidCopy === true`：在作答区域监听并 `preventDefault()` 拦截 `copy`、`paste`、`cut`、`contextmenu`，弹出友好提示并旁路上报 `COPY_PASTE`；
     - 若 `fullScreen === true`：进入考试时弹层引导学生点击全屏，监听 `fullscreenchange`，退出全屏时提示并旁路上报 `FULLSCREEN_EXIT`。
5. **教师端建考表单扩展**：
   - 在考试创建/编辑页提供可视化防作弊配置面板（切屏警告阈值、IP 白名单文本框、禁止复制粘贴开关、要求全屏作答开关）。
