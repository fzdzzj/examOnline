package com.exam.score.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.grading.model.GradeResult;
import com.exam.grading.model.GradingConfig;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.strategy.GradingStrategyRegistry;
import com.exam.grading.support.GradingPaperReader;
import com.exam.question.entity.QuestionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 逐题得分解析器（成绩导出/统计的统一取数口径）：
 *
 * <p>为什么现场重算客观题而不是另存逐题分数——客观判分是确定性的（同引擎同快照同答案），
 * 用判分引擎本体重算可保证"导出的逐题分"与"落库的客观总分"永远同口径，
 * 且不需要为导出额外建逐题分数表；主观题取教师批改终分（事实源），
 * 未批按 0 分并带 graded=false 标记（§7.5 部分批改口径）。
 */
@Component
public class QuestionScoreResolver {

    private final GradingStrategyRegistry strategyRegistry;
    private final GradingConfig gradingConfig;
    private final GradingPaperReader paperReader;
    private final SubjectiveGradeMapper subjectiveGradeMapper;

    public QuestionScoreResolver(GradingStrategyRegistry strategyRegistry,
                                 GradingConfig gradingConfig,
                                 GradingPaperReader paperReader,
                                 SubjectiveGradeMapper subjectiveGradeMapper) {
        this.strategyRegistry = strategyRegistry;
        this.gradingConfig = gradingConfig;
        this.paperReader = paperReader;
        this.subjectiveGradeMapper = subjectiveGradeMapper;
    }

    /**
     * 单题解析结果：
     * score=得分（未批主观题为 0）；max=本题满分；fullScore=是否得满分（答对率统计口径）；
     * graded=主观题是否已批（客观题恒 true）；comment=教师评语；detail=客观判分依据。
     */
    public record ResolvedQuestionScore(BigDecimal score, BigDecimal max, boolean fullScore,
                                        boolean graded, String comment, String detail) {
    }

    /**
     * 解析一份答卷的逐题得分（questionId → 得分明细）。
     * 答案 JSON 损坏时抛出异常——导出场景不允许静默给 0 分误导教师，由调用方决定跳过/报错。
     */
    public Map<Long, ResolvedQuestionScore> resolve(GradingSubmission submission, GradingPaper paper) {
        Map<Long, String> answers = paperReader.parseAnswers(submission.getAnswers());
        Map<Long, SubjectiveGrade> subjectiveRows = subjectiveGradeMapper.selectList(
                        Wrappers.<SubjectiveGrade>lambdaQuery()
                                .eq(SubjectiveGrade::getSubmissionId, submission.getId()))
                .stream()
                .collect(Collectors.toMap(SubjectiveGrade::getQuestionId, Function.identity(), (a, b) -> a));

        return paper.questions().stream().collect(Collectors.toMap(
                GradingQuestion::questionId,
                question -> resolveOne(question, answers.get(question.questionId()),
                        subjectiveRows.get(question.questionId()))));
    }

    private ResolvedQuestionScore resolveOne(GradingQuestion question, String studentAnswer,
                                             SubjectiveGrade subjectiveRow) {
        if (question.type() == QuestionType.SHORT_ANSWER) {
            boolean graded = subjectiveRow != null && subjectiveRow.getScore() != null;
            BigDecimal score = graded ? subjectiveRow.getScore() : BigDecimal.ZERO;
            return new ResolvedQuestionScore(score, question.score(),
                    graded && score.compareTo(question.score()) == 0,
                    graded,
                    subjectiveRow == null ? null : subjectiveRow.getComment(),
                    null);
        }
        GradeResult result = strategyRegistry.dispatch(question).grade(question, studentAnswer, gradingConfig);
        return new ResolvedQuestionScore(result.score(), question.score(),
                result.correct(), true, null, result.detail());
    }

    /** 批量解析（逐题明细导出用）：多份答卷一次解析，主观批改行按 submissionId 批查。 */
    public Map<Long, Map<Long, ResolvedQuestionScore>> resolveBatch(List<GradingSubmission> submissions,
                                                                    GradingPaper paper) {
        List<Long> submissionIds = submissions.stream().map(GradingSubmission::getId).toList();
        Map<Long, List<SubjectiveGrade>> rowsBySubmission = subjectiveGradeMapper.selectList(
                        Wrappers.<SubjectiveGrade>lambdaQuery()
                                .in(SubjectiveGrade::getSubmissionId, submissionIds))
                .stream()
                .collect(Collectors.groupingBy(SubjectiveGrade::getSubmissionId));

        Map<Long, Map<Long, String>> answersBySubmission = new java.util.HashMap<>();
        Map<Long, Map<Long, SubjectiveGrade>> subjectiveBySubmission = new java.util.HashMap<>();
        for (GradingSubmission submission : submissions) {
            answersBySubmission.put(submission.getId(), paperReader.parseAnswers(submission.getAnswers()));
            subjectiveBySubmission.put(submission.getId(),
                    rowsBySubmission.getOrDefault(submission.getId(), List.of()).stream()
                            .collect(Collectors.toMap(SubjectiveGrade::getQuestionId, Function.identity(),
                                    (a, b) -> a)));
        }

        Map<Long, Map<Long, ResolvedQuestionScore>> result = new java.util.LinkedHashMap<>();
        for (GradingSubmission submission : submissions) {
            Map<Long, String> answers = answersBySubmission.get(submission.getId());
            Map<Long, SubjectiveGrade> subjective = subjectiveBySubmission.get(submission.getId());
            Map<Long, ResolvedQuestionScore> perQuestion = new java.util.LinkedHashMap<>();
            for (GradingQuestion question : paper.questions()) {
                perQuestion.put(question.questionId(),
                        resolveOne(question, answers.get(question.questionId()), subjective.get(question.questionId())));
            }
            result.put(submission.getId(), perQuestion);
        }
        return result;
    }
}
