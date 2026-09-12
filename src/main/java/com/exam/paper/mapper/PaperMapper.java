package com.exam.paper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.paper.entity.Paper;
import org.apache.ibatis.annotations.Mapper;

/** 试卷 Mapper。 */
@Mapper
public interface PaperMapper extends BaseMapper<Paper> {
}
