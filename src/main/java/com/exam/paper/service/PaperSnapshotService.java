package com.exam.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.service.ExamPaperLockService;
import com.exam.paper.dto.PaperSnapshotResponse;
import com.exam.paper.entity.Paper;
import com.exam.paper.entity.PaperQuestion;
import com.exam.paper.entity.PaperSnapshot;
import com.exam.paper.mapper.PaperMapper;
import com.exam.paper.mapper.PaperQuestionMapper;
import com.exam.paper.mapper.PaperSnapshotMapper;
import com.exam.question.entity.Question;
import com.exam.question.mapper.QuestionMapper;
import com.exam.question.service.QuestionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 试卷快照服务（spec「抽题锁定与试卷快照」需求）：
 * <ul>
 *   <li>生成：抽题/组卷结果确定时，把题目内容+归一化答案+试卷内分值+题号顺序序列化为 JSON 落库，
 *       试卷随之进入"已锁定"，此后读取一律以快照为准（刷新不重新抽题/不换序）；</li>
 *   <li>不可变：快照是副本，之后题目被修改或软删除均不影响历史快照（spec「题目变更不影响快照」场景）；</li>
 *   <li>读取：供后续考试/答题/判分/回看统一消费。</li>
 * </ul>
 */
@Slf4j
@Service
public class PaperSnapshotService {

    private final PaperService paperService;
    private final PaperMapper paperMapper;
    private final PaperQuestionMapper paperQuestionMapper;
    private final PaperSnapshotMapper paperSnapshotMapper;
    private final QuestionMapper questionMapper;
    private final QuestionService questionService;
    private final ExamPaperLockService examPaperLockService;
    private final ObjectMapper objectMapper;

    public PaperSnapshotService(PaperService paperService, PaperMapper paperMapper,
                                PaperQuestionMapper paperQuestionMapper,
                                PaperSnapshotMapper paperSnapshotMapper,
                                QuestionMapper questionMapper, QuestionService questionService,
                                ExamPaperLockService examPaperLockService,
                                ObjectMapper objectMapper) {
        this.paperService = paperService;
        this.paperMapper = paperMapper;
        this.paperQuestionMapper = paperQuestionMapper;
        this.paperSnapshotMapper = paperSnapshotMapper;
        this.questionMapper = questionMapper;
        this.questionService = questionService;
        this.examPaperLockService = examPaperLockService;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成快照并锁定试卷（一卷一快照，不允许重复生成/重抽）：
     * 前置校验——试卷非空、所有题目未被软删、各题分值之和等于申报总分。
     */
    @Transactional
    public PaperSnapshotResponse generate(Long paperId) {
        Paper paper = paperService.getOwnedPaper(paperId);
        if (paper.getStatus() == Paper.STATUS_LOCKED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "试卷已锁定，快照已存在，不允许重复生成");
        }
        // 生成快照属于组卷动作：被进行中考试绑定的试卷同样禁止（§4.1 试卷锁定）
        examPaperLockService.assertPaperEditable(paperId);
        List<PaperQuestion> rows = paperQuestionMapper.selectList(Wrappers.<PaperQuestion>lambdaQuery()
                .eq(PaperQuestion::getPaperId, paperId)
                .orderByAsc(PaperQuestion::getNumber));
        if (rows.isEmpty()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "试卷还没有题目，无法生成快照");
        }

        // 逻辑删除过滤使软删题目从批量查询结果中消失——快照必须是完整副本，先补齐才允许锁定
        Map<Long, Question> questionMap = questionMapper.selectBatchIds(
                        rows.stream().map(PaperQuestion::getQuestionId).toList()).stream()
                .collect(Collectors.toMap(Question::getId, Function.identity()));
        for (PaperQuestion row : rows) {
            if (!questionMap.containsKey(row.getQuestionId())) {
                throw new BusinessException(ResponseCode.BAD_REQUEST,
                        "第 " + row.getNumber() + " 题已被删除，请先将其移出试卷再生成快照");
            }
        }

