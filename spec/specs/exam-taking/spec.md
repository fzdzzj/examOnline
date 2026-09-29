# exam-taking 规范

> 能力域：在线考试与交卷（阶段 5，W4-W6）。
> 来源：`spec/changes/archive/add-exam-taking` 合入（进入考试、答题导航与采集、交卷幂等、交卷削峰落库、超时交卷、自动保存与断线恢复）+ `spec/changes/archive/add-mq-trace-and-capacity` 合入（交卷落库容量与时延）+ `spec/changes/archive/fix-my-exams-list-scope` 合入（学生考试列表取数范围、分组与排序、上限）。
> 验收遗留：交卷链路 JMeter 5000 并发压测与硬指标验收（P99 < 2s / 0 丢单 / 批量落库 < 30s）尚未完成，见 `tasks.json` task 8。

## Requirements

### Requirement: 进入考试

WHEN 学生进入考试,

系统 SHALL 校验考试处于进行中，生成个人快照锁定题目与顺序，并 SHALL 以点击开始时间启动个人倒计时。

#### Scenario: 进入考试成功

GIVEN 考试处于进行中

AND 学生尚未开始该考试

WHEN 学生点击进入并开始

THEN 系统生成个人快照（锁定题目与选项顺序）

AND 记录 start_time 启动个人倒计时

#### Scenario: 刷新不换题

GIVEN 学生已进入考试并锁定快照

WHEN 学生刷新或重新进入

THEN 系统返回与首次一致的题目与顺序

AND 不重新抽题

#### Scenario: 非进行中禁止进入

GIVEN 考试未开始或已结束

WHEN 学生尝试进入考试

THEN 系统拒绝进入并返回对应错误

---

### Requirement: 答题导航与采集

WHEN 学生作答,

系统 SHALL 提供题目导航（已答/未答/标记），并 SHALL 采集切屏/失焦事件落行为日志且不强制交卷。

#### Scenario: 导航状态标识

GIVEN 学生已答部分题目

WHEN 学生查看导航面板

THEN 系统以三色标识已答/未答/标记状态

AND 支持跳转

#### Scenario: 切屏仅记录

GIVEN 学生考试中切出页面

WHEN 系统检测到切屏/失焦事件

THEN 记录行为日志

AND 不强制交卷

---

### Requirement: 交卷幂等

WHEN 学生交卷,

系统 SHALL 以三重幂等（防重表 + 唯一索引 + 分布式锁）保证同一考试同一学生只提交一次，重复提交 SHALL 返回首次结果。

#### Scenario: 首次交卷成功

GIVEN 学生答卷处于进行中

WHEN 学生提交交卷

THEN 答卷状态迁至已交卷

AND 答案落库

#### Scenario: 重复交卷幂等

GIVEN 答卷已交卷

WHEN 学生再次提交交卷请求

THEN 系统返回首次提交结果

AND 不产生第二条记录

---

### Requirement: 交卷削峰落库

WHEN 交卷发生,

系统 SHALL 经消息队列异步批量落库，并在失败时重试，重复投递 SHALL 不重复入库。

#### Scenario: 消息可靠落库

GIVEN 学生提交交卷消息

WHEN 消费者处理

THEN 批量写入答卷

AND 落库成功后才确认消息

#### Scenario: 重复投递幂等

GIVEN 相同交卷消息被重复投递

WHEN 消费者处理

THEN 唯一索引拒绝第二次写入

AND 业务仅执行一次

#### Scenario: 失败进死信

GIVEN 消费失败达到重试阈值

WHEN 消费者无法处理

THEN 消息进入死信队列

AND 可人工排查

---

### Requirement: 超时交卷

WHEN 个人倒计时归零或系统检测到超时,

系统 SHALL 强制交卷，且 SHALL 与手动交卷共享锁保证只提交一次。

#### Scenario: 前端归零强制提交

GIVEN 学生答卷倒计时归零

WHEN 前端触发自动提交

THEN 答卷被强制交卷

#### Scenario: 后端兜底

GIVEN 学生超时未交卷且前端未提交

WHEN 后端定时扫描发现超时未交卷答卷

THEN 后端自动强制交卷（服务端时间为准）

#### Scenario: 三路竞态仅一次

GIVEN 手动交卷、前端归零、后端兜底同时发生

WHEN 三者竞争提交

THEN 仅一个成功

AND 其余幂等返回

---

### Requirement: 自动保存与断线恢复

WHEN 学生作答,

系统 SHALL 每 30 秒自动保存答案；断线重连 SHALL 恢复已保存答案，冲突 SHALL 以最新版本为准。

#### Scenario: 自动保存

GIVEN 学生处于考试中

