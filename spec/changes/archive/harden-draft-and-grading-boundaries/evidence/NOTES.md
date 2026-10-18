# harden-draft-and-grading-boundaries · evidence 留档说明

全部日志为当次真实执行的原始输出（未经改写）；每份产物与产生它的命令、revision 绑定如下。
实施笔 `fd47549`（三防御 + 三测试类），本目录与 tasks.json 回填、gate-baseline 上调构成证据笔。

## 1. TDD 先红后绿（change 内三测试类，`-Dtest` 定向）

| 阶段 | 产物 | 命令 | revision | 结论 |
| --- | --- | --- | --- | --- |
| 先红 | tdd-red-phase.txt | `mvnw.cmd test -Dtest=ExamDraftServiceTest,SubjectiveScoreValidationTest,SubjectiveGradingServiceTest` | 实施前工作区（基线 6b3732e + 三测试文件） | 8 例 / 3 失败 / 0 错误 / 5 通过 |
| 回绿 | tdd-green-phase.txt | 同上 | 实施后工作区（= fd47549 内容） | 8 例 / 0 失败 / 0 错误 |

三条红均为目标断言级（与 proposal §3 预设一一对应）：

- `SubjectiveScoreValidationTest.commentOver1024CharsRejected:63` — 1025 字符评语必须被参数校验拦截 ==> expected: <false> but was: <true>
- `SubjectiveGradingServiceTest.insertDuplicateKeyConvertedToStateConflict:116` — Unexpected exception type thrown, expected: <com.exam.common.BusinessException> but was: <org.springframework.dao.DuplicateKeyException>
- `ExamDraftServiceTest.oversizedDraftRejectedWithoutWritingRedis:94` — Expected com.exam.common.BusinessException to be thrown, but nothing was thrown

5 条绿为边界护栏（恰好 64KB 受理、1024 字符受理、null 评语受理、插入成功路径、小体积既有语义），先红阶段即绿，防钳制方向写反（`>` 误成 `>=`）与 catch 误伤成功路径。

## 2. 变异击红与回绿（m1/m2/m3，均在实施笔入库后进行）

每次变异：编辑注入 → 定向跑对应测试类（红）→ `git checkout HEAD -- <注入文件>` 原样回退 → 复跑（绿）。

| 变异 | 注入点（proposal §3） | 击红断言 | 红档 | 回绿档 |
| --- | --- | --- | --- | --- |
| m1 删草稿体积校验 | ExamDraftService 超限 if 块改为恒否 | oversizedDraftRejectedWithoutWritingRedis:94 「nothing was thrown」 | mutation-m1-red.txt（3 例 1 失败） | mutation-m1-revert-green.txt（3/0/0） |
| m2 删 @Size | SubjectiveScoreRequest.comment 移除注解 | commentOver1024CharsRejected:63 「expected: <false> but was: <true>」 | mutation-m2-red.txt（3 例 1 失败） | mutation-m2-revert-green.txt（3/0/0） |
| m3 删 catch 转 409 | SubjectiveGradingService 移除 try-catch | insertDuplicateKeyConvertedToStateConflict:116 「was: <DuplicateKeyException>」 | mutation-m3-red.txt（2 例 1 失败） | mutation-m3-revert-green.txt（2/0/0） |

三次回退后 `git status --porcelain` 均为空（变异零残留）。

## 3. 双端门禁实测（全量、串行）

| 门禁 | 命令 | revision | 结论 |
| --- | --- | --- | --- |
| 后端 | `mvnw.cmd clean test`（仓库根） | fd47549 | Tests run: 722, Failures: 0, Errors: 0, Skipped: 1，BUILD SUCCESS（gate-backend.txt） |
| 前端 lint | `pnpm lint:check` | fd47549 | exit 0（gate-frontend-lint.txt） |
| 前端 type-check | `pnpm type-check:check` | fd47549 | exit 0（gate-frontend-typecheck.txt） |
| 前端 vitest | `pnpm test -- --maxWorkers=4` | fd47549 | 122 文件 / 939 例全绿（gate-frontend-test.txt） |

- 后端 714 → 722（+8，恰为本案新增 8 例）；`.github/ci/gate-baseline.json` backendTests 同笔上调 714→722。
- 前端 939 持平；openapi.yaml 100 paths 持平（`grep -cE '^  /' openapi.yaml` = 100）。
- Skipped=1 为存量既有（验收口径见 spec/specs/agent-harness/spec.md）。

## 4. 红线自查（证据笔落位前实测）

- `grep -n flyway pom.xml` → 0 命中；`test ! -e src/main/resources/db` → OK；
- `grep -rn '@Sql' src/test | …` → 空（三新测试类均为纯 Mockito / 纯 Bean Validation，无 Spring 上下文）；
- `git diff 6b3732e --stat -- src/main/resources/schema.sql docker/mysql/migrations frontend openapi.yaml` → 空；
- `git diff 6b3732e --stat` 总量 = 6 文件 +440/−3（3 主代码 + 3 测试），恰为 proposal §2.2 白名单。

## 5. 证据文件哈希清单

见 evidence-sha256.txt（对目录内其余全部文件现算，不含清单自身）。
