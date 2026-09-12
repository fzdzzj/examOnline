package com.exam.monitoring.dto;

import lombok.Data;

import java.util.List;

/**
 * 监考大屏总览（spec「监考大屏」需求：实时人数 + 答题进度 + 异常高亮）。
 * 教师端轮询本接口刷新（Redis 在线状态 + DB 快照聚合，无 WebSocket 依赖）。
 */
@Data
public class MonitorOverviewResponse {

    private Long examId;

    private String examTitle;

    /** 考试状态机：0未开始 1进行中 2已结束 3已批改 4已发布成绩 */
    private Integer examStatus;

    /** 已进入考试的学生总数（有答卷行） */
    private int totalStudents;

    /** 在线人数（心跳窗口内有活动） */
    private int onlineCount;

    /** 离线人数（进行中但心跳已过期） */
    private int offlineCount;

    /** 已交卷人数 */
    private int submittedCount;

    /** 异常学生数（严重度≥2 行为事件，大屏高亮对象） */
    private int abnormalCount;

    /** 试卷题目总数（进度条分母；个人快照题数与试卷一致，进入时锁定） */
    private int totalQuestions;

    /** 学生明细列表（异常学生排前，见 MonitorStudentItem 排序说明） */
    private List<MonitorStudentItem> students;
}
