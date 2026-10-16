package com.exam.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.CacheConfig;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.monitoring.metrics.BusinessMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 考试状态机服务（spec「考试状态机」需求）：
 *
 * <p>流转图：未开始 → 进行中 → 已结束 → 已批改 → 已发布（§4.3）。
 * 全部迁移经乐观锁 CAS（{@code UPDATE ... WHERE status=? AND version=?}）执行——
 * 并发迁移同一考试时仅一个请求影响行数为 1，另一个影响 0 行并报状态冲突
 * （spec「并发流转仅一次」场景），杜绝状态脏写。
 *
 * <p>定时推进不依赖外部调度中间件：Spring @Scheduled 固定间隔扫表 +
 * 启动时（ApplicationReadyEvent）补偿扫表，服务重启后停机期间错过的迁移自动补齐。
 *
 * <p><b>多实例策略（刻意不加分布式调度锁）</b>：多实例下每个实例都会各扫一遍，正确性由下游幂等
 * （状态 CAS + {@code INSERT IGNORE} + 唯一索引）保证，并有 {@code MultiInstanceSweepSafetyTest} 证明。
 * 代价是 N 实例重复扫描；换来的是<b>定时兜底不依赖 Redis 可用性</b>——若加调度锁，Redis 故障会让
 * 状态推进整体停摆，那比重复扫描严重得多。重复扫描可通过
 * {@code exam.sweep.duplicate_detected}（task=state-advance）观测。
 *
 * <p>将来若要省资源的可选路径：Redis 调度锁必须 <b>fail-open</b>
 * （抢不到锁或 Redis 异常时照常执行，退化回"重复扫但幂等"），
 * <b>禁止</b>加 fail-close 的锁把兜底能力锁死。
 *
 * <p>事务统一显式 rollbackFor=Exception.class（见 data-consistency 规范），防未来受检异常静默不回滚。
 * 注意：{@link #casTransition} 由外部入口经代理调用，注解式声明有效；而 {@link #autoAdvance}
 * 只被本类 {@code @Scheduled}/{@code @EventListener} 入口自调用，注解会静默失效，
 * 故那里改用编程式事务（见 {@link #autoAdvance()} 的 javadoc）。
 */
@Slf4j
@Service
public class ExamStateMachineService {

    /** 状态机唯一权威：key=源状态，value=允许迁移到的目标状态集合（禁止跳级/回退） */
    private static final Map<Integer, Set<Integer>> LEGAL_TRANSITIONS = Map.of(
            Exam.STATUS_NOT_STARTED, Set.of(Exam.STATUS_IN_PROGRESS),
            Exam.STATUS_IN_PROGRESS, Set.of(Exam.STATUS_ENDED),
            Exam.STATUS_ENDED, Set.of(Exam.STATUS_GRADED),
            Exam.STATUS_GRADED, Set.of(Exam.STATUS_PUBLISHED));

    private final ExamMapper examMapper;
    private final AbsenceService absenceService;
    private final BusinessMetrics metrics;
    private final CacheManager cacheManager;
    private final TransactionTemplate transactionTemplate;

    public ExamStateMachineService(ExamMapper examMapper, AbsenceService absenceService,
                                   BusinessMetrics metrics, CacheManager cacheManager,
                                   PlatformTransactionManager transactionManager) {
        this.examMapper = examMapper;
        this.absenceService = absenceService;
        this.metrics = metrics;
        this.cacheManager = cacheManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 单场考试的状态迁移（教师操作入口，如提前结束）：
     * 校验迁移合法性后按当前 version 执行 CAS，影响 0 行视为并发冲突抛 409。
     *
     * @return 迁移后的考试（status 已更新，version 已 +1）
     */
    @Transactional(rollbackFor = Exception.class)
    public Exam casTransition(Long examId, int expectedStatus, int newStatus) {
        if (!LEGAL_TRANSITIONS.getOrDefault(expectedStatus, Set.of()).contains(newStatus)) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "非法状态迁移: " + expectedStatus + " -> " + newStatus);
        }
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        int rows = examMapper.casUpdateStatus(examId, expectedStatus, newStatus, exam.getVersion());
        if (rows == 0) {
            // CAS 命中 0 行：状态/版本已被并发请求改变，本次迁移作废
            throw new BusinessException(ResponseCode.STATE_CONFLICT);
        }
        log.info("考试 {} 状态迁移 {} -> {}", examId, expectedStatus, newStatus);
        exam.setStatus(newStatus);
        exam.setVersion(exam.getVersion() + 1);
        return exam;
    }

    /**
     * 定时推进一轮（spec「定时发布/标准流转」场景）：
     * <ol>
     *   <li>定时开考：已发布（学生可见）且未开始的考试，到达 start_time 自动 未开始→进行中；
     *       未发布的考试不自动开考——教师尚未确认，学生也不可见；</li>
     *   <li>自然结束：进行中的考试，到达 end_time 自动 进行中→已结束（不要求已发布：
     *       教师手动开考/提前开始等场景下时间窗依然有效）。</li>
     * </ol>
     * 批量场景下 CAS 影响 0 行只记日志跳过（= 重试语义，下一轮扫表按新状态重算），
     * 与单笔接口抛 409 不同。
     *
     * <p><b>为什么是编程式事务而不是 {@code @Transactional} 注解（勿改回注解）</b>：
     * 本方法的调用者只有 {@link #scheduledAdvance()}（{@code @Scheduled}）与
     * {@link #advanceOnStartup()}（{@code @EventListener}）——两者都在<b>本类内部</b>
     * {@code this.autoAdvance()} 自调用。Spring 的注解式事务只在请求经过代理时生效，
     * 自调用直接命中目标对象、<b>绕过代理</b>，注解在生产定时路径静默失效：当轮 CAS 与
     * {@code markAbsence} 各自独立提交，{@code markAbsence} 瞬时失败即留下"考试已结束但缺考未标"，
     * 而本方法只扫 IN_PROGRESS，ENDED 后无自愈路径（漏标不可自愈）→ 缺考名单永久失真。
     * {@link TransactionTemplate} 直接调用事务基础设施，<b>不依赖调用路径</b>，故永久免疫该问题。
     * 仓内既有先例：{@code QuestionImportExportService} / {@code RetentionService} 同款构造注入。
     *
     * <p><b>事务边界 = 整轮一个事务</b>（与原注解意图一致）：CAS 批量推进 + 全部
     * {@code markAbsence} 同事务——任一 {@code markAbsence} 失败则当轮全部回滚，
     * 受影响考试保持原状态，由下一轮扫表重试补偿（CAS 乐观锁幂等 + {@code exam_absence}
     * 唯一索引 INSERT IGNORE 幂等，重试不产生重复数据）。
     * {@link TransactionTemplate} 默认仅 RuntimeException/Error 回滚；本路径 CAS 影响 0 行只跳过
     * 不抛异常，{@code markAbsence} 与缓存清除均为运行时异常，与原
     * {@code rollbackFor = Exception.class} 语义等价。
     *
     * <p><b>缓存清除在事务提交之后</b>：{@link #clearExamDetailCache()} 移出 {@code execute}
     * 回调——放在事务内会在提交前执行，clear 与 commit 之间存在并发读回填旧状态的窗口。
     *
     * @return 本轮实际迁移的考试数
     */
    public int autoAdvance() {
        Integer moved = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            int count = 0;

            List<Exam> toStart = examMapper.selectList(Wrappers.<Exam>lambdaQuery()
                    .eq(Exam::getStatus, Exam.STATUS_NOT_STARTED)
                    .eq(Exam::getPublished, 1)
                    .le(Exam::getStartTime, now));
            for (Exam exam : toStart) {
                count += casAdvanceQuietly(exam, Exam.STATUS_IN_PROGRESS);
            }

            List<Exam> toEnd = examMapper.selectList(Wrappers.<Exam>lambdaQuery()
                    .eq(Exam::getStatus, Exam.STATUS_IN_PROGRESS)
                    .le(Exam::getEndTime, now));
            for (Exam exam : toEnd) {
                int rows = casAdvanceQuietly(exam, Exam.STATUS_ENDED);
                if (rows > 0) {
                    // 缺考标记锚定"进行中→已结束"这一刻（§8.10）：时间窗彻底关闭、迟到窗口结束，
                    // 此刻"应考 − 有答卷"才是终局缺考判定（迟到学生可能已赶在窗口内交卷，不当误判）。
                    // 幂等：markAbsence 内部 INSERT IGNORE + 唯一索引，多次扫表不重复写。
                    absenceService.markAbsence(exam.getId());
                }
                count += rows;
            }
            return count;
        });
        int advanced = moved == null ? 0 : moved;
        if (advanced > 0) {
            // H-04（harden-core-security-and-state-boundaries）：本轮确有状态流转时主动清空考试详情缓存，
            // 否则定时扫表写库推进的状态要等最多 5 分钟短 TTL 过期后详情接口才读到最新值（脏读）。
            // 必须在事务提交之后执行：提交前清除会被并发读在 clear→commit 之间回填旧状态。
            clearExamDetailCache();
        }
        return advanced;
    }

    /**
     * H-04：主动清空 examDetail 缓存（定时流转直接写库，绕过 {@code ExamService} 的 @CacheEvict 写路径）。
     * cacheManager.getCache 理论不可达 null（缓存已在 {@link CacheConfig} 显式登记），空值兜底防御。
     */
    private void clearExamDetailCache() {
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_EXAM_DETAIL);
        if (cache != null) {
            cache.clear();
            log.info("考试状态机流转后已清除 examDetail 缓存");
        }
    }

    /** 批量推进的单场 CAS：0 行（并发已迁移）静默跳过，不打断同批其他考试。 */
    private int casAdvanceQuietly(Exam exam, int newStatus) {
        int rows = examMapper.casUpdateStatus(exam.getId(), exam.getStatus(), newStatus, exam.getVersion());
        if (rows > 0) {
            log.info("考试 {} 定时迁移 {} -> {}", exam.getId(), exam.getStatus(), newStatus);
        } else {
            // 重复扫描可观测：扫描命中但下游 CAS 已处理
            metrics.countSweepDuplicateDetected("state-advance");
            log.debug("考试 {} 状态已被并发迁移，本轮跳过", exam.getId());
        }
        return rows;
    }

    /** 定时扫表：默认每 10 秒一轮（exam.schedule.fixed-delay-ms 可调）。 */
    @Scheduled(fixedDelayString = "${exam.schedule.fixed-delay-ms:10000}",
            initialDelayString = "${exam.schedule.initial-delay-ms:10000}")
    public void scheduledAdvance() {
        autoAdvance();
    }

    /** 启动扫表：补偿停机期间错过的状态迁移（应用就绪后立即执行一轮）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void advanceOnStartup() {
        int moved = autoAdvance();
        if (moved > 0) {
            log.info("启动扫表推进考试状态 {} 场", moved);
        }
    }
}
