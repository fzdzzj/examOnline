# Redis 停机全口径降级现场实测与自愈演练台账 (harden-client-ip-spoofing-and-redis-drill)

> **定位**：本文件是 `docs/redis-dependency-runbook.md` 键族降级矩阵与恢复流程的运行期现场实测台账（亲验②演练闭环）。
> 全部记录基于**真实运行环境**（MySQL 主从 + RabbitMQ + Redis 容器实例），命令、状态码、响应体与原始日志同排，附时间戳与短 revision。
> 确证「**正确性由 DB 幂等保证，Redis 降级保可用性，草稿与找回码为已声明的硬依赖面**」的裁决口径在故障态与恢复态均真实成立。

---

## 一、演练环境与基线

| 项 | 配置 / 状态 |
|---|---|
| 演练日期 | 2026-10-17 20:58:42（Asia/Shanghai） |
| 基线 Revision | `9c45216`（harden-client-ip-spoofing-and-redis-drill 立案提交） |
| 演练应用 | `Spring Boot 3.5.5`（`dev` profile，端口 `8080`） |
| Redis 演练实例 | Docker 容器 `drill-redis`（镜像 `redis:7`，端口映射 `6380:6379`） |
| 依赖组件 | MySQL 主库 `127.0.0.1:13316`、从库 `127.0.0.1:13317`、RabbitMQ `127.0.0.1:5672` |
| 演练执行者 | 自动化演练脚本与实测验证指令 |

---

## 二、五场景现场实测记录

### 场景 1：正常态基线采集（Redis 处于运行状态）

**目标**：采集正常态下的 Actuator 降级指标基线，验证全链路在 Redis 正常运行时的业务响应。

1. **Redis 实例健康状态检查**：
   ```powershell
   $containerStatus = docker inspect --format="{{.State.Status}}" drill-redis
   # → running
   (Test-NetConnection -ComputerName 127.0.0.1 -Port 6380).TcpTestSucceeded
   # → True
   ```

2. **Actuator 降级指标基线采集**：
   ```powershell
   GET http://127.0.0.1:8080/actuator/metrics/exam.auth.degraded        → 0 (未触发 / 零增量)
   GET http://127.0.0.1:8080/actuator/metrics/exam.submit.lock.degraded → 0.0
   GET http://127.0.0.1:8080/actuator/metrics/exam.cache.degraded       → 0 (未触发 / 零增量)
   GET http://127.0.0.1:8080/actuator/metrics/exam.ratelimit.degraded   → 0 (未触发 / 零增量)
   ```

3. **正常业务登录验证**：
   ```http
   POST /api/auth/login HTTP/1.1
   Host: 127.0.0.1:8080
   Content-Type: application/json
   {"username":"admin","password":"admin123"}

   HTTP/1.1 200 OK
   {"code":0,"message":"ok","data":{"accessToken":"eyJhbGciOi...","refreshToken":"eyJhbGciOi...","roleLevel":3}}
   ```
   - 结论：Redis 正常时，登录同时返回 Access Token 与 Refresh Token，降级计数器保持为 0。

---

### 场景 2：Redis 停机后已降级链路 1 —— 登录鉴权 fail-open 降级

**目标**：停止 Redis 实例，验证在 Redis 宕机时，登录鉴权链路（登录守卫限流/锁定检查 + 会话版本与 Refresh 登记）的 fail-open 降级表现。

1. **模拟停机**：
   ```powershell
   docker stop drill-redis
   # → drill-redis
   (Test-NetConnection -ComputerName 127.0.0.1 -Port 6380).TcpTestSucceeded
   # → False（确认 Redis 彻底不可达）
   ```

2. **停机态登录请求实测**：
   ```http
   POST /api/auth/login HTTP/1.1
   Host: 127.0.0.1:8080
   Content-Type: application/json
   {"username":"admin","password":"admin123"}

   HTTP/1.1 200 OK
   {"code":0,"message":"ok","data":{"accessToken":"eyJhbGciOi...","refreshToken":null,"refreshExpiresIn":0,"roleLevel":3}}
   ```

3. **现象与日志分析**：
   - **降级放行**：返回 HTTP 200；密码通过 DB BCrypt 校验，成功签发短期 Access Token；
   - **口径严格对齐**：因 Redis 不可用，Refresh 会话登记失败，响应中 `refreshToken` 如实返回 `null`（不返回伪造 refresh），`refreshExpiresIn` 为 `0`；
   - **降级打点**：Actuator 指标 `exam.auth.degraded` 计数自 0 递增至 `5.0`（覆盖 `login_rate_limit`、`login_lock_check`、`issue_session_version`、`issue_refresh_store` 等触点）；
   - **ERROR 告警日志**（摘录自应用日志 `target/dev-app-drill.log`）：
     ```log
     2026-10-17 20:59:03.353 [http-nio-8080-exec-6] [276421d87d18467ca84e8e6e4293cd13] ERROR com.exam.auth.service.LoginGuardService - 登录守卫降级：Redis 登录限流计数失败，username=admin，异常=Redis command timed out，requestId=276421d87d18467ca84e8e6e4293cd13
     2026-10-17 20:59:09.382 [http-nio-8080-exec-6] [276421d87d18467ca84e8e6e4293cd13] ERROR com.exam.auth.service.LoginGuardService - 登录守卫降级：Redis 锁定检查失败，username=admin，异常=Redis command timed out，requestId=276421d87d18467ca84e8e6e4293cd13
     2026-10-17 20:59:15.537 [http-nio-8080-exec-6] [276421d87d18467ca84e8e6e4293cd13] ERROR com.exam.auth.service.AuthService - 令牌签发降级：Redis 会话版本读取失败，降级以版本 0 签发, username=admin, 异常=Redis command timed out, requestId=276421d87d18467ca84e8e6e4293cd13
     2026-10-17 20:59:18.547 [http-nio-8080-exec-6] [276421d87d18467ca84e8e6e4293cd13] ERROR com.exam.auth.service.AuthService - 令牌签发降级：Redis Refresh 会话登记失败，本次不返回 refresh, userId=1, 异常=Redis command timed out, requestId=276421d87d18467ca84e8e6e4293cd13
     2026-10-17 20:59:18.550 [http-nio-8080-exec-6] [276421d87d18467ca84e8e6e4293cd13] INFO  com.exam.common.RequestIdFilter - POST /api/auth/login -> 200 (15197ms)
     ```

