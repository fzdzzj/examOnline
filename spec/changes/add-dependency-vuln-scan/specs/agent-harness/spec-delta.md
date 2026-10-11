# agent-harness spec-delta：依赖漏洞扫描非阻断报告（add-dependency-vuln-scan）

## ADDED Requirement: 依赖漏洞扫描非阻断报告

WHEN 代码被 push 到远端 main 或 CI 触发分支（feature/**）、或有人对本仓库发起 pull request,
系统 SHALL 在既有双门禁 job 之外并行运行一个依赖漏洞扫描 job，以双栈（Maven 坐标 + 前端 lockfile）产出漏洞报告并作为 CI artifact 留痕；扫描发现漏洞 SHALL NOT 使该 job 或流水线变红（非阻断报告），扫描器执行失败 SHALL 按 job 失败传导变红（不得静默）。

#### Scenario: 三 job 并行且门禁语义零回归

GIVEN 一次触发 CI 的 push（main / feature/** / pull_request）
WHEN 流水线运行
THEN backend 与 frontend 两门禁 job 的定义、步骤与 gate-baseline 断言与本案之前完全一致（零改动）
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
AND Trivy action 以 pinned 稳定 tag 引用（禁 floating tag），对齐 agent-harness spec「构建配置的机器绑定不得进入版本库」与 CI 既有零秘钥口径
