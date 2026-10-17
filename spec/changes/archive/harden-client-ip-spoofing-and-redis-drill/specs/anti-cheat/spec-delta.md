# anti-cheat 规范变更

## MODIFIED Requirements

### Requirement: 考试 IP 范围准入校验

#### Scenario: 优先采信反代覆写头 X-Real-IP 防范 XFF 伪造 (harden-client-ip-spoofing-and-redis-drill)

GIVEN 客户端发起进入考试、登录或验证码发送请求
WHEN 请求经过受信任反向代理（Nginx TLS 终结）转发，携带不可伪造的 `X-Real-IP` 请求头
THEN `ClientIpResolver` SHALL 优先采信 `X-Real-IP` 对应地址作为客户端真实 IP
AND 忽略客户端在 `X-Forwarded-For` 自行注入的伪造 IP 前缀
AND 当且仅当 `X-Real-IP` 缺失或非法时，系统 SHALL 安全回退至既有 `X-Forwarded-For` 解析逻辑与直连地址
