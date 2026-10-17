# 变更提案：fix-mail-smtp-timeout-and-catch

## 为什么（Why）

2026-10-16/17 后端横切首扫中危发现 M4（发现台账 `docs/功能待办清单.md` B.2 中 4 列表）：

- **SMTP 无任何超时配置**：三处 profile（application / -dev / -prod）的 `spring.mail.properties` 均只有 `mail.smtp.auth` / `mail.smtp.ssl.enable` 两键——JavaMail 的连接/读/写默认**无限等待**。找回密码端点 `POST /api/password/reset-code` 同步发信，SMTP 服务器不可达/无响应时该请求线程无限挂起，故障期间持续累积占满 Tomcat 工作线程，可拖垮整个服务（M4 卡面原话「无限等挂线程」）。
- **捕面缺口 → 500 裸奔**：`MailService.sendResetCode`（L52）只捕 `jakarta.mail.MessagingException`。Spring 发送链路上连接/读/写超时、认证失败、SMTP 拒绝等最终都以 `MailException` 系（`MailSendException` extends `MailException`）形态从 `JavaMailSender.send()` 冒出——这是运行时异常，现有 catch 罩不住，直穿 `GlobalExceptionHandler` 的 `Exception` 兜底变成裸 500「系统内部错误」，与既有 `MessagingException` 分支「记 ERROR 日志 + 转业务异常」的诚实失败语义不一致。

## 改什么（What）

1. **三处 yml 补 SMTP 三向超时**（缺省各 10000ms，卡面建议值）：`application.yml` 新增 `spring.mail.properties` 块（base 块此前不存在，为 test profile 等未覆盖 profile 提供底座）、`application-dev.yml` / `application-prod.yml` 在既有 `spring.mail.properties` 下追加三键——`mail.smtp.connectiontimeout` / `mail.smtp.timeout` / `mail.smtp.writetimeout`。Spring 属性合并按 key 逐项合并，base 底座 + 各 profile 显式声明共存不冲突。
2. **捕面拓宽至 `MailException` 系**：`catch (MessagingException e)` 改 `catch (MessagingException | MailException e)`——处置语义逐字不动（同一 ERROR 日志语句 + 同一 `BusinessException(INTERNAL_ERROR, "验证码发送失败，请稍后重试或联系管理员")`），javadoc 注明超时以 `MailSendException` 形态冒出与捕面拓宽原因。诚实失败不吞：失败仍可见（日志 + 明确业务错误文案），不再 500 裸奔。

## 影响面（Impact）

- 单类（`MailService`）+ 三处 yml + 测试；零 DDL（schema.sql 零 diff）、零契约变更（openapi paths 97 不变）、零前端（frontend 零 diff）、零新依赖。
- 异常处置语义对齐调用方与 GlobalExceptionHandler 既有口径：`AuthService.sendResetCode` 不 catch（透传），`BusinessException(INTERNAL_ERROR)` → 500 + 业务文案——与 MessagingException 分支及 harden-auth-edge-cases 后的既有行为完全同构，只是把 MailException 系从「兜底裸奔」拉回同一口径。
- 行为面：SMTP 未配置的日志兜底分支（dev 默认态）零改动；发送成功路径零改动。
- 门禁基线：仅 backendTests 预期增长；S 级。

## 技术注记

- 超时 → `MailSendException` 的传导链：JavaMail transport connect/read/write 抛 `MessagingException`（内裹 `SocketTimeoutException` 等）→ `JavaMailSenderImpl.send` 捕获后包成 `MailSendException`（运行时）抛出——故捕 `MailException` 即覆盖全部超时形态。
- 多捕顺序：`MessagingException`（受检）与 `MailException`（运行时）无继承关系，multi-catch 无二义性；公共超类型为 Exception，日志语句不变。
- 等价变异体预警：若变异为「拓宽到 `catch (Exception)`」，在既有测试面上与 `MessagingException | MailException` 不可区分（两路径同文案同码）——如变异环节实际遇到，按等价变异体如实登记不强杀。
- 词法护栏的局限（如实声明）：护栏只保证三处 yml 的三键声明存在且值为正整数，不能证明运行期 JavaMail 真以该超时运行——base yml 的生效层由绑定测试（`MailProperties` 装配）补足；dev/prod 的绑定不在测试上下文装载范围，靠护栏防「删行/改键名」回退。

## 任务阶段

- 阶段 0 & 1：立项三件套与在途总账登记。
- 阶段 2：测试先红（捕面用例 + 超时配置护栏/绑定用例）+ 修复转绿 + 变异击红留证（还原只捕 MessagingException 形态）。
- 阶段 3：全量门禁实测、基线上调、规范合入、归档、`--no-ff` 合并入 main。
