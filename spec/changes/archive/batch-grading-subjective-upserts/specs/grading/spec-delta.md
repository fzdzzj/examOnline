# 规范差异：grading（整场判分主观行一次取出）

## MODIFIED Requirements

### Requirement: 简答批改

WHEN 系统判简答题,

系统 SHALL 以关键词初判提供提示分，最终分数 SHALL 由教师人工批改确定，且 SHALL 保留批改痕迹。整场判分时，已有主观批改行 SHALL 按本场答卷一次取出，而不是每份答卷每道简答各查一次；重判 SHALL NOT 覆盖教师终分、评语与 version。

#### Scenario: 教师批改留痕

GIVEN 教师批改一道简答题

WHEN 教师提交分数与评语

THEN 系统保存分数、评语、批改人与批改时间

#### Scenario: 并发批改防覆盖

GIVEN 两教师同时批改同一答卷

WHEN 乐观锁执行

THEN 仅一个成功

AND 另一个冲突重载或报错

#### Scenario: 整场判分主观行一次取出

GIVEN 同一场有多份已交卷答卷且卷面含多道简答题

WHEN 教师触发整场判分

THEN 这些答卷的已有主观批改行在进入逐份写入之前一次取出

AND 刷新初判提示分时不覆盖已有终分、评语与 version

AND 一份答卷判分失败仍只标记该份，不影响其他答卷