        // 总分校验（与保存元信息处形成双保险）
        BigDecimal sum = rows.stream().map(PaperQuestion::getScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(paper.getTotalScore()) != 0) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "各题分值之和(" + sum.stripTrailingZeros().toPlainString()
                            + ")与试卷总分(" + paper.getTotalScore() + ")不一致，请调整后再生成快照");
        }

        String snapshotJson = serialize(paper, rows, questionMap);

        PaperSnapshot snapshot = new PaperSnapshot();
        snapshot.setPaperId(paperId);
        snapshot.setPaperJson(snapshotJson);
        snapshot.setQuestionCount(rows.size());
        snapshot.setTotalScore(paper.getTotalScore());
        snapshot.setVersion(1);
        snapshot.setCreatedBy(paper.getCreatedBy());
        paperSnapshotMapper.insert(snapshot);

        // 锁定试卷并回填生效快照 ID：后续组卷编辑与重复生成全部被拒
        paper.setStatus(Paper.STATUS_LOCKED);
        paper.setSnapshotId(snapshot.getId());
        paperMapper.updateById(paper);

        log.info("试卷 {} 生成快照 id={}（{} 题，总分 {}）", paperId, snapshot.getId(), rows.size(), sum);
        return toResponse(snapshot);
    }

    /**
     * 读取当前生效快照（考试/答题/判分/回看的统一入口）：
     * 每次读取都命中同一行记录，内容与首次生成完全一致。
     */
    public PaperSnapshotResponse getCurrent(Long paperId) {
        Paper paper = paperService.getOwnedPaper(paperId);
        if (paper.getSnapshotId() == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "试卷尚未生成快照");
        }
        PaperSnapshot snapshot = paperSnapshotMapper.selectById(paper.getSnapshotId());
        if (snapshot == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "试卷快照不存在");
        }
        return toResponse(snapshot);
    }

    /**
     * 序列化快照：题目内容/选项/归一化答案/试卷内分值/题号顺序的完整副本。
     * 开放给考试快照复用（add-exam-management）：exam_snapshots.paper_json 与本结构保持一致，
     * 保证答题/判分/回看消费方对两种快照的解析口径统一。
     */
    public String serialize(Paper paper, List<PaperQuestion> rows, Map<Long, Question> questionMap) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("paperId", paper.getId());
        root.put("title", paper.getTitle());
        root.put("totalScore", paper.getTotalScore());
        root.put("questionCount", rows.size());
        root.put("generatedTime", LocalDateTime.now().toString());
        ArrayNode questions = root.putArray("questions");
        for (PaperQuestion row : rows) {
            Question question = questionMap.get(row.getQuestionId());
            ObjectNode node = questions.addObject();
            node.put("number", row.getNumber());
            node.put("questionId", question.getId());
            node.put("type", question.getType());
            node.put("content", question.getContent());
            // choices 在题库存 JSON 字符串，解析后嵌入（判断/简答为 null）
            if (question.getChoices() == null) {
                node.putNull("choices");
            } else {
                try {
                    node.set("choices", objectMapper.readTree(question.getChoices()));
                } catch (JsonProcessingException e) {
                    throw new BusinessException(ResponseCode.INTERNAL_ERROR, "快照序列化失败");
                }
            }
            node.put("correctAnswer", question.getCorrectAnswer());
            node.put("score", row.getScore());
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "快照序列化失败");
        }
    }

    private PaperSnapshotResponse toResponse(PaperSnapshot snapshot) {
        try {
            JsonNode content = objectMapper.readTree(snapshot.getPaperJson());
            return new PaperSnapshotResponse(snapshot.getId(), snapshot.getPaperId(),
                    snapshot.getVersion(), snapshot.getTotalScore(), snapshot.getQuestionCount(),
                    content, snapshot.getCreatedTime());
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "快照解析失败");
        }
    }
}
