package com.exam.grading.model;

import com.exam.question.entity.QuestionType;

import java.math.BigDecimal;
import java.util.List;

/**
 * 判分用的试卷视图（考试快照 paperJson 的解析结果）：题目列表 + 分值口径。
 */
public record GradingPaper(
        Long paperId,
        String title,
        BigDecimal totalScore,
        List<GradingQuestion> questions) {

    /** 客观题（单选/多选/判断）分值之和：objective_score 的满分上限（手动给分校验用）。 */
    public BigDecimal objectiveTotalScore() {
        return questions.stream()
                .filter(q -> q.type() != QuestionType.SHORT_ANSWER)
                .map(GradingQuestion::score)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 简答题列表（初判建行/批改工作台用）。 */
    public List<GradingQuestion> shortAnswerQuestions() {
        return questions.stream()
                .filter(q -> q.type() == QuestionType.SHORT_ANSWER)
                .toList();
    }
}
