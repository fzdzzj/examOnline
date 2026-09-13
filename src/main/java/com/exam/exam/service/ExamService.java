package com.exam.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.OwnershipGuard;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.CacheConfig;
import com.exam.exam.dto.ExamCreateRequest;
import com.exam.exam.dto.ExamDetailResponse;
import com.exam.exam.dto.ExamUpdateRequest;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.paper.entity.Paper;
import com.exam.paper.mapper.PaperMapper;
import com.exam.paper.service.PaperService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 考试服务：创建（绑定试卷/班级/时间窗/时长）、分页、详情、修改、删除。
 *
 * <p>关键约束：
 * <ul>
 *   <li>创建/换绑试卷必须存在且属于当前教师（ADMIN 放行），复用 assertTeacherOwnsPaper 模式；</li>
 *   <li>时间窗校验 end_time &gt; start_time、时长校验 duration_minutes &gt; 0（spec「时间窗非法/时长非法」场景）；</li>
 *   <li>修改/删除仅限"未发布且未开始"：已发布考试学生已可见，须走撤回流程（后续阶段提供）；</li>
 *   <li>状态流转一律经 {@link ExamStateMachineService} 的乐观锁 CAS；发布与考试快照见 ExamSnapshotService。</li>
 * </ul>
 *
 * <p>事务统一显式 rollbackFor=Exception.class（见 data-consistency 规范），防未来受检异常静默不回滚。
 */
@Slf4j
@Service
public class ExamService {

    private final ExamMapper examMapper;
    private final PaperService paperService;
    private final PaperMapper paperMapper;
    private final ExamStateMachineService stateMachineService;
    private final ExamSnapshotService snapshotService;
    private final ObjectMapper objectMapper;

    public ExamService(ExamMapper examMapper, PaperService paperService,
                       PaperMapper paperMapper, ExamStateMachineService stateMachineService,
                       ExamSnapshotService snapshotService, ObjectMapper objectMapper) {
        this.examMapper = examMapper;
        this.paperService = paperService;
        this.paperMapper = paperMapper;
        this.stateMachineService = stateMachineService;
        this.snapshotService = snapshotService;
        this.objectMapper = objectMapper;
    }

    /** 创建考试：初始状态未开始、未发布，等待教师发布与定时开考。 */
    @Transactional(rollbackFor = Exception.class)
    public Exam create(ExamCreateRequest request) {
        LoginUser operator = requireLogin();
        // 绑定试卷须存在且属于当前教师（教师不能拿别人的卷子开考；ADMIN 越级放行）
        Paper paper = paperService.getOwnedPaper(request.getPaperId());
        validateWindowAndDuration(request.getStartTime(), request.getEndTime(),
                request.getDurationMinutes(), request.getAllowLateMinutes());

        Exam exam = new Exam();
        exam.setTitle(request.getTitle().trim());
        exam.setDescription(request.getDescription() == null ? "" : request.getDescription());
        exam.setPaperId(paper.getId());
        exam.setCourseId(request.getCourseId());
        exam.setClassId(request.getClassId());
        exam.setStartTime(request.getStartTime());
        exam.setEndTime(request.getEndTime());
        exam.setDurationMinutes(request.getDurationMinutes());
        exam.setAllowLateMinutes(request.getAllowLateMinutes() == null ? 0 : request.getAllowLateMinutes());
        exam.setStatus(Exam.STATUS_NOT_STARTED);
        exam.setPublished(0);
        exam.setForceEnd(0);
        exam.setAntiCheatConfig(toJsonString(request.getAntiCheatConfig()));
        exam.setVersion(0);
        exam.setCreatedBy(operator.getId());
        examMapper.insert(exam);
        log.info("教师 {} 创建考试 id={} paperId={} 窗口 {} ~ {} 时长 {} 分钟",
                operator.getId(), exam.getId(), paper.getId(),
                exam.getStartTime(), exam.getEndTime(), exam.getDurationMinutes());
        return exam;
    }

    /** 考试分页：教师仅见自己的考试，ADMIN 可见全部。 */
    public Page<Exam> page(long page, long size) {
        LoginUser operator = requireLogin();
        return examMapper.selectPage(new Page<>(page, Math.min(size, 100)),
                Wrappers.<Exam>lambdaQuery()
                        .eq(operator.getRoleLevel() < RoleHierarchy.levelOf(RoleHierarchy.ADMIN),
                                Exam::getCreatedBy, operator.getId())
                        .orderByDesc(Exam::getId));
    }

