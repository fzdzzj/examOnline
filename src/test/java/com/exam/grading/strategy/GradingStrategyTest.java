package com.exam.grading.strategy;

import com.exam.grading.model.GradeResult;
import com.exam.grading.model.GradingConfig;
import com.exam.grading.model.GradingQuestion;
import com.exam.question.entity.QuestionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判分策略单元测试（spec add-grading-score「客观题判分」场景 + 简答初判）：
 * 纯内存测试，不启动 Spring——策略必须无状态，直接 new 验证算法本身。
 */
class GradingStrategyTest {

    private final GradingConfig config = new GradingConfig(BigDecimal.ONE);
    /** 漏选惩罚系数 0.5 的配置（部分分比例可配置的另一档验证）。 */
    private final GradingConfig halfConfig = new GradingConfig(new BigDecimal("0.5"));

    private SingleChoiceGradingStrategy single;
    private MultipleChoiceGradingStrategy multiple;
    private JudgeGradingStrategy judge;
    private ShortAnswerGradingStrategy shortAnswer;

    @BeforeEach
    void setUp() {
        single = new SingleChoiceGradingStrategy();
        multiple = new MultipleChoiceGradingStrategy();
        judge = new JudgeGradingStrategy();
        shortAnswer = new ShortAnswerGradingStrategy();
    }

    private GradingQuestion question(QuestionType type, String correctAnswer, double score) {
        return new GradingQuestion(1, 100L, type, "题干",
                type == QuestionType.SINGLE || type == QuestionType.MULTIPLE
                        ? List.of("选项A", "选项B", "选项C", "选项D") : null,
                correctAnswer, BigDecimal.valueOf(score));
    }

    // ==================== 单选 ====================

    @Test
    @DisplayName("单选：答对满分、答错0分、大小写归一化、非法答案0分")
    void singleChoice() {
        GradingQuestion q = question(QuestionType.SINGLE, "A", 5);
        assertEquals(0, single.grade(q, "A", config).score().compareTo(BigDecimal.valueOf(5)));
        assertTrue(single.grade(q, "A", config).correct());

        assertEquals(0, single.grade(q, "B", config).score().compareTo(BigDecimal.ZERO));
        assertFalse(single.grade(q, "B", config).correct());

        // 小写答案归一化后比对（复用阶段 3 AnswerNormalizer 口径）
        assertTrue(single.grade(q, "a", config).correct());

        // 未作答与格式非法都是 0 分而非判分失败
        assertEquals(0, single.grade(q, null, config).score().compareTo(BigDecimal.ZERO));
        assertEquals(0, single.grade(q, "  ", config).score().compareTo(BigDecimal.ZERO));
        assertEquals(0, single.grade(q, "E", config).score().compareTo(BigDecimal.ZERO));
    }

    // ==================== 多选 ====================

    @Test
    @DisplayName("多选：全对满分")
    void multipleFullScore() {
        GradingQuestion q = question(QuestionType.MULTIPLE, "A,B,C", 6);
        GradeResult result = multiple.grade(q, "A,B,C", config);
        assertEquals(0, result.score().compareTo(BigDecimal.valueOf(6)));
        assertTrue(result.correct());
    }

    @Test
    @DisplayName("多选漏选部分分：选2/3按比例给分，系数可配置")
    void multipleMissedPartialScore() {
        GradingQuestion q = question(QuestionType.MULTIPLE, "A,B,C", 6);

        // 系数 1.0：选 A,B（漏 C）→ 6 × 1.0 × (2/3) = 4.0
        GradeResult result = multiple.grade(q, "A,B", config);
        assertEquals(0, result.score().compareTo(BigDecimal.valueOf(4)));
        assertFalse(result.correct());

        // 系数 0.5：选 A（漏 B,C）→ 6 × 0.5 × (1/3) = 1.0
        GradeResult half = multiple.grade(q, "A", halfConfig);
        assertEquals(0, half.score().compareTo(BigDecimal.valueOf(1)));

        // 归一化：乱序/小写 "c,b,a" 等价 A,B,C 全对
        assertTrue(multiple.grade(q, "c,b,a", config).correct());
    }

