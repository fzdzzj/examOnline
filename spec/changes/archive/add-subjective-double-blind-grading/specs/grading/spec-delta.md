# 规范差异：grading 域增加简答题双评盲评与分差仲裁工作流

> **能力域**：`grading`  
> **对应变更**：`add-subjective-double-blind-grading`

---

## ADDED Scenarios

### Requirement: 主观题双评盲评与分差仲裁

系统必须在既有单评主观题批改机制之上，支持背靠背匿名双评盲评、分差自动计算与超差仲裁工作流，仲裁或裁决终分回填至权威主观分记录。

#### Scenario: 盲评一评与二评背靠背独立评分
- **GIVEN** 考试已结束且客观判分已运行，简答题批改行已就绪
- **WHEN** 教师 A 调用 `POST /api/exams/{examId}/grading/subjective/blind-submit` 对某答卷的简答题提交打分
- **THEN** 系统记录该教师的一评（`FIRST`）评分与评语，此时终分 `subjective_grades.score` 仍保持为 NULL
- **AND** 随后教师 B 调用同一端点提交二评（`SECOND`）评分，系统校验教师 B 与教师 A 不为同一人（若为同一人则抛出 `BAD_REQUEST` 400 阻断）
- **AND** 在二评提交前，系统查询接口不向教师 B 泄露教师 A 的评分与评语，且考生身份打码脱敏

#### Scenario: 分差容差范围内自动裁决均分
- **GIVEN** 某道简答题已有一评得分 $S_1$ 与二评得分 $S_2$，且考试设定的分差容差阈值为 $T$（缺省 2.0 分）
- **WHEN** 教师 B 提交二评后，分差 $|S_1 - S_2| \le T$
- **THEN** 系统自动裁决终分 $S_{final} = \text{round}((S_1 + S_2) / 2, 1)$
- **AND** 系统自动将 $S_{final}$ 原子回填至 `subjective_grades.score`，刷新答卷主观总分
- **AND** 批改状态标记为 `RESOLVED`（已裁决）

#### Scenario: 分差超标自动流转仲裁队列
- **GIVEN** 某道简答题一评得分 $S_1$ 与二评得分 $S_2$ 的分差 $|S_1 - S_2| > T$
- **WHEN** 二评提交入库后
- **THEN** 系统判定分差超标，状态流转为 `ARBITRATION_PENDING`（待仲裁）
- **AND** `subjective_grades.score` 继续保持为 NULL，避免未达成共识的主观分计入答卷总分
- **AND** 该题自动出现在待仲裁清单接口（`GET /api/exams/{examId}/grading/subjective/arbitration-list`）中

#### Scenario: 仲裁终审定分与终分回填
- **GIVEN** 某题处于待仲裁状态（`ARBITRATION_PENDING`）
- **WHEN** 仲裁员（拥有管理权限或教研组长）调用 `POST /api/exams/{examId}/grading/subjective/arbitrate` 提交仲裁终审打分 $S_3$
- **THEN** 系统记录仲裁评卷记录（`ARBITRATION`），并将终分强制设定为 $S_3$
- **AND** 原子回填 `subjective_grades.score`，刷新答卷主观总分，状态更新为 `ARBITRATED`（已仲裁）
- **AND** 仲裁员提交的分数不得超过该题目满分，否则抛出 `BAD_REQUEST` 异常阻断
