package com.exam.grading.strategy;

import com.exam.grading.model.GradeResult;
import com.exam.grading.model.GradingConfig;
import com.exam.grading.model.GradingQuestion;
import com.exam.question.entity.QuestionType;
import org.springframework.stereotype.Component;

/**
 * 填空题判分策略（spec「填空题自动判分」需求，客观自动判分、单空）：
 *
 * <p>标准答案以 {@code |} 分隔承载多个可接受答案（{@code 答案1|答案2}，入库前已经
 * {@link com.exam.question.support.AnswerNormalizer} 归一化）。学生答案仅做 <b>trim</b>
 * ——不拆分、不重组，与标准答案逐项比对：
 * <ul>
 *   <li>命中任一项即可满分：中文按精确匹配（trim 后全等）；</li>
 *   <li>纯英文字母答案忽略大小写比对（{@code Http} 与 {@code http} 等价）；
 *       含非字母字符的答案只走精确匹配，不做其他模糊/相似度处理；</li>
 *   <li>全不中零分，判 0 分而非判分失败（答案不匹配 ≠ 判分失败）。</li>
 * </ul>
 *
 * <p>学生答案原样存档（{@code student_answer} 快照保留原文）——本策略只返回判分结果，
 * 不改写学生答案，trim 仅用于比对。
 */
@Component
public class FillBlankGradingStrategy implements GradingStrategy {

    /** 可接受答案分隔符（与入库归一化口径一致）。 */
    private static final String ANSWER_SEPARATOR = "\\|";

    /** 纯英文字母（忽略大小写比对的适用范围）。 */
    private static final String ENGLISH_LETTERS = "[A-Za-z]+";

    @Override
    public QuestionType questionType() {
        return QuestionType.FILL_BLANK;
    }

    @Override
    public GradeResult grade(GradingQuestion question, String studentAnswer, GradingConfig config) {
        if (studentAnswer == null || studentAnswer.isBlank()) {
            return GradeResult.zero("未作答");
        }
        // 学生答案仅 trim，不做拆分/重组（spec「学生侧答案仅 trim 不做其他变换」）
        String normalizedStudent = studentAnswer.trim();
        for (String accepted : question.correctAnswer().split(ANSWER_SEPARATOR)) {
            if (matches(accepted, normalizedStudent)) {
                return new GradeResult(question.score(), true, "回答正确");
            }
        }
        return GradeResult.zero("正确答案 " + question.correctAnswer());
    }

    /** 命中判定：精确全等，或双方均为纯英文字母时忽略大小写全等。 */
    private boolean matches(String accepted, String student) {
        if (accepted.equals(student)) {
            return true;
        }
        return accepted.matches(ENGLISH_LETTERS) && student.matches(ENGLISH_LETTERS)
                && accepted.equalsIgnoreCase(student);
    }
}