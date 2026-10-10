# 规范差异：frontend（测试断言有效性纪律）

> 目标基线：`spec/specs/frontend/spec.md`（既有 Requirement 原文零改动）。本 delta 纯 ADDED：追加一个工程纪律 Requirement，落位于文件末尾（按合入顺序惯例）。

## ADDED Requirements

### Requirement: 测试断言有效性纪律

前端测试 SHALL 只包含必然执行的断言，SHALL NOT 以条件守卫或双通兜底使断言在被测行为失效时静默通过。

#### Scenario: exists 守卫禁用

GIVEN 测试以 `find(selector)` 定位被测元素

WHEN 该 selector 与生产代码失配

THEN 断言 SHALL 击红

AND 测试源码 SHALL NOT 出现 `if (...exists())` 守卫包裹断言的形态

#### Scenario: 双通兜底禁用

GIVEN 断言以「主条件 || 兜底条件」双通形态表达（如 `exists() || html().includes(...)`）

WHEN 主条件对应的被测行为失效而兜底条件仍为真

THEN 断言 SHALL NOT 因此通过

AND 测试源码 SHALL NOT 出现 `exists() ||` 双通形态

#### Scenario: 词法护栏拦截两禁形态

GIVEN 本 Requirement 生效后的 frontend 测试源码

WHEN 某测试文件出现 exists 守卫或双通两禁形态之一

THEN 词法护栏测试 SHALL 对该文件击红

#### Scenario: 坐实缺陷修复须先证装饰性

GIVEN 普查坐实一处装饰性断言

WHEN 修复它

THEN 修复 SHALL 附证据链：修复前原样跑绿证明装饰性、改为直接断言、以临时破坏被测行为的变异演示击红后复原

#### Scenario: 合法直断不受影响

GIVEN 测试以 `expect(find(selector).exists()).toBe(true | false)` 直接断言存在性

WHEN selector 与生产代码失配

THEN 断言自然击红

AND 该合法形态 SHALL NOT 被词法护栏误伤
