# Proposal: 反代客户端 IP 伪造防御与 Redis 停机降级演练 (harden-client-ip-spoofing-and-redis-drill)

## 1. 为什么（Why）

本项目经过多轮业务与韧性治理，核心逻辑已高度稳固。但在成熟度与生产收口阶段，仍有两项高收益的专项待闭环：

1. **反代 XFF 伪造漏洞（安全专项中危发现 1 · 亲验③）**：
   - 现状：`ClientIpResolver.java` 盲目提取 `X-Forwarded-For` 的首项作为客户端真实 IP；
   - 隐患：生产 Nginx 配置了 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`（追加型反代）。恶意客户端若自行发送 `X-Forwarded-For: 203.0.113.88`，Nginx 会在其后追加真实 IP（形如 `203.0.113.88, <真实IP>`），导致后端 `ClientIpResolver` 误将伪造的前缀当作客户端 IP；
   - 危害：考试 IP 白名单准入可被整段绕过、验证码限流 `auth:coderl:{email}:{ip}` 可被恶意换头绕过、安全审计日志记录虚假 IP；
   - 现成解法：生产 Nginx 已显式配置 `proxy_set_header X-Real-IP $remote_addr;`（覆盖型头，由 Nginx 从 TCP 握手直接填入，客户端无法伪造）。但后端此前从未读取 `X-Real-IP`。

2. **Redis 停机全口径降级演练（运行态确证 · 亲验②）**：
   - 现状：此前已完成 4 案 Redis 降级（登录守卫、交卷锁、令牌签发续期、Spring Cache），并在 `docs/redis-dependency-runbook.md` 建立了完整口径声明与键族矩阵；
   - 目标：执行现场停机演练，验证各链路在真实 Redis 宕机时的降级表现（登录、拉卷、交卷锁降级可用；草稿、找回码硬依赖诚实报错；恢复后自动重连自愈），并将演练过程与结果沉淀为正式演练台账（`docs/redis-drill-evidence.md`）。

本提案将上述两项高价值收尾工作合并立项，实现代码防御与运维演练双闭环。

---

## 2. 改什么（What）

### 2.1 ClientIpResolver 优先采信 X-Real-IP 防御伪造 (`ClientIpResolver.java`)
- 提取逻辑改造：
  1. 优先读取 `X-Real-IP` 请求头，若非空且为合法 IPv4/IPv6，直接作为真实客户端 IP 返回；
  2. 若 `X-Real-IP` 缺失或非法，安全回退至既有 `X-Forwarded-For` 解析逻辑；
  3. 若均无效，安全回退至直连 `request.getRemoteAddr()`。
- 单测覆盖：
  - 在 `IpMatcherTest` 补充用例：
    - 恶意客户端同时注入伪造 `X-Forwarded-For` 与合法的 `X-Real-IP` 时，系统准确采信 `X-Real-IP`，拦截伪造；
    - 仅有 `X-Real-IP` 时正确解析；
    - `X-Real-IP` 非法时平滑回退既有链路。

### 2.2 Redis 停机降级运行态实测与证据沉淀 (`docs/redis-drill-evidence.md`)
- 依据 `docs/redis-dependency-runbook.md` 演练指引执行：
  - 正常态基线采集（Actuator 降级指标基线）；
  - 模拟停机（`docker stop exam-online-redis` 或本地停止实例）；
  - 验证登录可用性（返回 200 + 降级计数）、详情拉卷可用性（回源 DB + 降级计数）、交卷可用性（放行 + 降级计数）；
  - 验证草稿保存与找回密码诚实报错（返回 500）；
  - 恢复 Redis，验证重连自愈与指标停止增长；
- 撰写 `docs/redis-drill-evidence.md` 记录详细步骤、实测命令、时间戳与日志。

---

## 3. 影响能力域与规范

- `anti-cheat`：客户端真实 IP 提取防伪造与准入守卫规范；
- `reliability`：Redis 依赖降级运行态演练证据与台账。

---

## 4. 零 DDL 与质量红线

- **零 DDL**：未新增、修改任何表结构，未改动 `schema.sql`，不添加任何 `docker/mysql/migrations/` 脚本；
- **契约稳定**：`openapi.yaml` 既有 97 个 paths 保持稳定；
- **测试零 @Sql**：单测一律基于 Mockito 与已有基础设施；
- **gate-baseline 只增不减**：全量后端用例数基线锁定上调。

---

## 5. 变异验证计划

- **m1（X-Real-IP 优先采信摘除）**：将 `ClientIpResolver.resolve` 改回直接读取 `X-Forwarded-For` 首项的旧形态，验证防伪造单测变红（期望得到 `X-Real-IP` 但得到伪造的 `XFF` 首项）。
