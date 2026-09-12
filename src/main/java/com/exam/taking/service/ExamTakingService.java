package com.exam.taking.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.dto.ExamSnapshotResponse;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.ExamSnapshotService;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.taking.dto.AutoSaveRequest;
import com.exam.taking.dto.AutoSaveResponse;
import com.exam.taking.dto.BehaviorReportRequest;
import com.exam.taking.dto.EnterExamResponse;
import com.exam.taking.dto.ExamListItem;
import com.exam.taking.dto.QuestionView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 进入考试与答题服务（spec「进入考试」「答题导航与采集」需求）：
 *
 * <ul>
 *   <li>进入考试：校验考试处于进行中 → 首次进入生成个人快照（题序/选项乱序锁定）并记录
 *       start_time 启动个人倒计时（点击开始才计时，服务端时间为准）；再次进入返回同一快照
 *       与草稿（刷新不换题、断线恢复）；</li>
 *   <li>答题数据：按个人快照返回题目（不含答案）+ 草稿答案 + 服务端剩余秒数（前端仅展示）；</li>
 *   <li>考试列表：学生视角按 待考/进行中/已完成 分组；</li>
 *   <li>行为采集：切屏/失焦事件落行为日志，只记录不强制交卷。</li>
 * </ul>
 *
 * <p>并发说明：uk_exam_student 唯一索引保证一人一场至多一条答卷——并发进入时
 * 第二次插入撞唯一索引，回读首次创建的答卷即幂等进入。
 */
@Slf4j
@Service
public class ExamTakingService {

    private final ExamMapper examMapper;
    private final ExamSubmissionMapper submissionMapper;
    private final ExamSnapshotService examSnapshotService;
    private final PersonalPaperService personalPaperService;
    private final ExamDraftService draftService;
    private final ExamBehaviorLogService behaviorLogService;

    public ExamTakingService(ExamMapper examMapper, ExamSubmissionMapper submissionMapper,
                             ExamSnapshotService examSnapshotService, PersonalPaperService personalPaperService,
                             ExamDraftService draftService, ExamBehaviorLogService behaviorLogService) {
        this.examMapper = examMapper;
        this.submissionMapper = submissionMapper;
        this.examSnapshotService = examSnapshotService;
        this.personalPaperService = personalPaperService;
        this.draftService = draftService;
        this.behaviorLogService = behaviorLogService;
    }

    /**
     * 进入考试（学生点击"进入并开始"，也服务于刷新/断线重进）：
     * 首次进入创建答卷行（个人快照 + 开始时间 + 个人截止），重复进入幂等返回同一快照。
     */
    public EnterExamResponse enter(Long examId) {
        Long studentId = requireStudent();
        Exam exam = requireEnterableExam(examId);

        ExamSubmission submission = submissionMapper.selectByExamStudent(examId, studentId);
        if (submission == null) {
            submission = createSubmission(exam, studentId);
        }
        return buildAnsweringContext(exam, submission, studentId);
    }

