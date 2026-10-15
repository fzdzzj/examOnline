package com.exam.question.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.OwnershipGuard;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.service.ExamPaperLockService;
import com.exam.question.dto.BatchDeleteQuestionsResponse;
import com.exam.question.dto.BatchTagsRequest;
import com.exam.question.dto.BatchTagsResponse;
import com.exam.question.dto.QuestionCreateRequest;
import com.exam.question.entity.Question;
import com.exam.question.entity.QuestionTag;
import com.exam.question.entity.QuestionType;
import com.exam.question.entity.Tag;
import com.exam.question.mapper.QuestionMapper;
import com.exam.question.mapper.QuestionTagMapper;
import com.exam.question.mapper.TagMapper;
import com.exam.question.support.AnswerNormalizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 题库服务：四类题目的 CRUD 与软删除、答案归一化、标签关联、分页筛选。
 *
 * <p>数据隔离：写操作前经 {@link #getOwnedQuestion} 做 owner 校验
 * （复用阶段 2 的 {@link OwnershipGuard}，即 assertTeacherOwnsQuestion 模式）——
 * 教师只能操作自己的题目，ADMIN 越级放行；分页查询教师仅见个人题库。
 *
 * <p>事务统一显式 rollbackFor=Exception.class（见 data-consistency 规范），防未来受检异常静默不回滚。
 */
@Slf4j
@Service
public class QuestionService {

    private final QuestionMapper questionMapper;
    private final QuestionTagMapper questionTagMapper;
    private final TagMapper tagMapper;
    private final ExamPaperLockService examPaperLockService;
    private final ObjectMapper objectMapper;

    public QuestionService(QuestionMapper questionMapper, QuestionTagMapper questionTagMapper,
                           TagMapper tagMapper, ExamPaperLockService examPaperLockService,
                           ObjectMapper objectMapper) {
        this.questionMapper = questionMapper;
        this.questionTagMapper = questionTagMapper;
        this.tagMapper = tagMapper;
        this.examPaperLockService = examPaperLockService;
        this.objectMapper = objectMapper;
    }

    /** 创建题目：答案先归一化再入库，选项序列化为 JSON 数组。 */
    @Transactional(rollbackFor = Exception.class)
    public Question create(QuestionCreateRequest request) {
        LoginUser operator = requireLogin();
        QuestionType type = parseType(request.getType());
        List<String> choices = validateChoices(type, request.getChoices());

        Question question = new Question();
        question.setType(type.getCode());
        question.setContent(request.getContent().trim());
        question.setImageUrl(imageUrlOf(request.getImageUrl()));
        question.setChoices(toJson(choices));
        question.setCorrectAnswer(AnswerNormalizer.normalize(type, request.getCorrectAnswer(), choices.size()));
        question.setScore(request.getScore());
        question.setDifficulty(request.getDifficulty());
        question.setAnalysis(request.getAnalysis());
        question.setCreatedBy(operator.getId());
        questionMapper.insert(question);

        replaceTags(question.getId(), request.getTagIds());
        log.info("教师 {} 创建题目 id={} type={}", operator.getId(), question.getId(), type);
        return question;
    }

    /**
     * 更新题目（全量覆盖）：owner 校验后重走归一化，保证存储口径不因编辑破坏。
     * tagIds 为 null 表示保留原标签关联。
     */
    @Transactional(rollbackFor = Exception.class)
    public Question update(Long id, QuestionCreateRequest request) {
        Question question = getOwnedQuestion(id);
        // 考试进行中锁定（§4.1）：被进行中考试试卷引用的题目现场不可改，走错题补偿流程
        examPaperLockService.assertQuestionEditable(id);
        QuestionType type = parseType(request.getType());
        List<String> choices = validateChoices(type, request.getChoices());

        question.setType(type.getCode());
        question.setContent(request.getContent().trim());
        question.setImageUrl(imageUrlOf(request.getImageUrl()));
        question.setChoices(toJson(choices));
        question.setCorrectAnswer(AnswerNormalizer.normalize(type, request.getCorrectAnswer(), choices.size()));
        question.setScore(request.getScore());
        question.setDifficulty(request.getDifficulty());
        question.setAnalysis(request.getAnalysis());
        questionMapper.updateById(question);

        if (request.getTagIds() != null) {
            replaceTags(id, request.getTagIds());
        }
        log.info("题目更新: id={}", id);
        return question;
    }

    /**
     * 软删除（spec「软删除题目」场景）：仅标记 is_deleted——
     * 已被引用的题目不影响历史试卷/快照，且不再参与后续组卷与抽题
     * （逻辑删除使 selectById/selectBatchIds 自动过滤）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void softDelete(Long id) {
        Question question = getOwnedQuestion(id);
        // 考试进行中锁定（§4.1）：被进行中考试试卷引用的题目不可软删（快照副本虽隔离，引用完整性仍须保证）
        examPaperLockService.assertQuestionEditable(id);
        questionMapper.deleteById(question.getId());
        log.info("题目软删除: id={}", id);
    }

    /**
     * 批量删除（add-question-batch-delete，逐题结果信封）：selectBatchIds 一次取回后逐题校验
     * 收集失败项（已软删/不存在逻辑删除过滤后为 null、非归属且非 ADMIN、被进行中考试引用），
     * 合规子集再逐题逻辑删除。任一题校验失败不阻止其余合规题目（部分成功），
     * 失败 reason 与单删路径 {@link #softDelete} 的校验链文案逐字一致——403/400 直接复用
     * {@link OwnershipGuard#assertOwner} 与 {@link ExamPaperLockService#assertQuestionEditable}
     * 抛出的既有文案（不新造口径、不随副本漂移），404 文案「题目不存在」逐字对齐
     * {@link #getOwnedQuestion}。全部失败时 succeeded 为空列表仍返回 200。
     */
    @Transactional(rollbackFor = Exception.class)
    public BatchDeleteQuestionsResponse batchDelete(List<Long> ids) {
        LoginUser operator = requireLogin();
        // 请求内重复前置显式校验（不靠逐题查重间接暴露），口径与批量入卷 addQuestions 一致
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "请求内存在重复题目");
        }
        Map<Long, Question> found = questionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Question::getId, question -> question));

        List<Long> deletable = new ArrayList<>();
        List<BatchDeleteQuestionsResponse.FailedItem> failed = new ArrayList<>();
        for (Long id : ids) {
            Question question = found.get(id);
            if (question == null) {
                // selectBatchIds 已被逻辑删除过滤：查不到 = 不存在或已软删，与 getOwnedQuestion 404 同文案
                failed.add(new BatchDeleteQuestionsResponse.FailedItem(id, "题目不存在"));
                continue;
            }
            try {
                OwnershipGuard.assertOwner(question.getCreatedBy(), operator, "题目");
                examPaperLockService.assertQuestionEditable(id);
                deletable.add(id);
            } catch (BusinessException e) {
                failed.add(new BatchDeleteQuestionsResponse.FailedItem(id, e.getMessage()));
            }
        }
        for (Long id : deletable) {
            questionMapper.deleteById(id);
        }
        log.info("题目批量删除: 请求 {} 题，成功 {} 题，失败 {} 题", ids.size(), deletable.size(), failed.size());
        return new BatchDeleteQuestionsResponse(deletable, failed);
    }

    /**
     * 批量打标/解绑（add-batch-tagging，逐题结果信封）：对多道题目批量追加（BIND）或
     * 解绑（UNBIND）标签。
     *
     * <p>所有权校验逐题执行（越权/不存在题目计入 {@code failed}，ADMIN 自动放行）；
     * BIND 用「已有关联差集」仅插入缺失的 (questionId, tagId) 对，天然幂等且保留原题
     * 其余标签；UNBIND 物理删除合法的 (questionId, tagId) 关联行，非选标签不受影响。
     * 标签本体要求全部存在且未删除（否则 400），与单题打标 {@link #replaceTags} 口径一致。
     *
     * @param questionIds 目标题目 ID 列表
     * @param tagIds      目标标签 ID 列表
     * @param action      BIND（追加）或 UNBIND（解绑）
     * @return 信封：succeeded（被处理的题目 id）与 failed（逐题失败原因）
     */
    @Transactional(rollbackFor = Exception.class)
    public BatchTagsResponse batchTags(List<Long> questionIds, List<Long> tagIds, String action) {
        LoginUser operator = requireLogin();
        // 请求内重复题目前置显式校验（口径与 batchDelete/addQuestions 一致，靠去重后长度变化暴露）
        List<Long> distinctQuestions = questionIds.stream().distinct().toList();
        if (distinctQuestions.size() != questionIds.size()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "请求内存在重复题目");
        }
        if (!BatchTagsRequest.ACTION_BIND.equals(action)
                && !BatchTagsRequest.ACTION_UNBIND.equals(action)) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "非法操作类型: " + action);
        }
        List<Long> distinctTags = tagIds.stream().distinct().toList();
        List<Tag> existingTags = tagMapper.selectBatchIds(distinctTags);
        if (existingTags.size() < distinctTags.size()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "部分标签不存在或已删除");
        }
        if (distinctTags.isEmpty()) {
            return new BatchTagsResponse(List.of(), List.of());
        }

        Map<Long, Question> found = questionMapper.selectBatchIds(distinctQuestions).stream()
                .collect(Collectors.toMap(Question::getId, question -> question));

        List<Long> allowed = new ArrayList<>();
        List<BatchTagsResponse.FailedItem> failed = new ArrayList<>();
        for (Long id : distinctQuestions) {
            Question question = found.get(id);
            if (question == null) {
                // selectBatchIds 已过滤软删：查不到 = 不存在或已软删，与单题 404 同文案
                failed.add(new BatchTagsResponse.FailedItem(id, "题目不存在"));
                continue;
            }
            try {
                OwnershipGuard.assertOwner(question.getCreatedBy(), operator, "题目");
                allowed.add(id);
            } catch (BusinessException e) {
                failed.add(new BatchTagsResponse.FailedItem(id, e.getMessage()));
            }
        }
        if (allowed.isEmpty()) {
            return new BatchTagsResponse(List.of(), failed);
        }

        // 批量取回合法题目的既有关联（questionId, tagId 对）
        List<QuestionTag> existingLinks = questionTagMapper.selectList(Wrappers.<QuestionTag>lambdaQuery()
                .in(QuestionTag::getQuestionId, allowed));
        Set<String> existingPairs = existingLinks.stream()
                .map(l -> l.getQuestionId() + ":" + l.getTagId())
                .collect(Collectors.toSet());

        for (Long questionId : allowed) {
            for (Long tagId : distinctTags) {
                String pair = questionId + ":" + tagId;
                if (BatchTagsRequest.ACTION_BIND.equals(action)) {
                    // BIND：仅插入差集中缺失的 (questionId, tagId) 对，天然幂等
                    if (!existingPairs.contains(pair)) {
                        QuestionTag link = new QuestionTag();
                        link.setQuestionId(questionId);
                        link.setTagId(tagId);
                        questionTagMapper.insert(link);
                    }
                } else {
                    // UNBIND：物理删除指定关联行，非指定标签原样保留
                    questionTagMapper.delete(Wrappers.<QuestionTag>lambdaQuery()
                            .eq(QuestionTag::getQuestionId, questionId)
                            .eq(QuestionTag::getTagId, tagId));
                }
            }
        }

        log.info("题目批量{}: 请求 {} 题 × {} 标签，成功 {} 题，失败 {} 题",
                BatchTagsRequest.ACTION_BIND.equals(action) ? "打标" : "解绑",
                distinctQuestions.size(), distinctTags.size(), allowed.size(), failed.size());
        return new BatchTagsResponse(allowed, failed);
    }

    /**
     * 加载题目并做水平越权校验（assertTeacherOwnsQuestion）：
     * 不存在或已软删返回 404；非归属教师返回 403，ADMIN 放行。
     */
    public Question getOwnedQuestion(Long id) {
        Question question = questionMapper.selectById(id);
        if (question == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "题目不存在");
        }
        OwnershipGuard.assertOwner(question.getCreatedBy(), SecurityUtil.getCurrentUser(), "题目");
        return question;
    }

    /**
     * 分页查询（题目列表）：支持题型/难度/标签/关键词筛选。
     * 教师仅见自己创建的题目（个人题库），ADMIN 可见全部。
     */
    public Page<Question> page(long page, long size, Integer type, Integer difficulty, Long tagId, String keyword) {
        LoginUser operator = requireLogin();
        LambdaQueryWrapper<Question> wrapper = Wrappers.<Question>lambdaQuery()
                // 非 ADMIN 强制限定创建者，防止跨教师读取题库
                .eq(operator.getRoleLevel() < RoleHierarchy.levelOf(RoleHierarchy.ADMIN),
                        Question::getCreatedBy, operator.getId())
                .eq(type != null, Question::getType, type)
                .eq(difficulty != null, Question::getDifficulty, difficulty)
                .like(StringUtils.hasText(keyword), Question::getContent, keyword);
        if (tagId != null) {
            // 按标签筛选：tagId 为 Long 类型，无注入风险
            wrapper.inSql(Question::getId,
                    "SELECT question_id FROM question_tags WHERE tag_id = " + tagId);
        }
        wrapper.orderByDesc(Question::getId);
        return questionMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
    }

    /**
     * 批量加载题目-标签（避免逐题 N+1）：questionId -> 标签列表。
     * 供题目列表/详情与试卷详情等复用。
     */
    public Map<Long, List<Tag>> loadTagsByQuestionIds(List<Long> questionIds) {
        if (questionIds.isEmpty()) {
            return Map.of();
        }
        List<QuestionTag> links = questionTagMapper.selectList(Wrappers.<QuestionTag>lambdaQuery()
                .in(QuestionTag::getQuestionId, questionIds));
        if (links.isEmpty()) {
            return Map.of();
        }
        Set<Long> tagIds = links.stream().map(QuestionTag::getTagId).collect(Collectors.toSet());
        Map<Long, Tag> tagMap = tagMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, tag -> tag));
        Map<Long, List<Tag>> result = new HashMap<>();
        for (QuestionTag link : links) {
            Tag tag = tagMap.get(link.getTagId());
            if (tag != null) {
                result.computeIfAbsent(link.getQuestionId(), key -> new ArrayList<>()).add(tag);
            }
        }
        return result;
    }

    /** 重建题目-标签关联：先清空后插入；要求标签全部存在且未删除。 */
    private void replaceTags(Long questionId, List<Long> tagIds) {
        questionTagMapper.delete(Wrappers.<QuestionTag>lambdaQuery()
                .eq(QuestionTag::getQuestionId, questionId));
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        List<Long> distinctIds = tagIds.stream().distinct().toList();
        List<Tag> tags = tagMapper.selectBatchIds(distinctIds);
        if (tags.size() < distinctIds.size()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "部分标签不存在或已删除");
        }
        for (Long tagId : distinctIds) {
            QuestionTag link = new QuestionTag();
            link.setQuestionId(questionId);
            link.setTagId(tagId);
            questionTagMapper.insert(link);
        }
    }

    /** 校验题型合法性。 */
    private QuestionType parseType(Integer type) {
        QuestionType questionType = QuestionType.of(type);
        if (questionType == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "非法题型: " + type);
        }
        return questionType;
    }

    /**
     * 选项校验：单选/多选必须提供 2-26 个非空选项（对应 A-Z）；
     * 判断/简答/填空忽略选项。
     */
    private List<String> validateChoices(QuestionType type, List<String> choices) {
        if (type == QuestionType.JUDGE || type == QuestionType.SHORT_ANSWER
                || type == QuestionType.FILL_BLANK) {
            return List.of();
        }
        if (choices == null || choices.size() < 2) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "客观题至少需要两个选项");
        }
        for (String choice : choices) {
            if (!StringUtils.hasText(choice)) {
                throw new BusinessException(ResponseCode.BAD_REQUEST, "选项内容不能为空");
            }
        }
        return choices;
    }

    /** 选项列表 -> JSON 数组字符串（判断/简答/填空传入空列表，存 null）。 */
    private String toJson(List<String> choices) {
        if (choices.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(choices);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "选项序列化失败");
        }
    }

    /** 选项 JSON 数组字符串 -> 列表（响应组装用）。 */
    public List<String> parseChoices(String choicesJson) {
        if (choicesJson == null || choicesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(choicesJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /**
     * 图片地址入库归一（add-question-image）：空/纯空白按无图（null）存储，
     * 非空去首尾空白（上传端点返回的 URL 恒为干净形态，此处仅兜底手输空格）。
     * 格式约束（/images/ 前缀、≤512）由 DTO 校验先行拦截。
     */
    private String imageUrlOf(String imageUrl) {
        return StringUtils.hasText(imageUrl) ? imageUrl.trim() : null;
    }

    private LoginUser requireLogin() {
        LoginUser operator = SecurityUtil.getCurrentUser();
        if (operator == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        return operator;
    }
}
