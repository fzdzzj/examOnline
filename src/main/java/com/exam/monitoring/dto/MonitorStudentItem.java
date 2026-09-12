package com.exam.monitoring.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 监考大屏学生行：状态 + 进度 + 异常高亮字段。
 * 点击 abnormal 学生的行，前端跳转行为时间线视图（GET .../behavior-logs/timeline）。
 */
@Data
public class MonitorStudentItem {

    /** 在线：心跳窗口内有答题活动 */
    public static final String STATUS_ONLINE = "ONLINE";

    /** 离线：进行中但心跳已过期（断网/关页/挂机） */
    public static final String STATUS_OFFLINE = "OFFLINE";

    /** 已交卷：不再参与在线判定 */
    public static final String STATUS_SUBMITTED = "SUBMITTED";

    private Long studentId;

    /** 学生姓名（users.name，缺失回退 username） */
    private String studentName;

    /** 在线/离线/已交卷（见 STATUS_* 常量） */
    private String status;

    /** 已答题数（草稿答案字段数，近似口径；已交卷=题目总数） */
    private int answeredCount;

    /** 进度百分比 0-100（大屏进度条） */
    private int progressPercent;

    /** 是否异常高亮：存在严重度≥2 的行为事件（切屏超阈值/交卷异常等） */
    private boolean abnormal;

    /** 异常事件数 */
    private long abnormalEventCount;

    /** 最高异常严重度（1低 2中 3高） */
    private Integer maxSeverity;

    /** 最近一次异常事件时间 */
    private LocalDateTime lastAbnormalTime;
}
