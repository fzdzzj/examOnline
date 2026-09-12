package com.exam.paper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.paper.entity.PaperSnapshot;
import org.apache.ibatis.annotations.Mapper;

/** 试卷快照 Mapper（只写不改，无更新路径）。 */
@Mapper
public interface PaperSnapshotMapper extends BaseMapper<PaperSnapshot> {
}
