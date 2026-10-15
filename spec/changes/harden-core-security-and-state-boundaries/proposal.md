# Proposal: 业务越权防御、考务门禁严密化与核心状态缓存一致性治理 (harden-core-security-and-state-boundaries)

## 1. 为什么（Why）

本项目经过多次迭代，核心业务功能已全面具备。但在系统边界、业务合理性与体验健壮性专项巡查中，发现了 4 处涉及业务权限越权、考务资格门禁、批改工作台体验故障与核心数据一致性的高危（High）缺陷：

1. **班级转班跨教师水平越权（H-01，安全越权）**：`ClassService.transfer` 仅校验了源班级属于当前操作教师，对目标班级 `targetClassId` 仅校验了记录存在性，未校验教师归属。教师 A 传入教师 B 的班级 ID 即可将学生强行转至教师 B 的班级中，破坏了班级数据隔离；
2. **成绩复核申请门禁缺失（H-02，考务严肃性）**：`ScoreReviewService.apply` 仅校验了考试状态与 7 天窗口期，完全漏掉了该学生是否参加过本场考试、是否属于该考试的班级、以及是否有已批改答卷（`GradingSubmission`）的校验。外班或未参考学生只需获知已发布考试的 `examId`，即可提交复核申请，强行灌入教师的复核工作台；
3. **批改工作台报错伪装为无主观题（H-03，判分安全隐患）**：`frontend/.../teacher/grading/index.page.vue` 查询主观题列表的 `useQuery` 未解构并处理 `error`。若接口遭遇 500 报错、超时或网络中断，数据回退为空数组，页面会直接弹出提示："该试卷没有主观题，客观题由后端自动判分，无需人工批改"。系统故障被伪装成业务结论，教师信以为真将导致未批改主观题直接被漏判；
4. **状态机定时流转与成绩发布撤回未失效详情缓存（H-04，数据一致性）**：`ExamService.detail(id)` 被 `@Cacheable(CACHE_EXAM_DETAIL)` 缓存 5 分钟。`ExamStateMachineService.autoAdvance()` 定时扫表将考试从未开始推进到进行中、从进行中推进到已结束，以及 `ScoreService.publish/revoke` 改变考试状态，均直接写库未清除缓存，导致详情接口返回过期旧状态长达 5 分钟。

本提案针对上述 4 项高危痛点集中立项，彻底闭环。

---

## 2. 改什么（What）

### 2.1 班级转班目标班级所有权强校验 (`ClassService`)
- 在 `transfer(Long classId, Long userId, Long targetClassId)` 中：
  - 将目标班级存在性校验替换为严格归属校验：`getOwnedClass(targetClassId);`；
  - 非归属教师且非 ADMIN 调用直接抛出 403 Forbidden；
  - 增加防呆：源班级与目标班级不能相同校验，相同抛出 400 Bad Request。

### 2.2 成绩复核申请前置有效答卷门禁 (`ScoreReviewService`)
- 在 `apply(Long examId, String reason)` 中：
  - 补充对学生已判分答卷的校验：
    ```java
    GradingSubmission submission = gradingMapper.selectOne(Wrappers.<GradingSubmission>lambdaQuery()
            .eq(GradingSubmission::getExamId, examId)
            .eq(GradingSubmission::getStudentId, studentId)
            .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED));
    if (submission == null || submission.getTotalScore() == null) {
        throw new BusinessException(ResponseCode.BAD_REQUEST, "未参加本场考试或成绩尚未批改完成，无法申请复核");
    }
    ```
  - 确保仅参考且已出分学生才可进入复核申请流程。

### 2.3 批改工作台主观题失败三态分离与显性呈现 (`grading/index.page.vue`)
- 在主观题列表 `useQuery` 中解构 `error: questionsError`；
- 计算 `questionsErrorText`，遵循 U-1 规范提取友好错误文案；
- 模板调整：当 `questionsErrorText` 存在时显性呈现 `<Alert type="error" show-icon :message="questionsErrorText" data-test="questions-error" class="mb-3" />`；
- 仅当接口成功且真正没有主观题时，才呈现无需人工批改的 info 提示。

### 2.4 状态机流转与成绩发布/撤回主动失效详情缓存 (`ExamStateMachineService`, `ScoreService`)
- `ExamStateMachineService` 注入 `CacheManager`，在 `autoAdvance()` 中若 `moved > 0`，调用 `cacheManager.getCache(CacheConfig.CACHE_EXAM_DETAIL).clear()` 清除缓存；
- `ScoreService` 在 `publish` 与 `revoke` 方法上添加注解 `@CacheEvict(cacheNames = CacheConfig.CACHE_EXAM_DETAIL, allEntries = true)`，确保成绩发布与撤回后详情缓存立即失效。

---

## 3. 影响能力域与规范

- `class-management`：转班目标班级所有权水平越权拦截规范；
- `score-review`：成绩复核申请资格与答卷门禁规范；
- `frontend`：批改工作台主观题列表查询失败三态分离规范；
- `exam-management`：状态机自动流转与成绩发布撤回主动失效详情缓存规范。

---

## 4. 零 DDL 与红线声明

- **零 DDL**：未新增、修改任何表结构，未改动 `schema.sql`，不添加任何 `docker/mysql/migrations/` 脚本；
- **契约稳定**：`openapi.yaml` 既有 89 个路径与 schema 保持稳定，无需修改契约；
- **严禁测试使用 `@Sql`**：新测用例一律基于已有数据库基础设施；
- **双端门禁只增不减**：全量后端用例数基线锁定上调。

---

## 5. 变异验证计划

- **m1（转班目标班级校验）**：抽掉 `getOwnedClass(targetClassId)`，改回普通 `selectById`，验证跨教师转班用例变红（期望 403 得 200）；
- **m2（复核答卷资格门禁）**：抽掉答卷存在性与已批改判定，验证未参考学生申请复核用例变红（期望 400 得 200）；
- **m3（批改工作台错误呈现）**：抽掉 `questionsErrorText` 处理分支，验证接口报错时测试断言未能渲染 error Alert 变红；
- **m4（状态流转缓存清除）**：抽掉 `cacheManager.clear()`，验证自动流转后详情读取命中旧状态变红。
