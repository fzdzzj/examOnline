# Redis 依赖与故障 Runbook（裁决口径 · 键族降级矩阵 · 告警映射 · 恢复流程）

> **定位**：本文件是 Redis 依赖口径的 canonical 事实源（add-redis-dependency-runbook，2026-10-17 收口）。
> 任何触碰 Redis 调用点的改动（新增键族 / 降级 / 容错）先读本文；新降级计数指标必须有对应告警规则并过
> `AlertAssetsTest` 守护。键族行为以代码为准——本文与代码不一致时，先修代码或修文档，不允许两处各说各话。
>
> **裁决背景**（用户拍板，2026-10-17）：Redis 故障口径统一为「**正确性由 DB 幂等保证，Redis 降级保可用性，
> 草稿为已声明的硬依赖面**」。

## 一、依赖口径声明

1. **正确性一律不由 Redis 保证**：交卷只发生一次靠防重表唯一索引 + 状态机 CAS + 消费端 casFillAnswers；
   登录正确性靠 DB 凭据校验（BCrypt）与 JWT 签名校验。Redis 承载的锁定/限流/计数/缓存只是**加速器与减速器**。
2. **Redis 故障降级保可用性**，且全仓库只有一套降级哲学——fail-open 同源：降级放行（或跳过该步）+
   ERROR 日志（含 requestId）+ 降级计数指标（`exam.*.degraded`）+ 对应告警规则。不新增第二套降级哲学。
3. **硬依赖面如实声明，不冒领为已降级**：草稿暂存、令牌签发与续期（登录/刷新/登出/改密踢人）、
   Spring Cache 快照与详情缓存、找回密码验证码——这四族在 Redis 故障期**不降级、直接失败**（见矩阵「硬依赖」行）。
   消除其中任一面需单独立案设计，禁止在本口径下顺手加兜底。
4. **降级口只认不可用，不认竞争**：锁被真占、限流超限、锁定命中等正常业务拒绝不因降级逻辑而放行。

## 二、键族降级矩阵（逐族故障行为）

| 键族（key 前缀） | 承载方 | 职责 | Redis 故障行为 | 计数指标 / 告警 | 恢复后 |
|---|---|---|---|---|---|
| `auth:bl:` / `auth:sv:`（请求时校验） | AuthenticationInterceptor | 登出吊销黑名单 / 会话版本校验 | **降级放行**（黑名单/版本检查暂缺，JWT 签名校验不降级）；半径：吊销延迟生效，受黑名单 TTL（=Access 剩余寿命 ≤30min）自限 | `exam.auth.degraded`(check=blacklist/session_version) / AuthDegraded | 自愈 |
| `auth:rl:`（登录限流） | LoginGuardService.checkRateLimit | 账号+IP 固定窗口限流 | **fail-open 放行** | `exam.auth.degraded`(check=login_rate_limit) / AuthDegraded | 自愈（计数键 TTL 1min） |
| `auth:lock:` / `auth:fail:`（登录锁定） | LoginGuardService.checkLocked / recordFailure | 连续失败锁定 | **降级放行/跳过**：锁定检查暂缺、失败计数跳过、锁定写入放弃（下次失败重试）、成功后清理跳过 | `exam.auth.degraded`(check=login_lock_check/login_fail_record/login_lock_write/login_fail_clear) / AuthDegraded | 自愈（计数键 TTL=lockMinutes） |
| `auth:coderl:`（验证码限流） | LoginGuardService.checkCodeRateLimit | 邮箱+IP 固定窗口限流 | **fail-open 放行** | `exam.auth.degraded`(check=code_rate_limit) / AuthDegraded | 自愈 |
| `exam:ratelimit:`（接口限流） | RedisTokenBucket / RateLimitInterceptor | @RateLimit 接口令牌桶 | **fail-open 放行** | `exam.ratelimit.degraded`(endpoint) / RateLimitDegraded | 自愈（桶 key TTL） |
| `exam:submit:lock:`（交卷锁） | ExamSubmitService + RedisLockHelper | 三路竞态收敛单飞（防抖器） | **降级放行**：SETNX 连接异常 → 走防重表+CAS+MQ 链路完成交卷；锁被真占（false）仍走既有竞争路径。降级只影响防抖半径，正确性由三层 DB 幂等保证 | `exam.submit.lock.degraded` / SubmitLockDegraded | 自愈（锁 TTL 30s） |
| `exam:behavior:count:`（行为计数） | BehaviorCounterService | 切屏等事件计数（防抖动策略升级判定） | **既有降级**：log.error + 返回 1，事件照常落库，防抖策略退化 | 无独立计数 / 无 | 自愈（计数键 TTL 12h） |
| `exam:monitor:online:`（在线状态） | OnlinePresenceService | 监考在线心跳与批量查询 | **既有降级**：心跳写入旁路忽略；批量查询按全员离线降级 | 无独立计数 / 无 | 自愈（在线键带 TTL） |
| `exam:cache:wrongQuestionJudgement:`（错题判定缓存） | WrongQuestionJudgementCache | 错题判定轻量缓存 | **既有降级**：任何故障 try/catch 退化现算，判分同源不变 | 无独立计数 / 无 | 自愈（版本戳自失效） |
| `exam:draft:`（草稿暂存） | ExamDraftService | 作答自动保存 / 断线恢复 / 后端兜底交卷答案源 | **硬依赖（无兜底，已声明）**：保存失败、断线恢复不可用、后端兜底交卷失败；**故障期刷新页面丢上次成功保存之后的作答** | 无 / 无 | 恢复后需手动重新保存（见 §四） |
| `auth:refresh:` / `auth:sv:`（签发与续期面） | AuthService.issueTokens / refresh / logout、TokenStoreService | 登录令牌签发、Refresh 原子轮换、登出吊销、改密/踢人版本递增 | **硬依赖（未降级，残留观察面）**：Redis 故障期**新登录 500**（凭据校验已过、签发步失败）、续期轮换失败（存量客户端到点被登出）、登出/改密踢人失败。已登录且 Access 未过期者不受影响（请求时校验已降级放行） | 无 / 无 | 自愈；续期失败累积的重新登录在恢复后成功 |
| `auth:reset:`（找回密码验证码） | TokenStoreService.storeResetCode/consumeResetCode | 验证码单次消费 | **硬依赖（未降级）**：找回流程故障期不可用（发码 500、验码 500） | 无 / 无 | 自愈 |
| `exam:cache:*`（Spring Cache：快照/详情） | ExamSnapshotService / PaperSnapshotService / ExamService + CacheMutexLoader | 拉卷快照、考试详情缓存、防击穿互斥与空标记 | **硬依赖（未降级，残留观察面 = H2 在案「RedisCacheManager 直连」）**：无 CacheErrorHandler，读/写/失效异常裸穿 500——故障期**拉卷、考试详情、考试写路径（@CacheEvict）失败** | 无 / 无 | 自愈（缓存回填） |

