package com.exam.adaptive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.adaptive.entity.CatQuizSession;
import org.apache.ibatis.annotations.Mapper;

/** CAT 自适应测验会话 Mapper（表 cat_quiz_sessions，见 schema.sql）。 */
@Mapper
public interface CatQuizSessionMapper extends BaseMapper<CatQuizSession> {
}
