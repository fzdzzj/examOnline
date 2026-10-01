package com.exam.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.dynamic.datasource.annotation.DS;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.common.cache.CacheMutexLoader;
import com.exam.config.CacheConfig;
import com.exam.exam.dto.ExamSnapshotResponse;
import com.exam.exam.entity.Exam;
import com.exam.exam.entity.ExamSnapshot;
import com.exam.exam.mapper.ExamSnapshotMapper;
import com.exam.paper.entity.Paper;
import com.exam.paper.entity.PaperQuestion;
import com.exam.paper.mapper.PaperMapper;
import com.exam.paper.mapper.PaperQuestionMapper;
import com.exam.paper.service.PaperSnapshotService;
import com.exam.question.entity.Question;
import com.exam.question.mapper.QuestionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 考试快照服务（spec「考试快照」需求）：
 *
 * <p>发布是快照生成的唯一时机（exam_id 唯一约束兜底）——发布时把考试配置与
 * 绑定试卷的完整内容（题目/选项/归一化答案/试卷内分值/题号顺序）序列化落库；
 * 之后试卷或题目被修改均不影响快照（副本隔离），答题/判分/回看一律读快照（§10.10）。
 *
 * <p>事务统一显式 rollbackFor=Exception.class（见 data-consistency 规范），防未来受检异常静默不回滚。
 */
@Slf4j
@Service
public class ExamSnapshotService {

    private final ExamSnapshotMapper examSnapshotMapper;
    private final PaperMapper paperMapper;
    private final PaperQuestionMapper paperQuestionMapper;
    private final QuestionMapper questionMapper;
    private final PaperSnapshotService paperSnapshotService;
    private final CacheMutexLoader cacheMutexLoader;
    private final ObjectMapper objectMapper;

    public ExamSnapshotService(ExamSnapshotMapper examSnapshotMapper, PaperMapper paperMapper,
                               PaperQuestionMapper paperQuestionMapper, QuestionMapper questionMapper,
                               PaperSnapshotService paperSnapshotService,
                               CacheMutexLoader cacheMutexLoader, ObjectMapper objectMapper) {
        this.examSnapshotMapper = examSnapshotMapper;
        this.paperMapper = paperMapper;
        this.paperQuestionMapper = paperQuestionMapper;
        this.questionMapper = questionMapper;
        this.paperSnapshotService = paperSnapshotService;
        this.cacheMutexLoader = cacheMutexLoader;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布时生成考试快照（由 ExamService.publish 调用，同一事务内落库）：
     * 校验试卷非空、题目未被软删、各题分值之和等于试卷申报总分后固化。
     *
     * <p>注意：不要求试卷先生成自己的试卷快照——考试快照自带完整试卷内容，
     * 是独立且自洽的副本；试卷侧快照是组卷锁定的手段，两者互不依赖。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExamSnapshot generateForPublish(Exam exam) {
        Paper paper = paperMapper.selectById(exam.getPaperId());
        if (paper == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "绑定的试卷不存在或已删除，无法发布");
        }
        List<PaperQuestion> rows = paperQuestionMapper.selectList(Wrappers.<PaperQuestion>lambdaQuery()
                .eq(PaperQuestion::getPaperId, paper.getId())
                .orderByAsc(PaperQuestion::getNumber));
        if (rows.isEmpty()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "试卷还没有题目，无法发布考试");
        }

        // 逻辑删除过滤使软删题目从批量查询结果中消失——快照必须是完整副本，先补齐才允许发布
        Map<Long, Question> questionMap = questionMapper.selectBatchIds(
                        rows.stream().map(PaperQuestion::getQuestionId).toList()).stream()
                .collect(Collectors.toMap(Question::getId, Function.identity()));
        for (PaperQuestion row : rows) {
            if (!questionMap.containsKey(row.getQuestionId())) {
                throw new BusinessException(ResponseCode.BAD_REQUEST,
                        "第 " + row.getNumber() + " 题已被删除，请先将其移出试卷再发布考试");
            }
        }

