package com.exam.grading.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.dynamic.datasource.annotation.DS;
import com.exam.auth.security.OwnershipGuard;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.dto.GradingProgressResponse;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.submission.entity.ExamSubmission;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 判分读侧服务：进度总览与批改工作台查询（教师视角，只读不写）。
 */
@Service
public class GradingQueryService {

    private final ExamMapper examMapper;
    private final GradingSubmissionMapper gradingSubmissionMapper;
    private final SubjectiveGradeMapper subjectiveGradeMapper;

    public GradingQueryService(ExamMapper examMapper,
                               GradingSubmissionMapper gradingSubmissionMapper,
                               SubjectiveGradeMapper subjectiveGradeMapper) {
        this.examMapper = examMapper;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.subjectiveGradeMapper = subjectiveGradeMapper;
    }

    /**
     * 判分进度总览：客观判分按答卷 grading_status 统计；
     * 主观批改按 subjective_grades 是否有终分统计（未运行判分时两列均为 0）。
     *
     * <p>读写分离（add-performance-deepening task3）：进度总览是<b>非强一致读</b>
     * （统计数字滞后一瞬无碍，前端会刷新）——{@code @DS("slave")} 走从库卸读压力。
     * 注意：判分/批改的<b>写</b>不经此服务（教师批改走 SubjectiveGradingService，明文走主库）。
     */
    @DS("slave")
    public GradingProgressResponse progress(Long examId) {
        requireOwnedExam(examId);
        GradingProgressResponse response = new GradingProgressResponse();

        List<GradingSubmission> submissions = gradingSubmissionMapper.selectList(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_SUBMITTED));
        response.setSubmittedCount(submissions.size());
        for (GradingSubmission submission : submissions) {
            int gradingStatus = submission.getGradingStatus() == null ? 0 : submission.getGradingStatus();
            if (gradingStatus == ObjectiveGradingService.GRADING_OK) {
                response.setGradedCount(response.getGradedCount() + 1);
            } else if (gradingStatus == ObjectiveGradingService.GRADING_FAILED) {
                response.setFailedCount(response.getFailedCount() + 1);
            } else {
                response.setPendingCount(response.getPendingCount() + 1);
            }
        }

        List<SubjectiveGrade> subjectiveRows = subjectiveGradeMapper.selectList(
                Wrappers.<SubjectiveGrade>lambdaQuery().eq(SubjectiveGrade::getExamId, examId));
        response.setSubjectiveTotal(subjectiveRows.size());
        subjectiveRows.forEach(row -> {
            if (row.getScore() != null) {
                response.setSubjectiveGraded(response.getSubjectiveGraded() + 1);
            }
        });
        // 未批行数 > 0 的答卷即"部分批改"（未运行判分的答卷不计入）
        // 单遍哈希替代 O(n*m) 嵌套匹配：先收集存在未批主观题（score == null）的答卷 ID，
        // 再单遍过滤答卷；n=答卷数、m=主观行数，整体降为 O(n+m)。
        Set<Long> ungradedSubmissionIds = subjectiveRows.stream()
                .filter(row -> row.getScore() == null)
                .map(SubjectiveGrade::getSubmissionId)
                .collect(Collectors.toSet());
        long partial = submissions.stream()
                .filter(submission -> ungradedSubmissionIds.contains(submission.getId()))
                .count();
        response.setPartialGradedCount((int) partial);
        return response;
    }

    /** 校验考试存在 + 操作者是考试归属教师（判分读接口同样做 owner 校验）。 */
    Exam requireOwnedExam(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        OwnershipGuard.assertOwner(exam.getCreatedBy(), SecurityUtil.getCurrentUser(), "考试");
        return exam;
    }
}
