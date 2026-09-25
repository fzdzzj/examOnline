package com.exam.grading.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.grading.model.GradeResult;
import com.exam.grading.model.GradingConfig;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.strategy.GradingStrategyRegistry;
import com.exam.grading.support.GradingPaperReader;
import com.exam.question.entity.QuestionType;
import com.exam.submission.entity.ExamSubmission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 单份答卷判分服务（spec「客观题判分」需求的执行体）：
 *
 * <ul>
 *   <li>客观题（单选/多选/判断）：按题型分派策略判分，得分为各题之和，落
 *       exam_submissions.objective_score；</li>
 *   <li>简答题：为每题建/更新 subjective_grades 行（关键词初判提示分 + 学生答案快照），
 *       不自动定分——重判只刷新提示分，教师终分/评语/批改痕迹一律不动（§7.2）；</li>
 *   <li>失败隔离：判分过程任何异常都由 {@link #gradeSafely} 捕获并标记
 *       grading_status=2 + 失败原因，只影响本答卷（spec「判分失败处理」场景）。</li>
 * </ul>
 *
 * <p>事务取舍：单份判分拆为多条独立写（主观批改行、答卷得分各自动提交），
 * 不做整体事务——中途失败留下的半成品状态由"重判"自愈（幂等 upsert），
 * 与交卷链路"对账补发"的最终一致思路一致；整体事务反而会因长事务放大锁竞争。
 */
@Slf4j
@Service
public class ObjectiveGradingService {

    /** 判分成功标记 */
    public static final int GRADING_OK = 1;
    /** 判分失败标记（可重判/手动给分） */
    public static final int GRADING_FAILED = 2;

    private final GradingPaperReader paperReader;
    private final GradingStrategyRegistry strategyRegistry;
    private final GradingConfig gradingConfig;
    private final GradingSubmissionMapper gradingSubmissionMapper;
    private final SubjectiveGradeMapper subjectiveGradeMapper;

    public ObjectiveGradingService(GradingPaperReader paperReader,
                                   GradingStrategyRegistry strategyRegistry,
                                   GradingConfig gradingConfig,
                                   GradingSubmissionMapper gradingSubmissionMapper,
                                   SubjectiveGradeMapper subjectiveGradeMapper) {
        this.paperReader = paperReader;
        this.strategyRegistry = strategyRegistry;
        this.gradingConfig = gradingConfig;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.subjectiveGradeMapper = subjectiveGradeMapper;
    }

    /** 单份判分结果：success=false 时 error 为失败原因（已落 grading_error）。 */
    public record GradeOutcome(Long submissionId, Long studentId, boolean success, String error) {
        static GradeOutcome ok(GradingSubmission submission) {
            return new GradeOutcome(submission.getId(), submission.getStudentId(), true, null);
        }

        static GradeOutcome fail(GradingSubmission submission, String error) {
            return new GradeOutcome(submission.getId(), submission.getStudentId(), false, error);
        }
    }

    /**
     * 安全判分入口：异常一律就地捕获并标记"判分失败"，绝不向编排层扩散——
     * 一份坏答卷（答案 JSON 损坏、快照缺题等）不能中断整场判分（失败隔离）。
     *
     * <p>{@code existingGrades} 是编排层在进入逐份写入<b>之前</b>按本场答卷一次取出的
     * 已有主观批改行（batch-grading-subjective-upserts，按题 ID 索引；无行传空 Map）——
     * 判分循环内不再按「答卷 × 题目」selectOne。
     */
    public GradeOutcome gradeSafely(GradingSubmission submission, GradingPaper paper,
                                    Map<Long, SubjectiveGrade> existingGrades) {
        try {
            doGrade(submission, paper, existingGrades);
            return GradeOutcome.ok(submission);
        } catch (Exception e) {
            // 判分失败只标记本答卷：grading_error 保留现场原因，供教师排查/重判/手动给分
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            markGradingFailed(submission.getId(), reason);
            log.warn("答卷判分失败: submission={} 原因={}", submission.getId(), reason);
            return GradeOutcome.fail(submission, reason);
        }
    }

    /**
     * 已有主观批改行一次取出（batch-grading-subjective-upserts）：按本场答卷 ID 一条
     * IN 查询取回，按答卷 ID 分组、组内按题 ID 归一（同题多行取首行，脏数据不中断判分）。
     * 无简答题或无答卷时不发这张表的查询。预取本身是一次读，失败按整场异常上抛
     * （与成绩汇总的批量读取口径一致），逐份写入的失败隔离不变。
     */
    public Map<Long, Map<Long, SubjectiveGrade>> loadSubjectiveGrades(List<GradingSubmission> submissions,
                                                                      GradingPaper paper) {
        if (submissions.isEmpty() || paper.shortAnswerQuestions().isEmpty()) {
            return Map.of();
        }
        List<SubjectiveGrade> rows = subjectiveGradeMapper.selectList(
                Wrappers.<SubjectiveGrade>lambdaQuery()
                        .in(SubjectiveGrade::getSubmissionId,
                                submissions.stream().map(GradingSubmission::getId).toList()));
        return rows.stream().collect(Collectors.groupingBy(SubjectiveGrade::getSubmissionId,
                Collectors.toMap(SubjectiveGrade::getQuestionId, Function.identity(), (a, b) -> a)));
    }

    /** 判分主体：客观题逐题分派策略 + 简答初判建行 + 汇总落库。 */
    private void doGrade(GradingSubmission submission, GradingPaper paper,
                         Map<Long, SubjectiveGrade> existingGrades) {
        Map<Long, String> answers = paperReader.parseAnswers(submission.getAnswers());

        BigDecimal objectiveScore = BigDecimal.ZERO;
        for (GradingQuestion question : paper.questions()) {
            String studentAnswer = answers.get(question.questionId());
            if (question.type() == QuestionType.SHORT_ANSWER) {
                // 简答不入客观分：建/更新批改行（含初判提示分），终分由教师批改产生
                upsertSubjectiveRow(submission, question, studentAnswer,
                        existingGrades.get(question.questionId()));
                continue;
            }
            // 策略分派：按题型路由到单选/多选/判断策略（新增题型在此自动生效）
            GradeResult result = strategyRegistry.dispatch(question)
                    .grade(question, studentAnswer, gradingConfig);
            if (result.score() != null) {
                objectiveScore = objectiveScore.add(result.score());
            }
        }

        // 客观分落库：仅限已交卷/已批改答卷（进行中答卷不该被判分，并发护栏）
        int rows = gradingSubmissionMapper.update(null, Wrappers.<GradingSubmission>lambdaUpdate()
                .eq(GradingSubmission::getId, submission.getId())
                .in(GradingSubmission::getStatus, ExamSubmission.STATUS_SUBMITTED, ExamSubmission.STATUS_GRADED)
                .set(GradingSubmission::getObjectiveScore, GradingConfig.scale(objectiveScore))
                .set(GradingSubmission::getGradingStatus, GRADING_OK)
                .set(GradingSubmission::getGradingError, null)
                .set(GradingSubmission::getUpdatedTime, LocalDateTime.now()));
        if (rows == 0) {
            throw new IllegalStateException("答卷状态不允许判分（未交卷或不存在）");
        }
        log.info("答卷客观判分完成: submission={} objective={}", submission.getId(), objectiveScore);
    }

    /**
     * 主观批改行 upsert（幂等，重判安全）：行来自预取结果，不再逐题查库。
     * 已有行只刷新学生答案与初判提示分（教师终分/评语/version 原样保留），
     * 无行则新建——同一答卷重判 N 次不会产生重复行或覆盖教师批改结果。
     */
    private void upsertSubjectiveRow(GradingSubmission submission, GradingQuestion question,
                                     String studentAnswer, SubjectiveGrade existing) {
        GradeResult suggestion = strategyRegistry.dispatch(question)
                .grade(question, studentAnswer, gradingConfig);

        if (existing != null) {
            subjectiveGradeMapper.update(null, Wrappers.<SubjectiveGrade>lambdaUpdate()
                    .eq(SubjectiveGrade::getId, existing.getId())
                    .set(SubjectiveGrade::getStudentAnswer, studentAnswer)
                    .set(SubjectiveGrade::getSuggestedScore, suggestion.score())
                    .set(SubjectiveGrade::getSuggestedDetail, suggestion.detail())
                    .set(SubjectiveGrade::getQuestionNumber, question.number())
                    .set(SubjectiveGrade::getUpdatedTime, LocalDateTime.now()));
            return;
        }
        SubjectiveGrade row = new SubjectiveGrade();
        row.setSubmissionId(submission.getId());
        row.setExamId(submission.getExamId());
        row.setStudentId(submission.getStudentId());
        row.setQuestionId(question.questionId());
        row.setQuestionNumber(question.number());
        row.setStudentAnswer(studentAnswer);
        row.setSuggestedScore(suggestion.score());
        row.setSuggestedDetail(suggestion.detail());
        row.setVersion(0);
        subjectiveGradeMapper.insert(row);
    }

    /** 标记判分失败（§9.8）：失败原因落库，教师可重判或手动给分。 */
    private void markGradingFailed(Long submissionId, String reason) {
        try {
            gradingSubmissionMapper.update(null, Wrappers.<GradingSubmission>lambdaUpdate()
                    .eq(GradingSubmission::getId, submissionId)
                    .set(GradingSubmission::getGradingStatus, GRADING_FAILED)
                    .set(GradingSubmission::getGradingError, truncate(reason, 512))
                    .set(GradingSubmission::getUpdatedTime, LocalDateTime.now()));
        } catch (Exception e) {
            // 标记本身失败（如 DB 抖动）只记日志：不能因标记失败掩盖原始判分异常
            log.error("判分失败标记落库异常: submission={}", submissionId, e);
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
