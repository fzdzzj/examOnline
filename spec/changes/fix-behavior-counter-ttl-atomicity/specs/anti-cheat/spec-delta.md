# spec-delta：anti-cheat（行为计数 TTL 原子性）

## 变更类型

MODIFIED（计数判定语义零变化；新增 Redis 键生命周期原子性 Scenario）

### 新增 Scenario: 行为计数键 TTL 的原子性 (fix-behavior-counter-ttl-atomicity)

- **WHEN**: 行为事件计数自增（`exam:behavior:count:{examId}:{studentId}:{eventType}`）
- **THEN**: 自增与首次 TTL 设置 SHALL 在同一 Lua 脚本内原子完成（INCR + 首置 PEXPIRE，
  先例 LoginGuardService.incrementWithTtl）；SHALL NOT 存在「键已自增但无 TTL」的两步间隙
  形态（进程崩溃或 Redis 重启不得导致键永不过期、键空间无界累积）
- **AND**: 计数判定语义 SHALL 逐字保留——返回值口径（首次返回 1）、TTL 配置
  （counter-ttl-hours 覆盖整场考试）、Redis 异常降级「按首次事件返回 1、不影响采集
  主链路」均不变
