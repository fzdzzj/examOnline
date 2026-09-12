package com.exam.grading.strategy;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.grading.model.GradingQuestion;
import com.exam.question.entity.QuestionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 判分策略注册中心（spec「按题型分派」场景）：
 *
 * <p>Spring 注入全部 {@link GradingStrategy} 实现并按题型建索引——判分核心只依赖
 * 本注册中心的 {@code dispatch}，不知道具体策略存在；新增题型策略（@Component 即注册）
 * 判分核心零改动，这就是"新增题型不改核心"的落地点。
 *
 * <p>分派不到策略（题型未实现）按"判分失败"处理而非静默 0 分：0 分会污染成绩，
 * 失败标记能让教师感知并重判/手动给分（spec「判分失败处理」场景）。
 */
@Slf4j
@Component
public class GradingStrategyRegistry {

    private final Map<QuestionType, GradingStrategy> strategies = new EnumMap<>(QuestionType.class);

    public GradingStrategyRegistry(List<GradingStrategy> candidates) {
        for (GradingStrategy strategy : candidates) {
            GradingStrategy previous = strategies.put(strategy.questionType(), strategy);
            if (previous != null) {
                // 同题型重复注册是装配事故，直接快速失败避免随机分派
                throw new IllegalStateException("题型 " + strategy.questionType() + " 重复注册判分策略");
            }
            log.info("注册判分策略: {} -> {}", strategy.questionType(), strategy.getClass().getSimpleName());
        }
    }

    /** 按题型取策略；未注册题型抛业务异常（调用方按判分失败隔离）。 */
    public GradingStrategy dispatch(GradingQuestion question) {
        GradingStrategy strategy = strategies.get(question.type());
        if (strategy == null) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR,
                    "题型 " + question.type() + " 未实现判分策略");
        }
        return strategy;
    }
}
