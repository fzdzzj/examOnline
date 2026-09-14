package com.exam.monitoring.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.OwnershipGuard;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.monitoring.dto.MonitorOverviewResponse;
import com.exam.monitoring.dto.MonitorStudentItem;
import com.exam.submission.dto.AbnormalBehaviorStat;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamBehaviorLogMapper;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.taking.service.ExamDraftService;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 监考大屏聚合服务（spec add-anti-cheat「监考大屏」需求，教师视角只读）：
 *
 * <ul>
 *   <li>实时人数：在线/离线由 Redis 心跳判定（{@link OnlinePresenceService}），
 *       已交卷人数由答卷状态统计——实时性要求高的在线判定不落 DB（proposal 风险应对）；</li>
 *   <li>答题进度：草稿答案字段数 / 题目总数（近似口径——自动保存含未作答空项时略偏高，
 *       监考场景足够；已交卷学生记 100%）；</li>
 *   <li>异常高亮：exam_behavior_logs 按 severity≥2 聚合（一条 GROUP BY 取回全考场），
 *       前端对 abnormal 学生高亮并跳转行为时间线（spec「查看异常详情」场景）。</li>
 * </ul>
 *
 * <p>权限与行为日志查询同款双层控制：接口层 exam:manage（STUDENT 403）+
 * Service 层 owner 校验（教师只看自己的考场，ADMIN 放行）。
 */
@Slf4j
@Service
public class MonitorService {

    /** 异常判定阈值：严重度 ≥ 2（中/高）的行为事件即视为异常高亮 */
    private static final int ABNORMAL_SEVERITY_THRESHOLD = 2;

    private final ExamMapper examMapper;
    private final ExamSubmissionMapper submissionMapper;
    private final ExamBehaviorLogMapper behaviorLogMapper;
    private final UserMapper userMapper;
    private final OnlinePresenceService presenceService;
    private final ExamDraftService draftService;
    private final ObjectMapper objectMapper;

    public MonitorService(ExamMapper examMapper, ExamSubmissionMapper submissionMapper,
                          ExamBehaviorLogMapper behaviorLogMapper, UserMapper userMapper,
                          OnlinePresenceService presenceService, ExamDraftService draftService,
                          ObjectMapper objectMapper) {
        this.examMapper = examMapper;
        this.submissionMapper = submissionMapper;
        this.behaviorLogMapper = behaviorLogMapper;
        this.userMapper = userMapper;
        this.presenceService = presenceService;
        this.draftService = draftService;
        this.objectMapper = objectMapper;
    }

    /** 监考大屏总览：人数统计 + 逐学生进度 + 异常高亮（异常学生排前）。 */
    public MonitorOverviewResponse overview(Long examId) {
        Exam exam = requireOwnedExam(examId);
        List<ExamSubmission> submissions = submissionMapper.selectList(
                Wrappers.<ExamSubmission>lambdaQuery().eq(ExamSubmission::getExamId, examId));

        List<Long> studentIds = submissions.stream().map(ExamSubmission::getStudentId).distinct().toList();
        Map<Long, String> names = resolveNames(studentIds);
        int totalQuestions = resolveTotalQuestions(submissions);

        // 在线判定只针对进行中学生（已交卷不再有心跳，直接归入已交卷态）
        List<Long> activeIds = submissions.stream()
                .filter(s -> s.getStatus() == ExamSubmission.STATUS_IN_PROGRESS)
                .map(ExamSubmission::getStudentId).toList();
        Set<Long> onlineIds = new HashSet<>(presenceService.onlineOf(examId, activeIds));

        // 异常聚合：severity>=2 的行为事件按学生一条 SQL 取回
        Map<Long, AbnormalBehaviorStat> abnormalStats = new HashMap<>();
        for (AbnormalBehaviorStat stat : behaviorLogMapper.selectAbnormalStats(examId, ABNORMAL_SEVERITY_THRESHOLD)) {
            abnormalStats.put(stat.getStudentId(), stat);
        }

        MonitorOverviewResponse response = new MonitorOverviewResponse();
        response.setExamId(examId);
        response.setExamTitle(exam.getTitle());
        response.setExamStatus(exam.getStatus());
        response.setTotalStudents(submissions.size());
        response.setTotalQuestions(totalQuestions);

        List<MonitorStudentItem> students = new ArrayList<>(submissions.size());
        for (ExamSubmission submission : submissions) {
            MonitorStudentItem item = buildStudentItem(submission, totalQuestions, names, onlineIds, abnormalStats);
            students.add(item);

            if (MonitorStudentItem.STATUS_SUBMITTED.equals(item.getStatus())) {
                response.setSubmittedCount(response.getSubmittedCount() + 1);
            } else if (MonitorStudentItem.STATUS_ONLINE.equals(item.getStatus())) {
                response.setOnlineCount(response.getOnlineCount() + 1);
            } else {
                response.setOfflineCount(response.getOfflineCount() + 1);
            }
            if (item.isAbnormal()) {
                response.setAbnormalCount(response.getAbnormalCount() + 1);
            }
        }
        // 大屏排序：异常优先（最高严重度降序 → 异常次数降序），其余按进度降序、学生号升序
        students.sort(Comparator
                .comparing(MonitorStudentItem::isAbnormal).reversed()
                .thenComparing(item -> Objects.requireNonNullElse(item.getMaxSeverity(), 0),
                        Comparator.reverseOrder())
                .thenComparing(MonitorStudentItem::getAbnormalEventCount, Comparator.reverseOrder())
                .thenComparing(MonitorStudentItem::getProgressPercent, Comparator.reverseOrder())
                .thenComparing(MonitorStudentItem::getStudentId));
        response.setStudents(students);
        return response;
    }

