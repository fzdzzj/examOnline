package com.exam.score.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.score.entity.ScoreReview;
import org.apache.ibatis.annotations.Mapper;

/**
 * 成绩复核 Mapper：无自定义 SQL，限次幂等由 uk_review_exam_student 唯一索引在 DB 层兜底。
 */
@Mapper
public interface ScoreReviewMapper extends BaseMapper<ScoreReview> {
}