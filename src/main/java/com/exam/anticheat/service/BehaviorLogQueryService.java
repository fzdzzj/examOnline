package com.exam.anticheat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.anticheat.dto.BehaviorLogItem;
import com.exam.anticheat.dto.BehaviorLogPageResponse;
import com.exam.anticheat.dto.BehaviorTimelineResponse;
import com.exam.anticheat.model.SeverityLevel;
import com.exam.auth.security.OwnershipGuard;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.submission.entity.ExamBehaviorLog;
import com.exam.submission.mapper.ExamBehaviorLogMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 行为日志查询服务（spec add-anti-cheat「行为日志时间线」需求，教师视角只读）：
 *
 * <ul>
 *   <li>分页筛选：按考试 + 可选 学生/事件类型/严重度 组合过滤，event_time 升序；</li>
 *   <li>学生时间线：单个学生的完整行为轨迹 + 低/中/高严重度统计，
 *       供教师判定"是否作废答卷"（决策记录 §3.1：处置权在教师）。</li>
 * </ul>
 *
 * <p>权限双层控制（spec「学生不可见」场景）：
 * <ol>
 *   <li>垂直——接口层 {@code @RequirePermission("exam:manage")}（STUDENT 无此权限点 → 403）；</li>
 *   <li>水平——本服务每次查询都经 {@link OwnershipGuard#assertOwner} 校验考试归属
 *       （教师只能看自己创建的考试，ADMIN 放行）。</li>
 * </ol>
 */
@Slf4j
@Service
public class BehaviorLogQueryService {

    private final ExamBehaviorLogMapper behaviorLogMapper;
    private final ExamMapper examMapper;
    private final ObjectMapper objectMapper;

    public BehaviorLogQueryService(ExamBehaviorLogMapper behaviorLogMapper, ExamMapper examMapper,
                                   ObjectMapper objectMapper) {
        this.behaviorLogMapper = behaviorLogMapper;
        this.examMapper = examMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 分页查询行为日志（时间线口径：event_time 升序）。
     *
     * @param examId    考试 ID（必填，查询范围以考试为界）
     * @param studentId 学生 ID（可空=全体学生）
     * @param eventType 事件类型（可空=全部类型）
     * @param severity  严重度存储值 1/2/3（可空=全部级别）
     */
    public BehaviorLogPageResponse page(Long examId, Long studentId, String eventType,
                                        Integer severity, long page, long size) {
        requireOwnedExam(examId);

        Page<ExamBehaviorLog> result = behaviorLogMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200)),
                Wrappers.<ExamBehaviorLog>lambdaQuery()
                        .eq(ExamBehaviorLog::getExamId, examId)
                        .eq(studentId != null, ExamBehaviorLog::getStudentId, studentId)
                        .eq(eventType != null && !eventType.isBlank(), ExamBehaviorLog::getEventType, eventType)
                        .eq(severity != null, ExamBehaviorLog::getSeverity, severity)
                        .orderByAsc(ExamBehaviorLog::getEventTime)
                        .orderByAsc(ExamBehaviorLog::getId));

        BehaviorLogPageResponse response = new BehaviorLogPageResponse();
        response.setTotal(result.getTotal());
        response.setPage(result.getCurrent());
        response.setSize(result.getSize());
        response.setItems(result.getRecords().stream().map(this::toItem).toList());
        return response;
    }

    /** 单个学生的行为时间线：完整轨迹（event_time 升序）+ 严重度分布统计。 */
    public BehaviorTimelineResponse timeline(Long examId, Long studentId) {
        requireOwnedExam(examId);

        List<ExamBehaviorLog> logs = behaviorLogMapper.selectList(
                Wrappers.<ExamBehaviorLog>lambdaQuery()
                        .eq(ExamBehaviorLog::getExamId, examId)
                        .eq(ExamBehaviorLog::getStudentId, studentId)
                        .orderByAsc(ExamBehaviorLog::getEventTime)
                        .orderByAsc(ExamBehaviorLog::getId));

        BehaviorTimelineResponse response = new BehaviorTimelineResponse();
        response.setExamId(examId);
        response.setStudentId(studentId);
        response.setTotal(logs.size());
        response.setItems(logs.stream().map(this::toItem).toList());
        for (ExamBehaviorLog row : logs) {
            SeverityLevel level = SeverityLevel.of(row.getSeverity());
            switch (level) {
                case LOW -> response.setLowCount(response.getLowCount() + 1);
                case MEDIUM -> response.setMediumCount(response.getMediumCount() + 1);
                case HIGH -> response.setHighCount(response.getHighCount() + 1);
            }
        }
        return response;
    }

    /** 考试存在 + 归属校验（与判分读侧 requireOwnedExam 同一模式，ADMIN 放行）。 */
    private void requireOwnedExam(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        OwnershipGuard.assertOwner(exam.getCreatedBy(), SecurityUtil.getCurrentUser(), "考试");
    }

    /** 实体 → 展示条目：eventData 反序列化为 JSON（失败保留原文），严重度补语义名。 */
    private BehaviorLogItem toItem(ExamBehaviorLog row) {
        BehaviorLogItem item = new BehaviorLogItem();
        item.setId(row.getId());
        item.setExamId(row.getExamId());
        item.setStudentId(row.getStudentId());
        item.setEventType(row.getEventType());
        item.setSeverity(row.getSeverity());
        item.setSeverityName(SeverityLevel.of(row.getSeverity()).label());
        item.setEventData(toJson(row.getEventData()));
        item.setEventTime(row.getEventTime());
        item.setCreatedTime(row.getCreatedTime());
        return item;
    }

    /** TEXT 列 → JSON 节点；历史数据/非法 JSON 保留原文（展示优先于严格校验）。 */
    private JsonNode toJson(String eventData) {
        if (eventData == null || eventData.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(eventData);
        } catch (Exception e) {
            return TextNode.valueOf(eventData);
        }
    }
}