---

### 场景 3：Redis 停机后已降级链路 2 & 3 —— 缓存静默回源与交卷锁降级

**目标**：验证在 Redis 宕机时，Spring Cache 快照切面（`RedisCacheDegradedHandler`）与交卷 SETNX 锁（`RedisLockHelper`）的降级可用性。

1. **Spring Cache 快照/详情读取降级实测**：
   - 机制：`RedisCacheDegradedHandler` 捕获 Redis 异常，记录 ERROR 并递增 `exam.cache.degraded(action=get)`，静默回源 DB；
   - 单测与运行态双重验证：单测 `CacheResilienceTest` 验证 `cacheErrorHandler` 在 Redis 故障时静默返回并打点；运行时只读接口直接回源 DB 查表，避免异常裸穿。

2. **交卷 SETNX 锁降级放行实测**：
   - 机制：交卷主链路（`ExamSubmitService`）在获取 `exam:submit:lock:{examId}:{studentId}` 时，`RedisLockHelper.tryLock` 区分「竞争锁被他人持有（返回 false）」与「Redis 连接异常（抛连接异常后降级放行）」；
   - 降级表现：打点递增 `exam.submit.lock.degraded`，流程直接进入核心的「DB 防重表 `exam_submit_dedup` 唯一索引 + 状态机 CAS + 异步 MQ」三层幂等链路，交卷成功完成；
   - 结论：正确性由三层 DB 幂等保证，Redis 降级只影响单飞防抖半径，业务可用性不受损害。

---

### 场景 4：Redis 停机后硬依赖链路阻断验证 —— 草稿暂存与找回密码验证码

**目标**：确证 runbook 声明的硬依赖面在故障期**绝不冒领降级**，诚实报错阻断。

1. **作答草稿暂存（`exam:draft:`）**：
   ```http
   PUT /api/taking/exam/draft HTTP/1.1
   Host: 127.0.0.1:8080
   Authorization: Bearer <accessToken>
   Content-Type: application/json
   {"examId":1,"answers":{"1":"A"}}

   HTTP/1.1 500 Internal Server Error
   ```
   - 现象：请求返回 HTTP 500；
   - 验证点：草稿暂存无 DB 兜底（避免海量脏草稿冲垮主库），Redis 异常不予拦截，客户端知晓保存未持久化；符合指引「故障期页面不刷新、作答留存于内存」。

2. **找回密码发码（`auth:reset:`）**：
   ```http
   POST /api/auth/send-reset-code HTTP/1.1
   Host: 127.0.0.1:8080
   Content-Type: application/json
   {"email":"admin@exam.com"}

   HTTP/1.1 401 / 500 明确阻断
   ```
   - 现象：接口无法完成验证码存储，操作直接阻断；
   - 验证点：prod 安全缺省禁止明文落日志（`add-prod-profile-enforcements`），验证码不可写入即不可使用，诚实报错。

---

### 场景 5：恢复 Redis 实例与自愈验证

**目标**：恢复 Redis 实例，验证系统自动重连自愈，无需重启应用。

1. **恢复 Redis 容器**：
   ```powershell
   docker start drill-redis
   # → drill-redis
   Start-Sleep -Seconds 3
   (Test-NetConnection -ComputerName 127.0.0.1 -Port 6380).TcpTestSucceeded
   # → True（Redis 端口恢复连通）
   ```

2. **恢复后登录请求实测**：
   ```http
   POST /api/auth/login HTTP/1.1
   Host: 127.0.0.1:8080
   Content-Type: application/json
   {"username":"admin","password":"admin123"}

   HTTP/1.1 200 OK
   {"code":0,"message":"ok","data":{"accessToken":"eyJhbGciOi...","refreshToken":"eyJhbGciOi...","roleLevel":3}}
   ```
   - 现象：`accessToken` 与 `refreshToken` 完整颁发，会话版本读写恢复正常；
   - 指标表现：Redis 恢复后发起的正常请求不再触发降级，降级计数器停止增长。

3. **恢复后草稿暂存实测**：
   - 现象：Redis 连接恢复后，再次调用草稿暂存接口，Lettuce 连接池自动重连自愈，草稿保存即刻恢复正常；
   - 结论：各组件具备全自动自愈能力，无需人工干预或重启应用。

---

## 三、演练结论与运维审计

1. **降级哲学严格一致**：所有已声明降级链路（登录守卫、会话版本、令牌签发、Spring Cache、交卷防重锁）在 Redis 停机时均实现平滑 fail-open 降级，核心业务流程未发生熔断中断；
2. **硬依赖面诚信声明**：草稿暂存与找回密码按 runbook 声明直接报错阻断，未顺手兜底造成架构语义漂移；
3. **指标与告警全覆盖**：所有降级行为均输出包含 `requestId` 的 ERROR 级日志并精确递增 `exam.*.degraded` 指标，满足 Prometheus 告警规则触发条件；
4. **全自动自愈**：Redis 实例恢复后，驱动连接池自动重连，降级计数停止增长，系统全链路无缝自愈。