WHEN 到达 30 秒周期

THEN 系统保存当前答案到草稿

#### Scenario: 断线恢复

GIVEN 学生断线后重新进入考试

WHEN 系统检测到草稿

THEN 恢复已保存的答案

#### Scenario: 多端冲突以最新为准

GIVEN 存在不同版本的答案草稿

WHEN 系统合并

THEN 以版本号与时间戳最新者为准

AND 记录冲突日志

---

### Requirement: 交卷落库容量与时延

WHEN 高并发交卷（如 5000 人同时交卷）,

系统 SHALL 使消费并发与批量参数**真实生效且可通过配置调整**，并 SHALL 提供可复算的容量模型以支撑落库时延目标，且 SHALL 有真跑压测证据支撑容量与时延结论。

#### Scenario: 消费参数真实生效

GIVEN 配置了消费并发与单批大小

WHEN 交卷监听容器启动

THEN 容器实际并发等于配置值

AND 不因手工构造容器工厂而退回框架默认值

#### Scenario: 容量可估算

GIVEN 已知单批落库耗时

WHEN 估算落库时延

THEN 按「吞吐 ≈ 并发 × 单批大小 / 单批耗时」推算积压与时效

AND 调参结论包含与数据库连接池上限的联动约束

#### Scenario: 压测硬指标真跑且如实判定

GIVEN 5000 并发交卷压测在真 dev 实例执行

WHEN 汇总指标

THEN 每项硬指标（提交 P99 < 2s、0 丢单、批量落库完成 < 30s）按该次真跑输出**如实判定并登记**

AND 不达标项 SHALL 输出瓶颈定位与后续判据（2026-09-22 首轮实测：0 丢单与落库 < 30s 达标，提交 P99 四轮 2088–3700ms 未达标，绑定约束为 Tomcat 线程上限 200，见 `docs/submit-loadtest-report.md`）

AND 不得为凑指标改口径、改测试或只报最好的一轮

AND 证据含实际执行的命令、该次原始输出与当时短 revision

#### Scenario: 压测资产可复现

GIVEN 需要复跑压测

WHEN 使用入库的压测资产

THEN 可在真 dev 环境重建同场景

AND 数据构造可重复执行

#### Scenario: 容量模型数值以实测定稿

GIVEN 容量模型参数与压测实测并存

WHEN 发布容量结论

THEN 数值以真跑结果定稿

AND 不得以模型估算冒充实测

---

### Requirement: 学生考试列表

学生查询考试列表时，系统 SHALL 只返回「我的考试」：已发布、未删除，且满足以下归属渠道之一——学生本人在该考试有答卷；或该考试（非补考）绑定于学生当前所在班级；或学生本人是该补考的名单内候选人。系统 SHALL NOT 以系统级全局窗口（含与本人无归属关系的考试）作为列表的取数范围。

系统 SHALL 以固定上限对「我的考试」集合截断（上限值唯一出处为代码常数）；截断 SHALL 发生在按本 Requirement 排序之后，SHALL NOT 作用于全局考试集合。

系统 SHALL 按状态分组组织列表：待考 → 进行中 → 已完成；组内按开始时间距当前时刻近→远（待考组先考的先显示；进行中/已完成组最近开始的先显示）。分组口径 SHALL 保持以「学生下一步动作」为准（三态判定与可进入性语义不变）；未发布考试 SHALL 不可见。

#### Scenario: 系统已发布考试超过上限

GIVEN 学生本人的一场考试已被指派（班级绑定或有答卷）

AND 系统内与他无归属关系的已发布考试超过列表上限

WHEN 学生查询我的考试列表

THEN 他本人的该场考试仍在列表中

AND 与他无归属关系的考试不出现在列表中

#### Scenario: 归属渠道齐备

GIVEN 学生有答卷的考试、其当前班级绑定的普通考试、本人为名单内候选人的补考

WHEN 学生查询我的考试列表

THEN 三类考试均出现在列表中

#### Scenario: 补考仅对名单内学生展示

GIVEN 一场补考有指定名单

WHEN 名单内与名单外的学生分别查询我的考试列表

THEN 名单内学生可见该补考

AND 名单外学生不可见

#### Scenario: 分组优先级与组内顺序

GIVEN 学生同时存在 待考 / 进行中 / 已完成 三组考试

WHEN 学生查询我的考试列表

THEN 列表先列待考、再进行中、最后已完成

AND 组内按开始时间距当前时刻近→远排序

#### Scenario: 无归属渠道的考试不可见

GIVEN 一场已发布考试既无班级绑定、学生本人也无其答卷、且不是其名单内候选人

WHEN 学生查询我的考试列表

THEN 该考试不出现
