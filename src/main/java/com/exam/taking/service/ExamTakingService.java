package com.exam.taking.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.anticheat.service.BehaviorEventCollectService;
import com.exam.auth.security.SecurityUtil;
import com.exam.monitoring.service.OnlinePresenceService;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.dto.ExamSnapshotResponse;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.ExamSnapshotService;
import com.exam.exam.service.MakeupService;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.taking.dto.AutoSaveRequest;
import com.exam.taking.dto.AutoSaveResponse;
import com.exam.anticheat.model.EventVerdict;
import com.exam.taking.dto.BehaviorReportRequest;
import com.exam.taking.dto.BehaviorReportResponse;
import com.exam.taking.dto.EnterExamResponse;
import com.exam.taking.dto.ExamListItem;
import com.exam.taking.dto.QuestionView;
import com.exam.clazz.entity.UserClass;
import com.exam.clazz.mapper.UserClassMapper;
import com.exam.exam.entity.ExamCandidate;
import com.exam.exam.mapper.ExamCandidateMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    /** 学生考试列表默认最大条数上限（需求决策记录 §12.1）。 */
    private static final long MY_EXAMS_DEFAULT_LIMIT = 50L;

    private final ExamMapper examMapper;
    private final ExamSubmissionMapper submissionMapper;
    private final UserClassMapper userClassMapper;
    private final ExamCandidateMapper examCandidateMapper;
    private final ExamSnapshotService examSnapshotService;
    private final PersonalPaperService personalPaperService;
    private final ExamDraftService draftService;
    private final BehaviorEventCollectService eventCollectService;
    private final ExamSubmitService submitService;
    private final OnlinePresenceService presenceService;
    private final MakeupService makeupService;

    public ExamTakingService(ExamMapper examMapper, ExamSubmissionMapper submissionMapper,
                             UserClassMapper userClassMapper, ExamCandidateMapper examCandidateMapper,
                             ExamSnapshotService examSnapshotService, PersonalPaperService personalPaperService,
                             ExamDraftService draftService, BehaviorEventCollectService eventCollectService,
                             ExamSubmitService submitService, OnlinePresenceService presenceService,
                             MakeupService makeupService) {
        this.examMapper = examMapper;
        this.submissionMapper = submissionMapper;
        this.userClassMapper = userClassMapper;
        this.examCandidateMapper = examCandidateMapper;
        this.examSnapshotService = examSnapshotService;
        this.personalPaperService = personalPaperService;
        this.draftService = draftService;
        this.eventCollectService = eventCollectService;
        this.submitService = submitService;
        this.presenceService = presenceService;
        this.makeupService = makeupService;
    }

    /**
     * 进入考试（学生点击"进入并开始"，也服务于刷新/断线重进）：
     * 首次进入创建答卷行（个人快照 + 开始时间 + 个人截止），重复进入幂等返回同一快照。
     * 进入即刷新监考在线心跳（阶段 7 监考大屏的实时数据源）。
     */
    public EnterExamResponse enter(Long examId) {
        Long studentId = requireStudent();
        Exam exam = requireEnterableExam(examId);

        // 补考名单限制（仅补考生效，§12.5「名单限制进入」场景）：名单外学生拒绝进入，
        // 不影响既有进入逻辑主体。非补考考试（parent_exam_id=null）此处直接放行。
        makeupService.assertCanEnter(exam, studentId);

        ExamSubmission submission = submissionMapper.selectByExamStudent(examId, studentId);
        if (submission == null) {
            submission = createSubmission(exam, studentId);
        }
        presenceService.touch(examId, studentId);
        return buildAnsweringContext(exam, submission, studentId);
    }

    /**
     * 答题数据（刷新页面/断线重连后拉取）：必须已进入考试；
     * 与 enter 共用上下文组装，题目与顺序与首次进入完全一致（spec「刷新不换题」场景）。
     * 拉题同样刷新在线心跳（断线重连即刻恢复在线）。
     */
    public EnterExamResponse current(Long examId) {
        Long studentId = requireStudent();
        Exam exam = requireEnterableExam(examId);
        ExamSubmission submission = submissionMapper.selectByExamStudent(examId, studentId);
        if (submission == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "尚未进入考试，请先开始作答");
        }
        presenceService.touch(examId, studentId);
        return buildAnsweringContext(exam, submission, studentId);
    }

    /** 30s 自动保存：转发草稿服务（版本冲突合并规则见 ExamDraftService）。 */
    public AutoSaveResponse saveDraft(Long examId, AutoSaveRequest request) {
        return draftService.save(examId, requireStudent(), request);
    }

    /** 切屏/失焦行为上报：校验在考（已进入且未交卷）后落行为日志，不强制交卷。 */
    /**
     * 切屏/失焦行为上报：校验在考（已进入且未交卷）后交由防作弊采集核心落行为日志。
     * 返回策略判定结果（是否警告/严重度/切屏次数）供前端弹提醒——
     * 只警告 + 记录，绝不强制交卷（spec「切屏警告不交卷」场景）。
     */
    public BehaviorReportResponse reportBehavior(Long examId, BehaviorReportRequest request) {
        Long studentId = requireStudent();
        ExamSubmission submission = submissionMapper.selectByExamStudent(examId, studentId);
        if (submission == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "尚未进入考试，无法上报行为");
        }
        if (submission.getStatus() != ExamSubmission.STATUS_IN_PROGRESS) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "答卷已提交，无需上报行为");
        }
        // 阶段 7 防作弊：事件经统一采集核心（策略模式）判定严重度后落库——
        // 严重度由服务端策略决定，客户端不再自带
        EventVerdict verdict = eventCollectService.collect(examId, studentId, request.getEventType(),
                request.getEventData(), request.getOccurredTime());
        return BehaviorReportResponse.from(request.getEventType(), verdict);
    }

    /**
     * 学生考试列表（spec：状态分组 待考/进行中/已完成）：
     * 范围：已发布 ∧ 未软删 ∧ (本人有答卷 ∪ 本人当前班级绑定的普通考试 ∪ 本人为候选人的补考)；
     * 排序：按 §12.1——状态分组优先级 待考(UPCOMING) → 进行中(ONGOING) → 已完成(FINISHED)，
     *       组内按开始时间距当前时刻近→远（待考先考的先显示，进行中/已完成最近开始的先显示）；
     * 上限：截断至 50 场（作用于本人考试集合内）。
     */
    public List<ExamListItem> myExams() {
        Long studentId = requireStudent();

        // 1. 本人答卷（列投影：只消费这三个标量列，不载入 paper_json/answers，且仅查一次）
        List<ExamSubmission> studentSubmissions = submissionMapper.selectList(Wrappers.<ExamSubmission>lambdaQuery()
                .select(ExamSubmission::getExamId, ExamSubmission::getStatus, ExamSubmission::getDeadlineTime)
                .eq(ExamSubmission::getStudentId, studentId));
        Map<Long, ExamSubmission> mine = new HashMap<>();
        studentSubmissions.forEach(s -> mine.put(s.getExamId(), s));

        // 2. 收集本人相关的考试 ID 集合：答卷 ∪ 本人所属班级绑定的非补考考试 ∪ 本人为候选人的补考
        Set<Long> myExamIds = new HashSet<>(mine.keySet());

        // 班级渠道：本人当前所在班级
        List<UserClass> userClasses = userClassMapper.selectList(Wrappers.<UserClass>lambdaQuery()
                .select(UserClass::getClassId)
                .eq(UserClass::getUserId, studentId));
        if (!userClasses.isEmpty()) {
            List<Long> classIds = userClasses.stream()
                    .map(UserClass::getClassId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (!classIds.isEmpty()) {
                List<Exam> classExams = examMapper.selectList(Wrappers.<Exam>lambdaQuery()
                        .select(Exam::getId)
                        .eq(Exam::getPublished, 1)
                        .isNull(Exam::getParentExamId)
                        .in(Exam::getClassId, classIds));
                classExams.forEach(e -> myExamIds.add(e.getId()));
            }
        }

        // 补考渠道：本人被指定为候选人
        List<ExamCandidate> candidates = examCandidateMapper.selectList(Wrappers.<ExamCandidate>lambdaQuery()
                .select(ExamCandidate::getExamId)
                .eq(ExamCandidate::getStudentId, studentId));
        candidates.forEach(c -> {
            if (c.getExamId() != null) {
                myExamIds.add(c.getExamId());
            }
        });

        if (myExamIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 3. 取出已发布考试实体（软删由 @TableLogic 自动过滤）
        List<Exam> exams = examMapper.selectList(Wrappers.<Exam>lambdaQuery()
                .eq(Exam::getPublished, 1)
                .in(Exam::getId, myExamIds));

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
        })
        .sorted(Comparator
                .comparingInt((ExamListItem item) -> groupPriority(item.getGroup()))
                .thenComparingLong((ExamListItem item) -> item.getStartTime() == null ? Long.MAX_VALUE : Math.abs(Duration.between(now, item.getStartTime()).toMillis()))
                .thenComparing((ExamListItem item) -> item.getStartTime(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(ExamListItem::getExamId))
        .limit(MY_EXAMS_DEFAULT_LIMIT)
        .toList();
    }

    private static int groupPriority(String group) {
        if (ExamListItem.GROUP_UPCOMING.equals(group)) return 1;
        if (ExamListItem.GROUP_ONGOING.equals(group)) return 2;
        if (ExamListItem.GROUP_FINISHED.equals(group)) return 3;
        return 4;
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
            // 已过个人截止仍未交卷（兜底扫描间隔内的窗口期）：就地兜底强制交卷——
            // 与手动/前端归零共享 SETNX 锁与状态机 CAS（三路竞态仅一次），服务端时间为准
            submitService.forceSubmitByBackend(exam.getId(), studentId);
            submission = submissionMapper.selectByExamStudent(exam.getId(), studentId);
            submitted = submission.getStatus() == ExamSubmission.STATUS_SUBMITTED;
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
