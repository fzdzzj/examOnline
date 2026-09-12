package com.exam.grading.strategy;

import com.exam.common.BusinessException;
import com.exam.grading.model.GradeResult;
import com.exam.grading.model.GradingConfig;
import com.exam.grading.model.GradingQuestion;
import com.exam.question.entity.QuestionType;
import com.exam.question.support.AnswerNormalizer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 多选题判分策略（docs/需求决策记录.md §8.9，spec「多选漏选部分分」「多选错选零分」场景）：
 *
 * <p>算法（三段式）：
 * <ol>
 *   <li>错选/多选即 0 分——学生选了任何标准答案之外的选项，说明概念掌握有硬伤，
 *       部分"蒙对"不折算，直接判 0（比按比例折算更严格，防凑选项得分）；</li>
 *   <li>全对得满分——选中集合与标准答案集合相等；</li>
 *   <li>漏选给部分分——得分 = 满分 × 配置系数 × (选中正确数 / 应选数)。
 *       除法先按 4 位小数求值、最后统一 setScale(1, DOWN) 截断：先乘后除会引入
 *       两次舍入误差，且 DOWN 截断保证系统绝不因舍入多给学生分。</li>
 * </ol>
 *
 * <p>学生答案仍经 {@link AnswerNormalizer} 归一化（"b,a"→"A,B"），复用阶段 3 口径。
 */
@Component
public class MultipleChoiceGradingStrategy implements GradingStrategy {

    @Override
    public QuestionType questionType() {
        return QuestionType.MULTIPLE;
    }

    @Override
    public GradeResult grade(GradingQuestion question, String studentAnswer, GradingConfig config) {
        if (studentAnswer == null || studentAnswer.isBlank()) {
            return GradeResult.zero("未作答");
        }
        Set<String> standard = letters(question.correctAnswer());
        Set<String> student;
        try {
            student = letters(AnswerNormalizer.normalize(
                    QuestionType.MULTIPLE, studentAnswer, question.choiceCount()));
        } catch (BusinessException e) {
            // 答案含无效选项等格式问题：按 0 分计，不中断整卷判分
            return GradeResult.zero("答案格式非法：" + studentAnswer);
        }

        // 第 1 段：错选/多选 0 分（选中集合须为标准答案的子集）
        Set<String> wrong = new LinkedHashSet<>(student);
        wrong.removeAll(standard);
        if (!wrong.isEmpty()) {
            return GradeResult.zero("错选 " + String.join(",", wrong) + "，判 0 分");
        }
        if (student.isEmpty()) {
            return GradeResult.zero("未选中任何有效选项");
        }

        // 第 2 段：全对满分
        if (student.equals(standard)) {
            return new GradeResult(question.score(), true, "全对");
        }

        // 第 3 段：漏选部分分 = 满分 × 系数 × (选中正确数 / 应选数)
        // 全部乘法用精确值先算、除法只做一次且最后做：若先除后乘，1/3 之类的无穷小数
        // 会在中间步骤损失精度（6×0.5×(1/3) 会得 0.9 而非精确的 1.0）；
        // 最终除法按 1 位小数向下截断（DOWN），保证系统绝不因舍入多给学生分。
        BigDecimal partial = question.score().multiply(config.getMultiplePartialRatio())
                .multiply(BigDecimal.valueOf(student.size()))
                .divide(BigDecimal.valueOf(standard.size()), 1, RoundingMode.DOWN);
        Set<String> missed = new LinkedHashSet<>(standard);
        missed.removeAll(student);
        return new GradeResult(partial, false,
                "漏选 " + String.join(",", missed) + "，按比例给部分分");
    }

    /** "A,B,C" → 字母集合（归一化格式保证升序去重，直接拆分即可）。 */
    private Set<String> letters(String normalizedAnswer) {
        Set<String> letters = new LinkedHashSet<>();
        for (String letter : normalizedAnswer.split(",")) {
            if (!letter.isBlank()) {
                letters.add(letter.trim());
            }
        }
        return letters;
    }
}
