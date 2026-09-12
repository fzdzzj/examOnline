package com.exam.taking.service;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.question.entity.QuestionType;
import com.exam.taking.dto.QuestionView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 个人试卷快照服务（spec「进入考试」：随机抽题/选项乱序进入时锁定，§3.4）：
 *
 * <p>学生首次进入考试时，以考试快照的 paperJson 为底本生成"个人快照"——
 * 题目顺序洗牌、客观题选项洗牌并重映射答案字母，随后随答卷行落库：
 * 刷新/断线重进一律读该行快照，保证"不换题、不换序"（spec「刷新不换题」场景）。
 *
 * <p>关键正确性约束：选项乱序后，correctAnswer 的选项字母必须按新顺序重映射，
 * 否则阶段 6 判分会以乱序后的字母对上原始答案造成错判——本服务在洗牌选项时
 * 同步把原答案字母换算为新顺序字母（多选保持升序逗号列表的归一化格式）。
 */
@Slf4j
@Service
public class PersonalPaperService {

    private final ObjectMapper objectMapper;

    public PersonalPaperService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 生成个人快照：题序洗牌 + 客观题选项洗牌（答案字母重映射）+ 题号重排。
     * 直接在新建的 JSON 树上变换并序列化，不改动考试快照本身。
     */
    public String personalize(JsonNode examPaper) {
        JsonNode root;
        try {
            root = objectMapper.readTree(examPaper.toString());
        } catch (Exception e) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "考试快照解析失败");
        }

        ArrayNode questions = (ArrayNode) root.get("questions");
        List<JsonNode> shuffled = new ArrayList<>();
        questions.forEach(shuffled::add);
        Collections.shuffle(shuffled);

        // 题号按新顺序重排为 1..n（questionId 不变，仍是作答与判分的真实主键）
        questions.removeAll();
        int number = 1;
        for (JsonNode question : shuffled) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) question).put("number", number++);
            shuffleChoicesAndRemapAnswer(question);
            questions.add(question);
        }
        return root.toString();
    }

    /**
     * 提取学生可见题目视图：剔除 correctAnswer（学生端不可见答案），
     * 其余字段与个人快照完全一致。
     */
    public List<QuestionView> toView(String personalPaperJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(personalPaperJson);
        } catch (Exception e) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "个人快照解析失败");
        }
        List<QuestionView> views = new ArrayList<>();
        for (JsonNode question : root.get("questions")) {
            views.add(new QuestionView(
                    question.get("number").asInt(),
                    question.get("questionId").asLong(),
                    question.get("type").asInt(),
                    question.get("content").asText(),
                    question.get("choices"),
                    question.get("score").decimalValue()));
        }
        return views;
    }

    /**
     * 客观题（单选/多选）选项洗牌 + 答案字母重映射；判断/简答（无选项）原样保留。
     * 归一化答案格式见 AnswerNormalizer：单选为单个字母，多选为升序逗号字母列表。
     */
    private void shuffleChoicesAndRemapAnswer(JsonNode question) {
        int type = question.get("type").asInt();
        JsonNode choices = question.get("choices");
        if (type != QuestionType.SINGLE.getCode() && type != QuestionType.MULTIPLE.getCode()) {
            return;
        }
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return;
        }

        // 原正确答案字母 → 原选项内容（内容作为洗牌后的定位依据）
        String correctAnswer = question.path("correctAnswer").asText("");
        List<Integer> originalCorrectIndexes = new ArrayList<>();
        for (String letter : correctAnswer.split(",")) {
            letter = letter.trim();
            if (letter.length() == 1) {
                int index = letter.charAt(0) - 'A';
                if (index >= 0 && index < choices.size()) {
                    originalCorrectIndexes.add(index);
                }
            }
        }

        List<JsonNode> shuffledChoices = new ArrayList<>();
        choices.forEach(shuffledChoices::add);
        Collections.shuffle(shuffledChoices);

        // 重映射：原正确选项内容在新顺序中的位置即新答案字母；重复选项取首个（判分不受影响）
        ArrayNode newChoices = objectMapper.createArrayNode();
        shuffledChoices.forEach(newChoices::add);
        List<String> newLetters = new ArrayList<>();
        for (int originalIndex : originalCorrectIndexes) {
            String content = choices.get(originalIndex).asText();
            for (int i = 0; i < shuffledChoices.size(); i++) {
                if (shuffledChoices.get(i).asText().equals(content)) {
                    newLetters.add(String.valueOf((char) ('A' + i)));
                    break;
                }
            }
        }
        Collections.sort(newLetters);

        ((com.fasterxml.jackson.databind.node.ObjectNode) question).set("choices", newChoices);
        if (!newLetters.isEmpty()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) question)
                    .put("correctAnswer", String.join(",", newLetters));
        }
        log.debug("个人快照选项乱序: questionId={} 原答案={} 新答案={}",
                question.get("questionId").asLong(), correctAnswer, question.path("correctAnswer").asText());
    }
}
