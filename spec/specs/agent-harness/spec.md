# agent-harness 规范

> 能力域：面向 agent 的项目事实路由与文档判据（工程性变更 E1，2026-09-21）。
> 来源：`spec/changes/archive/add-agent-context-routing`（E1）与 `spec/changes/archive/update-agent-gate-single-source`（E2）合入。
> 实施注记：
> - `README.md` 由"阶段 1 快照"改为路由表：删除端口/口令默认值/只列两包的工程结构/里程碑四类副本，全文不含连接事实字面值；dev 默认值指向 `src/main/resources/application-dev.yml`，进行中阶段指向 `spec/README.md` 的「当前状态」表。
> - 新增仓库根 `AGENTS.md`：4 条硬约定按「一句话规则 + canonical 出处 + 可机械执行的自查命令」三段式承载，禁忌清单只给指针（`docs/指导Agent交接文档.md` 的对应小节），不复制正文。**该文件确被 provider 注入每次 agent 会话**——"agent 必经索引"这条路在本环境实测成立。
> - 新增 `docker/mysql/migrations/README.md`：首段即"放进本目录 ≠ 变更已生效"，附 compose 实际挂载的两个 init 目录、`audit_log` 在 dev 缺失且异常被 `catch` 吞掉的先例、`idx_sweep_candidate` ≡ 既有 `idx_submissions_sweep` 的反例、存量库手工应用的顺序与验证、"新库无需执行"边界。**该目录既有 `.sql` 零改动**。
> - `spec/README.md` 三处硬编码计数（进行中数、归档数、能力地图条数）改为可复算语句；工作流「收尾五步」补第 7 步（收尾时同步 `AGENTS.md` 指针，只加指针不加正文）。
> - **自查命令里的字符类方括号是设计的一部分**：`330[6]`、`[T]ests run`、`root/[r]oot` 这类写法用于避免"判据命令自身含被禁字面量 → 永久命中自己那一行 → 判据永红等于没有判据"。把方括号"修正"回裸字面量会使该条自查失效。怀疑判据是装饰性的，按变异验证纪律走：注入违规 → 确认变红 → 撤销 → 确认变绿（E1 对三条自查各做过一次）。
> - E2 `update-agent-gate-single-source` 已收口：唯一后端门禁命令、验收绑定当次执行、声明的门禁能够失败、构建配置不绑定机器路径四条已由归档 spec-delta 合入；首次独立新克隆门禁暴露存量测试取号碰撞，另由 `8bd04c2` 修复后重新独立验收。

## Requirements

### Requirement: 根入口只路由不承载易变事实

WHEN agent 或新成员从仓库根开始了解项目,
系统 SHALL 通过 `README.md` 获得指向 canonical 真源的入口指针，且 `README.md` 不承载端口、口令、工程结构、里程碑等会随提交过期的事实副本。

#### Scenario: 查询 dev 数据库连接默认值

GIVEN 一个只读过 `README.md` 的 agent 需要知道 dev 环境连哪个 MySQL 地址与口令
WHEN 它按 `README.md` 的入口指针跳转
THEN 它被指向 `src/main/resources/application-dev.yml`
AND `README.md` 内不含该端口、口令或连接串的字面值
AND 它不需要在同一文件里判断"哪个说法是新的"

#### Scenario: 环境默认值变更后根入口无需跟改

GIVEN `application-dev.yml` 的默认端口或口令发生变更并已提交
WHEN 该变更完成
THEN `README.md` 无需同步修改即保持正确
AND 该次变更的 diff 不含 `README.md`（`git show --stat` 中不出现该文件）

#### Scenario: 有人把事实抄回根入口

GIVEN 贡献者向 `README.md` 添加了具体端口号或口令默认值
WHEN 执行本能力域约定的自查 `grep -nE '3306|13316|13317|root123|root/root' README.md`
THEN 命中数大于 0 即判定违反本需求
AND 评审要求把该值改回"以某文件为准"的指针形式

### Requirement: 硬约定必须有 agent 必经的索引入口

WHEN 一条约定足以让改动在任何环境必然失败或造成禁止性后果,
系统 SHALL 在仓库根 `AGENTS.md` 提供该约定的一句话表述、其 canonical 出处指针、以及可机械执行的自查命令。