    /**
     * 考试详情（含绑定试卷标题与防作弊配置）。
     *
     * <p>缓存设计（add-performance-deepening 阶段 8）：Exam 有更新路径（update/publish/forceEnd），
     * 用<b>短 TTL</b> 兜底（缓存层到期自愈）+ 写路径 @CacheEvict 显式失效，与只读快照缓存区分。
     * key = id + 请求者 ID（{@link #detailCacheKey}）：归属校验（getOwnedExam）在方法体内，
     * 缓存命中时被切面跳过，key 必须带上请求者，防止命中他人缓存绕过水平越权校验。
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_EXAM_DETAIL,
            key = "T(com.exam.exam.service.ExamService).detailCacheKey(#id)")
    public ExamDetailResponse detail(Long id) {
        Exam exam = getOwnedExam(id);
        return toDetail(exam);
    }

    /**
     * 考试详情缓存 key（@Cacheable SpEL 引用，保证 key 口径单点维护）：
     * id + ':' + 请求者 ID——归属校验在方法体内、缓存命中会跳过方法体，
     * 不带请求者会让教师 A 命中教师 B 的缓存、绕过水平越权校验。
     */
    public static String detailCacheKey(Long id) {
        return id + ":" + SecurityUtil.getUserId();
    }

    /**
     * 更新考试（部分更新）：仅"未发布且未开始"允许修改——
     * 已发布考试学生已可见、快照已生成，改配置会与快照不一致，须先撤回（后续阶段提供）。
     * null 字段保留原值，时间窗/时长按合并后的最终值整体校验。
     *
     * <p>写路径显式失效 examDetail 缓存：缓存 key 带请求者（每人各一份），这里 allEntries 清全缓存，
     * 保证其他用户（如 ADMIN）已缓存的详情不被旧值污染——考试写少读多，清全量代价可忽略。
     */
    @CacheEvict(cacheNames = CacheConfig.CACHE_EXAM_DETAIL, allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public ExamDetailResponse update(Long id, ExamUpdateRequest request) {
        Exam exam = getOwnedExam(id);
        assertEditable(exam);

        if (request.getTitle() != null) {
            exam.setTitle(request.getTitle().trim());
        }
        if (request.getDescription() != null) {
            exam.setDescription(request.getDescription());
        }
        // 换绑试卷：新试卷同样要求存在且归属当前教师
        if (request.getPaperId() != null && !request.getPaperId().equals(exam.getPaperId())) {
            Paper paper = paperService.getOwnedPaper(request.getPaperId());
            exam.setPaperId(paper.getId());
        }
        if (request.getCourseId() != null) {
            exam.setCourseId(request.getCourseId());
        }
        if (request.getClassId() != null) {
            exam.setClassId(request.getClassId());
        }
        LocalDateTime start = request.getStartTime() != null ? request.getStartTime() : exam.getStartTime();
        LocalDateTime end = request.getEndTime() != null ? request.getEndTime() : exam.getEndTime();
        Integer duration = request.getDurationMinutes() != null ? request.getDurationMinutes() : exam.getDurationMinutes();
        Integer allowLate = request.getAllowLateMinutes() != null ? request.getAllowLateMinutes() : exam.getAllowLateMinutes();
        validateWindowAndDuration(start, end, duration, allowLate);
        exam.setStartTime(start);
        exam.setEndTime(end);
        exam.setDurationMinutes(duration);
        exam.setAllowLateMinutes(allowLate);
        if (request.getAntiCheatConfig() != null) {
            exam.setAntiCheatConfig(toJsonString(request.getAntiCheatConfig()));
        }
        examMapper.updateById(exam);
        log.info("考试更新: id={}", id);
        return toDetail(exam);
    }

    /**
     * 删除考试（软删，§7.7）：仅"未发布且未开始"可删——
     * 已发布考试学生已可见须先撤回；进行中/已结束考试承载历史答卷，不可删。
     */
    @CacheEvict(cacheNames = CacheConfig.CACHE_EXAM_DETAIL, allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Exam exam = getOwnedExam(id);
        assertEditable(exam);
        examMapper.deleteById(id);
        log.info("考试删除: id={}", id);
    }

    /**
     * 发布考试（spec「考试发布」需求）：published=1 学生可见，状态仍为未开始——
     * 到达 start_time 由定时任务自动开考（定时发布场景）。
     * 发布是考试快照生成的唯一时机（§10.10）：同一事务内先校验并生成快照，再回填 snapshotId；
     * 试卷题目为空/被删/总分不一致会在发布时被拒绝，快照因此始终完整可信。
     * （快照侧的 404 空标记清除在 generateForPublish 内完成；末尾 detail(id) 为自调用不经缓存，恒为新值）
     */
    @CacheEvict(cacheNames = CacheConfig.CACHE_EXAM_DETAIL, allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public ExamDetailResponse publish(Long id) {
        Exam exam = getOwnedExam(id);
        if (exam.getPublished() != null && exam.getPublished() == 1) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "考试已发布，不允许重复发布");
        }
        if (exam.getStatus() != Exam.STATUS_NOT_STARTED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "考试已开始或结束，不允许发布");
        }
        Long snapshotId = snapshotService.generateForPublish(exam).getId();
        exam.setPublished(1);
        exam.setSnapshotId(snapshotId);
        examMapper.updateById(exam);
        log.info("考试 {} 发布（学生可见），等待定时开考", id);
        return detail(id);
    }

    /**
     * 教师提前结束（spec「教师提前结束」需求，§1.5）：进行中 → 已结束，
     * 并置位 force_end 标记——阶段 5 交卷链路据此对未交卷学生按最后自动保存强制交卷。
     * 状态迁移经乐观锁 CAS：并发重复提前结束仅一次成功，另一次收到 409 状态冲突。
     */
    @CacheEvict(cacheNames = CacheConfig.CACHE_EXAM_DETAIL, allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public ExamDetailResponse forceEnd(Long id) {
        Exam exam = getOwnedExam(id);
        if (exam.getStatus() != Exam.STATUS_IN_PROGRESS) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "仅进行中的考试允许提前结束");
        }
        stateMachineService.casTransition(id, Exam.STATUS_IN_PROGRESS, Exam.STATUS_ENDED);
        // CAS SQL 只负责状态与版本；force_end 标记单独置位，避免状态迁移 SQL 被附加语义
        examMapper.update(null, Wrappers.<Exam>lambdaUpdate()
                .eq(Exam::getId, id)
                .set(Exam::getForceEnd, 1));
        log.info("考试 {} 教师提前结束（force_end=1），强制交卷由阶段 5 交卷链路处理", id);
        return detail(id);
    }

    /**
     * 加载考试并做水平越权校验（assertTeacherOwns 模式）：
     * 不存在/已软删返回 404；非归属教师返回 403，ADMIN 放行。
     * 供本服务与状态机/快照服务共用。
     */
    public Exam getOwnedExam(Long id) {
        Exam exam = examMapper.selectById(id);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        OwnershipGuard.assertOwner(exam.getCreatedBy(), SecurityUtil.getCurrentUser(), "考试");
        return exam;
    }

    /** 修改/删除的准入门槛：未发布且未开始。 */
    private void assertEditable(Exam exam) {
        if (exam.getPublished() != null && exam.getPublished() == 1) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "考试已发布，不允许修改或删除（须先撤回）");
        }
        if (exam.getStatus() == null || exam.getStatus() != Exam.STATUS_NOT_STARTED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "仅未开始的考试允许修改或删除");
        }
    }

    /**
     * 时间窗与时长合法性校验（spec「时间窗非法/时长非法」场景）：
     * 结束时间必须晚于开始时间（相等也拒绝）、个人时长必须大于 0、迟到容忍不能为负。
     */
    private void validateWindowAndDuration(LocalDateTime start, LocalDateTime end,
                                           Integer durationMinutes, Integer allowLateMinutes) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "时间窗非法：结束时间必须晚于开始时间");
        }
        if (durationMinutes == null || durationMinutes <= 0) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "时长非法：个人时长必须大于 0 分钟");
        }
        if (allowLateMinutes != null && allowLateMinutes < 0) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "迟到容忍分钟数不能为负");
        }
    }

    private ExamDetailResponse toDetail(Exam exam) {
        Paper paper = paperMapper.selectById(exam.getPaperId());
        return new ExamDetailResponse(exam.getId(), exam.getTitle(), exam.getDescription(),
                exam.getPaperId(), paper == null ? null : paper.getTitle(),
                exam.getCourseId(), exam.getClassId(),
                exam.getStartTime(), exam.getEndTime(), exam.getDurationMinutes(), exam.getAllowLateMinutes(),
                exam.getStatus(), exam.getPublished(), exam.getForceEnd(),
                parseConfig(exam.getAntiCheatConfig()), exam.getSnapshotId(),
                exam.getCreatedBy(), exam.getCreatedTime(), exam.getUpdatedTime());
    }

    private String toJsonString(JsonNode config) {
        return config == null ? null : config.toString();
    }

    private JsonNode parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            // 配置入库前已序列化校验，理论上不可达；脏数据时按未配置处理而非 500
            return null;
        }
    }

    private LoginUser requireLogin() {
        LoginUser operator = SecurityUtil.getCurrentUser();
        if (operator == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        return operator;
    }
}
