package com.exam.grading.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.OwnershipGuard;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.model.GradingConfig;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.support.GradingPaperReader;
import com.exam.submission.entity.ExamSubmission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

        // 已有主观批改行一次取出（batch-grading-subjective-upserts）：与试卷快照同一思路，
        // 整场只读一次，逐份判分循环内不再按「答卷 × 题目」selectOne；
        // 无简答题或无答卷时不发查询。预取在逐份 try 之外——一次读失败按整场异常上抛
        // （与汇总的批量读取口径一致），不把整场判分包进大事务。
        Map<Long, Map<Long, SubjectiveGrade>> existingGrades =
                objectiveGradingService.loadSubjectiveGrades(submissions, paper);

        int failed = 0;
        List<ObjectiveGradingService.GradeOutcome> failures = new ArrayList<>();
        for (GradingSubmission submission : submissions) {
            // 失败隔离：gradeSafely 内部已标记失败并吞掉异常，循环永不被打断
            ObjectiveGradingService.GradeOutcome outcome = objectiveGradingService.gradeSafely(
                    submission, paper, existingGrades.getOrDefault(submission.getId(), Map.of()));
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
        // 该份答卷的已有主观行一次取出（batch-grading-subjective-upserts），不再按题各查
        Map<Long, SubjectiveGrade> existingGrades = objectiveGradingService
                .loadSubjectiveGrades(List.of(submission), paper)
                .getOrDefault(submissionId, Map.of());
        return objectiveGradingService.gradeSafely(submission, paper, existingGrades);
    }

    /**
     * 手动给分（spec「判分失败处理」场景的兜底通道）：
     * 判分反复失败（如答案数据损坏无法解析）时，教师可按纸质卷/人工核对结果直接裁定客观题总分。
     * 与重判的差异：重判走判分引擎重算，手动给分完全绕过引擎——教师裁定即终局。
     */
    public void manualScore(Long examId, Long submissionId, BigDecimal objectiveScore) {
        Exam exam = requireOperableExam(examId);
        OwnershipGuard.assertOwner(exam.getCreatedBy(), SecurityUtil.getCurrentUser(), "考试");

        GradingSubmission submission = gradingSubmissionMapper.selectById(submissionId);
        if (submission == null || !submission.getExamId().equals(examId)) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "答卷不存在或不属于该考试");
        }
        if (submission.getStatus() == ExamSubmission.STATUS_IN_PROGRESS) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "答卷尚未交卷，不能给分");
        }
        // 上限校验：手动给分不得超过快照客观题满分（防止超出卷面结构的成绩）
        GradingPaper paper = paperReader.readByExamId(examId);
        BigDecimal objectiveTotal = paper.objectiveTotalScore();
        if (objectiveScore.compareTo(objectiveTotal) > 0) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "手动给分不得超过客观题满分 " + objectiveTotal.stripTrailingZeros().toPlainString());
        }

        gradingSubmissionMapper.update(null, Wrappers.<GradingSubmission>lambdaUpdate()
                .eq(GradingSubmission::getId, submissionId)
                .in(GradingSubmission::getStatus, ExamSubmission.STATUS_SUBMITTED, ExamSubmission.STATUS_GRADED)
                .set(GradingSubmission::getObjectiveScore, GradingConfig.scale(objectiveScore))
                .set(GradingSubmission::getGradingStatus, ObjectiveGradingService.GRADING_OK)
                .set(GradingSubmission::getGradingError, null)
                .set(GradingSubmission::getUpdatedTime, LocalDateTime.now()));
        log.info("答卷手动给分: exam={} submission={} objective={} 操作人={}",
                examId, submissionId, objectiveScore, SecurityUtil.getUserId());
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
