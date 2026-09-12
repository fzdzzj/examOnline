package com.exam.taking.service;

import com.exam.submission.entity.ExamBehaviorLog;
import com.exam.submission.mapper.ExamBehaviorLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 行为日志服务：切屏/失焦/草稿冲突等事件统一落 exam_behavior_logs。
 *
 * <p>只记录不处置（spec「切屏仅记录」场景：检测到切屏不强制交卷），
 * 事件分析的完整防作弊能力在阶段 7 承接。
 */
@Slf4j
@Service
public class ExamBehaviorLogService {

    private final ExamBehaviorLogMapper behaviorLogMapper;
    private final ObjectMapper objectMapper;

    public ExamBehaviorLogService(ExamBehaviorLogMapper behaviorLogMapper, ObjectMapper objectMapper) {
        this.behaviorLogMapper = behaviorLogMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录一条行为事件：eventData 为结构化 JSON（可空），severity 缺省 1（提示级）。
     * 落库失败只记日志不上抛——行为采集是旁路，不能影响答题主链路。
     */
    public void record(Long examId, Long studentId, String eventType,
                       com.fasterxml.jackson.databind.JsonNode eventData, Integer severity,
                       LocalDateTime eventTime) {
        try {
            ExamBehaviorLog row = new ExamBehaviorLog();
            row.setExamId(examId);
            row.setStudentId(studentId);
            row.setEventType(eventType);
            row.setEventData(eventData == null ? null : eventData.toString());
            row.setSeverity(severity == null ? 1 : severity);
            row.setEventTime(eventTime == null ? LocalDateTime.now() : eventTime);
            behaviorLogMapper.insert(row);
            log.info("行为事件: exam={} student={} type={} severity={}",
                    examId, studentId, eventType, row.getSeverity());
        } catch (Exception e) {
            log.error("行为事件落库失败（旁路不阻断答题）: exam={} student={} type={}",
                    examId, studentId, eventType, e);
        }
    }

    /** 便捷重载：事件明细为原始 JSON 字符串。 */
    public void record(Long examId, Long studentId, String eventType,
                       String eventDataJson, Integer severity, LocalDateTime eventTime) {
        com.fasterxml.jackson.databind.JsonNode node = null;
        if (eventDataJson != null) {
            try {
                node = objectMapper.readTree(eventDataJson);
            } catch (Exception ignored) {
                // 明细非法时按无明细记录，不影响事件主信息
            }
        }
        record(examId, studentId, eventType, node, severity, eventTime);
    }
}
