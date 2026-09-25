## ADDED Requirements

### Requirement: 后端验证只有一条仓库自有的门禁命令

WHEN 需要验证一次后端改动是否通过,
系统 SHALL 提供一条由仓库自有、且不依赖仓库外机器路径的门禁命令，并且项目内所有文档只引用这一条命令。

#### Scenario: 按文档验证自己的改动

GIVEN 一个 agent 完成了对 `src/main` 的一次修改
WHEN 它按项目文档给出的唯一门禁命令执行验证
THEN 命令来自仓库自身（wrapper 或仓库内配置），不需要任何仓库外的安装目录路径
AND 命令的语义与判据在同一出处说明

#### Scenario: 文档中出现第二种命令说法

GIVEN 有人在文档或提示词中写入另一条与之互斥的门禁命令
WHEN 执行自查（对 `spec/`、`docs/`、`README.md` 检索门禁命令出处，结果应只指向同一条）
THEN 第二个出处判定为违反本需求
AND 整改方式是改为引用，而不是把两处的数字或参数对齐

#### Scenario: 命令在当前机器上跑不通

GIVEN 一台缺少离线依赖库或 wrapper distribution 的机器
WHEN 按唯一门禁命令执行并失败
THEN 失败信息必须指明缺失的前置条件
AND 文档如实记录该前置，不得为了"看起来能跑"而回抄旧的机器绑定命令，也不得承诺任何机器零准备可跑

### Requirement: 验收结论必须绑定产生它的执行

WHEN 一次交付声明"全绿"或"通过",
系统 SHALL 记录产生该结论的命令、该次真实输出数值与当时的 revision；缺少任一要素时该声明视为未验收。

#### Scenario: 阶段收尾时的回归结论

GIVEN 一个阶段即将进入验收
WHEN 记录回归结论
THEN 记录包含命令、该次 `Tests run / Failures / Errors / Skipped` 数值与短 revision
AND `Skipped: 1` 被说明为契约导出开关所致，而非被禁用的断言

#### Scenario: 用例总数下降

GIVEN 收尾记录的用例总数少于本阶段开工时记录的数值
WHEN 按判据比较
THEN 该阶段判定为不通过并停下回报
AND 修复方式只能是补回或删除得动的用例说明，不得通过减少用例使数字吻合

#### Scenario: 残留产物给出的数字不可信

GIVEN 上一次未执行 clean 而留下了旧的测试报告产物
WHEN 汇总测试总数
THEN 结论必须以一次带 clean 的执行为准
AND 由残留产物拼出的数值不得被写进任何验收记录

### Requirement: 声明的门禁必须能够失败

WHERE 仓库声明了某个校验脚本作为门禁,
该脚本 SHALL 在其任一被包含的检查失败时以非零退出码结束，并且存在明确的调用者或是文档化的执行判据。

#### Scenario: 前端类型检查失败

GIVEN `frontend/package.json` 的 `type-check:check` 串联 app 与 config 两个类型检查
WHEN app 侧 `vue-tsc --noEmit` 报告错误
THEN `type-check:check` 以非零退出码结束
AND 把它当作门禁的阶段提示词能够据此停下回报，而不是拿到通过信号

#### Scenario: 门禁没有调用者

GIVEN 一个声明为门禁的脚本没有任何 tracked 的调用者，也不在文档化的执行判据里
WHEN 审查该门禁的实际效力
THEN 它必须被要么纳入判据、要么删除
AND 不允许保留为"看起来像门禁、实际不产生后果"的脚本

#### Scenario: 变异验证确认门禁可失败

GIVEN 门禁修复完成
WHEN 人为注入一个会被该门禁覆盖的失败（如一个类型错误）并重跑门禁
THEN 门禁以非零退出码结束
AND 撤销注入后门禁恢复为通过，且工作树干净

### Requirement: 构建配置的机器绑定不得进入版本库

WHEN 构建或测试配置文件被纳入版本控制,
其内容 SHALL 不包含指向仓库外机器路径的绝对路径，且其所引用的本地产物或缓存的存在条件被明确记录。

#### Scenario: 新克隆解析构建配置

GIVEN 一份全新的 checkout，不含本机历史缓存目录
WHEN 按仓库自有命令执行构建配置解析
THEN settings 与本地仓库位置都能在仓库内解析或被环境变量显式覆盖
AND 在仓库根与非根子目录两种工作目录下执行结果一致

#### Scenario: 缓存目录不存在时

GIVEN 配置所依赖的本地依赖缓存在该机器上不存在
WHEN 执行门禁命令
THEN 得到的是可理解的前置缺失提示（并说明如何获得依赖）
AND 不是一串指向不存在文件的编译或解析错误

#### Scenario: 缓存迁移不得造成静默翻倍

GIVEN 调整本地依赖仓库的位置
WHEN 实施该调整
THEN 保留原缓存目录且不改写其内容，回滚路径被记录
AND 删除任何缓存目录需要单独授权，不属于本能力域的默认动作
