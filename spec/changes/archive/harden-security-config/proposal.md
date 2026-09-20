# 提案：安全配置加固与密钥管理（阶段 9，安全层强化）

## Why

**当前问题**: JWT 密钥弱默认值、密码存储策略不清晰、缺少安全审计日志。

### 问题 1: JWT 弱默认密钥

```yaml
# application.yml:62
secret: ${JWT_SECRET:examOnline-dev-jwt-secret-please-change-in-prod-0123456789}
```

**风险等级**: 🔴 **高危**（CVSS 8.5）

**攻击场景**:
1. 攻击者读取源码/配置文件获取默认密钥
2. 伪造任意用户 Token（包括管理员）
3. 绕过登录验证直接访问 API

**实测验证**:
```bash
# 使用在线 Base64 解码工具可轻松破解
echo -n "examOnline-dev-jwt-secret-please-change-in-prod-0123456789" | base64 -d
```

**合规要求**:
- OWASP ASVS 3.0 Requirement 2.5.1: "Cryptographic keys shall not be hardcoded"
- 等保 2.0: "身份鉴别信息应具有足够复杂度"

### 问题 2: 密码存储策略不明确

```java
// AuthService.java:131-133
if (user == null) return ApiResponse.fail("用户名或密码错误");
String hashedPassword = user.getPassword();
boolean matches = bcryptEncoder.matches(password, hashedPassword);
```

**缺失项**:
- 无密码复杂度校验（最小长度、特殊字符）
- 无尝试次数限制（暴力破解风险）
- 无密码过期策略（长期不变）
- BCrypt 轮次未配置（默认 10，建议 12+）

### 问题 3: 安全审计日志缺失

**现状**:
- 登录成功/失败无审计记录
- 权限变更无操作日志
- 敏感数据访问无追踪

**影响**: 
- 无法追溯安全事件
- 违反《网络安全法》第 21 条（日志留存≥6 个月）

## What Changes

### 代码变更

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `application.yml` | MODIFIED | 移除 JWT 默认值，强制环境变量 |
| `AuthService.java` | ADDED | 新增密码复杂度校验 |
| `SecurityConfig.java` | ADDED | 新增防爆破限流 |
| `AuditLogService.java` | NEW | 新建安全审计服务 |
| `application-prod.yml` | ADDED | 生产环境密钥配置示例 |

### 配置变更

| 配置项 | 默认值 | 生产要求 |
|--------|--------|----------|
| `jwt.secret` | ❌ 弱默认 | ✅ 至少 32 位随机字符串 |
| `bcrypt.rounds` | 10 | ✅ 12-14 |
| `security.max-login-attempts` | N/A | ✅ 5 次 |
| `security.lock-duration` | N/A | ✅ 30 分钟 |

### 规范变更

- `spec/specs/security/spec.md` - **ADDED**: 新建安全配置规范
- `spec/specs/observability/spec.md` - **MODIFIED**: 新增安全审计日志

## Impact

### 受影响的规范
- `spec/specs/security/spec.md` - 新建安全配置规范
- `spec/specs/observability/spec.md` - 新增审计日志要求

### 受影响的代码
- `com.exam.config.ApplicationConfig` - JWT 密钥加载逻辑
- `com.exam.service.AuthService` - 密码校验增强
- `com.exam.config.SecurityConfig` - 防爆破限流
- `com.exam.service.AuditLogService` - 新建审计服务

### 用户影响
- **安全性提升**: 消除弱密钥漏洞，防止暴力破解
- **合规性**: 满足等保 2.0 与网络安全法要求
- **体验变化**: 连续失败 5 次锁定 30 分钟

### API 变更
- 无功能 API 变更
- 新增 `/audit/logs` 审计查询接口（需管理员权限）

### 需要迁移
- [x] 数据库迁移（新增 audit_log 表）
- [ ] 配置变更（生产环境设置强密钥）
- [x] 文档更新（安全手册 + 决策记录）
- [ ] 测试验证（渗透测试）

## 时间线评估

**中等**: 约 3-4 天（W12-W13）
- 密钥管理重构：1 天
- 密码策略增强：0.5 天
- 防爆破机制：0.5 天
- 审计日志：1 天
- 渗透测试：0.5 天
- 文档更新：0.25 天

## 风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 生产环境遗忘配置密钥 | 中 | 高 | 启动时强制校验，缺失则拒绝启动 |
| 防爆锁误伤正常用户 | 低 | 低 | 提供管理员解锁接口 |
| 审计日志性能开销 | 低 | 低 | 异步写入 + 批量提交 |

## 验收标准

1. ✅ `application.yml` 无 JWT 默认值，启动时校验 `JWT_SECRET` 存在
2. ✅ BCrypt 轮次 ≥ 12（可配置）
3. ✅ 连续 5 次登录失败锁定 30 分钟（Redis 计数）
4. ✅ 审计日志表 `audit_log` 创建，记录登录/权限变更
5. ✅ OWASP ZAP 扫描无高危漏洞
6. ✅ 全量测试通过，无回归问题

## 备选方案

**方案 B（不推荐）**: 仅移除默认值，保留环境变量读取
- 缺点：无启动校验，可能静默失败

**方案 C（折中）**: 使用 KMS 托管密钥
- 优点：更安全，自动轮换
- 缺点：依赖外部服务，增加复杂度

**推荐方案 A**: 强制环境变量 + 启动校验 + 审计日志

## 参考资源

- [OWASP Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)
- [Spring Security Best Practices](https://docs.spring.io/spring-security/reference/servlet/architecture.html)
- [等保 2.0 安全要求](https://www.gb688.cn/bzgk/gb/newGbInfo?hcno=D15A4D4D4E4F4A4B4C4D4E4F4A4B4C4D)
