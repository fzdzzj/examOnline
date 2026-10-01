package com.exam.question.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.question.entity.QuestionTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 题目 - 标签关联查询仓库：提供参数化的标签查询能力，避免 SQL 注入风险。
 */
@Mapper
public interface QuestionTagRepository extends BaseMapper<QuestionTag> {

    /**
     * 根据标签 ID 列表查询关联的题目 ID（参数化查询，防止 SQL 注入）。
     *
     * @param tagIds 标签 ID 列表
     * @return 关联的题目 ID 列表
     */
    default List<Long> findQuestionIdsByTagIds(@Param("tagIds") List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        // 使用 MyBatis-Plus 的 lambda 查询，自动参数化
        return selectList(Wrappers.<QuestionTag>lambdaQuery()
                .select(QuestionTag::getQuestionId)
                .in(QuestionTag::getTagId, tagIds))
                .stream()
                .map(QuestionTag::getQuestionId)
                .toList();
    }
}
