package com.exam.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>事务统一显式 rollbackFor=Exception.class（见 data-consistency 规范），防未来受检异常静默不回滚。
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

    public ExamStateMachineService(ExamMapper examMapper, AbsenceService absenceService) {
        this.examMapper = examMapper;
        this.absenceService = absenceService;
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
     * @return 本轮实际迁移的考试数
     */
    @Transactional(rollbackFor = Exception.class)
    public int autoAdvance() {
        LocalDateTime now = LocalDateTime.now();
        int moved = 0;

        List<Exam> toStart = examMapper.selectList(Wrappers.<Exam>lambdaQuery()
                .eq(Exam::getStatus, Exam.STATUS_NOT_STARTED)
                .eq(Exam::getPublished, 1)
                .le(Exam::getStartTime, now));
        for (Exam exam : toStart) {
            moved += casAdvanceQuietly(exam, Exam.STATUS_IN_PROGRESS);
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
            moved += rows;
        }
        return moved;
    }

    /** 批量推进的单场 CAS：0 行（并发已迁移）静默跳过，不打断同批其他考试。 */
    private int casAdvanceQuietly(Exam exam, int newStatus) {
        int rows = examMapper.casUpdateStatus(exam.getId(), exam.getStatus(), newStatus, exam.getVersion());
        if (rows > 0) {
            log.info("考试 {} 定时迁移 {} -> {}", exam.getId(), exam.getStatus(), newStatus);
        } else {
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
