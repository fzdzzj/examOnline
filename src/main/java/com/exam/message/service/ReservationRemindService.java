package com.exam.message.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.exam.entity.Exam;
import com.exam.exam.entity.ExamReservation;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.mapper.ExamReservationMapper;
import com.exam.message.entity.UserMessage;
import com.exam.message.mapper.UserMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 开考提醒投递扫描（add-exam-reservation）：
 *
 * <p>扫描「需预约」(reservation_required=1) ∧ 已发布(published=1) ∧ 未开始(status=0) ∧
 * 距开考 &le; 提前量（exam.reservation.remind-ahead-minutes，默认 30 分钟）的考试，
 * 对其<b>尚未投递提醒</b>（reminded_time IS NULL）的预约行逐行投递一条站内「开考提醒」消息。
 *
 * <p><b>行级幂等</b>：投递 = {@link ExamReservationMapper#markRemindedIfNull} 以
 * {@code UPDATE ... WHERE reminded_time IS NULL} 原子回填，影响行数=1 才写 user_messages。
 * 多实例并发竞争同一行时仅一个影响行数为 1，其余影响 0 行自动跳过——
 * 重复扫描 / 重启 / 多实例竞争均不重发（对齐 add-multi-instance-sweep-safety 竞态安全先例）。
 *
 * <p>与 {@code ExamSweepService} 同构：Spring @Scheduled 固定间隔 + 启动补偿扫表，不依赖外部调度中间件。
 * 服务端不承诺秒级实时（扫描间隔沿用 exam.taking.sweep.*，默认 10s 级）。
 */
@Slf4j
@Service
public class ReservationRemindService {

    private final ExamMapper examMapper;
    private final ExamReservationMapper reservationMapper;
    private final UserMessageMapper messageMapper;
    private final TransactionTemplate transactionTemplate;

    @Value("${exam.reservation.remind-ahead-minutes:30}")
    private int remindAheadMinutes;

    public ReservationRemindService(ExamMapper examMapper,
                                    ExamReservationMapper reservationMapper,
                                    UserMessageMapper messageMapper,
                                    PlatformTransactionManager transactionManager) {
        this.examMapper = examMapper;
        this.reservationMapper = reservationMapper;
        this.messageMapper = messageMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 执行一轮提醒投递：返回本轮实际投递消息数。 */
    public int remind() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.plusMinutes(remindAheadMinutes);

        // 1. 扫描候选考试：需预约 ∧ 已发布 ∧ 未开始 ∧ start_time <= now + 提前量
        List<Exam> exams = examMapper.selectList(Wrappers.<Exam>lambdaQuery()
                .eq(Exam::getReservationRequired, 1)
                .eq(Exam::getPublished, 1)
                .eq(Exam::getStatus, Exam.STATUS_NOT_STARTED)
                .le(Exam::getStartTime, threshold));

        if (exams.isEmpty()) {
            return 0;
        }

        // 2. 一次 IN(examIds) + reminded_time IS NULL 批量取回未投递提醒的预约行（去 N+1），
        //    再按 examId 内存分组走原有逐行投递循环（保持「每场只处理本场预约行」的语义）。
        List<Long> examIds = new ArrayList<>(exams.size());
        for (Exam exam : exams) {
            examIds.add(exam.getId());
        }
        List<ExamReservation> pending = reservationMapper.selectList(
                Wrappers.<ExamReservation>lambdaQuery()
                        .in(ExamReservation::getExamId, examIds)
                        .isNull(ExamReservation::getRemindedTime));
        Map<Long, List<ExamReservation>> pendingByExam = new HashMap<>();
        for (ExamReservation reservation : pending) {
            pendingByExam.computeIfAbsent(reservation.getExamId(), k -> new ArrayList<>())
                    .add(reservation);
        }

        int delivered = 0;
        for (Exam exam : exams) {
            for (ExamReservation reservation : pendingByExam.getOrDefault(exam.getId(), List.of())) {
                delivered += deliverIfAbsent(exam, reservation);
            }
        }
        if (delivered > 0) {
            log.info("开考提醒投递 {} 条（提前量 {} 分钟）", delivered, remindAheadMinutes);
        }
        return delivered;
    }

    /**
     * 单条预约的行级幂等投递：markRemindedIfNull 影响行数=1（本行赢得投递权）才写消息。
     * 影响行数=0（已被并发/其他实例投递，或非候选）→ 静默跳过，不写消息（幂等核心）。
     *
     * <p><b>为什么是编程式事务而不是 {@code @Transactional} 注解（勿改回注解）</b>：
     * 本方法唯一调用点是 {@link #remind()} 的 {@code this.deliverIfAbsent(...)} <b>同类自调用</b>
     * （remind 自身无事务，由 {@code @Scheduled}/{@code @EventListener} 无事务直入）。Spring 的
     * 注解式事务只在请求经过代理时生效，自调用直接命中目标对象、<b>绕过代理</b>，注解静默失效：
     * 认领回填与消息插入各自 auto-commit——消息插入瞬时失败时认领已提交，reminded_time 非空使
     * 后续扫描（{@code WHERE reminded_time IS NULL}）永不再扫到该行且消息未写出，提醒永久丢失
     * 不可自愈。{@link TransactionTemplate} 直接调用事务基础设施，<b>不依赖调用路径</b>，永久免疫
     * 该问题（仓内先例：ExamStateMachineService 同款修复）。
     *
     * <p><b>事务边界 = 单条投递的认领 + 写消息两写一个事务</b>（刻意不取"整轮一事务"）：
     * 消息插入失败仅回滚本行认领（reminded_time 回到 NULL），下一轮扫描按幂等语义重新认领、
     * 重投恰好一条；同轮其他行照常投递——某一行永久性失败不阻塞其余投递，重试补偿半径最小。
     * 认领与消息同生共死，重试路径不存在"认领在而消息不在"或重复投递窗口；多实例并发时认领
     * UPDATE 行锁持有至本事务提交，竞争实例在提交后重评估 WHERE 影响行数为 0 照常跳过，不重发。
     * {@link TransactionTemplate} 默认仅 RuntimeException/Error 回滚；本路径异常均为运行时异常
     * （MyBatis DataAccessException 系），无受检异常路径，与原 {@code rollbackFor = Exception.class}
     * 意图等价。
     */
    int deliverIfAbsent(Exam exam, ExamReservation reservation) {
        Integer delivered = transactionTemplate.execute(status -> {
            int claimed = reservationMapper.markRemindedIfNull(reservation.getId(), LocalDateTime.now());
            if (claimed != 1) {
                // 已被其他实例/扫描投递：行级幂等跳过，不重发
                return 0;
            }
            UserMessage message = new UserMessage();
            message.setUserId(reservation.getStudentId());
            message.setExamId(exam.getId());
            message.setTitle("开考提醒");
            message.setContent("您预约的考试《" + exam.getTitle() + "》即将开考，请准时参加。");
            message.setReadFlag(UserMessage.READ_FLAG_UNREAD);
            messageMapper.insert(message);
            return 1;
        });
        return delivered == null ? 0 : delivered;
    }

    /** 定时扫表：默认每 10 秒一轮（沿用 exam.taking.sweep.* 间隔）。 */
    @Scheduled(fixedDelayString = "${exam.taking.sweep.fixed-delay-ms:10000}",
            initialDelayString = "${exam.taking.sweep.initial-delay-ms:10000}")
    public void scheduledRemind() {
        remind();
    }

    /** 启动扫表：补偿停机期间错过的开考提醒。 */
    @EventListener(ApplicationReadyEvent.class)
    public void remindOnStartup() {
        int delivered = remind();
        if (delivered > 0) {
            log.info("启动补偿投递开考提醒 {} 条", delivered);
        }
    }
}