        // 总分校验：与试卷申报总分一致，保证快照 total_score 可信
        BigDecimal sum = rows.stream().map(PaperQuestion::getScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(paper.getTotalScore()) != 0) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "各题分值之和(" + sum.stripTrailingZeros().toPlainString()
                            + ")与试卷总分(" + paper.getTotalScore() + ")不一致，请调整后再发布考试");
        }

        ExamSnapshot snapshot = new ExamSnapshot();
        snapshot.setExamId(exam.getId());
        snapshot.setExamJson(serializeExam(exam));
        // 试卷内容复用试卷快照的序列化，保证两代快照 JSON 结构一致
        snapshot.setPaperJson(paperSnapshotService.serialize(paper, rows, questionMap));
        snapshot.setVersion(1);
        snapshot.setCreatedBy(exam.getCreatedBy());
        examSnapshotMapper.insert(snapshot);
        // 快照已生成：清除此前"未发布"探测留下的 404 空标记（防穿透空值缓存），
        // 避免发布后短 TTL 窗口内拉卷被旧空标记误挡为 404
        cacheMutexLoader.clearEmptyMarker(CacheConfig.CACHE_EXAM_SNAPSHOT, exam.getId());
        log.info("考试 {} 发布生成快照 id={}（{} 题）", exam.getId(), snapshot.getId(), rows.size());
        return snapshot;
    }

    /**
     * 读取考试快照（答题/判分/回看的统一入口）：
     * 每次读取都命中同一行记录，内容与发布时完全一致。
     *
     * <p>缓存设计（add-performance-deepening 阶段 8）：
     * 快照发布后<b>只读不更新</b>（试卷/题目再修改均不影响已生成副本）——缓存内容与 DB 天然一致，
     * 可用长 TTL 且无需失效逻辑；key 用 examId（一场考试仅一份快照，与快照行一一对应）。
     * 未命中进入方法体后经 CacheMutexLoader 互斥回源：防击穿（开考 5000 人并发拉卷仅一个线程查 DB），
     * 查无结果（未发布/不存在）写短 TTL 空标记防穿透。
     *
     * <p>读写分离（add-performance-deepening task3）：快照<b>只读不可变</b>，属非强一致读
     * （开考拉卷高并发、可容忍秒级延迟）——{@code @DS("slave")} 走从库卸热读压力；
     * 写后窗口内命中由 ReadYourWriteRouter 临时转主库。
     */
    @DS("slave")
    @Cacheable(cacheNames = CacheConfig.CACHE_EXAM_SNAPSHOT, key = "#examId")
    public ExamSnapshotResponse getCurrent(Long examId) {
        return cacheMutexLoader.load(CacheConfig.CACHE_EXAM_SNAPSHOT, examId, () -> {
            ExamSnapshot snapshot = examSnapshotMapper.selectOne(Wrappers.<ExamSnapshot>lambdaQuery()
                    .eq(ExamSnapshot::getExamId, examId));
            if (snapshot == null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "考试尚未发布或快照不存在");
            }
            return toResponse(snapshot);
        });
    }

    /** 考试配置序列化：时间窗/时长/迟到容忍/防作弊配置的完整副本。 */
    private String serializeExam(Exam exam) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("examId", exam.getId());
        root.put("title", exam.getTitle());
        root.put("paperId", exam.getPaperId());
        if (exam.getCourseId() == null) {
            root.putNull("courseId");
        } else {
            root.put("courseId", exam.getCourseId());
        }
        if (exam.getClassId() == null) {
            root.putNull("classId");
        } else {
            root.put("classId", exam.getClassId());
        }
        root.put("startTime", exam.getStartTime().toString());
        root.put("endTime", exam.getEndTime().toString());
        root.put("durationMinutes", exam.getDurationMinutes());
        root.put("allowLateMinutes", exam.getAllowLateMinutes());
        // 防作弊配置原样嵌入（JSON 字符串解析回树，脏数据置 null 不阻断发布）
        JsonNode antiCheat = parseOrNull(exam.getAntiCheatConfig());
        if (antiCheat == null) {
            root.putNull("antiCheatConfig");
        } else {
            root.set("antiCheatConfig", antiCheat);
        }
        root.put("generatedTime", LocalDateTime.now().toString());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "考试快照序列化失败");
        }
    }

    private JsonNode parseOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private ExamSnapshotResponse toResponse(ExamSnapshot snapshot) {
        try {
            return new ExamSnapshotResponse(snapshot.getId(), snapshot.getExamId(), snapshot.getVersion(),
                    objectMapper.readTree(snapshot.getExamJson()),
                    objectMapper.readTree(snapshot.getPaperJson()),
                    snapshot.getCreatedTime());
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "考试快照解析失败");
        }
    }
}
