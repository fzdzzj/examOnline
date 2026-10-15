# 变更提案：add-paper-pdf-export

## 为什么（Why）

在高校与专业考试场景中，教师组卷完成后往往需要将试卷导出为规范的纸质版 PDF 文件，用于：
1. **线下纸质考试与备用卷**：部分考场或因网络环境限制需采用纸质考试，需要具备规范的试卷卷头、考生信息栏、各大题得分汇总栏与规范排版；
2. **教学归档与教务抽检**：高校期末统考要求试卷原题及参考答案纸质归档备查；
3. **教师评卷参考标准**：需要导出包含标准答案与评分说明的「参考答案卷」用于教师统一阅卷。

目前系统已具备个人成绩单 PDF 导出能力，但试卷域仅支持在线预览与快照锁定，缺乏标准试卷与参考答案的 PDF 流式导出能力。

## 改什么（What）

### 1. 后端服务与端点
- **服务层实现**：创建 `PaperExportService`，提供 `byte[] exportPaperPdf(Long paperId, boolean withAnswer)`：
  - 基于 iText 5 生成规范排版的 A4 试卷 PDF；
  - 试卷卷头：试卷大标题、试卷基本信息（总分、题数、注意事项）、考生信息栏（姓名、学号、班级、考场/座位号）、大题得分汇总表格（题号/一/二/三/四/五/总分/评卷人）；
  - 题目按题型规整大题分组展示（单选题、多选题、判断题、填空题、简答题），每题展示题号、分值、题干及选项；
  - 学生作答版（`withAnswer=false`）：填空题提供下划线横线，简答题预留答题空白区域；
  - 教师参考答案版（`withAnswer=true`）：标出【参考答案与评分标准】，输出各题标准答案与题目解析；
  - 文本输入全面经 Unicode 安全字符清洗（`sanitizePdfText`，过滤增补平面生僻字/Emoji/控制符，杜绝 iText 抛出内部异常）；
  - 权限与所有权门禁：调用 `paperService.detail(id)` 自动执行 `requireOwnedPaper` 越权校验（非 owner 且非 ADMIN 拒绝 403，不存在/软删 404）。
- **控制器端点**：
  - 在 `PaperController` 新增 `GET /api/papers/{id}/export-pdf`（可选 query 参数 `withAnswer=false`）；
  - 响应类型：`application/pdf`，RFC 5987 UTF-8 编码的 `Content-Disposition: attachment; filename*=UTF-8''...`。

### 2. 前端界面与交互
- 试卷详情页（`papers/[id].page.vue`）：在操作栏增加「导出试卷 PDF」按钮（支持下拉或确认选择「学生作答卷」与「含答案解析卷」），点击触发下载；
- 试卷列表页（`papers/index.page.vue`）：在操作列补充「导出 PDF」入口；
- 下载过程具有 loading 反馈，失败时给出友好提示。

### 3. API 契约与质量门禁
- 重新导出 `openapi.yaml`，端点数 89 → 90 paths；
- 重新生成前端 API SDK；
- 编写定向集成测试与单元测试，变异击红留证；
- 门禁下限只增不减，收口时上调 `gate-baseline.json`。

## 影响面与边界约束（Scope & Constraints）

- **零 DDL**：严禁修改 `src/main/resources/schema.sql`，严禁新增 `docker/mysql/migrations/` 迁移脚本；
- **零新依赖**：直接复用项目中已有的 `iText` 5 依赖及中文字体配置；
- **集成测试禁 `@Sql`**；
- **向后兼容**：既有试卷创建、分页、详情、克隆、锁定等接口语义与返回值逐字零漂移。

## 任务分组与流转（Tasks）

1. **阶段 1：三件套制定与大盘登记**（主 Agent）；
2. **阶段 2：服务层与控制器实现、前端对接与先红后绿验证**（子 Agent 实施，受控白名单，变异击红）；
3. **阶段 3：全量门禁实测、契约同步、规范合入与收口归档**（主 Agent 收口合流）。
