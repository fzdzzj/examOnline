package com.exam.grading.support;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.ExamSnapshot;
import com.exam.exam.mapper.ExamSnapshotMapper;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.model.GradingQuestion;
import com.exam.question.entity.QuestionType;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 考试快照判分读取器（spec「客观题判分读考试快照取标准答案」的统一入口）：
 *
 * <p>只读 exam_snapshots.paper_json（含 correctAnswer/score/题序的完整副本），
 * 绝不回读 questions 表——快照是判分事实源，题目被改/软删不影响历史判分（§10.10）。
 * 解析失败抛 INTERNAL_ERROR，由判分服务按"判分失败"隔离该答卷。
 */
@Slf4j
@Component
public class GradingPaperReader {

    private final ExamSnapshotMapper examSnapshotMapper;
    private final ObjectMapper objectMapper;

    public GradingPaperReader(ExamSnapshotMapper examSnapshotMapper, ObjectMapper objectMapper) {
        this.examSnapshotMapper = examSnapshotMapper;
        this.objectMapper = objectMapper;
    }

    /** 按考试加载判分试卷视图（考试快照 exam_id 唯一，发布时生成）。 */
    public GradingPaper readByExamId(Long examId) {
        ExamSnapshot snapshot = examSnapshotMapper.selectOne(
                Wrappers.<ExamSnapshot>lambdaQuery().eq(ExamSnapshot::getExamId, examId));
        if (snapshot == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试快照不存在，无法判分");
        }
        return parse(snapshot.getPaperJson());
    }

    /** 解析快照试卷 JSON → 判分题目列表（字段口径与 PaperSnapshotService.serialize 一致）。 */
    public GradingPaper parse(String paperJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(paperJson);
        } catch (Exception e) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "考试快照解析失败");
        }
        List<GradingQuestion> questions = new ArrayList<>();
        for (JsonNode node : root.get("questions")) {
            QuestionType type = QuestionType.of(node.get("type").asInt());
            if (type == null) {
                throw new BusinessException(ResponseCode.INTERNAL_ERROR,
                        "快照含未知题型: " + node.get("type").asInt());
            }
            List<String> choices = null;
            JsonNode choicesNode = node.get("choices");
            if (choicesNode != null && choicesNode.isArray()) {
                List<String> parsedChoices = new ArrayList<>();
                for (JsonNode choice : choicesNode) {
                    parsedChoices.add(choice.asText());
                }
                choices = parsedChoices;
            }
            questions.add(new GradingQuestion(
                    node.get("number").asInt(),
                    node.get("questionId").asLong(),
                    type,
                    node.get("content").asText(),
                    choices,
                    node.path("correctAnswer").asText(""),
                    node.get("score").decimalValue()));
        }
        return new GradingPaper(
                root.path("paperId").asLong(),
                root.path("title").asText(""),
                root.path("totalScore").decimalValue(),
                questions);
    }

    /**
     * 解析学生答案 JSON（questionId→答案）为 Map；键兼容数字与字符串两种写法。
     * 解析失败抛异常——answers 损坏属于"判分失败"（spec §9.8），由调用方标记隔离，
     * 不静默当 0 分卷处理。
     */
    public Map<Long, String> parseAnswers(String answersJson) {
        if (answersJson == null || answersJson.isBlank()) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "答卷答案尚未落库");
        }
        try {
            JsonNode root = objectMapper.readTree(answersJson);
            if (!root.isObject()) {
                throw new BusinessException(ResponseCode.INTERNAL_ERROR, "答卷答案 JSON 结构非法");
            }
            Map<Long, String> answers = new HashMap<>();
            var fields = root.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                answers.put(Long.valueOf(entry.getKey()),
                        entry.getValue().isNull() ? null : entry.getValue().asText());
            }
            return answers;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "答卷答案解析失败");
        }
    }
}
