package com.exam.grading.strategy;

import com.exam.grading.model.GradeResult;
import com.exam.grading.model.GradingConfig;
import com.exam.grading.model.GradingQuestion;
import com.exam.question.entity.QuestionType;

/**
 * 题型判分策略（面试弹药 A「策略模式」的代码载体，spec「判分策略」需求）：
 *
 * <p>为什么用策略模式——判分规则按题型差异极大（精确匹配/比例给分/关键词初判），
 * if-else 分支会让判分核心随题型膨胀；策略接口把"分派"与"规则"解耦，
 * 新增题型（如阶段决策中的填空 §7.4、代码 §9.7）只需新增实现并注册，判分核心零改动。
 *
 * <p>约定：实现必须无状态（单例复用），答案归一化一律复用阶段 3 的 {@code AnswerNormalizer}，
 * 不允许自行实现归一化（保证"入库口径 = 判分口径"）。
 */
public interface GradingStrategy {

    /** 本策略负责的题型（注册与分派依据）。 */
    QuestionType questionType();

    /**
     * 判分。
     *
     * @param question      考试快照题目（含归一化标准答案与分值）
     * @param studentAnswer 学生原始答案（answers JSON 中的字符串，可能为 null/空/格式非法）
     * @param config        判分配置（部分分比例等）
     * @return 判分结果；学生答案为空或非法时返回 0 分，不抛异常（答案问题 ≠ 判分失败）
     */
    GradeResult grade(GradingQuestion question, String studentAnswer, GradingConfig config);
}
