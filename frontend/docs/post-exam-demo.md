# 考后闭环端到端演示脚本（前端阶段 23）

> 覆盖「交卷 → 批改（含并发冲突）→ 汇总预览 → 发布 → 学生查询 → 申请复核 → 教师处理 →
> 学生看到结果」，另加「force-end → 缺考名单 → 为该生创建补考」与「管理员撤回」。
> 每步写明：**操作 / 实测现象（真实 HTTP 与界面文本）/ 它证明了后端哪条能力**。

## 0. 实跑状态（2026-09-19 真机联调已完成）

| 项目 | 状态 |
|---|---|
| 前端门禁（lint / type-check(app+config) / vitest） | ✅ 0 错误 / 0 错误 / **15 文件 125 用例全通过** |
| 后端全量回归 | ✅ 218 run / 0 fail / 0 error / 1 skipped（后端零改动） |
| 前端页面与后端真机联调 | ✅ **已实跑**（Chromium 驱动真实页面，三角色独立会话并发在线） |
| 并发批改冲突 | ✅ **已实跑两次**，真实 `HTTP 409 + code 1012` 已观察到 |
| 学生答题界面 | ❌ 未实跑：阶段 22 未合入，交卷走后端接口 |

上一版把「未实跑」归因于启动命令未获授权。本轮授权后一次跑通，另外补一条环境事实：
**本机 Bash 工具执行含非 ASCII 字符的命令会直接失败（exit 127）**，之前的启动尝试很可能同时踩到了这一点。

实跑中发现并修复 **5 个真实缺陷**（详见第 5 节），其中 4 个是「页面渲染不出来 / 提交必失败」级别。

## 1. 环境与账号（实测值）

| 项 | 值 |
|---|---|
| 后端 | `java -jar target/exam-online.jar`（JDK 21，PATH 默认 java 是 1.8，必须显式指定）+ `SPRING_PROFILES_ACTIVE=dev` |
| 中间件 | Docker `exam-mysql-master` 宿主 **13316**、`exam-mysql-slave` **13317**（`docker-compose.yml` 写的是 3306/3307，本机 Windows `MySQL80` 服务占着 3306 所以被改过映射），RabbitMQ 5672，Redis 走本机服务 6379 |
| 启动变量 | `DB_URL`（13316）/ `DB_PASSWORD=root123` / `SLAVE_DB_URL`（13317）/ `SLAVE_DB_PASSWORD=root123` |
| 启动判据 | `GET /actuator/health` → `{"status":"UP"}`，db/redis/rabbit/ping 全 UP |
| 前端 | `npm run dev -- --port 5173 --strictPort`；`/api` 代理不 rewrite（未登录访问 `/api/auth/me` 得 401 而非 404，即代理生效） |
| 账号 | 管理员 `admin/admin123`（`application.yml` 的 `exam.auth.admin`）；教师与学生由 `POST /api/auth/register` 现造（教师需 `POST /api/admin/invite-codes` 换邀请码），口令统一 `DemoPass123` |

> 本轮造的数据：班级 #1、试卷 #4、考试 #2（主考）、#3/#4（补考）、用户 `pe_t160824` / `pe_s1_160824` / `pe_s2_160824`。
> 驱动脚本放在 `target/demo/`（被 gitignore），只走 HTTP 接口，未直接改库。

## 2. 主链路实测

### 步骤 1 — 学生交卷（接口，非 UI）

`enter` → `GET paper` → `submit {"answers":{"8":"B","9":"主库负责写入…"},"submitType":"MANUAL"}`。

- 首次 submit 返回 **HTTP 500**「系统繁忙，请稍后重试或检查答题进度自动保存后重新提交」，重试同一幂等键返回
  **HTTP 200** `{"submissionId":3,"status":2,"submitType":1}`，`exam_submissions` 落库、答案已存。
- 后端日志同步出现遗留 #10：`交卷消息发送失败，答案暂存草稿等待补发: submission=3` →
  `AmqpException: 交卷消息未被 broker 确认`。**非致命**，但意味着客观题自动判分不会由消息触发，
  需 `POST /api/exams/{id}/grading/run` 补偿（本步实测走的就是补偿）。

