# 变更提案：add-question-duplicate-detection

## 为什么（Why）

在题库管理与组卷过程中，随着试题数量的增加，教师经常因不同批次录入、跨学期复制或多人协同维护，无意中录入题干高度相同或极其相近的重复试题（Duplicate Questions），导致：
1. **题库数据冗余膨胀**：同题不同 ID 产生多份副本，增加维护负担；
2. **抽题与组卷出现同题风险**：随机抽题或矩阵蓝图组卷时，可能同一套试卷抽入实质相同的两道题，严重破坏考试公平性；
3. **缺乏录入前置防呆**：当前系统在创建题目与编辑题目时，未对题干内容做任何查重检测或相似度预警提示。

本案落实产品愿景说明书（`docs/examOnline需求规格说明书.md` §2.5「题目查重：相似度检测，避免重复录入」），在零 DDL 前提下补齐该功能差距。

## 改什么（What）

### 1. 后端服务与端点
- **服务层实现**：
  - 新增 `QuestionDuplicateService`（或在 `QuestionService` 扩展查重核心），提供 `List<QuestionDuplicateItemResponse> checkDuplicate(String stem, Long excludeId, Integer type, Double threshold, Integer limit)`：
  - 基于中文与英文兼顾的 **字符双字切分（Bigram Jaccard）** 与 **编辑距离相似度（Normalized Levenshtein / LCS）** 混合算法，高效计算文本相似度：
    - 预处理：过滤常见标点符号、全角/半角空白、大小写归一化；
    - 计算待检测题干与题库中有效题目（`is_deleted=0` 且归属当前教师，ADMIN 查全量）的相似度得分（0.0 ~ 1.0）；
    - 过滤相似度 $\ge \text{threshold}$（默认 0.6 即 60%）的候选集，按相似度降序排列，取 Top N（默认 5 条，上限 10 条）；
    - 若指定 `excludeId`，排除自身（编辑题目场景）。
- **控制器端点**：
  - 在 `QuestionController` 新增 `GET /api/questions/check-duplicate`；
  - 权限要求：`question:manage`（教师仅查自己题库，ADMIN 查全量）；
  - 参数校验：`stem` 不能为空且去除空白后不少于 2 个字符，否则返回 400。

### 2. 前端界面与交互
- **题目创建/编辑防呆**（`QuestionFormModal.vue`）：
  - 题干（`content`）输入框失焦或带有防抖（debounce 500ms）在字数 $\ge 4$ 时发起查重预检；
  - 若命中相似题目（相似度 $\ge 60\%$），在题干下方显性渲染 `<Alert type="warning" show-icon>` 提示：「检测到题库中存在 N 道相似题目（最高相似度 XX%）」，并支持展开预览相似题目的题干摘要、题型、分值与相似度 Tag；
  - 该提示为知情防呆，不强制阻断保存（教师可点击「已知晓并继续」正常提交）；
- **题库页面独立查重入口**（`questions/index.page.vue`）：
  - 列表工具栏增加「题目查重」快捷按钮，支持教师直接粘贴一段题干文本进行主动查重排查。

### 3. API 契约与质量门禁
- 重新导出 `openapi.yaml`，端点数 90 → 91 paths；
- 重新生成前端 API SDK（`pnpm run gen:api`）；
- 编写定向集成测试与算法单元测试，变异击红留证；
- 门禁下限只增不减，收口时上调 `gate-baseline.json`。

## 影响面与边界约束（Scope & Constraints）

- **零 DDL**：严禁修改 `src/main/resources/schema.sql`，严禁新增 `docker/mysql/migrations/` 迁移脚本；
- **零新第三方依赖**：纯 Java 算法实现双字分词与编辑距离，不引入重量级分词器与外部搜索引擎；
- **集成测试禁 `@Sql`**；
- **向后兼容**：既有创建题目、编辑题目、分页、删除端点行为与契约逐字零漂移。

## 任务分组与流转（Tasks）

1. **阶段 1：三件套制定与大盘登记**（主 Agent）；
2. **阶段 2：服务层与控制器实现、前端对接与先红后绿验证**（子 Agent 实施，受控白名单，变异击红）；
3. **阶段 3：全量门禁实测、契约同步、规范合入与收口归档**（主 Agent 收口合流）。
