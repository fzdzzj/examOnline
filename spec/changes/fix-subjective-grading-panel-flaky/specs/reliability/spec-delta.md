# spec-delta：reliability（前端组件测试稳定性）

## 变更类型

MODIFIED（测试稳定性治理，产品行为零变化）

### 新增 Scenario: 批改面板测试负载稳定性 (fix-subjective-grading-panel-flaky)

- **GIVEN**: 全量 vitest 并行执行（同 CI 负载形态）
- **WHEN**: 运行 SubjectiveGradingPanel 测试套件（含「Enter 提交复用 createGradingFlow」
  用例）
- **THEN**: 连续多轮全量执行 SHALL 零该用例超时（等待条件外化于测试侧，不依赖组件
  内部异步链在 5s 默认窗口内返回）；孤立运行 SHALL 保持通过
- **AND**: 治理 SHALL 仅落测试侧（组件源码、vitest 全局配置、其余测试用例零改动；
  若确证组件侧真实缺陷则另案处理，不冒领入本案）
