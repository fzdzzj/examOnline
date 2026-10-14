# validate-question-import-answer-length：题库导入答案长度应用层前置校验

## 背景与必要性

在变更 `add-question-import-export` 验收闭环时，收口注记中登记了一项低风险观察：
“导入答案长度无 app 层校验（DB 兜底、文案不对称，收口后小修）”。

当前现场核证：
- `questions.correct_answer` 在数据表定义为 `VARCHAR(512) NOT NULL`；
- 单条建题/改题 DTO `QuestionCreateRequest` 与 `QuestionUpdateRequest` 均显式声明了 `@Size(max = 512, message = "答案最长 512 字")`；
- 批量导入服务 `QuestionImportExportService` 已对 `CONTENT`（≤5000 字）、`IMAGE_URL`（≤512 字）进行了应用层长度预校验，并在超长时记入行错误跳过；
- 但在解析 `ANSWER` 时，仅调用了 `AnswerNormalizer.normalize`，未做应用层长度校验。若用户导入了超长答案（如简答题超 512 字、填空题拼接后超 512 字），写入阶段会触发底层数据库字段溢出异常（Data truncation / value too long），被捕获为生硬且模糊的 `"入库失败，请检查数据"`，破坏了逐行容错的可解释性与端到端文案一致性。

## 设计裁决

1. **分级流转定位**：S 级轻量任务（局部逻辑补强、小残差修复、文件数 < 5、单 Agent 一票到底）。
2. **应用层前置校验**：
   - 在 `QuestionImportExportService` 中定义常量 `ANSWER_MAX = 512`；
   - 在 `parseRow` 解析归一化答案后，校验 `parsed.answer.length() > ANSWER_MAX`；
   - 若超长，将友好错误信息 `"答案最长 512 字"` 写入 `parsed.errors`，该行跳过并记入错误报告，不进入数据库写入。
3. **零 DDL、零 API 契约漂移**：
   - 零 DDL（`schema.sql` 无需改动，无迁移脚本）；
   - 接口契约与请求参数不变，仅完善校验分支的错误原因可读性。
4. **测试与守护**：
   - 在 `QuestionImportExportIntegrationTest` 中追加单行答案超 512 字的导入用例，断言错误报告行号正确且错误原因包含 `"答案最长 512 字"`，同时同批次正确行仍可正常入库。
