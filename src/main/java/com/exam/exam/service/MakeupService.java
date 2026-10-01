package com.exam.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.OwnershipGuard;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.dto.MakeupCandidateItem;
import com.exam.exam.dto.MakeupCreateRequest;
import com.exam.exam.dto.MakeupCreateResponse;
import com.exam.exam.entity.Exam;
import com.exam.exam.entity.ExamAbsence;
import com.exam.exam.entity.ExamCandidate;
import com.exam.exam.mapper.ExamAbsenceMapper;
import com.exam.exam.mapper.ExamCandidateMapper;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 补考服务（spec「补考独立记录」「名单限制进入」，§8.2/§12.5）：
 *
 * <p><b>补考 = 独立考试记录</b>（复用 exams 表，parent_exam_id 指向主考）：独立时间窗/时长/
 * 迟到容忍/成绩规则，与主考互不影响（§12.5）——复用主考会让补考污染主考时间窗与答卷。
 *
 * <p><b>名单限制进入</b>：创建时把勾选学生写入 {@code exam_candidates}，学生进入补考时由
 * {@link #assertCanEnter}（接入考试入口）校验，名单外学生拒绝进入。
 *
 * <p><b>成绩规则合并</b>：最终成绩按makeup_score_rule 由 {@link MakeupScoreService} 合并，
 * 历史主考与补考成绩均保留。
 */
@Slf4j
@Service
public class MakeupService {

    private final ExamMapper examMapper;
    private final ExamCandidateMapper candidateMapper;
    private final ExamAbsenceMapper absenceMapper;
    private final GradingSubmissionMapper gradingMapper;
    private final UserMapper userMapper;

    public MakeupService(ExamMapper examMapper, ExamCandidateMapper candidateMapper,
                         ExamAbsenceMapper absenceMapper, GradingSubmissionMapper gradingMapper,
                         UserMapper userMapper) {
        this.examMapper = examMapper;
        this.candidateMapper = candidateMapper;
        this.absenceMapper = absenceMapper;
        this.gradingMapper = gradingMapper;
        this.userMapper = userMapper;
    }

    /**
     * 创建补考：教师按主考筛选勾选学生 → 生成独立补考考试（parent_exam_id 指向主考）+
     * 写入 exam_candidates 名单（幂等）。校验：主考须已结束、归属当前教师（ADMIN 放行）、
     * 规则取值合法、时间窗与时长合法。
     */
    @Transactional(rollbackFor = Exception.class)
    public MakeupCreateResponse createMakeup(Long mainExamId, MakeupCreateRequest request) {
        Exam main = requireOwnedExam(mainExamId);
        if (main.getStatus() < Exam.STATUS_ENDED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "主考尚未结束，不能组织补考");
        }
        if (main.getParentExamId() != null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "不能以补考再开补考");
        }
        validateWindowAndDuration(request.getStartTime(), request.getEndTime(),
                request.getDurationMinutes(), request.getAllowLateMinutes());
        String rule = StringUtils.hasText(request.getMakeupScoreRule())
                ? request.getMakeupScoreRule() : Exam.MAKEUP_DEFAULT_RULE;
        if (!Exam.MAKEUP_RULES.contains(rule)) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "补考成绩规则不合法");
        }

        // 独立考试记录：复用主考绑定内容，但时间窗/时长来自请求（与主考互不影响，§12.5）
        Exam makeup = new Exam();
        makeup.setTitle(StringUtils.hasText(request.getTitle())
                ? request.getTitle().trim() : main.getTitle() + "-补考");
        makeup.setDescription(main.getDescription());
        makeup.setPaperId(main.getPaperId());
        makeup.setCourseId(main.getCourseId());
        makeup.setClassId(main.getClassId());
        makeup.setParentExamId(main.getId());
        makeup.setMakeupScoreRule(rule);
        makeup.setStartTime(request.getStartTime());
        makeup.setEndTime(request.getEndTime());
        makeup.setDurationMinutes(request.getDurationMinutes());
        makeup.setAllowLateMinutes(Objects.requireNonNullElse(request.getAllowLateMinutes(), 0));
        makeup.setStatus(Exam.STATUS_NOT_STARTED);
        makeup.setPublished(0);
        makeup.setForceEnd(0);
        makeup.setVersion(0);
        makeup.setCreatedBy(SecurityUtil.getUserId());
        examMapper.insert(makeup);

        // 名单（去重后批量幂等写入；重复组织同场补考不产生脏名单）
        List<Long> studentIds = request.getStudentIds().stream().distinct().toList();
        LocalDateTime now = LocalDateTime.now();
        List<ExamCandidate> candidates = studentIds.stream().map(id -> {
            ExamCandidate candidate = new ExamCandidate();
            candidate.setExamId(makeup.getId());
            candidate.setStudentId(id);
            candidate.setCreatedTime(now);
            return candidate;
        }).collect(Collectors.toList());
        candidateMapper.insertIgnoreBatch(candidates);

        log.info("教师 {} 为主考 {} 创建补考 {} id={}，名单 {} 人，规则 {}",
                SecurityUtil.getUserId(), mainExamId, makeup.getTitle(), makeup.getId(),
                studentIds.size(), rule);
        return new MakeupCreateResponse(makeup.getId(), makeup.getTitle(), main.getId(),
                rule, studentIds.size());
    }

    /**
     * 补考名单限制（接入学生进入考试入口）：非补考考试不限制；补考名单外学生拒绝进入（§12.5「名单限制进入」场景）。
     */
    public void assertCanEnter(Exam exam, Long studentId) {
        if (exam.getParentExamId() == null) {
            return;   // 非补考：无名单限制，走既有进入逻辑
        }
        Long inList = candidateMapper.selectCount(Wrappers.<ExamCandidate>lambdaQuery()
                .eq(ExamCandidate::getExamId, exam.getId())
                .eq(ExamCandidate::getStudentId, studentId));
        if (inList == null || inList == 0) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "你不在本次补考的名单内，无法进入");
        }
    }

    /**
     * 取主考可筛选补考的学生（教师组织补考前勾选）：缺考（应考无答卷）∪
     * 有成绩但低于分数线。passLine 为 null 时只筛缺考。
     */
    public List<MakeupCandidateItem> listEligibleStudents(Long mainExamId, BigDecimal passLine) {
        requireOwnedExam(mainExamId);
        LinkedHashMap<Long, MakeupCandidateItem> eligible = new LinkedHashMap<>();

        // 缺考：主考下 exam_absence 已标记的学生（任务是缺考标记的消费方）
        absenceMapper.selectList(Wrappers.<ExamAbsence>lambdaQuery()
                        .eq(ExamAbsence::getExamId, mainExamId))
                .forEach(a -> eligible.put(a.getStudentId(),
                        new MakeupCandidateItem(a.getStudentId(), null, MakeupCandidateItem.REASON_ABSENT)));

        // 低于分数线：有已批改总分且低于 passLine
        if (passLine != null) {
            gradingMapper.selectList(Wrappers.<GradingSubmission>lambdaQuery()
                            .eq(GradingSubmission::getExamId, mainExamId)
                            .isNotNull(GradingSubmission::getTotalScore))
                    .stream()
                    .filter(g -> g.getTotalScore().compareTo(passLine) < 0)
                    .forEach(g -> eligible.put(g.getStudentId(),
                            new MakeupCandidateItem(g.getStudentId(), null,
                                    MakeupCandidateItem.REASON_BELOW_LINE)));
        }

        if (eligible.isEmpty()) {
            return List.of();
        }
        // 批量装学生姓名
        List<Long> ids = eligible.keySet().stream().toList();
        java.util.Map<Long, User> users = userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return ids.stream().map(id -> {
            MakeupCandidateItem item = eligible.get(id);
            User user = users.get(id);
            return new MakeupCandidateItem(id, user == null ? null : user.getName(), item.reason());
        }).toList();
    }

    // ==================== 私有 ====================

    private void validateWindowAndDuration(LocalDateTime start, LocalDateTime end,
                                           Integer durationMinutes, Integer allowLateMinutes) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "时间窗非法：结束时间必须晚于开始时间");
        }
        if (durationMinutes == null || durationMinutes <= 0) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "时长非法：个人时长必须大于 0 分钟");
        }
        if (allowLateMinutes != null && allowLateMinutes < 0) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "迟到容忍分钟数不能为负");
        }
    }

    /** 越权校验：存在/软删 404，非归属教师 403（ADMIN 放行）。 */
    private Exam requireOwnedExam(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        OwnershipGuard.assertOwner(exam.getCreatedBy(), SecurityUtil.getCurrentUser(), "考试");
        return exam;
    }
}