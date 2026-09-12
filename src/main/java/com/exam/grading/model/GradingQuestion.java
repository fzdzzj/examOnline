package com.exam.grading.model;

import com.exam.question.entity.QuestionType;

import java.math.BigDecimal;
import java.util.List;

/**
 * 参与判分的单题数据（从考试快照 paperJson 解析，而非读 questions 表）：
 *
 * <p>为什么读快照——spec「考试快照」约定：考试发布后题目/答案/分值/题序以快照为准，
 * 之后题库被改甚至软删都不影响历史场次（§10.10）；判分读题库会造成
 * "学生考的题"与"判分用的题"不一致。选项乱序在个人快照里做且已重映射答案字母，
 * 快照内 correctAnswer 恒与个人快照口径一致，判分可直接比对。
 *
 * @param number        考试快照内题号（1 起连续，展示用）
 * @param questionId    题目真实主键（作答与判分的对齐键）
 * @param type          题型（策略分派依据）
 * @param content       题干（工作台/个人成绩单展示）
 * @param choices       客观题选项内容列表（判断/简答为 null）
 * @param correctAnswer 归一化标准答案（阶段 3 AnswerNormalizer 口径：单选字母/多选升序字母列表/判断 T-F/简答原文）
 * @param score         试卷内分值（快照锁定，覆盖题库默认分）
 */
public record GradingQuestion(
        int number,
        Long questionId,
        QuestionType type,
        String content,
        List<String> choices,
        String correctAnswer,
        BigDecimal score) {

    /** 客观题选项数量：学生答案归一化时需要校验选项字母范围（判断/简答传 0）。 */
    public int choiceCount() {
        return choices == null ? 0 : choices.size();
    }
}
