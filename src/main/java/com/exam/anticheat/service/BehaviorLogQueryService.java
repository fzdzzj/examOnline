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

    /**
     * 时间线明细条数上限（change harden-behavior-report-and-monitor）：1000 条。
     * 覆盖正常考试场景任何单人事件量（偶发切屏/失焦/粘贴累计量级为几十），
     * 超出即说明存在脚本刷写——此时只截明细，统计口径仍取自全量窄投影。
     */
    static final int TIMELINE_ITEM_LIMIT = 1000;

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

    /**
     * 单个学生的行为时间线：明细（event_time/id 升序，行数上限 {@value #TIMELINE_ITEM_LIMIT}）
     * + 严重度分布统计（全量，不受明细截断影响）。
     *
     * <p><b>为什么拆两查（change harden-behavior-report-and-monitor）：</b>旧实现一条无上限
     * {@code selectList} 同时产出 total/严重度分布/items，且逐行载 {@code event_data} TEXT——
     * 与上报侧刷写叠加后单人时间线查询膨胀（同文件 {@link #page} 的 size≤200 上限也救不了统计口径）。
     * 但<b>不能直接在原查询上加 LIMIT</b>：total 与三档计数取自同一份行集，截断会让统计口径失真
     * （教师据 low/medium/highCount 判定是否作废答卷）。故：
     * <ol>
     *   <li><b>统计窄投影</b>（{@code selectSeverities}）：仅 severity 列、全量、不载 event_data
     *       → total / lowCount / mediumCount / highCount；</li>
     *   <li><b>明细查询</b>：带 {@code LIMIT}（{@value #TIMELINE_ITEM_LIMIT} 条，event_time/id 升序），
     *       items 如实反映实际返回条数——上限覆盖正常考试场景任何单人事件量。</li>
     * </ol>
     * 权限与可见性校验保持不变（{@link #requireOwnedExam}）。
     */
    public BehaviorTimelineResponse timeline(Long examId, Long studentId) {
        requireOwnedExam(examId);

        // 1) 统计窄投影：全量，只取 severity 列（不载 event_data TEXT）
        List<Integer> severities = behaviorLogMapper.selectSeverities(examId, studentId);
        BehaviorTimelineResponse response = new BehaviorTimelineResponse();
        response.setExamId(examId);
        response.setStudentId(studentId);
        response.setTotal(severities.size());
        for (Integer severity : severities) {
            switch (SeverityLevel.of(severity)) {
                case LOW -> response.setLowCount(response.getLowCount() + 1);
                case MEDIUM -> response.setMediumCount(response.getMediumCount() + 1);
                case HIGH -> response.setHighCount(response.getHighCount() + 1);
            }
        }

        // 2) 明细：带行数上限，event_time/id 升序（覆盖正常场景单人事件量；触发上限时保住最早轨迹）
        List<ExamBehaviorLog> logs = behaviorLogMapper.selectList(
                Wrappers.<ExamBehaviorLog>lambdaQuery()
                        .eq(ExamBehaviorLog::getExamId, examId)
                        .eq(ExamBehaviorLog::getStudentId, studentId)
                        .orderByAsc(ExamBehaviorLog::getEventTime)
                        .orderByAsc(ExamBehaviorLog::getId)
                        .last("LIMIT " + TIMELINE_ITEM_LIMIT));
        response.setItems(logs.stream().map(this::toItem).toList());
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
