package com.exam.grading.strategy;

import com.exam.grading.model.GradeResult;
import com.exam.grading.model.GradingConfig;
import com.exam.grading.model.GradingQuestion;
import com.exam.question.entity.QuestionType;
import com.exam.question.support.AnswerNormalizer;
import com.exam.common.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 单选题判分策略：与标准答案精确匹配，对得满分、错得 0 分（spec「单选正确」场景）。
 *
 * <p>学生答案先经 {@link AnswerNormalizer} 归一化（兼容小写字母等录入差异）再比对，
 * 复用阶段 3 的统一口径而非重新实现；答案为空/非法按 0 分计，不视为判分失败。
 */
@Component
public class SingleChoiceGradingStrategy implements GradingStrategy {

    @Override
    public QuestionType questionType() {
        return QuestionType.SINGLE;
    }

    @Override
    public GradeResult grade(GradingQuestion question, String studentAnswer, GradingConfig config) {
        if (studentAnswer == null || studentAnswer.isBlank()) {
            return GradeResult.zero("未作答");
        }
        String normalized;
        try {
            normalized = AnswerNormalizer.normalize(QuestionType.SINGLE, studentAnswer, question.choiceCount());
        } catch (BusinessException e) {
            // 答案格式非法（如越界字母）：按 0 分计并说明原因，不中断整卷判分
            return GradeResult.zero("答案格式非法：" + studentAnswer);
        }
        boolean correct = Objects.equals(normalized, question.correctAnswer());
        return new GradeResult(correct ? question.score() : BigDecimal.ZERO, correct,
                correct ? "回答正确" : "正确答案 " + question.correctAnswer());
    }
}
