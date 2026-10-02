package com.exam.paper.service;

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
import com.exam.paper.dto.BatchAddPaperQuestionsRequest;
import com.exam.paper.dto.PaperCreateRequest;
import com.exam.paper.dto.PaperDetailResponse;
import com.exam.paper.dto.PaperQuestionItemResponse;
import com.exam.paper.dto.PaperUpdateRequest;
import com.exam.paper.dto.RandomDrawPreviewResponse;
import com.exam.paper.dto.RandomDrawRequest;
import com.exam.paper.entity.Paper;
import com.exam.paper.entity.PaperQuestion;
import com.exam.paper.mapper.PaperMapper;
import com.exam.paper.mapper.PaperQuestionMapper;
import com.exam.question.entity.Question;
import com.exam.question.entity.QuestionType;
import com.exam.question.mapper.QuestionMapper;
import com.exam.question.repository.QuestionTagRepository;
import com.exam.question.service.QuestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 组卷服务：手动组卷（加题/调序/分值覆盖）与标签随机抽题。
 *
 * <p>关键约束：
 * <ul>
 *   <li>试卷内分值（paper_questions.score）覆盖题目默认分，两边互不影响（spec §11.1 决策）；</li>
 *   <li>总分一致性强校验：各题分值之和必须等于试卷申报总分（保存元信息与生成快照两处校验）；</li>
 *   <li>抽题先取候选 id 再内存 shuffle（避免 DB 端 ORDER BY RAND() 全表扫描）；</li>
 *   <li>两层锁：快照锁定（status=已锁定）+ 考试进行中锁定（被进行中考试绑定的试卷只读，§4.1）；</li>
 *   <li>数据隔离：写操作经 {@link #getOwnedPaper} owner 校验（assertTeacherOwnsPaper 模式）。</li>
 * </ul>
 *
 * <p>事务统一显式 rollbackFor=Exception.class（见 data-consistency 规范），防未来受检异常静默不回滚。
 */
@Slf4j
@Service
public class PaperService {

    private final PaperMapper paperMapper;
    private final PaperQuestionMapper paperQuestionMapper;
    private final QuestionMapper questionMapper;
    private final QuestionService questionService;
    private final ExamPaperLockService examPaperLockService;
    private final QuestionTagRepository questionTagRepository;

    public PaperService(PaperMapper paperMapper, PaperQuestionMapper paperQuestionMapper,
                        QuestionMapper questionMapper, QuestionService questionService,
                        ExamPaperLockService examPaperLockService,
                        QuestionTagRepository questionTagRepository) {
        this.paperMapper = paperMapper;
        this.paperQuestionMapper = paperQuestionMapper;
        this.questionMapper = questionMapper;
        this.questionService = questionService;
        this.examPaperLockService = examPaperLockService;
        this.questionTagRepository = questionTagRepository;
    }

    /** 创建试卷（草稿）。 */
    @Transactional(rollbackFor = Exception.class)
    public Paper create(PaperCreateRequest request) {
        LoginUser operator = requireLogin();
        Paper paper = new Paper();
        paper.setTitle(request.getTitle().trim());
        paper.setDescription(request.getDescription() == null ? "" : request.getDescription());
        paper.setTotalScore(request.getTotalScore());
        paper.setQuestionCount(0);
        paper.setStatus(Paper.STATUS_DRAFT);
        paper.setCreatedBy(operator.getId());
        paperMapper.insert(paper);
        log.info("教师 {} 创建试卷 id={} total={}", operator.getId(), paper.getId(), request.getTotalScore());
        return paper;
    }

    /** 试卷分页：教师仅见自己的试卷，ADMIN 可见全部。 */
    public Page<Paper> page(long page, long size) {
        LoginUser operator = requireLogin();
        return paperMapper.selectPage(new Page<>(page, Math.min(size, 100)),
                Wrappers.<Paper>lambdaQuery()
                        .eq(operator.getRoleLevel() < RoleHierarchy.levelOf(RoleHierarchy.ADMIN),
                                Paper::getCreatedBy, operator.getId())
                        .orderByDesc(Paper::getId));
    }

    /** 试卷详情：题目项按题号排序；后来被软删的题目标记 questionDeleted，便于教师处理。 */
    public PaperDetailResponse detail(Long id) {
        Paper paper = getOwnedPaper(id);
        List<PaperQuestion> rows = listRows(paper.getId());
        Map<Long, Question> questionMap = loadQuestionMap(
                rows.stream().map(PaperQuestion::getQuestionId).toList());
        List<PaperQuestionItemResponse> items = rows.stream()
                .map(row -> toItem(row, questionMap.get(row.getQuestionId())))
                .toList();
        return toDetail(paper, items);
    }

    /**
     * 更新试卷元信息：已有题目时若更新 totalScore，则校验"各题分值之和 = 总分"
     * （spec「总分校验」场景：不一致则保存失败并提示调整）。
     */
    @Transactional(rollbackFor = Exception.class)
    public PaperDetailResponse updateMeta(Long id, PaperUpdateRequest request) {
        Paper paper = getOwnedPaper(id);
        assertNotLocked(paper);
        examPaperLockService.assertPaperEditable(paper.getId());
        if (request.getTotalScore() != null && paper.getQuestionCount() > 0) {
            BigDecimal sum = sumScores(paper.getId());
            if (sum.compareTo(request.getTotalScore()) != 0) {
                throw new BusinessException(ResponseCode.BAD_REQUEST,
                        "各题分值之和(" + sum.stripTrailingZeros().toPlainString()
                                + ")与试卷总分(" + request.getTotalScore() + ")不一致，请调整题目分值或总分");
            }
        }
        if (StringUtils.hasText(request.getTitle())) {
            paper.setTitle(request.getTitle().trim());
        }
        if (request.getDescription() != null) {
            paper.setDescription(request.getDescription());
        }
        if (request.getTotalScore() != null) {
            paper.setTotalScore(request.getTotalScore());
        }
        paperMapper.updateById(paper);
        return detail(id);
    }

    /** 删除试卷（草稿期）：已锁定（生成过快照）或被进行中考试绑定的试卷不允许删除，保护历史组卷结果。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Paper paper = getOwnedPaper(id);
        examPaperLockService.assertPaperEditable(paper.getId());
        if (paper.getStatus() == Paper.STATUS_LOCKED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "试卷已锁定（已生成快照），不允许删除");
        }
        paperQuestionMapper.delete(Wrappers.<PaperQuestion>lambdaQuery()
                .eq(PaperQuestion::getPaperId, id));
        paperMapper.deleteById(id);
        log.info("试卷删除: id={}", id);
    }

    // ==================== 手动组卷 ====================

    /** 手动加题入卷：score 不传时用题目默认分（分值覆盖场景显式传入）。 */
    @Transactional(rollbackFor = Exception.class)
    public PaperQuestionItemResponse addQuestion(Long paperId, Long questionId, BigDecimal score) {
        Paper paper = getOwnedPaper(paperId);
        assertNotLocked(paper);
        examPaperLockService.assertPaperEditable(paper.getId());
        PaperQuestion row = addQuestionInternal(paper, loadLiveQuestion(questionId), score);
        return toItem(row, questionMapper.selectById(questionId));
    }

    /**
     * 批量加题入卷：整批单事务全有全无（commitRandomDraw 同构），任一题失败整体回滚，
     * 杜绝半批入卷。请求内重复 questionId 前置显式校验（不靠逐题查重间接暴露）；
     * 单项失败语义与单题端点一致（已在卷中/不存在/锁定/越权同文案）。
     */
    @Transactional(rollbackFor = Exception.class)
    public PaperDetailResponse addQuestions(Long paperId, BatchAddPaperQuestionsRequest request) {
        Paper paper = getOwnedPaper(paperId);
        assertNotLocked(paper);
        examPaperLockService.assertPaperEditable(paper.getId());
        List<Long> questionIds = request.getItems().stream()
                .map(BatchAddPaperQuestionsRequest.Item::getQuestionId)
                .toList();
        if (new HashSet<>(questionIds).size() != questionIds.size()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "请求内存在重复题目");
        }
        for (BatchAddPaperQuestionsRequest.Item item : request.getItems()) {
            addQuestionInternal(paper, loadLiveQuestion(item.getQuestionId()), item.getScore());
        }
        log.info("试卷 {} 批量加题 {} 道", paperId, request.getItems().size());
        return detail(paperId);
    }

    /** 移出题目并重排剩余题号，保持 1..n 连续。 */
    @Transactional(rollbackFor = Exception.class)
    public void removeQuestion(Long paperId, Long questionId) {
        Paper paper = getOwnedPaper(paperId);
        assertNotLocked(paper);
        examPaperLockService.assertPaperEditable(paper.getId());
        int removed = paperQuestionMapper.delete(Wrappers.<PaperQuestion>lambdaQuery()
                .eq(PaperQuestion::getPaperId, paperId)
                .eq(PaperQuestion::getQuestionId, questionId));
        if (removed == 0) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "该题目不在试卷中");
        }
        renumber(listRows(paperId));
        paper.setQuestionCount(paper.getQuestionCount() - 1);
        paperMapper.updateById(paper);
    }

    /** 调整试卷内单题分值（覆盖题目默认分；题库默认分不动）。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateQuestionScore(Long paperId, Long questionId, BigDecimal score) {
        Paper paper = getOwnedPaper(paperId);
        assertNotLocked(paper);
        examPaperLockService.assertPaperEditable(paper.getId());
        PaperQuestion row = paperQuestionMapper.selectOne(Wrappers.<PaperQuestion>lambdaQuery()
                .eq(PaperQuestion::getPaperId, paperId)
                .eq(PaperQuestion::getQuestionId, questionId));
        if (row == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "该题目不在试卷中");
        }
        row.setScore(score);
        paperQuestionMapper.updateById(row);
    }

    /**
     * 调整题目顺序：按传入的题目 ID 顺序重排题号 1..n。
     * 传入列表必须与试卷现有题目一一对应（多/少/不匹配均拒绝），防止静默丢题。
     */
    @Transactional(rollbackFor = Exception.class)
    public PaperDetailResponse updateOrder(Long paperId, List<Long> orderedQuestionIds) {
        Paper paper = getOwnedPaper(paperId);
        assertNotLocked(paper);
        examPaperLockService.assertPaperEditable(paper.getId());
        List<PaperQuestion> rows = listRows(paperId);
        Set<Long> currentIds = rows.stream().map(PaperQuestion::getQuestionId).collect(Collectors.toSet());
        if (orderedQuestionIds.size() != rows.size()
                || !currentIds.equals(new HashSet<>(orderedQuestionIds))) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "题目顺序列表与试卷现有题目不一致");
        }
        Map<Long, PaperQuestion> rowMap = rows.stream()
                .collect(Collectors.toMap(PaperQuestion::getQuestionId, Function.identity()));
        List<PaperQuestion> reordered = orderedQuestionIds.stream()
                .map(rowMap::get)
                .collect(Collectors.toList());
        renumber(reordered);
        return detail(paperId);
    }

    // ==================== 标签随机抽题 ====================

    /** 抽题预览：不落库，教师可确认入卷或重抽（spec「抽题不足时提示调整」场景）。 */
    public RandomDrawPreviewResponse previewDraw(RandomDrawRequest request) {
        List<List<Question>> drawn = draw(request.getRules(), List.of());
        List<RandomDrawPreviewResponse.RuleResult> results = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < drawn.size(); i++) {
            List<Question> questions = drawn.get(i);
            total += questions.size();
            results.add(new RandomDrawPreviewResponse.RuleResult(i, questions.size(),
                    questions.stream().map(this::toDrawn).toList()));
        }
        return new RandomDrawPreviewResponse(results, total);
    }

    /**
     * 抽题入卷：按规则抽取后追加到试卷（使用题目默认分），已有题目自动排除在候选之外。
     */
    @Transactional(rollbackFor = Exception.class)
    public PaperDetailResponse commitRandomDraw(Long paperId, RandomDrawRequest request) {
        Paper paper = getOwnedPaper(paperId);
        assertNotLocked(paper);
        examPaperLockService.assertPaperEditable(paper.getId());
        Set<Long> existing = paperQuestionMapper.selectList(Wrappers.<PaperQuestion>lambdaQuery()
                        .eq(PaperQuestion::getPaperId, paperId)).stream()
                .map(PaperQuestion::getQuestionId)
                .collect(Collectors.toSet());
        List<List<Question>> drawn = draw(request.getRules(), existing);
        for (List<Question> questions : drawn) {
            for (Question question : questions) {
                addQuestionInternal(paper, question, null);
            }
        }
        log.info("试卷 {} 随机抽题入卷 {} 条规则", paperId, request.getRules().size());
        return detail(paperId);
    }

    /**
     * 抽题核心：逐规则查候选题 id（限定教师个人题库、排除已选），内存 shuffle 后取前 N，
     * 再按 id 批量加载完整题目——避免 DB 端 ORDER BY RAND() 全表排序。
     *
     * @throws BusinessException 题库容量不足（候选数 < 抽数），提示教师调整条件或数量
     */
    private List<List<Question>> draw(List<RandomDrawRequest.Rule> rules, Collection<Long> excludeQuestionIds) {
        LoginUser operator = requireLogin();
        boolean isAdmin = operator.getRoleLevel() >= RoleHierarchy.levelOf(RoleHierarchy.ADMIN);
        List<List<Question>> result = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            RandomDrawRequest.Rule rule = rules.get(i);
            LambdaQueryWrapper<Question> wrapper = Wrappers.<Question>lambdaQuery()
                    .select(Question::getId)
                    .eq(!isAdmin, Question::getCreatedBy, operator.getId())
                    .eq(rule.getType() != null, Question::getType, rule.getType())
                    .eq(rule.getDifficulty() != null, Question::getDifficulty, rule.getDifficulty())
                    .notIn(!excludeQuestionIds.isEmpty(), Question::getId, excludeQuestionIds);
            List<Long> candidateIds = candidateIdsFor(wrapper, rule.getTagIds());
            if (candidateIds.size() < rule.getCount()) {
                throw new BusinessException(ResponseCode.BAD_REQUEST,
                        "满足抽题条件的题目不足：第 " + (i + 1) + " 条规则需要 "
                                + rule.getCount() + " 题，仅匹配到 " + candidateIds.size()
                                + " 题，请调整抽题条件或数量");
            }
            // 内存 shuffle 替代 DB 端 ORDER BY RAND()（stream.toList() 不可变，需可变列表）
            Collections.shuffle(candidateIds, ThreadLocalRandom.current());
            result.add(questionMapper.selectBatchIds(candidateIds.subList(0, rule.getCount())));
        }
        return result;
    }

    /**
     * 按规则取候选题 id：有标签约束时先查关联表再按 id 集合过滤（参数化，见
     * {@link com.exam.question.repository.QuestionTagRepository}）。
     *
     * <p>标签一道题都没命中时<b>必须短路</b>，不能把空集合交给 {@code wrapper.in()}：
     * MyBatis-Plus 会原样拼出 {@code id IN ()}，MySQL 与 H2 都当语法错误抛出，
     * 端点于是返回 500；而部分版本又会跳过空 in 条件，变成"标签过滤形同不存在"、
     * 从整个题库抽题的静默错误结果。两种都不可接受，这里直接返回 0 候选，
     * 交给调用方既有的「题目不足」分支去报错。
     *
     * @return 可变列表（调用方要对其 shuffle）
     */
    private List<Long> candidateIdsFor(LambdaQueryWrapper<Question> wrapper, List<Long> tagIds) {
        if (tagIds != null && !tagIds.isEmpty()) {
            List<Long> tagged = questionTagRepository.findQuestionIdsByTagIds(tagIds);
            if (tagged.isEmpty()) {
                return new ArrayList<>();
            }
            wrapper.in(Question::getId, tagged);
        }
        return questionMapper.selectList(wrapper).stream()
                .map(Question::getId)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    // ==================== 共用 ====================

    /**
     * 加载试卷并做水平越权校验（assertTeacherOwnsPaper）：
     * 不存在/已软删返回 404；非归属教师返回 403，ADMIN 放行。
     * 供本服务与 PaperSnapshotService 共用。
     */
    public Paper getOwnedPaper(Long id) {
        Paper paper = paperMapper.selectById(id);
        if (paper == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "试卷不存在");
        }
        OwnershipGuard.assertOwner(paper.getCreatedBy(), SecurityUtil.getCurrentUser(), "试卷");
        return paper;
    }

    /** 试卷锁定后禁止一切组卷编辑（保证快照前的题目/分值/顺序稳定）。 */
    private void assertNotLocked(Paper paper) {
        if (paper.getStatus() == Paper.STATUS_LOCKED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "试卷已锁定（快照已生成），不允许修改");
        }
    }

    /** 追加题目到试卷：题号接在末尾；score 为空时取题目默认分（分值覆盖仅影响试卷内）。 */
    private PaperQuestion addQuestionInternal(Paper paper, Question question, BigDecimal score) {
        Long exists = paperQuestionMapper.selectCount(Wrappers.<PaperQuestion>lambdaQuery()
                .eq(PaperQuestion::getPaperId, paper.getId())
                .eq(PaperQuestion::getQuestionId, question.getId()));
        if (exists > 0) {
            throw new BusinessException(ResponseCode.DATA_ALREADY_EXISTS, "该题目已在试卷中");
        }
        PaperQuestion row = new PaperQuestion();
        row.setPaperId(paper.getId());
        row.setQuestionId(question.getId());
        row.setNumber(paper.getQuestionCount() + 1);
        row.setScore(score == null ? question.getScore() : score);
        paperQuestionMapper.insert(row);
        paper.setQuestionCount(paper.getQuestionCount() + 1);
        paperMapper.updateById(paper);
        return row;
    }

    /**
     * 两阶段重排题号，规避 uk(paper_id, number) 唯一键冲突：
     * 先整体偏移到高位段清空 1..n，再写入最终连续题号。
     */
    private void renumber(List<PaperQuestion> orderedRows) {
        int offset = orderedRows.size() + 1000;
        for (PaperQuestion row : orderedRows) {
            row.setNumber(row.getNumber() + offset);
            paperQuestionMapper.updateById(row);
        }
        for (int i = 0; i < orderedRows.size(); i++) {
            PaperQuestion row = orderedRows.get(i);
            row.setNumber(i + 1);
            paperQuestionMapper.updateById(row);
        }
    }

    /** 试卷题目行（按题号升序）。 */
    private List<PaperQuestion> listRows(Long paperId) {
        return paperQuestionMapper.selectList(Wrappers.<PaperQuestion>lambdaQuery()
                .eq(PaperQuestion::getPaperId, paperId)
                .orderByAsc(PaperQuestion::getNumber));
    }

    /** 批量加载题目（逻辑删除自动过滤，缺失即已软删）。 */
    private Map<Long, Question> loadQuestionMap(List<Long> questionIds) {
        if (questionIds.isEmpty()) {
            return Map.of();
        }
        return questionMapper.selectBatchIds(questionIds).stream()
                .collect(Collectors.toMap(Question::getId, Function.identity()));
    }

    /** 加载未删除的题目（手动加题入口用，软删题目不允许入卷）。 */
    private Question loadLiveQuestion(Long questionId) {
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "题目不存在或已删除");
        }
        return question;
    }

    private BigDecimal sumScores(Long paperId) {
        return paperQuestionMapper.selectList(Wrappers.<PaperQuestion>lambdaQuery()
                        .eq(PaperQuestion::getPaperId, paperId)).stream()
                .map(PaperQuestion::getScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 题目行 -> 详情项：题目已软删时内容置空并打标。 */
    private PaperQuestionItemResponse toItem(PaperQuestion row, Question question) {
        if (question == null) {
            return new PaperQuestionItemResponse(row.getQuestionId(), row.getNumber(), row.getScore(),
                    null, null, List.of(), null, null, true);
        }
        return new PaperQuestionItemResponse(row.getQuestionId(), row.getNumber(), row.getScore(),
                question.getType(), question.getContent(),
                questionService.parseChoices(question.getChoices()),
                question.getCorrectAnswer(), question.getScore(), false);
    }

    private PaperDetailResponse toDetail(Paper paper, List<PaperQuestionItemResponse> items) {
        return new PaperDetailResponse(paper.getId(), paper.getTitle(), paper.getDescription(),
                paper.getTotalScore(), paper.getQuestionCount(), paper.getStatus(),
                paper.getSnapshotId(), paper.getCreatedBy(),
                paper.getCreatedTime(), paper.getUpdatedTime(), items);
    }

    private RandomDrawPreviewResponse.DrawnQuestion toDrawn(Question question) {
        QuestionType type = QuestionType.of(question.getType());
        return new RandomDrawPreviewResponse.DrawnQuestion(question.getId(), question.getType(),
                type == null ? null : type.getLabel(), question.getContent(),
                question.getScore(), question.getDifficulty());
    }

    private LoginUser requireLogin() {
        LoginUser operator = SecurityUtil.getCurrentUser();
        if (operator == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        return operator;
    }
}
