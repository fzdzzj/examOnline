# 规范增量：authentication

## MODIFIED Requirement: 密码找回（追加 Scenario）

> 合入注记：由 fix-mail-smtp-timeout-and-catch 追加（2026-10-17）。背景：SMTP 未配置任何超时（JavaMail 连接/读/写默认无限等待），找回密码端点同步发信——SMTP 故障时请求线程无限挂起持续累积占满工作线程；且 `MailService` 只捕 jakarta `MessagingException`，Spring 发送链路（连接/读/写超时、认证失败、SMTP 拒绝）以 `MailException` 系（`MailSendException`）运行时异常冒出未被捕获，落 `GlobalExceptionHandler` 的 `Exception` 兜底裸 500「系统内部错误」，与既有 `MessagingException` 分支「记 ERROR 日志 + 转业务异常」的诚实失败语义不一致。

### ADDED Scenario: SMTP 三向超时与发送失败显性化

- **WHEN**: 用户请求找回密码触发邮件发送，SMTP 服务器不可达、无响应或发送失败
- **THEN**: SMTP 客户端 SHALL 配置连接/读/写三向超时（`mail.smtp.connectiontimeout` / `mail.smtp.timeout` / `mail.smtp.writetimeout`，缺省各 10000ms，三处 profile 配置齐备）——任一方向挂起 SHALL 在超时后转为发送失败，SHALL NOT 无限等待占住请求线程
- **AND**: 发送失败（含超时）SHALL 记 ERROR 日志并统一转业务异常（INTERNAL_ERROR，文案「验证码发送失败，请稍后重试或联系管理员」）走 GlobalExceptionHandler 既有口径，与既有 MessagingException 处置分支同语义，SHALL NOT 以未捕获异常落 500 兜底裸奔
