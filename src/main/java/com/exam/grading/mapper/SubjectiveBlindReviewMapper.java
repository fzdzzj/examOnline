package com.exam.grading.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.grading.entity.SubjectiveBlindReview;
import org.apache.ibatis.annotations.Mapper;

/**
 * 双评盲评 Mapper（add-subjective-double-blind-grading）：
 * 一评/二评/仲裁记录的常规 CRUD 走 MyBatis-Plus；
 * 一轮一条由 uk_blind_review(submission_id, question_id, round_type) 数据库兜底，
 * 并发重复提交的 DuplicateKeyException 由服务层转 409。
 */
@Mapper
public interface SubjectiveBlindReviewMapper extends BaseMapper<SubjectiveBlindReview> {
}
