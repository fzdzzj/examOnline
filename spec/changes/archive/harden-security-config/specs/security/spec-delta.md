# 规范差异：security（安全配置与审计）

本文件包含对 `spec/specs/security/spec.md` 的规范变更。

## ADDED Requirements

### Requirement: 密钥管理

```markdown
WHEN 配置敏感密钥（如 JWT Secret、数据库密码）,
系统 SHALL:
1. 禁止在代码或配置文件中硬编码默认值
2. 强制通过环境变量或密钥管理服务注入
3. 启动时校验必要密钥存在，缺失则拒绝启动

#### Scenario: JWT 密钥加载
GIVEN 应用启动阶段
WHEN 读取 JWT_SECRET 环境变量
THEN 若未设置则抛出 IllegalStateException
AND 终止启动流程
AND 日志输出："ERROR: JWT_SECRET environment variable is required"

#### Scenario: 生产环境配置
GIVEN production 环境
WHEN 部署应用
THEN 使用至少 32 位随机字符串作为密钥
AND 密钥存储于 KMS 或 Vault（可选）
AND 定期轮换（90 天周期）
```

---

### Requirement: 密码策略

```markdown
### Requirement: 密码复杂度
WHEN 用户注册或修改密码，
系统 SHALL 验证密码满足以下要求：
- 最小长度：8 字符
- 包含大写字母（A-Z）
- 包含小写字母（a-z）
- 包含数字（0-9）
- 不包含用户名或邮箱子串

#### Scenario: 弱密码拒绝
GIVEN 用户提交密码 "password123"
WHEN 调用 register() 或 updatePassword()
THEN 返回错误码 PASSWORD_WEAK
AND 提示"密码需包含大小写字母和数字，长度至少 8 位"

#### Scenario: BCrypt 轮次
GIVEN 密码哈希需求
WHEN 调用 bcryptEncoder.encode()
THEN 使用 rounds = 12（可配置 10-14）
AND 生成的 hash 以 $12$ 开头
```

---

### Requirement: 防爆破保护

```markdown
### Requirement: 登录限流
WHEN 用户尝试登录，
系统 SHALL:
- 记录失败次数（Redis key: auth:fail:{username}）
- 连续失败 5 次后锁定账户 30 分钟
- 锁定期间返回友好提示而非详细错误

#### Scenario: 正常登录
GIVEN 用户首次输入错误密码
WHEN 登录失败
THEN 失败计数 +1
AND 返回"用户名或密码错误"

#### Scenario: 账户锁定
GIVEN 用户已连续失败 5 次
WHEN 第 6 次尝试登录
THEN 返回"账户已锁定，请 30 分钟后重试"
AND 不再验证密码
AND 记录审计日志"account_locked_by_brute_force"

#### Scenario: 解锁机制
GIVEN 被锁定的账户
WHEN 管理员调用 unlockUser(userId)
THEN 清除 Redis 失败计数
AND 允许重新登录
AND 发送通知邮件/短信
```

---

### Requirement: 安全审计日志

```markdown
### Requirement: 审计事件记录
WHEN 发生安全相关事件，
系统 SHALL 记录到 audit_log 表，包含：
- 用户 ID（匿名化：anonymous）
- 事件类型（LOGIN_SUCCESS, LOGIN_FAIL, PERMISSION_CHANGE）
- IP 地址（X-Forwarded-For）
- 时间戳（精确到毫秒）
- 详情（JSON 格式，脱敏）

#### Scenario: 登录成功
GIVEN 用户正确输入凭据
WHEN 认证完成
THEN 记录 audit_log:
  {
    "user_id": 123,
    "action": "LOGIN_SUCCESS",
    "ip": "192.168.1.100",
    "timestamp": "2026-09-19T10:30:00.000Z",
    "details": {"method": "jwt", "device": "Chrome/Windows"}
  }

#### Scenario: 登录失败
GIVEN 用户输入错误密码
WHEN 认证失败
THEN 记录 audit_log:
  {
    "user_id": "anonymous",
    "action": "LOGIN_FAIL",
    "ip": "192.168.1.100",
    "details": {"reason": "invalid_password"}
  }

#### Scenario: 权限变更
GIVEN 管理员修改用户角色
WHEN saveUserRole() 完成
THEN 记录 audit_log:
  {
    "user_id": 100,
    "action": "PERMISSION_CHANGE",
    "target_user_id": 123,
    "details": {"old_role": "STUDENT", "new_role": "TEACHER"}
  }
```

