# polish-mutation-and-ci-tooling 提案

## 背景与问题（2026-10-12 收官时累积的五个顺手窗口观察项）

来源：add-mutation-check-tool 复核观察项①②③④ + project-analysis-report-graded-read 复核更新⑤。五项均为纯工具/纯文档/编排配置打磨，无业务代码：

1. **①判败不落盘 report**：`scripts/mutation-check.sh` self-c 判败路径（注入失配等）经 `fail()` 退出时不写 report.txt——判败轮次无留证，复核需回看时只能重跑。
2. **②--out-dir 留证逃生**：工具留证固定落 `target/mutation-check/`，被 `mvnw clean` 清空（生命周期冲突）——需要留证跨门禁存续时无逃生口。
3. **③.github/ci/ 三脚本 $() 子壳 fail 噪音**：`get_baseline` 缺键时主脚本带空基线走到比较行，产生告警噪音后才 rc=1（安全性质成立、仅消息噪音；add-ci-gate-pipeline 复核 O-4）。
4. **④spec/README.md 工具措辞与 T2 proposal 略不齐**：无语义冲突，纯措辞对齐。
5. **⑤工具文档 Windows 驱动形态**：WSL 不可挂盘时 Git-Bash 驱动同工具可行（两案复核实证），文档未记载（原「PowerShell 原生入口」候选降级）。

## 变更内容

1. `scripts/mutation-check.sh`：①判败路径（fail() 调用点）补写 report.txt 再退出；②新增 `--out-dir` 可选参数——缺省时行为与现状逐字一致（仍落 target/mutation-check/），指定时留证落指定目录；⑤头注释/用法补「Windows 下 Git-Bash 驱动形态」说明。
2. `.github/ci/` 三脚本：$() 子壳内 fail() 不传播处改用命令替换退出码判断或内联 fail（收敛噪音，不改断言语义）。
3. `spec/README.md`：工具措辞与归档 proposal 对齐（④，无语义变化）。

## 红线与验收边界

- **零触碰域**：`src/`、`frontend/src/`、`pom.xml`、`schema.sql`、`openapi.yaml`、`docker-compose.yml`、`docker/`、`.github/workflows/` 一律零改动；`gate-baseline.json` 零改动（无新 surefire/vitest 用例，合法持平——CI 断言层绿即证）。
- **工具语义红线**：mutation-check.sh 五步原子流程（备份→注入+命中核验→击红非零断言→复原 cmp 字节一致→回绿零退出）与 fail-closed 性质零变化；`--out-dir` 缺省路径与默认行为逐字不变。
- **CI 断言红线**：解析断言脚本「任一检查点失败必须非零退出」性质保留（注入违规红/正常绿双向自测）。
- **停止条件**：任一改动导致五步原子、fail-closed 或断言失败传播性质破坏，立即停止并如实回报，不得绕过。

## 复核分级判定（2026-10-12 定版规则，显式声明）

本变更为**零业务代码变更**（纯工具/纯文档/编排配置类）→ **分级①**：门禁复跑降级为「CI run 核证」（gh 核证执行侧 push 后三 job 绿替代本地全量复跑）；保留红线域 diff、逐字比对（spec-delta/台账 step 文本）、**独立复现核心**（判败留证与 --out-dir 功能实测、CI 脚本注入红绿）；派发轮次采用「执行侧一次到底含收口」形态（执行子 agent 完成全部任务含收口，复核终验 main；FAIL 时打修复笔可接受）。台账返修笔机制不变。

## 验收判据

- 工具自测三证：self-c 判败轮 report.txt 存在且内容正确；`--out-dir` 指定目录留证成功且缺省路径行为零变化；既有 self-a 全绿样例回归绿。
- CI 脚本双向自测：注入违规（基线缺键/断言失败）→ 非零退出且无「空基线走到比较行」噪音；正常路径 → 零退出。
- CI 三 job 绿（gate-baseline 持平断言 OK）；实施笔 diff 仅 `scripts/mutation-check.sh`、`.github/ci/` 三脚本、`spec/README.md`（红线域全空）。