    /**
     * 答题数据（刷新页面/断线重连后拉取）：必须已进入考试；
     * 与 enter 共用上下文组装，题目与顺序与首次进入完全一致（spec「刷新不换题」场景）。
     */
    public EnterExamResponse current(Long examId) {
        Long studentId = requireStudent();
        Exam exam = requireEnterableExam(examId);
        ExamSubmission submission = submissionMapper.selectByExamStudent(examId, studentId);
        if (submission == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "尚未进入考试，请先开始作答");
        }
        return buildAnsweringContext(exam, submission, studentId);
    }

    /** 30s 自动保存：转发草稿服务（版本冲突合并规则见 ExamDraftService）。 */
    public AutoSaveResponse saveDraft(Long examId, AutoSaveRequest request) {
        return draftService.save(examId, requireStudent(), request);
    }

    /** 切屏/失焦行为上报：校验在考（已进入且未交卷）后落行为日志，不强制交卷。 */
    public void reportBehavior(Long examId, BehaviorReportRequest request) {
        Long studentId = requireStudent();
        ExamSubmission submission = submissionMapper.selectByExamStudent(examId, studentId);
        if (submission == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "尚未进入考试，无法上报行为");
        }
        if (submission.getStatus() != ExamSubmission.STATUS_IN_PROGRESS) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "答卷已提交，无需上报行为");
        }
        behaviorLogService.record(examId, studentId, request.getEventType(), request.getEventData(),
                request.getSeverity(), request.getOccurredTime());
    }

    /**
     * 学生考试列表（spec：状态分组 待考/进行中/已完成）：
     * 只展示已发布考试；分组口径见 {@link ExamListItem}（以学生下一步动作为准）。
     */
    public List<ExamListItem> myExams() {
        Long studentId = requireStudent();
        List<Exam> exams = examMapper.selectPage(new Page<>(1, 50), Wrappers.<Exam>lambdaQuery()
                        .eq(Exam::getPublished, 1)
                        .orderByDesc(Exam::getStartTime))
                .getRecords();

        Map<Long, ExamSubmission> mine = new HashMap<>();
        if (!exams.isEmpty()) {
            submissionMapper.selectList(Wrappers.<ExamSubmission>lambdaQuery()
                            .eq(ExamSubmission::getStudentId, studentId)
                            .in(ExamSubmission::getExamId, exams.stream().map(Exam::getId).toList()))
                    .forEach(s -> mine.put(s.getExamId(), s));
        }

        LocalDateTime now = LocalDateTime.now();
        return exams.stream().map(exam -> {
            ExamSubmission submission = mine.get(exam.getId());
            String group;
            boolean canEnter = false;
            Long remaining = null;
            if (submission != null && submission.getStatus() == ExamSubmission.STATUS_IN_PROGRESS) {
                if (now.isAfter(submission.getDeadlineTime()) || exam.getStatus() != Exam.STATUS_IN_PROGRESS) {
                    group = ExamListItem.GROUP_FINISHED;   // 已超时/考试已结束，等待兜底收卷
                } else {
                    group = ExamListItem.GROUP_ONGOING;
                    canEnter = true;
                    remaining = Math.max(0, Duration.between(now, submission.getDeadlineTime()).getSeconds());
                }
            } else if (submission != null || exam.getStatus() >= Exam.STATUS_ENDED) {
                group = ExamListItem.GROUP_FINISHED;       // 已交卷，或考试已结束/已批改
            } else if (exam.getStatus() == Exam.STATUS_IN_PROGRESS) {
                group = ExamListItem.GROUP_UPCOMING;       // 进行中且未进入：可进入并开始
                canEnter = true;
            } else {
                group = ExamListItem.GROUP_UPCOMING;       // 未开始：待考
            }
            return new ExamListItem(exam.getId(), exam.getTitle(), exam.getStartTime(), exam.getEndTime(),
                    exam.getDurationMinutes(), exam.getStatus(), group, canEnter,
                    submission == null ? null : submission.getStatus(), remaining);
        }).toList();
    }

    // ==================== 内部组装 ====================

    /**
     * 组装答题上下文：个人快照题目（不含答案）+ 服务端倒计时 + 草稿（断线恢复）。
     * 已交卷返回封闭上下文（无题目、剩余 0 秒），前端渲染交卷结果页。
     */
    private EnterExamResponse buildAnsweringContext(Exam exam, ExamSubmission submission, Long studentId) {
        LocalDateTime now = LocalDateTime.now();
        boolean submitted = submission.getStatus() == ExamSubmission.STATUS_SUBMITTED;
        if (!submitted && now.isAfter(submission.getDeadlineTime())) {
            // 已过个人截止（扫描间隔内的窗口期）：暂以业务错误提示，交卷链路就绪后改为就地兜底强制交卷
            throw new BusinessException(ResponseCode.BAD_REQUEST, "已超过个人作答截止时间，请等待系统自动收卷");
        }

        List<QuestionView> questions = submitted
                ? List.of()
                : personalPaperService.toView(submission.getPaperJson());
        ExamDraftService.DraftState draft = draftService.get(exam.getId(), studentId);

        return new EnterExamResponse(
                exam.getId(), exam.getTitle(), submission.getId(), submission.getStatus(),
                submission.getStartTime(), submission.getDeadlineTime(), now,
                submitted ? 0L : Math.max(0, Duration.between(now, submission.getDeadlineTime()).getSeconds()),
                questions,
                draft == null ? null : draft.answers(),
                draft == null ? List.of() : draft.marked(),
                draft == null ? null : draft.version());
    }

    /** 首次进入：生成个人快照并创建答卷行；并发进入撞唯一索引时回读幂等。 */
    private ExamSubmission createSubmission(Exam exam, Long studentId) {
        ExamSnapshotResponse snapshot = examSnapshotService.getCurrent(exam.getId());
        String personalJson = personalPaperService.personalize(snapshot.getPaper());

        LocalDateTime now = LocalDateTime.now();
        // 个人截止 = min(点击开始 + 个人时长, 考试结束时间)：个人倒计时与整场时间窗取早者
        LocalDateTime deadline = earliest(now.plusMinutes(exam.getDurationMinutes()), exam.getEndTime());
        if (deadline.isBefore(now)) {
            deadline = now;   // 极端边界（时间窗已尽）：立即到期，交由兜底链路收卷
        }

        ExamSubmission submission = new ExamSubmission();
        submission.setExamId(exam.getId());
        submission.setStudentId(studentId);
        submission.setStartTime(now);
        submission.setDeadlineTime(deadline);
        submission.setPaperJson(personalJson);
        submission.setStatus(ExamSubmission.STATUS_IN_PROGRESS);
        submission.setVersion(0);
        try {
            submissionMapper.insert(submission);
            log.info("学生 {} 进入考试 {} 创建答卷 id={}，个人截止 {}（快照题序/选项已锁定）",
                    studentId, exam.getId(), submission.getId(), deadline);
        } catch (DuplicateKeyException e) {
            // 并发进入撞 uk_exam_student：回读首次创建的答卷（幂等进入，不换题）
            log.info("学生 {} 并发进入考试 {}，回读既有答卷", studentId, exam.getId());
            submission = submissionMapper.selectByExamStudent(exam.getId(), studentId);
            if (submission == null) {
                throw new BusinessException(ResponseCode.INTERNAL_ERROR, "答卷初始化失败，请重试");
            }
        }
        return submission;
    }

    /** 进入考试的准入校验：已发布且处于进行中（spec「非进行中禁止进入」场景）。 */
    private Exam requireEnterableExam(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        if (exam.getPublished() == null || exam.getPublished() != 1) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "考试未发布，无法进入");
        }
        if (exam.getStatus() == Exam.STATUS_NOT_STARTED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "考试尚未开始，请等待开考");
        }
        if (exam.getStatus() != Exam.STATUS_IN_PROGRESS) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "考试已结束，无法进入");
        }
        return exam;
    }

    private LocalDateTime earliest(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private Long requireStudent() {
        Long userId = SecurityUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        return userId;
    }
}