> 矩阵读法：**「降级」行**是裁决口径的已落地承诺（改代码前必须维持其语义与计数告警不变）；
> **「硬依赖」行**是如实声明的故障半径（其中 `auth:refresh:` 签发续期面与 `exam:cache:*` 缓存面是
> 登录/拉卷可用性的残留大半径面，消除需单独立案，禁顺手兜底）；恢复列描述故障恢复后的自愈方式。

## 三、监控告警现状盘点（2026-10-17，本卡收口时点）

| 告警规则 | 盯的指标 | 覆盖面 | 状态 |
|---|---|---|---|
| RateLimitDegraded | `exam_ratelimit_degraded_total` | 接口限流 fail-open | 既有 |
| AuthDegraded | `exam_auth_degraded_total`（全 tag 增量） | 鉴权拦截器（blacklist/session_version）+ 登录守卫（login_rate_limit/login_lock_check/login_fail_record/login_lock_write/login_fail_clear/code_rate_limit） | 既有（tag 面随 A1 扩大，规则零改动自动覆盖） |
| SubmitLockDegraded | `exam_submit_lock_degraded_total` | 交卷锁降级放行 | **本卡补齐**（此前 D1 新增计数器无告警——降级等于没人知道，照抄 AuthDegraded 形态） |

- 降级计数器 → 告警规则映射现状：三个 `exam.*.degraded` 计数器各有一条对应告警，**映射完整**。
- 无计数指标的两个既有降级面（行为计数、在线状态）与错题缓存退化：属旁路/诊断性质，不设告警；
  故障期的间接信号由 AuthDegraded / Http5xxRatioHigh 等兜住。
- 硬依赖面的故障信号：无业务计数器，由 `ExamOnlineDown`（Redis 连不上时应用侧报错激增）与
  `Http5xxRatioHigh` 间接呈现；Redis 自身存活监控（exporter）属部署栈观测，不在本 runbook 重复登记。
- 守护：`AlertAssetsTest`（EXPECTED_ALERTS 集合 + known 指标名断言）防止规则与代码漂移。

## 四、快速恢复流程与故障期学生端行为指引

**恢复流程（按序）**：

1. 确认告警来源：AuthDegraded / RateLimitDegraded / SubmitLockDegraded 任一触发即说明应用侧已感知
   Redis 不可用；结合 `Http5xxRatioHigh` 与部署栈 Redis 监控定位是连接故障还是实例宕机。
2. 恢复 Redis 实例/连接（重启容器或修复网络）。**不要清空数据**：键族全 TTL 自管理，恢复后自愈。
3. 恢复后观察（无需重启应用）：降级计数停止增长、告警解除；降级期间积累的
   限流/锁定/计数键按各自 TTL 自然过期；交卷对账扫描器（ExamSweepService）自动补发降级期
   「MQ 发送失败暂存草稿」的答卷；缓存按 TTL 与互斥回源自愈。
4. 复核降级半径内的业务申诉：降级期间发生的「吊销延迟生效」（已登出/被踢 token 至多再活剩余 TTL）、
   故障期新登录失败、锁定暂缺——逐条对照矩阵口径解释，不需人工修数。

**故障期学生端行为指引**（教师/客服可按此答复）：

- **不要刷新页面、不要关闭浏览器**：草稿是已声明的硬依赖面，故障期刷新后无法从服务端恢复
  上次成功保存之后的作答。页面保持打开，本地倒计时继续。
- **作答照常**：已作答内容在页面内存中，Redis 恢复后自动保存链路自愈，恢复正常后**立即触发一次保存**。
- **交卷**：正常点交卷即可（交卷载荷直接提交，不依赖草稿读取；交卷锁故障自动降级放行）。
  若交卷提示「系统繁忙，答卷已锁定，答案将自动补交」——答案已暂存草稿，恢复后由对账扫描自动补交，
  **不要反复重试交卷**。
- **故障期新登录会失败**（令牌签发依赖 Redis）：已登录用户不要主动退出；被登出的用户等恢复后重新登录。
- **找回密码在故障期不可用**，恢复后重试。

## 五、口径落地的变更链（审计）

- harden-auth-interceptor-redis-degradation（拦截器降级，2026-10-17）
- harden-login-guard-redis-degradation（登录守卫六触点降级，2026-10-17，A1）
- harden-submit-lock-redis-degradation（交卷锁两类失败区分，2026-10-17，D1）
- add-redis-dependency-runbook（本卡：口径声明 + 矩阵 + SubmitLockDegraded 告警补齐，2026-10-17，B2+C2）
