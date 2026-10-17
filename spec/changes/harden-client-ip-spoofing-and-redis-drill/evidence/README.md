# 证据索引：harden-client-ip-spoofing-and-redis-drill（反代 X-Real-IP 优先采信防伪造 + Redis 停机全口径降级现场演练台账）

> 门禁唯一命令：仓库根 `mvnw.cmd clean test`（带 clean，未用 `-o`）。本目录所有日志均为当次原样输出，
> 未经手工编辑；`06` 绑定产生它的 revision（实施笔 `e56d5c7`）。

## 01 先红说明

- `01-tdd-red-old-form.log`：在未实现 `X-Real-IP` 优先采信逻辑前，`IpMatcherTest` 执行失败：
  - `clientIpPrefersRealIpOverSpoofedForwarded` 确定性断言级失败：`expected: <198.51.100.1> but was: <203.0.113.88>`（恶意伪造 XFF 首项被误采信）；
  - `clientIpRealIpOnly` 确定性断言级失败：`expected: <198.51.100.2> but was: <127.0.0.1>`；
  - `BUILD FAILURE`。

## 02 定向绿（实施后）

- `02-targeted-green-impl.log` —— `mvnw.cmd test -Dtest=IpMatcherTest`：
  - `ClientIpResolver` 实现 `X-Real-IP` 优先合法性解析与平滑回退；
  - `IpMatcherTest` 20/20 全绿（`Tests run: 20, Failures: 0, Errors: 0, Skipped: 0`），`BUILD SUCCESS`。

## 03 变异击红（行为级）

- `03-m1-red.log` —— 变异 m1（将 `ClientIpResolver.resolve` 改回裸读 `X-Forwarded-For` 首项的旧形态）：
  - `IpMatcherTest#clientIpPrefersRealIpOverSpoofedForwarded` 确定性断言级击红：`expected: <198.51.100.1> but was: <203.0.113.88>`；
  - `clientIpRealIpOnly` 确定性断言级击红：`expected: <198.51.100.2> but was: <127.0.0.1>`；
  - `BUILD FAILURE`。

## 04 复原回绿

- `04-m1-green.log` —— 复原 `ClientIpResolver.java` 采信 `X-Real-IP` 逻辑后重新执行：
  - `Tests run: 20, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`；
  - 先红后绿与变异闭环留证完成。

### 等价变异登记（如实登记，不强杀）

- `ClientIpResolver.validRealIp` 对 `X-Real-IP` 提取前后的 trim 操作：若移除 `trim()`，在标准反向代理（如 Nginx `proxy_set_header X-Real-IP $remote_addr;`）下传入的 `$remote_addr` 本身即为无首尾空格的合规 IP 字符串，`IpMatcher.isValidIp` 亦能直接判定；保留 `trim()` 属防御性编程（防手写注入或特殊反代带空格），不以破坏防御性容错为目的进行强杀。
- `ClientIpResolver` 中 `X-Real-IP` 请求头名称的大小写（HTTP 请求头在 Servlet 容器内部为大小写不敏感匹配）：属于网络协议层规范，`request.getHeader("x-real-ip")` 与 `request.getHeader("X-Real-IP")` 在标准实现下等价。

## 05 范围外自查

- `05-out-of-scope-diff.txt` —— `git diff main -- src/main/resources/schema.sql docker/mysql/migrations/ openapi.yaml pom.xml frontend/` **输出 0 字节**（零 DDL、零契约、零前端改动）。
- 代码修改严格限制在派工白名单范围内（`ClientIpResolver`、`IpMatcherTest`、`docs/redis-drill-evidence.md`、`docs/redis-dependency-runbook.md`、`docs/功能待办清单.md`）。

## 06 全量门禁

- `06-full-gate-summary.log`：
  - 执行命令：`mvnw.cmd clean test`
  - 门禁结果：`Tests run: 692, Failures: 0, Errors: 0, Skipped: 1` + `BUILD SUCCESS`。
  - baseline 前后值：`.github/ci/gate-baseline.json` `backendTests` **689 → 692**（+3：`IpMatcherTest` 17 → 20 用例），与门禁实数同笔上调；
  - `frontendTests` 908 / `openapiPaths` 97 保持不变（零前端、零契约变更）。
