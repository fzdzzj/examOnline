package com.exam.submission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.submission.dto.AbnormalBehaviorStat;
import com.exam.submission.entity.ExamBehaviorLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 考试行为日志 Mapper：切屏/失焦等事件的落库通道。
 * 阶段 7 防作弊开始消费本表：分页/时间线走 MyBatis-Plus 条件构造，聚合统计走自定义 SQL。
 */
@Mapper
public interface ExamBehaviorLogMapper extends BaseMapper<ExamBehaviorLog> {

    /**
     * 异常行为聚合（监考大屏异常高亮数据源）：按学生聚合严重度达到阈值的行为事件。
     * severity >= 2（中/高）即视为异常（spec「异常行为学生被高亮」场景）；
     * GROUP BY 一次取回全考场，教师轮询大屏不产生逐学生查询。
     */
    @Select("SELECT student_id, COUNT(*) AS eventCount, MAX(severity) AS maxSeverity, "
            + "MAX(event_time) AS lastEventTime "
            + "FROM exam_behavior_logs WHERE exam_id = #{examId} AND severity >= #{minSeverity} "
            + "GROUP BY student_id")
    List<AbnormalBehaviorStat> selectAbnormalStats(@Param("examId") Long examId,
                                                   @Param("minSeverity") int minSeverity);
}
