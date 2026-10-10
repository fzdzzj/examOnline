# score-management 规范增量（add-class-leaderboard）

## 变更概述

新增学生视角「班级匿名榜单」：已发布考试下，学生可查全班前 10 名单（姓名脱敏、不下发他人 ID）与本人位置；口径与教师榜单（publishPreview）同源，发布门控与 myScore 一致。教师端不做（既有实名全榜覆盖）。

---

## 新增的 Requirement

### Requirement: 班级匿名榜单

WHEN 学生查询已发布考试的班级榜单,

系统 SHALL 返回按总分降序的前 10 名（并列同名次跳号，与竞赛排名口径逐条一致）与本人位置；他人姓名 SHALL 脱敏展示（保留姓氏）且 SHALL NOT 下发他人学生标识；榜单 SHALL 仅在成绩已发布且本人可查到成绩时可见；取数 SHALL 固定为前 10 行 + 本人行，行级取数条数 SHALL NOT 随班级人数增长。

#### Scenario: 已发布考试返回匿名前 10

GIVEN 考试成绩已发布且学生为该场考生

WHEN 学生查询班级榜单

THEN 返回总分降序前 10（含名次、脱敏姓名如「张**」、总分）

AND 并列同分同名次、后续名次跳号（与 publishPreview 口径逐条一致）

#### Scenario: 本人位置的两种形态

GIVEN 学生本人已汇总成绩

WHEN 本人名次在前 10 内

THEN 榜单中本人行实名且携带 isMe 标记

WHEN 本人名次不在前 10

THEN 榜单外单独返回本人行（名次 + 总分 + isMe），不混入前 10 截断

#### Scenario: 未发布或无本人成绩不可见

GIVEN 考试成绩未发布，或本人无成绩记录（未交卷 / 未汇总 / 状态非已批改）

WHEN 学生查询班级榜单

THEN 与 myScore 同 404 语义返回「暂无本人成绩记录」

AND 不泄露榜单任何行

#### Scenario: 非本班考生不可见

GIVEN 学生不是该场考试的考生

WHEN 查询该考试班级榜单

THEN 拒绝访问（与 myScore 同准入判定）

#### Scenario: 匿名不泄露他人标识

GIVEN 榜单返回

THEN 每个非本人行仅含名次、脱敏姓名、总分

AND 不含他人 studentId / 学号等可定位标识
