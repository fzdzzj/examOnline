# Proposal: 成绩复核终局防呆、导出批量分片防 OOM 与交互细节打磨治理 (harden-review-flow-and-resource-limits)

## 1. 为什么（Why）

系统边界与合理性巡查已完成路线 A（核心安全越权与缓存一致性）与路线 B（数据进出清洗与主要页面三态分离）的全面闭环。本提案作为全仓巡查收官案（路线 C），集中解决巡查档案中的剩余风险与细节体验痛点：

1. **成绩复核调分不可逆防呆缺失（M-05，终局操作防呆）**：`ReviewHandleModal.vue` 中教师勾选「同意（可调整总分）」时，可直接录入 `adjustedTotalScore` 并确定提交。后端 `ScoreReviewService` 收到调分后将直接更新答卷表（`grading_submissions.total_score`）的最终成绩，不可撤销。弹层缺乏显性的「不可逆」警示说明与防手误二次确认；
2. **用户导出巨型 IN 查询与 OOM 隐患（M-06，资源保护与大批量健壮性）**：`UserImportExportService.java` 在 `exportStudents`、`exportTeachers`、`exportAllUsers` 中，先查出所有关联的 `userIds`，随后直接调用 `userMapper.selectBatchIds(userIds)`。当用户数量达到数千或上万时，会构造超长 `WHERE id IN (?, ?, ...)` 触发 MySQL `max_allowed_packet` 异常与大对象瞬时堆积；
3. **考后页面考试下拉候选加载失败静默吞没（L-01，体验打磨）**：5 个使用 `createTeacherExamsQueryOptions` 的教师业务页面（成绩、批改、缺考、补考、复核）在考试列表拉取失败时，下拉框静默展示为空，缺乏失败视觉提示；
4. **成绩单 PDF 导出生僻字符容错（L-02，导出健壮性）**：`ScoreExportService.java` 中个人成绩单 PDF 导出使用 iText `STSong-Light` + `UniGB-UCS2-H` 字体，遇到非常规数学符号、表情或生僻字时容易触发编码异常中断整个导出请求；
5. **班级学生移除操作缺乏 loading 动画（L-03，视觉反馈）**：`classes/index.page.vue` 移出学生按钮虽然配置了 `:disabled`，但缺少 `:loading="removing(record)"` 动画反馈，教师在网络较慢时无法感知请求在途状态。

本提案针对上述 5 项治理点立项，彻底收口本次系统巡查档案。

---

## 2. 改什么（What）

### 2.1 成绩复核调分不可逆警示与二次确认（`ReviewHandleModal.vue`）
- 当教师在弹窗中选择 `action === 'AGREE'` 且输入了 `adjustedTotalScore` 时：
  - 动态渲染 `<Alert type="warning" show-icon message="调整总分将直接覆写该场考试的学生最终得分且不可逆，请核对准确" class="mb-3" />`；
  - 提交动作增加确认防呆（Modal 或 Popconfirm 二次知情确认），防止误触提交。

### 2.2 用户导出批量分片防巨型 IN 查询（`UserImportExportService.java`）
- 定义分批上限常量 `BATCH_SIZE = 500`；
- 提供安全的批量查询工具方法 `batchSelectUsers(List<Long> userIds)`，对传入的 `userIds` 按 500 一批分批次执行 `selectBatchIds(subList)` 并累加到结果集中，杜绝超大单条 IN 查询；
- 在 `exportStudents`、`exportTeachers`、`exportAllUsers` 中统一调用分批查询。

### 2.3 教师考后页面考试下拉候选加载失败状态增强（`useTeacherExams.ts` 及消费处）
- 在 `createTeacherExamsQueryOptions` 包装层及消费页面，对拉取失败提供合理处理或占位提示，避免下拉组件完全静默显示无数据。

### 2.4 个人成绩单 PDF 导出生僻字符安全替换（`ScoreExportService.java`）
- 在 PDF 文本写入前增加字符串安全清洗 `sanitizePdfText(String text)`，将超出当前字体编码支持范围或特殊不可见控制字符安全替换为通用占位符，保证 PDF 成功生成并下载。

### 2.5 班级移除学生按钮补齐 loading 动效（`classes/index.page.vue`）
- 为移除按钮绑定 `:loading="removing(record)"`，实现与 `:disabled="removing(record)"` 一致的在途反馈。

---

## 3. 影响能力域与规范

- `score-review`：成绩复核调分操作不可逆防呆警示规范；
- `authentication`：用户导出大批量分批查询资源保护规范；
- `score-management`：成绩单 PDF 导出生僻字符安全容错规范；
- `frontend`：复核操作防呆、下拉错误反馈与班级移出动效规范。

---

## 4. 零 DDL 与红线声明

- **零 DDL**：未新增、修改任何表结构，未改动 `schema.sql`，不添加任何 `docker/mysql/migrations/` 脚本；
- **契约稳定**：`openapi.yaml` 既有 89 个 paths 与 schema 保持稳定，无需修改契约；
- **严禁测试使用 `@Sql`**：新测用例一律基于已有数据库基础设施；
- **双端门禁只增不减**：全量后端用例数基线锁定上调。

---

## 5. 变异验证计划

- **m1（复核调分不可逆警示）**：抽掉调分时的 warning Alert 渲染，验证调分模式下单测断言 Alert 缺失而变红；
- **m2（用户导出批量分片）**：抽掉分批查询（恢复单次调用 `selectBatchIds`），通过模拟大于 500 个 ID 的列表验证分批分发逻辑生效；
- **m3（PDF 导出生僻字符容错）**：抽掉特殊字符安全替换，验证含有特殊符号时 PDF 导出用例抛出异常变红；
- **m4（班级移出学生 loading）**：抽掉移除按钮的 `:loading` 属性绑定，验证单元测试断言 loading 状态缺失变红。
