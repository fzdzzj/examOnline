package com.exam.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.OwnershipGuard;
import com.exam.auth.security.SecurityUtil;
import com.exam.clazz.service.ClassService;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.dto.AbsenceItemResponse;
import com.exam.exam.entity.Exam;
import com.exam.exam.entity.ExamAbsence;
import com.exam.exam.mapper.ExamAbsenceMapper;
import com.exam.exam.mapper.ExamMapper;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 缺考标记与查询服务（spec「缺考标记」，§8.10/§12.5）。
 *
 * <p><b>缺考识别时机：考试状态机"进行中→已结束"迁移时</b>（由 {@link ExamStateMachineService}
 * 的 toEnd 处理调用 {@link #markAbsence}）——
 * <ul>
 *   <li>时间窗内允许迟到（{@code allow_late_minutes}）进入，学生可能在开考后一段时间才补点"开始"，
 *       此刻或许已有答卷，若开考即判缺考会在迟到窗口内误伤；</li>
 *   <li>只有考试彻底结束（状态机 → 已结束，时间窗关闭、无人再可进入）时，"仍无答卷"才是终局判定；
 *       因此缺考标记锚定"进行中→已结束"这一迁移，而非开考。</li>
 * </ul>
 *
 * <p><b>幂等</b>：缺考 = 应考名单（{@link ClassService#listStudentIds}）− 有答卷者（exam_submissions），
 * 差集以 {@code INSERT IGNORE} 批量落 exam_absence，uk_exam_student 唯一索引兜底——
 * 定时扫表 + 启动补偿被重复触发时不会重复写。
 */
@Slf4j
@Service
public class AbsenceService {

    private final ExamAbsenceMapper absenceMapper;
    private final ExamMapper examMapper;
    private final ExamSubmissionMapper submissionMapper;
    private final ClassService classService;
    private final UserMapper userMapper;

    public AbsenceService(ExamAbsenceMapper absenceMapper, ExamMapper examMapper,
                          ExamSubmissionMapper submissionMapper, ClassService classService,
                          UserMapper userMapper) {
        this.absenceMapper = absenceMapper;
        this.examMapper = examMapper;
        this.submissionMapper = submissionMapper;
        this.classService = classService;
        this.userMapper = userMapper;
    }

    /**
     * 标记缺考（状态机 toEnd 触发；幂等）：应考名单 − 有答卷者 = 缺考，批量 INSERT IGNORE 写入。
     * 未绑班级（classId 为 null）的考试无法推导应考名单，直接跳过（返回 0）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int markAbsence(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null || exam.getClassId() == null) {
            return 0;   // 无班级归属：无应考名单可言，不标记
        }
        List<Long> expectedIds = classService.listStudentIds(exam.getClassId());
        if (expectedIds.isEmpty()) {
            return 0;
        }

        // 有答卷的学生（老师可跨班拉名单，这里仅需当前应考名单内的答卷）
        // 列投影：差集判定只用 student_id，不载入 paper_json/answers
        List<ExamSubmission> submitted = submissionMapper.selectList(
                Wrappers.<ExamSubmission>lambdaQuery()
                        .select(ExamSubmission::getStudentId)
                        .eq(ExamSubmission::getExamId, examId)
                        .in(ExamSubmission::getStudentId, expectedIds));
        java.util.Set<Long> submittedIds = submitted.stream()
                .map(ExamSubmission::getStudentId).collect(Collectors.toSet());

        LocalDateTime now = LocalDateTime.now();
        // 缺考 = 应考 − 有答卷；重复扫表时这批含已标记行，INSERT IGNORE 自动跳过保证幂等
        List<ExamAbsence> absences = new ArrayList<>();
        for (Long studentId : expectedIds) {
            if (!submittedIds.contains(studentId)) {
                ExamAbsence absence = new ExamAbsence();
                absence.setExamId(examId);
                absence.setStudentId(studentId);
                absence.setStatus(ExamAbsence.STATUS_ABSENT);
                absence.setMarkedTime(now);
                absence.setCreatedTime(now);
                absences.add(absence);
            }
        }
        if (absences.isEmpty()) {
            return 0;
        }
        int inserted = absenceMapper.insertIgnoreBatch(absences);
        log.info("考试 {} 缺考标记：应考 {} 人，有答卷 {} 人，本次新增缺考 {} 人",
                examId, expectedIds.size(), submittedIds.size(), inserted);
        return inserted;
    }

    /**
     * 按考试查缺考学生（教师）：返回该考试全部已标记缺考的学生及标记时间。
     * 越权校验：仅归属教师（ADMIN 放行）可查。
     */
    public List<AbsenceItemResponse> listAbsences(Long examId) {
        requireOwnedExam(examId);
        List<ExamAbsence> absences = absenceMapper.selectList(Wrappers.<ExamAbsence>lambdaQuery()
                .eq(ExamAbsence::getExamId, examId)
                .orderByAsc(ExamAbsence::getStudentId));
        if (absences.isEmpty()) {
            return List.of();
        }
        List<Long> studentIds = absences.stream().map(ExamAbsence::getStudentId).toList();
        Map<Long, User> users = userMapper.selectBatchIds(studentIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        LocalDateTime now = LocalDateTime.now();
        return absences.stream().map(a -> new AbsenceItemResponse(
                a.getStudentId(),
                users.get(a.getStudentId()) == null
                        ? null : users.get(a.getStudentId()).getName(),
                a.getMarkedTime() == null ? now : a.getMarkedTime())).toList();
    }

    /** 越权校验：存在/软删 404，非归属教师 403（ADMIN 放行）。 */
    private void requireOwnedExam(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        OwnershipGuard.assertOwner(exam.getCreatedBy(), SecurityUtil.getCurrentUser(), "考试");
    }
}