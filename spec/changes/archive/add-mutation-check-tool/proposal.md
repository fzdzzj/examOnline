# 提案：变异校验脚本化（mutation-check 工具，生产化 T2，add-mutation-check-tool）

> 状态：已立项待执行。阶段 0 查证基于 main `176baeb`（2026-10-12）现场核：仓库无任何变异验证脚本/工具（`scripts/` 目录与全仓 grep 无 mutation 类工具）；T2 台账定性属实——离线重入案变异 B 装饰性断言假绿、榜单案执行侧数值碰巧一致，两案实证执行侧变异自报存在失真，复核必须独立复现而流程全手工（cp→注入→跑→还原→grep），token 成本高且踩过三次坑（CRLF 模式串失配、Git Bash→node 传参链反斜杠改写、$() 子壳 exit 不传播）。

## Why

- **真缺口（`176baeb` 现场核）**：变异验证（AGENTS.md「变异验证纪律」：注入违规→确认变红→撤销→确认变绿）是本仓库护栏可信度的根基动作，但每案都是手工流程——无工具、无统一留证形态、三次历史坑全靠人记（CRLF：工作树文件与 git show 管道输出行尾不同，sed/grep 模式串静默失配；传参链：命令行内嵌字符串做权威比对不可靠；假绿：verify 击红步骤若被装饰性断言吞掉即静默通过）。
- **两案实证失真**：执行侧变异自报不可信（P3 部署栈案复核曾以镜像真实质核证、split-score 案复核独立复现三变异）——工具化后执行与复核共用同一驱动脚本，注入点定制、流程与留证统一，人为失误面收窄。
- **对齐既有纪律**：AGENTS.md「怀疑某条自查是装饰性的，就按变异验证纪律走一遍」+ agent-harness spec「声明的门禁必须能够失败」——工具自身也须红绿自测（假绿样例、注入失配样例必须被工具判败）。

## What Changes

1. **`scripts/mutation-check.sh`（唯一新增工具，bash，Git Bash 可跑）**，CLI 契约：
   - 参数：`--target <file>`（注入目标）、`--inject-sed '<sed 表达式>'` 或 `--inject-file <file>`（整文件替换形态）、`--verify '<command>'`（预期注入后非零、复原后零）、`--label <name>`（变异标识，用于留证命名）；`--help` 自述用法。
   - 内部原子五步，任一步失败即非零退出并指明步骤：
     ① **备份**：目标字节级复制到 `target/mutation-check/<label>/`（target/ 天然 gitignored）；
     ② **注入 + 命中核验**：应用注入后**必须 grep 到注入标志在场**——sed 静默失配（CRLF 坑形态）即判「注入未生效」失败，杜绝假跑；
     ③ **击红**：跑 `--verify`，输出 tee 留证 `target/mutation-check/<label>/`，**断言非零退出**——verify 仍零退出即判「假绿/装饰性断言」失败；
     ④ **复原 + 归零**：字节级复制回目标，`cmp` 校验与备份字节一致（防行尾转换），grep 断言注入标志不在场；
     ⑤ **回绿**：重跑 `--verify` 断言零退出；终态汇总报告（五步各 PASS/FAIL + 留证路径），任一失败非零退出。
   - `set -euo pipefail`；不依赖 bash 5 专属变量（EPOCHREALTIME 坑在案）；全程不向 `target/` 之外写任何文件。
2. **工具红绿自测（实测留证，非 surefire/vitest 用例）**：
   - 全绿样例：构造临时假目标 + 假 verify，五步全 PASS；
   - **假绿样例**：构造「注入后 verify 仍零退出」场景 → 工具必须判败非零（证明击红断言真在比对）；
   - **注入失配样例**：构造 sed 表达式打不中场景 → 工具必须判「注入未生效」非零（证明命中核验真在比对）。
3. **真实变异验证（对真实护栏跑一轮，复用先例样板注入点）**：以 `exam-online-alerts.yml` 指标名拼写错误注入（add-dlq 案先例样板）→ `--verify 'mvnw.cmd -Dtest=AlertAssetsTest test'` 击红 → 复原归零回绿——五步全绿留证（在独立 worktree 或当前分支以 target/ 备份隔离，工作树零残留）。
4. **文档登记**：脚本头注释自述（对齐 `.github/ci/` 脚本先例，不单独建 README）；`spec/README.md` 工作流补「变异验证可用 scripts/mutation-check.sh 驱动」一行纪律 + 三处常规登记。
5. **spec 基线**：`spec/specs/agent-harness/spec.md` 末尾 ADDED「变异校验工具化」Requirement（六 Scenario 见 spec-delta）。
6. **三件套随实施笔一并入库**。

## Impact

- **规范**：agent-harness ADDED 1 Requirement（六 Scenario）；既有零改动。
- **代码**：零业务代码（`src/`、`frontend/`、`pom.xml`、`schema.sql`、`openapi.yaml`、`.github/` 零触碰）；新增 `scripts/mutation-check.sh` 一文件；零新增 surefire/vitest 用例——工具自测以实测留证，`gate-baseline.json` 合法持平零改动。
- **用户/API**：无可见变化。
- **数据与部署**：无。工具仅写 `target/`（gitignored），跑完零工作树残留（git status 自证）。
- **流程影响（新义务）**：此后执行侧变异自证与复核侧独立复现**优先**以本工具驱动（注入点仍按案定制）；手工五步保留为兜底而非默认。
- **非目标（显式排除）**：通用变异测试框架（PIT 类字节码变异）；CI 集成（不在 CI 加 job）；注入点自动发现（注入点永远人工按案设计——工具只固化流程与防护）。

## 验收与停止条件

- **验收**：① 工具三样例自测（全绿/假绿判败/失配判败）实测留证；② 真实护栏变异五步全绿留证（AlertAssetsTest 击红实证）；③ 工具跑完后 `git status` 零残留、`cmp` 复原字节一致实证；④ 双端门禁实施笔前后持平全绿；⑤ gate-baseline 零改动自证。
- **停止条件**：① Git Bash 环境缺关键特性（sed -i 形态差异、bash 版本不足）两轮排查仍致复原无法字节一致 → 停止上报（不得改用 PowerShell 重写后再隐藏失败语义）；② 工具须修改 `.github/` 或业务代码才能工作 → 停止；③ 真实变异五步任一步两轮走不通 → 停止上报，不得放宽断言（如允许「击红失败也算过」）；④ 工具被要求写 `target/` 之外的任何路径 → 停止。
