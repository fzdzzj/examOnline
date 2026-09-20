package com.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.audit.dto.AuditLogResponse;
import com.exam.audit.entity.AuditLog;
import com.exam.audit.mapper.AuditLogMapper;
import com.exam.common.RequestIdFilter;
import com.exam.config.TraceIdInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 安全审计日志服务：把登录、账户锁定等安全事件落到 audit_log 表。
 *
 * <p><b>同步写，不用 @Async。</b>异步边界上 MDC 与 SecurityContext 都不传递，旧实现因此在
 * 两处静默失效：它用反射取当前登录用户（工作线程里 SecurityContext 是空的，取到也从未被使用），
 * 又当场 new 了一个随机 UUID 当 traceId（与本次请求的链路毫无关系，拿它查不到任何 trace）。
 * 同步多写一条 insert 换来"审计真的可读可查"，登录路径本就有 BCrypt 与多次查询，不构成瓶颈。
 *
 * <p><b>写失败不阻断业务。</b>审计是旁路：持久化异常一律吞掉并打 ERROR，绝不因审计库抖动
 * 把一次正常登录变成 500。代价是这类缺口只能靠 ERROR 日志发现。
 */
@Slf4j
@Service
public class AuditLogService {

    /** 单页上限：控制器用 @Max 拒绝越界请求，这里再夹紧一次兜底。 */
    private static final long MAX_PAGE_SIZE = 100;

    private final AuditLogMapper auditLogMapper;

    public AuditLogService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 记录登录事件。userId 由调用方传入——登录处已握有 User，不必为写审计再查一次库；
     * 账号不存在时传 null（对外与"密码错误"共用同一提示，此处不做区分以防枚举）。
     */
    public void logLoginEvent(Long userId, String username, boolean success, String ip, String reason) {
        record(userId, username, AuditLog.ACTION_LOGIN, ip,
                success ? AuditLog.STATUS_SUCCESS : AuditLog.STATUS_FAILURE, reason);
    }

    /** 记录账户锁定（连续登录失败触发）。 */
    public void logAccountLock(String username, String ip, String lockReason) {
        record(null, username, AuditLog.ACTION_ACCOUNT_LOCKED, ip, AuditLog.STATUS_WARNING, lockReason);
    }

    /**
     * 分页查询审计事件（管理员）。按 id 倒序即"最近优先"，故不提供时间区间参数——
     * 翻最近若干页已覆盖"查近期事件"这一诉求，加 from/to 只会多两个校验面。
     *
     * <p>返回值是页内记录而非 {@code Page} 信封：与 {@code ExamController.page} 同一口径，
     * 不把 MyBatis-Plus 的分页内部字段（searchCount / orders 等）泄进对外契约。
     *
     * <p>size 走 {@code Math.min} 兜底：控制器的 {@code @Max} 负责拒绝，这里负责夹紧，二者互补。
     */
    public List<AuditLogResponse> page(long page, long size, String username, String action) {
        Page<AuditLog> raw = auditLogMapper.selectPage(new Page<>(page, Math.min(size, MAX_PAGE_SIZE)),
                Wrappers.<AuditLog>lambdaQuery()
                        .eq(StringUtils.hasText(username), AuditLog::getUsername, username)
                        .eq(StringUtils.hasText(action), AuditLog::getAction, action)
                        .orderByDesc(AuditLog::getId));
        return raw.getRecords().stream().map(AuditLogService::toResponse).toList();
    }

    private static AuditLogResponse toResponse(AuditLog e) {
        AuditLogResponse r = new AuditLogResponse();
        r.setId(e.getId());
        r.setTraceId(e.getTraceId());
        r.setUserId(e.getUserId());
        r.setUsername(e.getUsername());
        r.setAction(e.getAction());
        r.setIpAddress(e.getIpAddress());
        r.setStatus(e.getStatus());
        r.setDetails(e.getDetails());
        r.setCreatedTime(e.getCreatedTime());
        return r;
    }

    private void record(Long userId, String username, String action, String ip, String status, String details) {
        try {
            AuditLog entry = new AuditLog();
            entry.setTraceId(currentTraceId());
            entry.setUserId(userId);
            entry.setUsername(username);
            entry.setAction(action);
            entry.setIpAddress(ip);
            entry.setStatus(status);
            entry.setDetails(truncate(details));
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            log.error("审计写入失败: username={} action={} status={}", username, action, status, e);
        }
    }

    /** 优先取 OTel traceId（可跳 Jaeger 查链路），无 span 的线程回落 requestId。 */
    private String currentTraceId() {
        String traceId = MDC.get(TraceIdInterceptor.MDC_KEY);
        return traceId != null ? traceId : MDC.get(RequestIdFilter.MDC_KEY);
    }

    /** details 列宽 512，超长截断而不是让整条审计写失败。 */
    private String truncate(String details) {
        if (details == null || details.length() <= 512) {
            return details;
        }
        return details.substring(0, 512);
    }
}
