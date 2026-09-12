package com.exam.grading.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.OwnershipGuard;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.support.GradingPaperReader;
import com.exam.submission.entity.ExamSubmission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 整场判分编排服务（教师触发）：
 *
 * <p>职责：加载考试快照（一次，整场共用）→ 遍历已交卷答卷逐份判分。
 * 每份答卷的判分（含失败标记）相互独立——一份失败只标记本份，
 * 绝不中断整场（spec「判分失败不影响其他答卷」场景）。
 *
 * <p>判分时机约束：考试须"已结束"（status=2/3）才能判分——进行中还有学生在答，
 * 判分会拿到不完整答案；已发布（status=4）须先撤回成绩再重判（§5.2 错题修正流程）。
 */
@Slf4j
@Service
public class ExamGradingService {

    private final ExamMapper examMapper;
    private final GradingSubmissionMapper gradingSubmissionMapper;
    private final GradingPaperReader paperReader;
    private final ObjectiveGradingService objectiveGradingService;

    public ExamGradingService(ExamMapper examMapper,
                              GradingSubmissionMapper gradingSubmissionMapper,
                              GradingPaperReader paperReader,
                              ObjectiveGradingService objectiveGradingService) {
        this.examMapper = examMapper;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.paperReader = paperReader;
        this.objectiveGradingService = objectiveGradingService;
    }

    /** 整场判分统计。 */
    public record RunStats(int total, int success, int failed, List<ObjectiveGradingService.GradeOutcome> failures) {
    }

    /**
     * 整场判分（幂等：重复执行即整场重判，客观分覆盖写，主观批改结果不丢）。
     * 仅返回失败的答卷明细（成功无需逐份列出，前端只关心失败清单）。
     */
    public RunStats runExamGrading(Long examId) {
        Exam exam = requireOperableExam(examId);
        OwnershipGuard.assertOwner(exam.getCreatedBy(), SecurityUtil.getCurrentUser(), "考试");

        // 快照整场只读一次：同一考试所有答卷共用同一份标准答案与分值
        GradingPaper paper = paperReader.readByExamId(examId);

        List<GradingSubmission> submissions = gradingSubmissionMapper.selectList(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_SUBMITTED));

        int failed = 0;
        List<ObjectiveGradingService.GradeOutcome> failures = new ArrayList<>();
        for (GradingSubmission submission : submissions) {
            // 失败隔离：gradeSafely 内部已标记失败并吞掉异常，循环永不被打断
            ObjectiveGradingService.GradeOutcome outcome = objectiveGradingService.gradeSafely(submission, paper);
            if (!outcome.success()) {
                failed++;
                failures.add(outcome);
            }
        }
        log.info("整场判分完成: exam={} 总数={} 成功={} 失败={}",
                examId, submissions.size(), submissions.size() - failed, failed);
        return new RunStats(submissions.size(), submissions.size() - failed, failed, failures);
    }

    /**
     * 单份重判（spec「标记失败可重判」场景）：教师对判分失败/需要重算的答卷单独重跑。
     * 失败同样就地标记（grading_status=2 + 原因），结果由返回值带回给教师。
     */
    public ObjectiveGradingService.GradeOutcome rejudge(Long examId, Long submissionId) {
        Exam exam = requireOperableExam(examId);
        OwnershipGuard.assertOwner(exam.getCreatedBy(), SecurityUtil.getCurrentUser(), "考试");

        GradingSubmission submission = gradingSubmissionMapper.selectById(submissionId);
        if (submission == null || !submission.getExamId().equals(examId)) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "答卷不存在或不属于该考试");
        }
        GradingPaper paper = paperReader.readByExamId(examId);
        return objectiveGradingService.gradeSafely(submission, paper);
    }

    /** 校验考试存在且当前状态可判分（已结束/已批改；已发布须先撤回，未结束不可判）。 */
    private Exam requireOperableExam(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        if (exam.getStatus() == Exam.STATUS_PUBLISHED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "成绩已发布，须先撤回再重判");
        }
        if (exam.getStatus() < Exam.STATUS_ENDED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "考试尚未结束，不能判分");
        }
        return exam;
    }
}
