package com.exam.anticheat.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 行为事件上下文：一次待采集事件的全部输入，策略（{@code BehaviorEventCollector}）
 * 据此判定严重度与警告标记。
 *
 * <p>有意保持最小字段集（考试/学生/明细/时间）：策略判定所需的额外数据
 * （如切屏历史计数）由策略自己经计数器获取，上下文不随事件类型膨胀——
 * 这样新增事件类型永远不会改动上下文结构。
 *
 * @param examId       考试 ID
 * @param studentId    学生 ID
 * @param eventData    客户端上报的事件明细 JSON（离开时长/客户端时间等，可为 null）
 * @param occurredTime 事件发生时间（客户端上报或服务端记录）
 */
public record BehaviorEventContext(Long examId,
                                   Long studentId,
                                   JsonNode eventData,
                                   LocalDateTime occurredTime) {
}
