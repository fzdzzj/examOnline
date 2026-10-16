# fix-behavior-counter-ttl-atomicity

## 一、背景与问题（harden-auth-edge-cases 台账未修注记，2026-10-16 立案）

`anticheat/service/BehaviorCounterService.increment` L46-48：Redis INCR 后判
`count == 1` 才 `expire(key, 12h)`——两步非原子。INCR 与 EXPIRE 间隙进程崩溃或 Redis
重启，键存在但无 TTL → **永不过期**：每「考试×学生×事件类型」一键长期累积（键空间
无界增长），且超 TTL 窗口后同键计数残留影响阈值升级判定口径。

同型缺陷已在 harden-auth-edge-cases 修复过三计数点（LoginGuardService.incrementWithTtl，
DefaultRedisScript Lua「INCR + 首置 PEXPIRE」原子化，先例 RedisTokenBucket），当时本服务
因白名单限制（anticheat 包）保持未修并留台账注记。本案是该注记的收口，**修复模式照抄
先例，不改任何判定语义**。

## 二、方案

- increment 改 Lua 单脚本原子化（INCR + 首次置 TTL），脚本常量**本地复制于 anticheat 包**
  （不引用 auth 包内部常量，保持包独立、白名单干净）
- 判定语义零变化：返回值口径（首次 1）、TTL 配置（counter-ttl-hours 默认 12h）、
  Redis 异常降级「按首次事件返回 1、不影响采集主链路」逐字保留
- 不动：KEY_PREFIX、key 维度（examId/studentId/eventType）、调用方
  （切屏策略阈值升级判定链路）

## 三、白名单

- `src/main/java/com/exam/anticheat/**` 与 `src/test/java/com/exam/anticheat/**`
- 禁触：schema.sql、docker/mysql/migrations/、openapi.yaml、pom.xml、frontend/、auth 包
  （LoginGuardService 仅作只读先例参照，零改动）

## 四、验收要点

- 单测先红后绿：原子化路径（首增置 TTL、续增不重置）、异常降级路径保留
- 变异验证：退回两步 INCR/EXPIRE 形态 → 崩溃间隙用例击红；复原回绿
- 全量门禁 `mvnw.cmd clean test`（仓库根、带 clean、禁 -o）留证；backendTests 基线只增不减
- S 级理由：单类修复、同型先例照抄、无契约变化、无设计权衡