### 步骤 2 — 教师批改（UI）

`/teacher/grading` → 选考试（下拉只出现本人的考试，#2 标「已结束」）→ 主观题进度
`0 / 1` → 去批改 → 行内 `参考评分建议：0 分`（关键词命中 0/3）+ 未批 → 打分 + 评语 → 提交 **HTTP 200**。

### 步骤 3 — 并发批改冲突（UI，两个真实窗口）

窗口 B 先提交 7 分成功（`version` 0→1）；窗口 A 仍持 `expectedVersion:0` 提交：

```
POST /api/exams/2/grading/subjective/save
{"submissionId":3,"questionId":9,"score":9,"comment":"A窗口预填：要点完整","expectedVersion":0}
-> HTTP 409 {"code":1012,"message":"批改已被他人更新，请刷新后重试"}
```

界面实测：顶部黄色 Alert「**该答卷已被他人批改，请查看最新内容后重新打分**」+「系统不会用你刚才的分数覆盖他人的批改」；
**没有**任何成功提示；行自动刷新为最新内容（他人 7 分 + 评语）；随后 A 用新版本重提交 → **HTTP 200**，行显示 9 分。
第二次冲突（A 持 v1、B 提交 6 分）同样 409，界面刷新后显示 6 分。

### 步骤 4 — 汇总成绩（UI）

`/teacher/scores` 选考试 → 状态标签「已结束」，只有「汇总成绩」可点，导出三个按钮全部 `disabled`，
无发布按钮（后端门槛）。点汇总 → **HTTP 200**，界面「本次汇总 **1人** / 跳过 **0人** / examGraded=**true**」，
状态变「已批改」，发布前预览 + 发布成绩按钮出现，导出按钮解禁。

### 步骤 5 — 发布前预览 → 发布（UI）

预览榜：`1 | 交卷学生 | 客观 5 | 主观 9 | 总分 14 | Excel / PDF`。
发布 → 确认弹窗「确认发布成绩」→ `POST /api/scores/publish` → 结果表 `2 | 成功 | 发布成功`，状态「已发布」。
教师视角额外实测：**「当前角色非管理员：撤回入口不可用」** 标签出现，且发布后本页无任何成绩动作。

接口侧门槛实测（同账号）：

| 调用 | 结果 |
|---|---|
| 重复发布 | `200 [{"examId":2,"success":true,"message":"已发布（幂等跳过）"}]` |
| 已发布后再汇总 | `400 {"code":400,"message":"成绩已发布，须先撤回再重新汇总"}` |

### 步骤 6 — 学生查询成绩（UI）

`/student/scores` 选考试 → `GET /api/scores/my?examId=2` →
`{"objectiveScore":5,"subjectiveScore":9,"totalScore":14,"rank":1,"partialGraded":0,"reviewing":false}` →
卡片「排名 第 1 名 / 客观题 5 / 主观题 9 / 总分 14 / 批改状态 已全部批改」。

撤回后同一查询 → **HTTP 400** `{"code":400,"message":"成绩待发布"}` → 界面显示
「**成绩未发布**」，**不显示 0 分也不显示空白分数**（实测无分数泄露）。

### 步骤 7 — 学生申请复核（UI）

「申请成绩复核」→ 填理由 → `POST /api/exams/2/score-reviews` → **200**
`{"id":1,"status":0,"reason":"第 2 题答案含强一致读，希望重新核对给分","applyTime":"..."}`。

学生重新查询 → `GET /api/scores/my` → `{"rank":0,"reviewing":true}`（**后端已把三处分数置空**）→
界面「**成绩复核中，暂不可见**」+「复核进行中，处理完成后成绩恢复显示」。
页面顶部固定声明不显示「剩余次数 / 剩余天数」——后端没有资格查询端点，前端不本地计数（接口事实）。

### 步骤 8 — 教师处理复核（UI）

