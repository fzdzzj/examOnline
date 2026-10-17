# exam-taking 规范变更

## MODIFIED Requirements

### Requirement: 考试预约与开考提醒

#### Scenario: 站内消息查询上限保护 (harden-low-risk-security-residues)

GIVEN 用户请求查询站内消息列表（`GET /api/messages`）
WHEN 请求传入任意数值的 limit 参数（包括负数、0 或超大数值）
THEN 系统 SHALL 对查询上限进行安全钳制，将单次查询记录数限制在 `[1, 100]` 区间内
AND 绝不执行无界或超大 LIMIT 查询，保护服务端内存与数据库资源
