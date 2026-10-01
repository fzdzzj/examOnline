package com.exam.taking.dto;

import com.exam.anticheat.model.EventVerdict;

/**
 * 行为上报响应（spec「切屏警告不交卷」场景——前端据此弹警告提醒）：
 *
 * <p>响应即"警告提示"的载体：warned=true 时前端弹提醒弹窗；message 面向学生
 * 文案化说明"已记录、不强制交卷"。severity/count 同时供前端做提醒分级展示。
 * 注意：本响应永远不包含"强制交卷"类指令——切屏处置权在教师，不在采集体系。
 *
 * @param warned       是否弹警告（切屏事件 true）
 * @param severity     服务端策略判定的严重度存储值（1低 2中 3高）
 * @param severityName 严重度语义名（低/中/高）
 * @param count        该学生本事件累计次数（切屏策略提供；无计数策略的事件为 null）
 * @param message      警告文案（不弹警告时为 null）
 */
public record BehaviorReportResponse(boolean warned,
                                     int severity,
                                     String severityName,
                                     Integer count,
                                     String message) {

    /** 由策略判定结果组装响应；警告文案在此统一维护（学生可见文案不散落策略内）。 */
    public static BehaviorReportResponse from(String eventType, EventVerdict verdict) {
        Integer count = null;
        if (verdict.extraData() != null && verdict.extraData().hasNonNull("count")) {
            count = verdict.extraData().get("count").asInt();
        }
        String message = verdict.warn()
                ? "检测到切屏行为，已记录（第 " + count + " 次）。请注意考试纪律；切屏不会强制交卷，请尽快返回答题。"
                : null;
        return new BehaviorReportResponse(verdict.warn(), verdict.severity().code(),
                verdict.severity().label(), count, message);
    }
}