`/teacher/reviews` 选考试 → 行 `1 | 5 | 第 2 题答案含强一致读… | 待处理 | 2026-09-19 16:48:28 | 查看 处理`
→ 处理 → 选「同意（可调整总分）」+ 调整后总分 `13` + 处理说明 →
`POST /api/score-reviews/1/handle` → **200** → 行变「已同意」，说明落在 `result` 字段，操作列变「已出结论」（无处理入口）。

### 步骤 9 — 学生看到复核结果（UI）

`GET /api/scores/my` → `{"objectiveScore":5,"subjectiveScore":9,"totalScore":13,"rank":1,"reviewing":false}` →
卡片总分 **13**（后端调整值，前端不做任何本地合并），复核中提示消失。

## 3. 附加链路实测：force-end → 缺考 → 补考

- **强制结束**：`POST /api/exams/2/force-end` → status 1→2、`forceEnd=1`；`GET /api/exams/2/absences` →
  `[{"studentId":6,"studentName":"缺考学生","markedTime":"2026-09-19T16:09:11"}]`（未交卷者被标记）。
- **缺考名单页** `/teacher/absences`：`6 | 缺考学生 | 2026-09-19 16:09:11 | 为该生创建补考`，
  并固定展示「后端只有 `studentId / studentName / markedTime`，**没有标记来源字段**，因此本页不区分哪条路径」。
- **补考页** `/teacher/makeups?examId=2&studentIds=6`（从缺考名单跳转时预填生效，候选人已勾选）：
  `GET /api/exams/2/makeup-eligible?passLine=60` → `6 缺考学生 ABSENT` / `5 交卷学生 BELOW_LINE`（原因由后端给出）；
  标题留空创建 → `POST /api/exams/2/makeups`
  `{"startTime":"2026-09-21T14:30:00","endTime":"2026-09-21T15:30:00","durationMinutes":60,"allowLateMinutes":0,"makeupScoreRule":"takeHighest","studentIds":[6]}`
  → **200** `{"examId":4,"title":"考后闭环演示考 160824-补考","parentExamId":2,"makeupScoreRule":"takeHighest","candidateCount":1}`
  —— 顺带验证了「标题留空由后端按默认规则生成」。
- 页面顶部固定声明：**不展示主考 vs 补考合并后的最终成绩**（`MakeupScoreService.finalScore` 全仓零调用，遗留 #5）。

## 4. 导出实测

`/teacher/scores` 状态「已批改」后导出按钮解禁；点「全班成绩单」→ `GET /api/exams/2/scores/export/class-sheet` **200**。
四个导出接口实测响应（同一教师会话）：

| 接口 | Content-Type | Content-Disposition | 字节 / 魔数 |
|---|---|---|---|
| `export/class-sheet` | `...spreadsheetml.sheet` | `attachment; filename*=UTF-8''%E5%85%A8%E7%8F%AD...xlsx` | 3759 / `504b`(PK) |
| `export/detail` | 同上 | `filename*=UTF-8''%E9%80%90%E9%A2%98...xlsx` | 3697 / `504b` |
| `export/question-stats` | 同上 | `filename*=UTF-8''%E9%A2%98%E7%9B%AE...xlsx` | 3912 / `504b` |
| `export/personal/5?format=pdf` | `application/pdf` | `filename*=UTF-8''%E4%B8%AA%E4%BA%BA...pdf` | 1931 / `2550`(%P) |

前端实测 toast：`已下载：逐题得分明细-2-1789806873320.xlsx` —— 文件名取自后端 RFC 5987 头，
未在前端取全量数据拼表（无 `xlsx` / `exceljs` 依赖）。

## 5. 本轮发现并修复的缺陷（全部由真机联调暴露）

