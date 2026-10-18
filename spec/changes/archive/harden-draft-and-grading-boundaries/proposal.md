# Proposal: harden-draft-and-grading-boundaries（草稿体积与批改边界安全加固，S 级后端特性）

## 1. 为什么做（背景与动机）

依据 [`docs/功能待办清单.md`](../../docs/功能待办清单.md) §B.2 巡查备忘中登记的 3 项后端边界隐患：
1. **草稿 answers 字节体积上限缺失**：
   - 目前 `ExamDraftService.saveDraft` 对学生提交的草稿 answers（`JsonNode`）未限制体积上限。恶意考生或异常脚本可伪造超大 JSON（如数十 MB）反复推送，刷写 Redis 并消耗带宽与内存，威胁考试集群稳定性；
   - 加固方案：在 `ExamDraftService` 保存前对 `answers` 序列化后的 UTF-8 字节体积实施 $\le 64\text{KB}$（65,536 字节）上限钳制，超限抛出 `BusinessException(ResponseCode.BAD_REQUEST, "草稿内容超过大小上限 (64KB)")`。
2. **批改评语超长触发 500**：
   - 数据库表 `subjective_grades` 中 `comment` 列为 `VARCHAR(1024)`，但入参 DTO `SubjectiveScoreRequest` 缺少长度约束，评语超过 1024 字符将直接引发 `DataIntegrityViolationException` 导致 500 错误；
   - 加固方案：在 `SubjectiveScoreRequest.comment` 补充 `@Size(max = 1024, message = "批改评语不能超过 1024 个字符")`，由框架统一在参数校验层阻断并返回 400。
3. **初次批改并发唯一键冲突未捕获**：
   - 当两名教师同时批改同一名学生同一道尚未有批改记录的主观题时，双端均判定 `row == null` 并发起 `insert`，后执行者撞击 `uk_subjective_grades (submission_id, question_id)` 抛出 `DuplicateKeyException` 500；
   - 加固方案：在 `SubjectiveGradingService.saveScore` 的插入路径使用 `try-catch` 捕获 `DuplicateKeyException`，统一转抛 `BusinessException(ResponseCode.STATE_CONFLICT, "批改已被他人更新，请刷新后重试")`，与既有 CAS 乐观锁冲突（409）语义对齐。

## 2. 改动范围与设计细节

### 2.1 零 DDL 与纯后端红线
- **零 DDL**：`src/main/resources/schema.sql` 与 `docker/mysql/migrations/` **0 字节改动**；
- **前端零改动**：`frontend/` **0 字节改动**；
- **契约保持不变**：`openapi.yaml` 100 paths 保持不变；
- **门禁基线**：后端单调上调（714 $\to$ 714 + N），前端 939 保持不变。

### 2.2 涉及代码白名单
- `src/main/java/com/exam/taking/service/ExamDraftService.java`
- `src/main/java/com/exam/grading/dto/SubjectiveScoreRequest.java`
- `src/main/java/com/exam/grading/service/SubjectiveGradingService.java`
- 测试：`ExamDraftServiceTest.java` / `SubjectiveGradingServiceTest.java` / `SubjectiveScoreValidationTest.java`
- `spec/changes/harden-draft-and-grading-boundaries/`

## 3. 变异验证预设（三项必检闭环）

1. **变异 m1（移除草稿体积上限校验）**：
   - 注入点：移除 `ExamDraftService` 对 answers 字节体积超过 64KB 的检查；
   - 预期击红：测试断言「提交 65KB 草稿被拒绝并抛出 BAD_REQUEST」断言级失败。
2. **变异 m2（移除批改评语 @Size 校验）**：
   - 注入点：移除 `SubjectiveScoreRequest.comment` 的 `@Size(max = 1024)` 注解；
   - 预期击红：测试断言「提交 1025 字符评语触发 Bean Validation 校验失败」断言级失败。
3. **变异 m3（移除初次批改并发 DuplicateKeyException 捕获）**：
   - 注入点：移除 `SubjectiveGradingService` 中的 `try-catch(DuplicateKeyException)` 转换；
   - 预期击红：测试断言「初次批改并发唯一键冲突返回 409 STATE_CONFLICT」断言级失败（得到未捕获的原始异常）。

## 4. 门禁与交付基线
- 后端门禁：`./mvnw.cmd clean test` 全绿，上调 `.github/ci/gate-baseline.json` 的 `backendTests`；
- 前端门禁：`pnpm test` 939 例全绿；
- 契约：100 paths 保持不变。
