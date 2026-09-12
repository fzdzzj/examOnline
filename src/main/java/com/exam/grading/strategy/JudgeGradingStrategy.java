package com.exam.grading.strategy;

import com.exam.common.BusinessException;
import com.exam.grading.model.GradeResult;
import com.exam.grading.model.GradingConfig;
import com.exam.grading.model.GradingQuestion;
import com.exam.question.entity.QuestionType;
import com.exam.question.support.AnswerNormalizer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 判断题判分策略：学生答案归一化为 T/F 后与标准答案精确匹配（spec「客观题判分」需求）。
 *
 * <p>前端可能传 "T"/"F"/"对"/"错"/"true"/"false" 等形态——统一走 {@link AnswerNormalizer}
 * 的判断别名表归一化，与题目入库时的答案归一化共用同一套别名映射，避免
 * "录入认得的别名判分不认得"的口径漂移。
 */
@Component
public class JudgeGradingStrategy implements GradingStrategy {

    @Override
    public QuestionType questionType() {
        return QuestionType.JUDGE;
    }

    @Override
    public GradeResult grade(GradingQuestion question, String studentAnswer, GradingConfig config) {
        if (studentAnswer == null || studentAnswer.isBlank()) {
            return GradeResult.zero("未作答");
        }
        String normalized;
        try {
            normalized = AnswerNormalizer.normalize(QuestionType.JUDGE, studentAnswer, 0);
        } catch (BusinessException e) {
            return GradeResult.zero("答案格式非法：" + studentAnswer);
        }
        boolean correct = Objects.equals(normalized, question.correctAnswer());
        return new GradeResult(correct ? question.score() : BigDecimal.ZERO, correct,
                correct ? "回答正确" : "正确答案 " + question.correctAnswer());
    }
}