| # | 缺陷 | 根因 | 修复 |
|---|---|---|---|
| 1 | **6 处表格列渲染为空**：主观题进度（题号/题干/满分）、成绩发布结果表、缺考名单、补考候选人、复核列表、发布预览榜 | 全仓列定义只写 `key` 不写 `dataIndex`；ant-design-vue 4.2.6 取单元格值用的是 `getPathValue(record, dataIndex)`（`node_modules/ant-design-vue/es/vc-table/Cell/index.js`），`key` 不参与取值，未被 `#bodyCell` 分支命中的列一律空白 | 为纯展示列补 `dataIndex`；`标记时间 / 申请时间` 两个时间列按仓库既有约定加 dayjs 格式化分支 |
| 2 | 冲突后 Alert 说「最新批改内容见下表」，表格却仍是旧值（显示「未批」，接口已返回 `graded:true, score:7`） | `useGradingFlow` 只把最新行放进 `state.conflict.latestRow`，表格数据源是父级查询，而 `emit('refreshed')` 只在**成功**分支调用 | 冲突分支也 `emit('refreshed')`，实测后表格显示 6 分（服务端真值） |
| 3 | 保存 9 分后成功提示写成「已保存…6 分」 | `lastSuccess` 存的是**点击时**的行对象（他人旧分数），重拉后未更新引用 | 改为按 `submissionId` 从最新行派生 |
| 4 | **补考创建必然失败**：`POST /api/exams/{id}/makeups` 返回 `400 请求体格式错误` | 时间以 `2026-09-20 09:00:00` 空格格式发送，后端字段是 `LocalDateTime`，只认带 `T` 的 ISO-8601（`spring.jackson.date-format` 只作用于 `java.util.Date`） | 新增 `utils/dateTime.ts` 的 `toIsoLocalDateTime()`（+4 例单测），不可解析时不发请求并提示 |
| 5 | 同一屏矛盾：题级进度显示 `0 / 1`，面板却显示「已批 1/1」 | 面板只重拉行列表（`@refreshed="refetchRows"`），题级进度查询未失效 | `onRowRefreshed` 同时重拉 rows 与 questions |

**尚未处理（需要单独决定，均属其他阶段的写入边界）**：

- 阶段 21 的班级 / 考试管理列表命中同一根因（缺陷 1）：班级表 5 列全空、考试表 7 列全空，实测已复现；
- 阶段 21 的 `teacher/exams/create.page.vue` 用 `value-format="YYYY-MM-DD HH:mm:ss"`，与缺陷 4 同一条序列化路径
  ——**未实跑**，按接口事实推断考试创建也会 400，需一并改成 ISO；
- `vitest` 配置未加载 `@vitejs/plugin-vue`，无法 mount `.vue`，因此缺陷 1/2/3/5 这类模板级问题**单测拦不住**，
  只有缺陷 4 被抽成纯函数后补了单测。建议单独立项补配置 + 组件级测试。
- 遗留 #10（交卷消息未被 broker 确认）实测复现，未修。

## 6. 每一步对应的自动化证据

| 演示步骤 | 自动化守门 | 文件 |
|---|---|---|
| 步骤 3 并发冲突 | 8 例（冲突可见 / 不重试 / 不算成功 / 拉最新行 / 校验拦截 / 非冲突错误 / 只认 1012 / 缺 version） | `src/hooks/__tests__/useGradingFlow.spec.ts` |
| 步骤 4/5 动作可用性 | 6 例（五状态动作集合 / 非管理员无撤回 / 未知状态 fail-closed） | `src/utils/__tests__/scoreActions.spec.ts` |
| 步骤 6/9 成绩可见性 | 5 例（三态 + reviewing 时不含分数字段 + 非「成绩待发布」400 不误吞） | `src/utils/__tests__/scoreVisibility.spec.ts` |
| 步骤 2 打分校验 | 5 例（0/满分/一位小数通过；空值/非数字/负数/超满分/两位小数拦截） | `src/utils/__tests__/gradingValidation.spec.ts` |
| 步骤 4 时间序列化（本轮新增） | 4 例（空格→ISO / ISO 原样 / 补零秒 / 空值与不可解析返回 null） | `src/utils/__tests__/dateTime.spec.ts` |
| 步骤 4 导出 | 6 例（文件名解析 / 成功保存 / 失败抛原因 / 同 URL 重试成功等） | `src/utils/__tests__/exportDownload.spec.ts` |
| 步骤 7/8/11 状态映射 | 5 例（1012/1001 常量 / 复核四态 ongoing 语义 / 补考规则 / 缺考无来源字段） | `src/constants/__tests__/postExam.spec.ts` |
