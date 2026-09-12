package com.exam.grading.strategy;

import com.exam.grading.model.GradeResult;
import com.exam.grading.model.GradingConfig;
import com.exam.grading.model.GradingQuestion;
import com.exam.question.entity.QuestionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 简答题关键词初判策略（spec「简答批改」需求：初判仅提供提示分，最终分数由教师人工批改确定）。
 *
 * <p>为什么不定分——关键词匹配无法理解语义（同义改写漏判、关键词堆砌误判），
 * 自动定分会造成成绩争议（docs/需求决策记录.md 简答初判误伤风险）；
 * 初判只产出"提示分 + 命中明细"，写入 subjective_grades.suggested_score 供教师参考。
 *
 * <p>初判算法：把参考答案按分隔符（中英文逗号/分号/顿号/空白/句号）切成关键词集合，
 * 命中率 = 学生答案（忽略大小写）包含的关键词数 / 关键词总数，提示分 = 满分 × 命中率；
 * 参考答案无可拆关键词（如纯数字短答案）时不给提示分（返回 null，交由教师自批）。
 */
@Component
public class ShortAnswerGradingStrategy implements GradingStrategy {

    /** 参考答案关键词分隔符：中英文逗号/分号/顿号/空白/句号/分号。 */
    private static final String KEYWORD_SEPARATOR = "[,，;；、。\\.\\s]+";

    @Override
    public QuestionType questionType() {
        return QuestionType.SHORT_ANSWER;
    }

    @Override
    public GradeResult grade(GradingQuestion question, String studentAnswer, GradingConfig config) {
        if (studentAnswer == null || studentAnswer.isBlank()) {
            return GradeResult.zero("未作答");
        }
        List<String> keywords = extractKeywords(question.correctAnswer());
        if (keywords.isEmpty()) {
            // 参考答案无法拆出关键词：无提示分（detail 说明，让教师知道为何没有初判）
            return new GradeResult(null, false, "参考答案无可拆分关键词，无初判提示分");
        }
        String lowerAnswer = studentAnswer.toLowerCase(Locale.ROOT);
        List<String> missed = new ArrayList<>();
        int hits = 0;
        for (String keyword : keywords) {
            if (lowerAnswer.contains(keyword.toLowerCase(Locale.ROOT))) {
                hits++;
            } else {
                missed.add(keyword);
            }
        }
        // 提示分 = 满分 × 命中数 / 关键词总数（乘法在前除法在后，避免中间精度损失；
        // 1 位小数向下截断，与分值口径一致）
        BigDecimal suggested = question.score().multiply(BigDecimal.valueOf(hits))
                .divide(BigDecimal.valueOf(keywords.size()), 1, RoundingMode.DOWN);
        return new GradeResult(suggested, false,
                "命中关键词 " + hits + "/" + keywords.size()
                        + (missed.isEmpty() ? "" : "，未命中：" + String.join("、", missed)));
    }

    /** 参考答案切关键词：去重保序，单字关键词无判别力，过滤掉。 */
    private List<String> extractKeywords(String referenceAnswer) {
        if (referenceAnswer == null || referenceAnswer.isBlank()) {
            return List.of();
        }
        List<String> keywords = new ArrayList<>();
        for (String token : referenceAnswer.split(KEYWORD_SEPARATOR)) {
            if (token.length() > 1 && !keywords.contains(token)) {
                keywords.add(token);
            }
        }
        return keywords;
    }
}