#### Scenario: 变更涉及建表或改表

GIVEN 一个 agent 准备新增一列或一张表
WHEN 它读取仓库根 `AGENTS.md`
THEN 它读到"本项目未接 Flyway，建表唯一入口是 `src/main/resources/schema.sql`"及其出处（`docs/需求决策记录.md` 中标题含「未接 Flyway」的那条决策）
AND 它读到存量库手工脚本的位置及其不自动执行的限制

#### Scenario: 集成测试需要建表数据

GIVEN agent 想让集成测试在自有表上运行
WHEN 它按 `AGENTS.md` 的自查命令执行 `grep -rn '@Sql' src/test`
THEN 任何 `@Sql` **注解**命中都判定为违反硬约定
AND 复述本规矩的注释行不计为违规（`src/test/java/com/exam/monitoring/retention/DataRetentionIntegrationTest.java` 类注释中即有一处，属合规；自查须能区分注释与注解）

#### Scenario: 索引本身不得复制易变事实

GIVEN `AGENTS.md` 需要提及一条会漂移的事实
WHEN 撰写该条目
THEN 条目只写规则与指向 canonical 文件的指针
AND 不复制端口、口令、测试用例数、覆盖率、表清单等数值
AND 自查 `grep -nE '13316|13317|root123|Tests run' AGENTS.md` 命中为 0

### Requirement: 无自动执行者的 SQL 目录必须自述触发方式

WHERE 仓库某目录内的 SQL 脚本不会被任何启动或测试路径自动执行,
该目录 SHALL 提供一份说明，写明其执行者（手工）、触发时机、不执行时的后果，以及一个仓库内已发生过的真实先例。

#### Scenario: agent 在 migrations 目录前决策

GIVEN `docker/mysql/migrations/` 下有一批 `2026-WNN-add-*.sql`，而 `docker-compose.yml` 只挂载 `master/init` 与 `slave/init`
WHEN 一个 agent 考虑"在此目录添加脚本来完成 schema 变更"
THEN 目录第一份说明让它立即得知：放进该目录不等于变更已生效，dev 与存量库不会因此自动补表
AND 说明指向 `audit_log` 在 dev 库缺失、异常被 catch 静默吞掉的先例
AND 说明要求"先查 `schema.sql` 是否已有等价对象，再决定是否写脚本"

#### Scenario: 存量库确需手工脚本

GIVEN 一个已存在的库需要一次 `CREATE TABLE IF NOT EXISTS` 无法覆盖的变更
WHEN 按目录说明手工应用脚本
THEN 说明给出应用顺序、执行后的验证方式，以及"新库无需执行本脚本"的边界
AND 目录内既有脚本文件未被本次说明的撰写所修改

#### Scenario: 测试环境看不见该类问题

GIVEN H2 测试库每轮由 `schema.sql` 重建
WHEN 存量库因缺表导致某条写库路径异常
THEN 说明明确指出这类缺口在集成测试中不可见，必须在 dev 真库上单独核对

### Requirement: 文档判据优先于文档常量

WHEN 项目级文档需要引用一次执行的输出（测试计数、覆盖率、健康检查结果等）,
文档 SHALL 引用产生该数值的命令与产物位置；如必须记录历史数值，须与产生它的 revision 一并记录。

**范围界定**：本需求只约束**文档表述**（治理文档、规范、提示词里怎么写）。单次交付的验收记录要素由 `update-agent-gate-single-source` 的「验收结论必须绑定产生它的执行」约束，两条需求各管一层，不得以其中一条的完成冒充另一条。

#### Scenario: 撰写阶段验收判据

GIVEN 一份阶段提示词需要规定"回归不得变差"
WHEN 撰写该判据
THEN 判据写成"执行唯一门禁命令后 Failures=0 且 Errors=0，用例总数不得少于开工时实测记录的数值"
AND 不写"必须仍是 NNN 全绿"这类需要人在文档顶部加更正段才能自洽的常量

#### Scenario: 发现残留的无 revision 常量

GIVEN 治理文档中出现未绑定 revision 的数值常量
WHEN 执行自查 `grep -rn '基线' spec/README.md docs/`
THEN 命中项必须全部是"判据"或"带 revision 的历史记录"
AND 其余命中判定为需要整改，整改方式是把常量换成判据