    /** 单个答卷 → 学生行：状态（在线/离线/已交卷）+ 进度 + 异常字段。 */
    private MonitorStudentItem buildStudentItem(ExamSubmission submission, int totalQuestions,
                                                Map<Long, String> names, Set<Long> onlineIds,
                                                Map<Long, AbnormalBehaviorStat> abnormalStats) {
        MonitorStudentItem item = new MonitorStudentItem();
        item.setStudentId(submission.getStudentId());
        item.setStudentName(names.get(submission.getStudentId()));
        if (submission.getStatus() == ExamSubmission.STATUS_IN_PROGRESS
                && onlineIds.contains(submission.getStudentId())) {
            item.setStatus(MonitorStudentItem.STATUS_ONLINE);
        } else if (submission.getStatus() == ExamSubmission.STATUS_IN_PROGRESS) {
            item.setStatus(MonitorStudentItem.STATUS_OFFLINE);
        } else {
            item.setStatus(MonitorStudentItem.STATUS_SUBMITTED);
        }

        // 进度：进行中读草稿答案字段数（近似）；已交卷即 100%
        int answered = 0;
        if (submission.getStatus() == ExamSubmission.STATUS_IN_PROGRESS) {
            ExamDraftService.DraftState draft = draftService.get(submission.getExamId(), submission.getStudentId());
            if (draft != null && draft.answers() != null && draft.answers().isObject()) {
                answered = draft.answers().size();
            }
        } else {
            answered = totalQuestions;
        }
        item.setAnsweredCount(answered);
        item.setProgressPercent(totalQuestions > 0
                ? Math.min(100, (int) Math.round(answered * 100.0 / totalQuestions)) : 0);

        AbnormalBehaviorStat stat = abnormalStats.get(submission.getStudentId());
        if (stat != null) {
            item.setAbnormal(true);
            item.setAbnormalEventCount(stat.getEventCount());
            item.setMaxSeverity(stat.getMaxSeverity());
            item.setLastAbnormalTime(stat.getLastEventTime());
        }
        return item;
    }

    /** 批量解析学生姓名（大屏展示友好名；users 已软删/缺失回退 username 或 ID 字符串）。 */
    private Map<Long, String> resolveNames(List<Long> studentIds) {
        Map<Long, String> names = new HashMap<>();
        if (studentIds.isEmpty()) {
            return names;
        }
        for (User user : userMapper.selectBatchIds(studentIds)) {
            names.put(user.getId(), user.getName() != null ? user.getName() : user.getUsername());
        }
        return names;
    }

    /**
     * 题目总数：任取一份个人快照解析 questions 数组长度——同一场考试题数一致，
     * 且快照即学生作答所见（进入时锁定），比试卷表更贴近真实进度分母。
     */
    private int resolveTotalQuestions(List<ExamSubmission> submissions) {
        return submissions.stream()
                .map(ExamSubmission::getPaperJson)
                .filter(json -> json != null && !json.isBlank())
                .findFirst()
                .map(this::countQuestions)
                .orElse(0);
    }

    private int countQuestions(String paperJson) {
        try {
            JsonNode questions = objectMapper.readTree(paperJson).path("questions");
            return questions.isArray() ? questions.size() : 0;
        } catch (Exception e) {
            log.error("个人快照解析失败，题目总数按 0 处理", e);
            return 0;
        }
    }

    /** 考试存在 + 归属校验（与判分/行为日志读侧同一模式，ADMIN 放行）。 */
    private Exam requireOwnedExam(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        OwnershipGuard.assertOwner(exam.getCreatedBy(), SecurityUtil.getCurrentUser(), "考试");
        return exam;
    }
}
