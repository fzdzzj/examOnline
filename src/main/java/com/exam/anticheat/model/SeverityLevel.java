package com.exam.anticheat.model;

/**
 * 行为事件严重度分级（spec add-anti-cheat「事件严重度分级」需求）：
 *
 * <p>与 exam_behavior_logs.severity 列的存储口径对齐（阶段 5 建表：1提示 2警告 3严重），
 * 规范语义为 低/中/高 三级。分级由各事件策略（{@code BehaviorEventCollector}）判定，
 * 采集核心不写死任何事件的级别——新增/调整事件的严重度只改策略实现，不动采集核心。
 */
public enum SeverityLevel {

    /** 低（存储 1，阶段 5 表注释为"提示"）：常见正常波动，如单次切屏、多端草稿冲突 */
    LOW(1, "低"),

    /** 中（存储 2，阶段 5 表注释为"警告"）：可疑但未达严重，如切屏达到次数阈值 */
    MEDIUM(2, "中"),

    /** 高（存储 3，阶段 5 表注释为"严重"）：系统级异常或屡次可疑行为，如交卷链路异常、切屏超严重阈值 */
    HIGH(3, "高");

    /** exam_behavior_logs.severity 列存储值（与阶段 5 建表口径一致，不新增列不改语义） */
    private final int code;

    /** 规范语义名（低/中/高），用于接口响应与教师端展示 */
    private final String label;

    SeverityLevel(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    /**
     * 按存储值解析；非法值（前端乱传/历史脏数据）回退 LOW——
     * 行为采集是旁路，任何解析异常都不允许影响答题主链路。
     */
    public static SeverityLevel of(Integer code) {
        if (code == null) {
            return LOW;
        }
        for (SeverityLevel level : values()) {
            if (level.code == code) {
                return level;
            }
        }
        return LOW;
    }
}
