# add-question-fill-blank：填空题题型

## 背景与必要性

成熟产品差距分析（2026-10-13，指导主agent 现场核证）：当前题型仅单选/多选/判断/简答四种
（`QuestionType.java`，questions.type 注释同步），无填空题——文科听写、理科计算类题目无法组卷，
属内容表达力的硬缺口。用户裁决立项（同批裁决：题干图片、题库批量导入两案排队，本案先行；
批量导入殿后以一次性覆盖最终题型集）。

## 现场核证基础（HEAD=1192a7e）

- 判分侧为策略注册表模式：`com.exam.grading.strategy`（GradingStrategyRegistry + 4 策略类），
  「新增题型不改判分核心」为既有设计承诺（BehaviorEventRegistry 同构先例）。
- 答案归一化集中在 `com.exam.question.support.AnswerNormalizer`（switch 按题型分派，SHORT_ANSWER
  同构旁路即 FILL_BLANK 的扩展位）。
- `questions.correct_answer VARCHAR(512)`（归一化答案）足以承载多可接受答案，**零 DDL**；
  schema.sql 仅更新 type 列注释（建表唯一入口纪律）。
- 组卷/随机抽题/试卷快照均题型无关，天然兼容（执行侧须现场核证抽题的 QUESTION_TYPE 标签
  供给链是否需登记新题型标签行——查 tags 种子数据形态，如实处理）。

## 设计裁决

1. **type=5 FILL_BLANK「填空」**；范围=**单空**（题干以 4+ 连续下划线 `____` 表示空位）；
   多空明确不做、不预留半成品（proposal 边界，将来需要另行立项）。
2. **correct_answer 编码**：多个可接受答案以 `|` 分隔（`答案1|答案2|答案3`）；录入校验拒绝
   答案自身含 `|`、拒绝空列表；VARCHAR(512) 长度约束照旧（超长校验报错，不静默截断）。
3. **判分口径：客观自动判分**——学生答案 trim 后与可接受答案逐一比对：中文精确匹配、
   英文忽略大小写；命中任一即满分，全不中零分（不做模糊/相似度）。归一化与比对规则
   落 AnswerNormalizer 与 FillBlankGradingStrategy，判分核心（ExamGradingService 等）零改动。
4. **主观/客观分流**：填空走客观链（自动判分），不进主观批改工作台；客观/主观分流的
   判定机制由执行侧现场核证（枚举硬编码/策略自声明/其他），所有分流点同批扩展——
   发现分流点分散超出单一判定处时，逐处扩展并逐一登记，不静默漏改。
5. **作答形态**：answers JSON 中 questionId→答案值为文本串（与简答同构，提交链路无新分支）；
   执行侧现场核证提交侧是否存在按题型的答案格式校验链，有则同批补 FILL_BLANK 分支。
6. **前端**：题目编辑表单加「填空」类型与可接受答案动态列表录入；作答页渲染单行/多行
   文本输入（与简答区分：填空输入框不展示参考答案）；回顾页按题型正确展示。错题本/
   逐题回顾/导出路径若按题型有渲染分支，同批覆盖。
7. **契约**：openapi.yaml 同步（type 枚举值 5、题目创建/响应 DTO 描述）；OpenApiContractTest
   契约重新导出按既有离线路径 A 流程；`gate-baseline.json` 用例数同批只增不减。

## 红线（零触碰）

- 判分核心既有方法体（ExamGradingService/ObjectiveGradingService/SubjectiveGradingService
  既有方法零改动——新策略只经注册表接入；若分流机制要求在既有类加 FILL_BLANK 分派行，
  允许最小分派行插入，但不得改动既有题型的行为路径）。
- schema.sql 仅注释行更新（零结构变更）；既有四个题型的判分/归一化/渲染行为逐字零回归。
- com.exam.clazz 包、ScoreService/MakeupScoreService、auth/security、pom.xml 零触碰
  （新增测试依赖走既有栈，不引新框架）。
- spec/specs/ 既有基线零修改（spec-delta 只在 changes 目录）。

## 验收边界（三案串行的第一案）

- 全新链路可用：建填空题（多可接受答案）→ 手动组卷/随机抽题含填空 → 学生作答 →
  自动判分命中各口径（精确/忽略大小写/未命中）→ 成绩与逐题回顾正确展示。
- 双端门禁全绿且用例数净增；变异自证≥3 项（如：撤忽略大小写→英文大小写不同判零分→红；
  撤多答案任一命中→只认第一个答案→红；撤 trim→带空白答案误判错→红）。
- 停止条件：分流机制核证发现填空无法走客观链的架构障碍；答案编码与既有归一化约定冲突；
  任一红线需破坏才能实现——立即停止上报，不硬凑。
