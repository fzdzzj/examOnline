# agent-harness spec-delta：CI 门禁机检化（add-ci-gate-pipeline）

## ADDED Requirement: CI 门禁机检化

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