#### Scenario: 记录历史数值

GIVEN 需要留档某次验收的测试数
WHEN 写入文档
THEN 同一行记录产生该数值的命令与短 revision
AND 后续读者知道该数值描述的是哪一次执行，而非当前状态

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

### Requirement: CI 门禁机检化

WHEN 代码被 push 到远端 main 或 CI 触发分支（feature/**）、或有人对本仓库发起 pull request,
系统 SHALL 通过 GitHub Actions 流水线自动复跑双端门禁（后端仓库根 `./mvnw clean test` 与前端 lint / type-check / test 三门禁），并以入库基线文件机检「用例总数只增不减」与「契约路径数下限」，任一断言失败以非零退出结束对应 job。

#### Scenario: push 到 main 后自动双端门禁

GIVEN 一个已推送的 main 提交（本地门禁已在合并前跑过）
WHEN GitHub Actions 对该 push 触发
THEN backend job 在 ubuntu runner 上以 temurin 17 执行仓库根 `./mvnw clean test`（Redis 依赖由 redis:7 service container 提供，不指向任何私有主机）
AND frontend job 以 packageManager 字段固定的 pnpm 执行 `lint:check` / `type-check:check` / `test` 三门禁
AND CI 是机检化复核与第二防线，不改变本地唯一门禁命令的地位——本地验收结论的 canonical 判据仍在 agent-harness spec 既有 Requirement

#### Scenario: 用例总数低于基线即红

GIVEN 基线文件 `.github/ci/gate-baseline.json` 登记了后端 surefire 与前端 vitest 的用例数下限（数值由登记它的收口笔绑定当次实测与 revision）
WHEN 一次 CI 运行解析出的用例总数低于对应下限
THEN 解析断言以非零退出结束对应 job
AND 后续变更的收口笔须同步上调基线（只增不减）；下调基线使数字吻合按「用例总数下降」同罪处理

#### Scenario: 契约路径数低于基线即红

GIVEN 基线文件登记了仓库根 `openapi.yaml` 的 paths 键数下限
WHEN 一次 CI 运行计数出的 paths 数低于下限
THEN 解析断言以非零退出结束对应 job
AND 该断言是契约门槛的下防线，不替代 OpenApiContractTest 的既有契约校验

#### Scenario: banner 与非空断言防假绿

GIVEN CI 上的门禁输出经 tee 留档并交给解析断言脚本
WHEN 前端三门禁任一日志为空、或 type-check 日志缺失 vue-tsc 与 tsc 各自的真实 banner、或后端日志缺失 BUILD SUCCESS banner
THEN 解析断言以非零退出结束对应 job
AND 解析断言脚本自身的任一被包含检查失败时以非零退出码结束（对齐「声明的门禁必须能够失败」）

#### Scenario: CI 配置零机器绑定

GIVEN `.github/` 下的 workflow 与脚本被纳入版本控制
WHEN 审查其内容
THEN 不含指向仓库外机器的绝对路径（盘符等模式）与私有凭证
AND 外部依赖（Redis 等）以 service container 或 runner 自带工具域声明，不引用任何私有主机地址（对齐「构建配置的机器绑定不得进入版本库」）

#### Scenario: CI 门禁的验收绑定真实运行

GIVEN 一次变更声明「CI 门禁已建立」
WHEN 验收该声明
THEN 记录须包含至少一次全绿 run、一次基线抬高超限导致的红 run、以及复绿（或 main 绿）run 的三要素（run URL + 汇总数字 + 短 revision）
AND 以「workflow 已写」冒充「CI 已复验」按未验收处理
AND 红绿验证用注入分支（用后即删）承载，不污染 feature 分支与 main 历史

> 合入注记（2026-10-11，变更 `add-ci-gate-pipeline`，生产化 P1，台账候选 CI-only）：
> 「CI 门禁机检化」Requirement 合入（6 个 Scenario，文本与该卡 `specs/agent-harness/spec-delta.md` 逐字一致）。
> 实施边界：`.github/workflows/ci.yml` 双 job（backend＝temurin 17 + redis:7 service + `.m2-repo` cache +
> `./mvnw clean test` + surefire/paths 解析断言；frontend＝node 22 + `pnpm install --frozen-lockfile` +
> 三门禁串行 tee + passed/双 banner/非空断言）；`.github/ci/gate-baseline.json` 三下限 398/610/74 以开工基线
> K3/K4/K5 当次实测写入并绑定 e71a6fa；`.github/ci/` 三解析断言脚本任一检查失败非零退出；
> `frontend/package.json` 补 `packageManager` 字段定版 pnpm@10.25.0。机制选型一处如实登记：pnpm 定版用
> pnpm/action-setup@v4 读 `packageManager` 字段（proposal 字面的 corepack 机制替换——规避 corepack 对新版
> pnpm 签名密钥的已知校验风险），交付语义「packageManager 字段固定的 pnpm」不变。
> 验收边界＝GitHub Actions 真实 run 三要素：feature 首跑绿 run `38104764997`（headSha `eaec0e7`，双 job success，
> CI 端 surefire 398/0/0/1、paths 74、vitest 610）；probe 注入红 run `38105268913`（headSha `f292025`，基线抬至
> 399/611/75，测试本体仍绿而断言层红——Tests run=398 < 399、vitest passed=610 < 611；注入分支用后即删）；
> --no-ff 合并笔 `fc21d90` 后 main 绿 run `38105772968`。本地双端门禁在 `eaec0e7` 与 `fc21d90` 两次持平全绿
> （398/0/0/1 + BUILD SUCCESS；vitest 86 文件 610 例 ×3 退出码 0），CI 引入零业务代码改动。

### Requirement: 依赖漏洞扫描非阻断报告

WHEN 代码被 push 到远端 main 或 CI 触发分支（feature/**）、或有人对本仓库发起 pull request,
系统 SHALL 在既有双门禁 job 之外并行运行一个依赖漏洞扫描 job，以双栈（Maven 坐标 + 前端 lockfile）产出漏洞报告并作为 CI artifact 留痕；扫描发现漏洞 SHALL NOT 使该 job 或流水线变红（非阻断报告），扫描器执行失败 SHALL 按 job 失败传导变红（不得静默）。

#### Scenario: 三 job 并行且门禁语义零回归

GIVEN 一次触发 CI 的 push（main / feature/** / pull_request）
WHEN 流水线运行
THEN backend 与 frontend 两门禁 job 的定义、步骤与 gate-baseline 断言与该 Requirement 合入之前完全一致（零改动）
AND dependency-scan job 与两门禁 job 并行运行、独立判定，扫描 job 的红绿不改变两门禁 job 的判定语义

#### Scenario: 发现漏洞不阻断（非阻断的全部含义）

GIVEN 依赖扫描在 Maven 坐标或前端 lockfile 中发现任意数量的已知漏洞
WHEN 扫描步骤以 `exit-code: 0` 完成
THEN dependency-scan job 仍为绿
AND 报告如实列出全部发现（不因非阻断而截断或降级）
AND 非阻断语义仅此一处：扫描器自身执行失败（步骤非零退出）仍按 job 失败传导变红——扫描器坏了不得静默

#### Scenario: 双栈覆盖

GIVEN 仓库根 `pom.xml`（含 dependencyManagement 锁定坐标）与 `frontend/pnpm-lock.yaml`
WHEN Trivy filesystem 扫描运行
THEN 扫描范围同时覆盖 Java 依赖坐标与前端 lockfile 两栈
AND 扫描跳过 `.git`、`target`、`node_modules`、`.m2-repo` 等非依赖清单目录

#### Scenario: 报告留痕可核验

GIVEN 一次完成的 dependency-scan job
WHEN 验收者查看该 run
THEN 报告以 artifact 形式上传并可经 `gh run download`（或等效 API）下载且内容非空
AND 报告行数或漏洞计数被写入 job summary（数字在 run 页可见，不埋没于 artifact）

#### Scenario: 扫描 job 真实执行、非恒绿装饰

GIVEN 有人在 probe 注入分支向 dependency-scan job 注入必败步骤（如前置 `run: exit 1`）
WHEN 该分支的 CI 运行
THEN dependency-scan job 变红
AND 既有两门禁 job 照常独立判定（不受 scan job 变红影响）
AND probe 分支用后即删，不污染 feature 分支与 main 历史

#### Scenario: 扫描配置零秘钥零机器绑定

GIVEN `.github/workflows/ci.yml` 中的 dependency-scan job 定义被纳入版本控制
WHEN 审查其内容
THEN 零 `secrets` 引用、零私有凭证、零指向仓库外机器的绝对路径
AND Trivy action 以 pinned 稳定 tag 引用（禁 floating tag），对齐「构建配置的机器绑定不得进入版本库」与 CI 既有零秘钥口径

> 合入注记（2026-10-11，变更 `add-dependency-vuln-scan`，生产化 P4，台账候选依赖漏洞扫描入 CI）：
> 「依赖漏洞扫描非阻断报告」Requirement 合入（6 个 Scenario，名称与该卡 `specs/agent-harness/spec-delta.md` 逐字一致，正文按基线行文规范化）。
> 实施边界：`.github/workflows/ci.yml` 新增第三 job dependency-scan（checkout@v4 → trivy-action@v0.36.0 fs 扫描，scan-ref `.` /
> scanners vuln / format table / output trivy-report.txt / exit-code '0' / skip-dirs `.git,target,node_modules,frontend/node_modules,.m2-repo`
> → 报告 tee 落日志、报告行数与 distinct CVE 计数 echo 进 `$GITHUB_STEP_SUMMARY` → upload-artifact@v7.0.2 上传 trivy-dependency-report
> artifact，if-no-files-found: error）；非阻断语义写死：exit-code 0（首跑发现 9 项 CVE（pnpm 栈 5 + pom 栈 4）而 job 仍绿，
> 报告 76 行如实列出），扫描器执行失败仍按 job 失败传导变红；零 secrets/零盘符绝对路径/零机器绑定；触发与 concurrency 沿用
> workflow 级既有定义；`.github/ci/gate-baseline.json` 零改动（扫描不产生用例数与契约 paths 变化，合法持平）。
> 验收边界＝GitHub Actions 真实 run 三要素：feature 首跑绿 run `38123773442`（headSha `f143ec4`，三 job success，artifact 13,636 字节/
> 76 行非空，双栈覆盖 pnpm 5 + pom 4）；probe 注入红 run `38124945091`（headSha `8f00423`，前置必败步骤 `run: exit 1` → scan job 红
> 而两门禁 job 照常绿，注入分支用后即删）；--no-ff 合并笔 `515b79d` 后 main 绿 run `38125602056`。本地双端门禁在实施笔前后两次
> 持平全绿（398/0/0/1 + BUILD SUCCESS；vitest 86 文件 610 例 ×3 退出码 0），本案零业务代码改动。

> 合入注记（2026-10-12，`add-mutation-check-tool`）：ADDED「变异校验工具化」Requirement（生产化 T2）。
> 六 Scenario 名称与 `spec/changes/archive/add-mutation-check-tool/spec-delta.md` 逐字一致；纯脚本域（scripts/mutation-check.sh）零业务代码，
> 用例数零变化、gate-baseline.json 零改动合法持平；既有 Requirement 零改动。

---

### Requirement: 变异校验工具化

WHEN 变更涉及护栏、断言或自查的可信度需要以变异注入验证（注入违规 → 击红 → 复原 → 归零 → 回绿）,

系统 SHALL 提供统一的变异校验驱动脚本固化五步流程与历史坑防护，AND SHALL 使执行侧自证与复核侧独立复现共用同一工具与留证形态。

#### Scenario: 五步原子流程

GIVEN 一个注入目标文件与一条验证命令

WHEN 以参数化方式（目标/注入/验证/标识）调用驱动脚本

THEN 备份、注入、击红、复原归零、回绿五步依序执行且任一步失败即非零退出并指明失败步骤

AND 留证（备份、verify 输出、汇总报告）全部落于 target/ 之下

#### Scenario: 注入命中核验防静默失配

WHEN 注入表达式因行尾差异或模式不匹配而未生效

THEN 脚本以命中核验判「注入未生效」失败而非静默继续（CRLF 失配坑的机械化防护）

AND 不产生「以为验证了其实没注入」的假结论

#### Scenario: 击红断言防装饰性假绿

WHEN 注入生效后验证命令仍零退出（断言被守卫或双通兜底吞掉）

THEN 脚本判「假绿」失败非零退出

AND 该判败本身以实测样例留证（工具自测须含假绿样例）

#### Scenario: 复原子节级一致

WHEN 五步完成或任一步失败后

THEN 目标文件与备份以字节级比较工具核验一致（防行尾转换）

AND 注入标志确认不在场、工作树零残留

#### Scenario: 工具自身红绿自测

WHEN 维护者验证工具自身可信度

THEN 全绿样例、假绿判败样例、注入失配判败样例三者的实测结果可复现

AND 任一判败样例被工具放行即视为工具不可用

#### Scenario: 流程义务登记

WHEN 此后执行侧以变异自证护栏、或复核侧独立复现变异

THEN 优先以本工具驱动（注入点仍按变更定制），手工五步仅作兜底

AND 该义务登记于工作流纪律而非仅存于工具头注释

---

> 合入注记（2026-10-12，`polish-mutation-and-ci-tooling`）：ADDED「变异校验工具与 CI 解析脚本的留证与失败传播完善」Requirement（分级①收口）。
> 多 Scenario 名称与正文与 `spec/changes/archive/polish-mutation-and-ci-tooling/spec-delta.md` 逐字一致；纯脚本/文档域（scripts/mutation-check.sh、.github/ci/ 三脚本、spec/README.md）零业务代码，
> 用例数零变化、gate-baseline.json 零改动合法持平；既有 Requirement 零改动。

### Requirement: 变异校验工具与 CI 解析脚本的留证与失败传播完善

#### Scenario: 判败亦留证

WHEN mutation-check.sh 任一步骤判败退出（含注入失配、击红断言失败、复原不一致）

THEN 该轮 report.txt 已写入留证目录后以非零码退出——判败轮次与判胜轮次同规格留证

AND fail-closed 性质零变化（任何非测试失败的非零退出仍整体判败，不假绿放行）

#### Scenario: 留证目录可指定

WHEN 调用方以 --out-dir 指定留证目录

THEN 五步留证（备份、注入核验、击红输出、复原比对、回绿输出）落指定目录

AND 未指定时留证路径与既有行为逐字一致（仍落 target/mutation-check/，被 mvnw clean 清空的生命周期形态不变）

#### Scenario: 失败传播无噪音与文档形态

WHEN .github/ci/ 解析断言脚本遇到缺键或检查点失败

THEN 直接以非零码退出，不携带空基线或无效输入走到后续比较行产生告警噪音

AND 任一检查点失败必须非零退出的既有断言性质零变化

AND mutation-check.sh 文档记载 Windows 下 Git-Bash 驱动形态（WSL 不可挂盘时的实证可行路径）

---

> 合入注记（2026-10-14，optimize-agent-workflow-and-specs）：ADDED「规范台账冷热分离」「派工协议轻量化与分级流转」「评审产物与历史过程文档隔离」三项 Requirement。
> 正文与 spec/changes/optimize-agent-workflow-and-specs/spec-delta.md 逐字一致；纯文档与规范治理变更，零业务代码，用例数与门禁基线零改动合法持平。

### Requirement: 规范台账冷热分离

WHERE 规范系统的已归档变更条目随项目演进持续增长,
规范根入口 `spec/README.md` SHALL 仅保留进行中的变更、能力地图基线索引与近期归档速览，并将历史全量归档清单剥离至独立的冷存储文件 `spec/CHANGES_ARCHIVE.md`。

#### Scenario: Agent 读取 spec 根入口

GIVEN 任何新接手或日常工作的 Agent 读取 `spec/README.md` 获取当前项目状态
WHEN 它读取该文件
THEN 文件的文本体积不得超过 25 KB
AND 它能直接查阅当前正在进行的变更与各能力域的最新落点
AND 它无需在同一会话中被迫读入数十万字符的历史已归档变更文本

#### Scenario: 查阅历史已归档变更

GIVEN 用户或 Agent 需要溯源某个已归档历史变更的详细实施笔、复核依据与覆盖范围
WHEN 它查阅规范台账
THEN 它能通过 `spec/README.md` 中的指针跳转至 `spec/CHANGES_ARCHIVE.md`
AND `spec/CHANGES_ARCHIVE.md` 完整保留历史变更的全部归档元数据与实施结论

---

### Requirement: 派工协议轻量化与分级流转

WHEN 主 Agent 为子 Agent 编制任务提示词或规划执行流程,
系统 SHALL 依据改动范围实行分级流转，且单次派工提示词不得在会话正文中复制仓库既有规则的全文副本。

#### Scenario: S 级轻量任务一票到底

GIVEN 一个仅涉及文档整理、配置微调或改动少于 5 个文件且无破坏性业务逻辑的轻量任务（S 级）
WHEN 主 Agent 规划该任务
THEN 允许实施 Agent 在完成先红后绿自测与验证后直接合并收口
AND 无需强制拆分为实施、独立复核、独立收口三个独立的子 Agent 会话

#### Scenario: 派工提示词不得粘贴全量规则副本

GIVEN 主 Agent 向子 Agent 发出派工提示词
WHEN 派工卡成文
THEN 其字数限制在 300 字以内，仅声明目标变更路径、执行 step、受控白名单与关键验收断言
AND 不得在 Prompt 中整段复述 `AGENTS.md` 四条硬约定、变异五步法或八段式报告全文
AND 约束规则统一由子 Agent 直接读取版本库对应文件获取

---

### Requirement: 评审产物与历史过程文档隔离

WHERE 持续交付过程中产生独立复核判定文件（VERDICT）与历史过程性交接文档,
系统 SHALL 将其隔离至专用的评审归档目录，严禁长期散落于仓库根目录污染工作树。

#### Scenario: 评审 VERDICT 文件不得污染工作树根目录

GIVEN 复核子 Agent 或自动化流程生成 `review-*-VERDICT.md`
WHEN 评审产物落盘
THEN 文件应输出至 `_review/verdicts/` 或变更 evidence 目录下
AND 仓库根目录在 `git status` 下不得散落未跟踪的 VERDICT 判定文件

#### Scenario: 历史过程交接文档冷归档

GIVEN 过去某日期（如特定月份或开发冲刺）的过程性交接日志
WHEN 阶段已收口且结论已合入基线
THEN 该过程文档应归入 `docs/archive/historical-handover/`
AND 不得与当前的活动规范或执行指南在同一目录下并列，避免 Agent 误读过期结论

### Requirement: 前端单测超时预算固化

前端 vitest 单测超时预算 SHALL 在 `frontend/vitest.config.ts` 显式固化为 `testTimeout: 30000`，消除宿主 CPU 争用下默认 5s 超时的随机抖动（受害集合随机、单跑全过、双轮复核实测 `--testTimeout=30000` 稳定）；该改动 SHALL 不增删任何用例（用例数与既有基线一致）且不动 `include`/`setupFiles`/`restoreMocks`/`environment` 既有配置。

#### Scenario: 超时预算配置生效且用例集不变

GIVEN `frontend/vitest.config.ts` 的 `test` 块含 `testTimeout: 30000`，`include`/`setupFiles`/`restoreMocks`/`environment` 与既有配置逐项一致
WHEN 以默认命令运行前端单测（不加 `--testTimeout` 覆盖参数）
THEN 全量用例在宿主存在合理 CPU 争用时不再出现 `Test timed out in 5000ms` 型失败
AND 用例总数与变更前基线一致（配置改动不得增删用例）

> 合入注记（2026-10-19，`polish-observation-debts`，T 级打磨，**GO**）：`add-irt-adaptive-testing` 与 `add-score-chain-reconciliation` 两轮独立复核实测默认 5s 超时在宿主 CPU 争用（外部构建并存）下产生受害集合随机的超时误红（单跑全过、`--testTimeout=30000` 或 `--maxWorkers=4` 独占复跑稳定），判定为环境性而非回归；本案把 30000 固化进配置（净增 3 行：1 行配置 + 2 行注释），`include`/`setupFiles`/`restoreMocks`/`environment` 逐字未动，用例数 980 不变，backendTests 853→855 同笔仅调后端项。同案实施的另两件：reliability 域对账补例显性化（见该域合入注记）与 spec/README.md 归档速览瘦身（56 条→10 条 + 41 条 byte-identical 回填 CHANGES_ARCHIVE.md 冷存储——冷存储自 f1c42d8 后断供 5 天是速览膨胀根因，本案已补齐，后续归档须同笔回填冷存储）。

