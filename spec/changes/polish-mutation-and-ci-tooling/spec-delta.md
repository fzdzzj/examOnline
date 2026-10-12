# polish-mutation-and-ci-tooling spec-delta

能力域：`spec/specs/agent-harness/spec.md`（工具纪律 Requirement 域，收口时按基线实际挂载位合入，Scenario 文本逐字一致）

变更类型：ADDED Requirement

## ADDED Requirement：变异校验工具与 CI 解析脚本的留证与失败传播完善

### Scenario: 判败亦留证

- WHEN mutation-check.sh 任一步骤判败退出（含注入失配、击红断言失败、复原不一致）
- THEN 该轮 report.txt 已写入留证目录后以非零码退出——判败轮次与判胜轮次同规格留证
- AND fail-closed 性质零变化（任何非测试失败的非零退出仍整体判败，不假绿放行）

### Scenario: 留证目录可指定

- WHEN 调用方以 --out-dir 指定留证目录
- THEN 五步留证（备份、注入核验、击红输出、复原比对、回绿输出）落指定目录
- AND 未指定时留证路径与既有行为逐字一致（仍落 target/mutation-check/，被 mvnw clean 清空的生命周期形态不变）

### Scenario: 失败传播无噪音与文档形态

- WHEN .github/ci/ 解析断言脚本遇到缺键或检查点失败
- THEN 直接以非零码退出，不携带空基线或无效输入走到后续比较行产生告警噪音
- AND 任一检查点失败必须非零退出的既有断言性质零变化
- AND mutation-check.sh 文档记载 Windows 下 Git-Bash 驱动形态（WSL 不可挂盘时的实证可行路径）
