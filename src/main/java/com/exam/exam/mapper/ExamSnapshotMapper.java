package com.exam.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.exam.entity.ExamSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 考试快照 Mapper：快照只写不改，无自定义 SQL。
 */
@Mapper
public interface ExamSnapshotMapper extends BaseMapper<ExamSnapshot> {
}
