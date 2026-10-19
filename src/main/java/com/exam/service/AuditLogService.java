package com.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.audit.dto.AuditLogResponse;
import com.exam.audit.entity.AuditLog;
import com.exam.audit.mapper.AuditLogMapper;
import com.exam.anticheat.ip.ClientIpResolver;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.RequestIdFilter;
import com.exam.config.TraceIdInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 安全审计日志服务：把登录、账户锁定等安全事件与考务核心写操作（考试发布/删除、
 * 成绩发布/撤回、管理员强制下线）落到 audit_log 表。
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

    // ==================== 考务核心操作审计（add-exam-operation-audit-logs） ====================

    /** 发布考试。操作人/IP 从当前请求现场提取，details 带关联考试 ID 与标题。 */
    public void logExamPublish(Long examId, String examTitle) {
        recordOperation(AuditLog.ACTION_EXAM_PUBLISH, "发布考试: examId=" + examId + ", title=" + examTitle);
    }

    /** 删除考试（软删）。 */
    public void logExamDelete(Long examId, String examTitle) {
        recordOperation(AuditLog.ACTION_EXAM_DELETE, "删除考试: examId=" + examId + ", title=" + examTitle);
    }

    /** 发布成绩。count 为本次发布的已批改答卷数。 */
    public void logScorePublish(Long examId, String examTitle, int count) {
        recordOperation(AuditLog.ACTION_SCORE_PUBLISH,
                "发布成绩: examId=" + examId + ", title=" + examTitle + ", count=" + count);
    }

    /**
     * 考中应急顺延与个性化补时（add-exam-time-extension）：details 记模式（GLOBAL/INDIVIDUAL）、
     * 顺延时长、受影响在途答卷数、原因，个人模式附受影响学生名单；操作人/IP 由公共入口提取。
     */
    public void logExamExtendTime(Long examId, String examTitle, int extensionMinutes,
                                  boolean global, int affectedCount, List<Long> studentIds, String reason) {
        recordOperation(AuditLog.ACTION_EXAM_EXTEND_TIME,
                "顺延考试时间: examId=" + examId + ", title=" + examTitle
                        + ", mode=" + (global ? "GLOBAL" : "INDIVIDUAL")
                        + ", minutes=" + extensionMinutes
                        + ", affectedSubmissions=" + affectedCount
                        + (global ? "" : ", studentIds=" + studentIds)
                        + ", reason=" + (reason == null ? "" : reason));
    }

    /** 撤回成绩。reason 由撤回接口强制必填（审计要求）。 */
    public void logScoreRevoke(Long examId, String examTitle, String reason) {
        recordOperation(AuditLog.ACTION_SCORE_REVOKE,
                "撤回成绩: examId=" + examId + ", title=" + examTitle + ", reason=" + reason);
    }

    // ==================== 成绩链对账（add-score-chain-reconciliation） ====================

    /** 无登录上下文（定时对账线程）时的系统操作人：audit_log.username 是 NOT NULL，缺省会写失败。 */
    static final String SYSTEM_OPERATOR = "SYSTEM";

    /**
     * 对账确定性缺失的自动补齐：记考试/答卷/回填后的总分与补齐依据。
     * 定时对账在调度线程执行，{@link SecurityUtil} 取不到操作人时以 {@link #SYSTEM_OPERATOR}
     * 落审计（username NOT NULL），userId 仍为 NULL——不能因为「没有请求上下文」让补齐动作没有审计。
     */
    public void logScoreReconcileAutoFix(Long examId, Long submissionId, String totalScore) {
        recordOperationOrSystem(AuditLog.ACTION_SCORE_RECONCILE_FIX,
                "对账补齐总分: examId=" + examId + ", submissionId=" + submissionId
                        + ", totalScore=" + totalScore
                        + ", basis=判分完成但总分未回填（同源汇总口径）");
    }

    /** 差异票人工关闭：记票 ID、差异类型与定位，reason 为人工填写的处置说明（可空）。 */
    public void logScoreReconcileDiffClose(Long diffId, String diffType, Long examId,
                                           Long submissionId, String reason) {
        recordOperationOrSystem(AuditLog.ACTION_SCORE_RECONCILE_CLOSE,
                "关闭对账差异票: diffId=" + diffId + ", type=" + diffType
                        + ", examId=" + examId + ", submissionId=" + submissionId
                        + ", reason=" + (reason == null ? "" : reason));
    }

    /**
     * 同上，但无请求上下文时以系统操作人落审计（对账既有手动（有操作人）也有定时（无操作人）两条路径）。
     */
    private void recordOperationOrSystem(String action, String details) {
        LoginUser operator = SecurityUtil.getCurrentUser();
        record(operator == null ? null : operator.getId(),
                operator == null ? SYSTEM_OPERATOR : operator.getUsername(),
                action, currentClientIp(), AuditLog.STATUS_SUCCESS, details);
    }

    /** 管理员强制下线。targetUserId 为被踢用户。 */
    public void logUserKick(Long targetUserId) {
        recordOperation(AuditLog.ACTION_USER_KICK, "强制下线: targetUserId=" + targetUserId);
    }

    /**
     * 考务操作审计公共入口：业务调用点都在操作成功之后，状态恒为 SUCCESS；
     * 操作人取 {@link SecurityUtil}，IP 经 {@link #currentClientIp()} 从请求现场解析。
     */
    private void recordOperation(String action, String details) {
        LoginUser operator = SecurityUtil.getCurrentUser();
        record(operator == null ? null : operator.getId(),
                operator == null ? null : operator.getUsername(),
                action, currentClientIp(), AuditLog.STATUS_SUCCESS, details);
    }

    /**
     * 客户端真实 IP：复用防作弊域的 {@link ClientIpResolver}（X-Real-IP 优先、XFF 首项次之、
     * 直连兜底），不另起一套会漂移的解析逻辑。无请求上下文（定时任务/MQ 线程直调）时返回 null。
     */
    private String currentClientIp() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttributes) {
            return ClientIpResolver.resolve(servletAttributes.getRequest());
        }
        return null;
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
