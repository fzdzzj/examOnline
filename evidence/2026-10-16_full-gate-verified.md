# 全量门禁实测留证（optimize-wrong-question-derivation-cost 阶段 2）

- 执行命令（仓库根，第二唯一门禁命令）：
  `.\mvnw.cmd clean test`
- 执行时刻：2026-10-16 20:03（本地），BUILD SUCCESS，总耗时约 10:40 min。
- 与执行绑定的短 revision：见本笔提交（`git log -1`）；原始 8.9MB / 30k 行完整日志不宜入版本库，
  需求复核可用 `target/surefire-reports/` 与当次命令产产生物核对（命令 + 聚合结果 + revision 已绑定）。
- 聚合结果（surefire 汇总行）：
  `Tests run: 626, Failures: 0, Errors: 0, Skipped: 1`
  （Skipped=1 为既有用例的既定跳过，非本次引入。）
- backendTests 基线（`.github/ci/gate-baseline.json`）= 618，本次 626，**只增不减**（新增
  `WrongQuestionJudgementCacheTest`=5 + `WrongQuestionDerivationCacheSemanticsTest`=3，共 +8）。
  基线上调属任务 3（阶段 3 收口），本阶段不调整。
- 测出入口必含本次新增与受影响用例全通过：命中不装配 / 版本戳失效 / 未命中回填 /
  故障退化 / 装配次数≤未命中场数 / 双 parse 收敛 / 重练池与错题本集成链。
- 与执行绑定的短 revision：见本笔提交（`git log -1`），完整实测日志原始超大不做版本内保留。