---

### Requirement: 敏感数据访问追踪

```markdown
### Requirement: 数据访问审计
WHEN 查询敏感数据（成绩、个人信息）,
系统 SHALL 记录访问日志。

#### Scenario: 成绩查询
GIVEN 学生查看自己的成绩单
WHEN ScoreController.getMyScores() 执行
THEN 记录 audit_log:
  {
    "action": "SCORE_VIEW",
    "target_exam_id": 456,
    "timestamp": "..."
  }

#### Scenario: 批量导出
GIVEN 教师导出全班成绩
WHEN exportAllScores() 完成
THEN 记录 audit_log:
  {
    "action": "SCORE_EXPORT",
    "count": 100,
    "file_size_mb": 2.5
  }
```

---

## MODIFIED Requirements

### Requirement: 认证机制

**原需求文本**:
```markdown
WHEN 用户登录，
系统 SHALL 生成 JWT Token 并返回。
```

**新需求文本**:
```markdown
WHEN 用户登录，
系统 SHALL:
1. 验证密码复杂度符合策略
2. 记录登录审计日志
3. 生成 JWT Token（HS256 算法）
4. Token 有效期≤24 小时
5. 刷新 Token 时验证原始凭证有效性
```

#### Scenario: Token 有效期
GIVEN 用户登录成功
WHEN 获取 JWT Token
THEN access_token 有效期 ≤ 2 小时
AND refresh_token 有效期 ≤ 7 天
AND Token payload 包含 userId, role, exp, iat

---

## REMOVED Requirements

无移除项。

---

## 规范变更总结

| 类型 | 需求名称 | 变更说明 |
|------|---------|----------|
| ADDED | 密钥管理 | 禁止硬编码，强制环境变量 |
| ADDED | 密码策略 | 复杂度规则 + BCrypt 轮次 |
| ADDED | 防爆破保护 | 5 次失败锁定 30 分钟 |
| ADDED | 安全审计日志 | 登录/权限/数据访问追踪 |
| ADDED | 敏感数据追踪 | 成绩查看与导出审计 |
| MODIFIED | 认证机制 | 新增 Token 有效期限制 |

---

## 验证清单

- [ ] `grep -r 'JWT_SECRET:' application.yml` 结果为空
- [ ] 本地删除 JWT_SECRET 变量，应用拒绝启动
- [ ] 注册弱密码返回 PASSWORD_WEAK 错误
- [ ] 连续 5 次错误密码后账户锁定
- [ ] `audit_log` 表存在且记录完整
- [ ] OWASP ZAP 扫描无高危漏洞
- [ ] Grafana Dashboard 可见登录成功率趋势

---

## 参考示例

### ✅ 正确示例：密钥加载

```java
@Configuration
public class SecurityConfig {
    
    @Value("${JWT_SECRET}")
    private String jwtSecret;
    
    @PostConstruct
    public void validateSecret() {
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalStateException(
                "JWT_SECRET must be at least 32 characters"
            );
        }
    }
}
```

### ❌ 错误示例：硬编码密钥

```java
// ❌ 禁止
private static final String JWT_SECRET = "weak-secret-123";
```

### ✅ 正确示例：防爆锁实现

```java
@Service
public class AuthService {
    
    @Autowired
    private RedisTemplate<String, Integer> redisTemplate;
    
    public ApiResponse<?> login(String username, String password) {
        int failCount = redisTemplate.opsForValue().getOrDefault(
            "auth:fail:" + username, 0
        );
        
        if (failCount >= 5) {
            log.warn("Account locked: {}", username);
            return ApiResponse.fail("账户已锁定");
        }
        
        // ... 验证逻辑
        if (success) {
            redisTemplate.delete("auth:fail:" + username);
        } else {
            redisTemplate.opsForValue().increment("auth:fail:" + username);
        }
    }
}
```