    @Test
    @DisplayName("多选错选零分：含标准答案外选项直接0分")
    void multipleWrongChoiceZero() {
        GradingQuestion q = question(QuestionType.MULTIPLE, "A,B,C", 6);

        // 错选 D（3对1错也 0 分）
        assertEquals(0, multiple.grade(q, "A,B,C,D", config).score().compareTo(BigDecimal.ZERO));
        // 只选错项
        assertEquals(0, multiple.grade(q, "D", config).score().compareTo(BigDecimal.ZERO));
        // 越界字母按格式非法 0 分
        assertEquals(0, multiple.grade(q, "A,E", config).score().compareTo(BigDecimal.ZERO));
        // 未作答 0 分
        assertEquals(0, multiple.grade(q, null, config).score().compareTo(BigDecimal.ZERO));
    }

    // ==================== 判断 ====================

    @Test
    @DisplayName("判断：T/F 精确匹配，别名归一化（对/错、true/false）")
    void judge() {
        GradingQuestion q = question(QuestionType.JUDGE, "T", 4);
        assertTrue(judge.grade(q, "T", config).correct());
        assertEquals(0, judge.grade(q, "T", config).score().compareTo(BigDecimal.valueOf(4)));

        // 别名归一化复用阶段 3 AnswerNormalizer："对"/"正确"/"true" 均归一为 T
        assertTrue(judge.grade(q, "对", config).correct());
        assertTrue(judge.grade(q, "true", config).correct());

        assertFalse(judge.grade(q, "F", config).correct());
        assertEquals(0, judge.grade(q, "F", config).score().compareTo(BigDecimal.ZERO));
        assertEquals(0, judge.grade(q, "乱写", config).score().compareTo(BigDecimal.ZERO));
    }

    // ==================== 简答初判 ====================

    @Test
    @DisplayName("简答初判：按关键词命中率给提示分，不定分不判对")
    void shortAnswerSuggestion() {
        // 参考答案拆关键词：HTTP/协议/无状态 → 全命中提示满分
        GradingQuestion q = question(QuestionType.SHORT_ANSWER, "HTTP,协议,无状态", 10);
        GradeResult hitAll = shortAnswer.grade(q, "HTTP 是无状态的协议", config);
        assertEquals(0, hitAll.score().compareTo(BigDecimal.TEN));
        assertFalse(hitAll.correct(), "初判不是终分，恒不判满分对");
        assertTrue(hitAll.detail().contains("3/3"));

        // 命中 2/3 → 提示 6.7（10 × 2/3 = 6.666 向下截断 6.6）
        GradeResult hitPartial = shortAnswer.grade(q, "HTTP 是协议", config);
        assertEquals(0, hitPartial.score().compareTo(new BigDecimal("6.6")));
        assertTrue(hitPartial.detail().contains("2/3"));

        // 全未命中 → 0 分提示
        assertEquals(0, shortAnswer.grade(q, "不知道", config).score().compareTo(BigDecimal.ZERO));

        // 参考答案无可拆关键词（单字无判别力）→ 无提示分（null），教师自批
        GradingQuestion noKeyword = question(QuestionType.SHORT_ANSWER, "好", 10);
        assertNull(shortAnswer.grade(noKeyword, "函数调用自身", config).score());

        // 未作答 → 0 分
        assertEquals(0, shortAnswer.grade(q, null, config).score().compareTo(BigDecimal.ZERO));
    }

    // ==================== 策略注册 ====================

    @Test
    @DisplayName("策略注册中心：按题型分派，重复注册快速失败")
    void registryDispatch() {
        GradingStrategyRegistry registry = new GradingStrategyRegistry(
                List.of(single, multiple, judge, shortAnswer));
        GradingQuestion q = question(QuestionType.SINGLE, "A", 5);
        assertTrue(registry.dispatch(q) instanceof SingleChoiceGradingStrategy);
        assertTrue(registry.dispatch(question(QuestionType.MULTIPLE, "A", 5))
                instanceof MultipleChoiceGradingStrategy);
        assertTrue(registry.dispatch(question(QuestionType.JUDGE, "T", 5)) instanceof JudgeGradingStrategy);
        assertTrue(registry.dispatch(question(QuestionType.SHORT_ANSWER, "x", 5))
                instanceof ShortAnswerGradingStrategy);

        // 同题型两个实现：装配事故必须快速失败，不能随机分派
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new GradingStrategyRegistry(List.of(single, new SingleChoiceGradingStrategy())));
    }

    @Test
    @DisplayName("判分配置：比例系数越界拒绝")
    void configValidation() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new GradingConfig(BigDecimal.ZERO));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new GradingConfig(new BigDecimal("1.5")));
    }
}
