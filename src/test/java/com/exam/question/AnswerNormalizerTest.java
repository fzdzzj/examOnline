package com.exam.question;

import com.exam.common.BusinessException;
import com.exam.question.entity.QuestionType;
import com.exam.question.support.AnswerNormalizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 答案归一化单元测试（spec「答案归一化」需求）：
 * 判断题只存 T/F（多种别名）、多选去重升序消除顺序歧义、单选字母范围校验、简答保留原文。
 */
class AnswerNormalizerTest {

    // ==================== 判断题 -> T/F ====================

    @Test
    void judgeTrueAliasesNormalizeToT() {
        assertEquals("T", AnswerNormalizer.normalize(QuestionType.JUDGE, "正确", 0));
        assertEquals("T", AnswerNormalizer.normalize(QuestionType.JUDGE, "对", 0));
        assertEquals("T", AnswerNormalizer.normalize(QuestionType.JUDGE, "A", 0));
        assertEquals("T", AnswerNormalizer.normalize(QuestionType.JUDGE, "t", 0));
        assertEquals("T", AnswerNormalizer.normalize(QuestionType.JUDGE, "TRUE", 0));
        assertEquals("T", AnswerNormalizer.normalize(QuestionType.JUDGE, "√", 0));
        assertEquals("T", AnswerNormalizer.normalize(QuestionType.JUDGE, "是", 0));
    }

    @Test
    void judgeFalseAliasesNormalizeToF() {
        assertEquals("F", AnswerNormalizer.normalize(QuestionType.JUDGE, "错误", 0));
        assertEquals("F", AnswerNormalizer.normalize(QuestionType.JUDGE, "错", 0));
        assertEquals("F", AnswerNormalizer.normalize(QuestionType.JUDGE, "B", 0));
        assertEquals("F", AnswerNormalizer.normalize(QuestionType.JUDGE, "f", 0));
        assertEquals("F", AnswerNormalizer.normalize(QuestionType.JUDGE, "false", 0));
        assertEquals("F", AnswerNormalizer.normalize(QuestionType.JUDGE, "×", 0));
        assertEquals("F", AnswerNormalizer.normalize(QuestionType.JUDGE, "否", 0));
    }

    @Test
    void judgeInvalidAnswerRejected() {
        assertThrows(BusinessException.class,
                () -> AnswerNormalizer.normalize(QuestionType.JUDGE, "C", 0));
        assertThrows(BusinessException.class,
                () -> AnswerNormalizer.normalize(QuestionType.JUDGE, "  ", 0));
    }

    // ==================== 多选题 -> 去重升序字母列表 ====================

    @Test
    void multipleChoiceSortedDeduplicated() {
        // 乱序/重复/小写统一为升序大写，消除顺序歧义
        assertEquals("A,B,C", AnswerNormalizer.normalize(QuestionType.MULTIPLE, "B,A,C", 4));
        assertEquals("A,B,C", AnswerNormalizer.normalize(QuestionType.MULTIPLE, "c b a", 4));
        assertEquals("A,B,C,D", AnswerNormalizer.normalize(QuestionType.MULTIPLE, "d,c,b,a", 4));
        assertEquals("A,B", AnswerNormalizer.normalize(QuestionType.MULTIPLE, "A,A,B,B", 4));
        // 连写与中文分隔符均可录入
        assertEquals("A,B,C", AnswerNormalizer.normalize(QuestionType.MULTIPLE, "ACB", 4));
        assertEquals("A,B", AnswerNormalizer.normalize(QuestionType.MULTIPLE, "A、b", 4));
    }

    @Test
    void multipleChoiceInvalidLetterRejected() {
        // 超出选项范围（4 个选项 -> A-D）
        assertThrows(BusinessException.class,
                () -> AnswerNormalizer.normalize(QuestionType.MULTIPLE, "A,E", 4));
        // 非字母字符
        assertThrows(BusinessException.class,
                () -> AnswerNormalizer.normalize(QuestionType.MULTIPLE, "A,5", 4));
    }

    // ==================== 单选题 -> 单个大写字母 ====================

    @Test
    void singleChoiceNormalizedAndRangeChecked() {
        assertEquals("A", AnswerNormalizer.normalize(QuestionType.SINGLE, "a", 4));
        assertEquals("D", AnswerNormalizer.normalize(QuestionType.SINGLE, "d", 4));
        // 范围外/多字母拒绝
        assertThrows(BusinessException.class,
                () -> AnswerNormalizer.normalize(QuestionType.SINGLE, "E", 4));
        assertThrows(BusinessException.class,
                () -> AnswerNormalizer.normalize(QuestionType.SINGLE, "AB", 4));
    }

    // ==================== 简答题 -> 保留原文 ====================

    @Test
    void shortAnswerKeepsRawText() {
        assertEquals("参考答案内容", AnswerNormalizer.normalize(QuestionType.SHORT_ANSWER, "  参考答案内容  ", 0));
    }

    @Test
    void blankAnswerRejected() {
        assertThrows(BusinessException.class,
                () -> AnswerNormalizer.normalize(QuestionType.SINGLE, " ", 4));
    }
}
