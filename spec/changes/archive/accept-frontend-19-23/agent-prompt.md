# 子 agent 提示词 —— `accept-frontend-19-23`（前端阶段 19–23 统一验收·真机走查部分，P2）

> 用法：整份复制发给子 agent。自包含。
> 先读 `spec/changes/accept-frontend-19-23/proposal.md`、`tasks.json`，再读仓库根 `AGENTS.md`。
> **分工边界**：任务 1（门禁复跑与回勾裁决）、任务 2（基线合入）、任务 4（登记收口）是**指导 agent** 的活——`tasks.json` 回勾、`spec/**` 改动从来不是子 agent 的事。**本提示词只派任务 3：统一真机走查**。走查证据回传后，指导 agent 做裁决与收口。

---

## 现状

- 仓库主工作树 `D:\code\examOnline`；你在主工作树干活但**只写走查记录文档**（写入边界见下）。开工 `git rev-parse HEAD` + `git status --short` 自检。
- 事实基线（开工现场复核，别信文档旧数字）：
  - 阶段 21/22/23 已验收归档（`spec/changes/archive/` 下有目录），frontend 基线已含 13 个 Requirement；阶段 19/20 的验收由指导 agent 另行处理，与你无关。
  - 真机走查**余项清单**（登记于 spec/README.md 归档表各行，现场 `git grep -n "真机走查" -- spec/README.md` 复核）：阶段 21 页面族（监考/行为时间线/Grafana 入口/两个确认弹窗的浏览器侧演示）、阶段 22 返修后口径的切屏演示（`frontend/docs/student-taking-demo.md` 脚本四）。
  - dev 栈（`exam-mysql-master` 13316 / `exam-mysql-slave` 13317 / `exam-rabbitmq` / 宿主 Redis 6379）当前状态未知；起栈前 `docker ps` 确认无冲突任务。
  - 演示脚本：`frontend/docs/student-taking-demo.md`、`frontend/docs/post-exam-demo.md`——**这两份是走查的操作蓝本**，先通读。

## 验收判据（不是常量）

1. **走查记录三要素**：每条记录 = 实走路径（页面/操作）+ 真实观察现象（请求 URL 与状态、界面呈现）+ 当时短 revision；**未实走的条目如实标注原因**（环境/账号/时间），不留空也不编造。
2. **缺陷零静默**：逻辑/契约级缺陷（接口报错、数据不对、守卫失效、防重失效等）当场记录并**停下回报**，不修不绕；演示级小修（错别字/纯样式）可当场修，留前后证据。
3. **数据清理**：走查用演示账号与测试考试，结束前清理（不留脏业务数据；不删历史遗留表 `rep_test`）。

## 环境纪律（违反即返工）

1. 起栈前确认：Docker 引擎可用（`docker info`）、端口无冲突、无并行真实环境任务（错峰，见工程惯例）。
2. **不动共享容器**：dev 栈容器一个不许删改重建；起不来就停下回报。
3. 走查前端：`pnpm dev`（或仓库既有启动方式，先看 `frontend/package.json`）；后端按 `docs/指导Agent交接文档.md` 工具链小节拼启动命令，回报里给**实际执行的命令**。

## 实施（对应 tasks.json 任务 3）

### 走查范围（逐条留证）

1. **阶段 19**：认证四页（登录/登出/改密）、角色守卫（admin 未改密被拦 → 改密后放行）、令牌续期（可观察的网络行为）。
2. **阶段 20**：题库列表与题型编辑表单、标签管理、手动组卷、标签随机抽题、试卷预览（留意已知修复过的「列定义只写 key 不写 dataIndex」类渲染问题是否复发）。
3. **阶段 21**：考试详情五 Tab（快照预览/考生名单与进度/监考/行为日志）、Grafana 只读入口（未配置时降级提示也算一条）、发布/force-end 两个确认弹窗正文**真实可见**（Alert 具名插槽修复的浏览器侧确认）。
4. **阶段 22**：切屏检测返修后口径——按 `student-taking-demo.md` 脚本四实走：离开暂存、回归上报一条含 `durationMs`、多次离开各报各的、（可模拟的）兜底路径。
5. **阶段 23**：批改工作台（含一次并发冲突演示）、成绩汇总/预览/发布/撤回、导出下载、缺考名单、补考创建、学生成绩三态与复核申请闭环、发布/复核两个弹窗正文真实可见（本轮修复的浏览器侧确认）。

### 记录

- 走查记录写 `frontend/docs/frontend-stages-walkthrough.md`（新增）：按阶段分节、每条三要素、缺陷单列一节（级别 + 现象 + 复现步骤 + 建议处置：另立项 / 演示级已修）。

## 写入边界

允许新增 / 修改：

- `frontend/docs/frontend-stages-walkthrough.md`（新增，唯一必产文件）
- `frontend/src/**`（**仅演示级小修**：错别字/纯样式；逻辑/契约级禁改——停下回报）

禁止其它路径。**特别禁止**：`src/main`、`src/test`、`pom.xml`、`spec/**`、`docker-compose.yml`、dev 容器与 dev 库结构、`rep_test` 表。

## Commit

- 分支 `feature/accept-frontend-19-23`，**自己 commit**：走查记录一笔（若含演示级小修，小修单独一笔，前缀 `fix(frontend)`），中文描述，前缀 `docs(frontend)`。
- 每次提交后 `git rev-parse HEAD` + `git status --short`；HEAD 若变 unborn，从 `.git/logs/HEAD` 取 sha 补 ref，**不要用 `git update-ref`**。
- **禁止 `stash` / `reset` / `checkout -- .` / `clean`**。

## 回报格式（按此六段，不要写散文）

1. **环境**：起栈命令与原始输出、走查期间短 revision
2. **实走清单**：五阶段逐条「路径 + 观察现象」，未实走条目及原因
3. **缺陷清单**（若有）：级别 / 现象 / 复现步骤 / 建议处置；零缺陷就写「零缺陷」
4. **演示级小修**（若有）：改动点 + 前后证据
5. **数据清理确认**：演示数据已清、dev 栈状态（是否保持原样）
6. **意外发现**：与文档/预期不符的任何事实

## 禁止

- 禁止编造未实走的记录；
- 禁止修逻辑/契约级缺陷（停下回报是合格行为，顺手修是违规）；
- 禁止勾 `tasks.json` 或动 `spec/**`（回勾与收口是指导 agent 的事）；
- 禁止删 `rep_test`、禁止动共享容器；
- 禁止走查数据残留。
