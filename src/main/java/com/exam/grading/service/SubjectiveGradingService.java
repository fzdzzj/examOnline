package com.exam.grading.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.ReadYourWriteMark;
import com.exam.grading.dto.BlindArbitrateRequest;
import com.exam.grading.dto.BlindArbitrateResponse;
import com.exam.grading.dto.BlindArbitrationItem;
import com.exam.grading.dto.BlindReviewSubmitRequest;
import com.exam.grading.dto.BlindReviewSubmitResponse;
import com.exam.grading.dto.SubjectiveGradePageResponse;
import com.exam.grading.dto.SubjectiveGradeRow;
import com.exam.grading.dto.SubjectiveQuestionItem;
import com.exam.grading.dto.SubjectiveScoreRequest;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveBlindReview;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveBlindReviewMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.support.GradingPaperReader;
import com.exam.submission.entity.ExamSubmission;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 简答批改工作台服务（spec「简答批改」需求，教师逐题批改）：
 *
 * <ul>
 *   <li>按题批改：同题列出全部学生的答案/提示分/终分，教师逐行打分评语，
 *       避免按学生翻卷来回切换；</li>
 *   <li>打回重批：已批题目可再次提交批改（版本号取最新即可覆盖重批），
 *       批改人/时间随之更新，留痕可追溯；</li>
 *   <li>并发防覆盖：两教师同时批改同一答卷同题，以 version 乐观锁 CAS 裁决——
 *       仅一个成功，另一个收到 409 冲突重载（spec「并发批改防覆盖」场景）。</li>
 * </ul>
 *
 * <p>事务统一显式 rollbackFor=Exception.class（见 data-consistency 规范），防未来受检异常静默不回滚。
 */
@Service
public class SubjectiveGradingService {

    private final GradingQueryService gradingQueryService;
    private final GradingPaperReader paperReader;
    private final SubjectiveGradeMapper subjectiveGradeMapper;
    private final GradingSubmissionMapper gradingSubmissionMapper;
    private final SubjectiveBlindReviewMapper blindReviewMapper;
    private final UserMapper userMapper;
    private final ReadYourWriteMark readYourWriteMark;

    public SubjectiveGradingService(GradingQueryService gradingQueryService,
                                    GradingPaperReader paperReader,
                                    SubjectiveGradeMapper subjectiveGradeMapper,
                                    GradingSubmissionMapper gradingSubmissionMapper,
                                    SubjectiveBlindReviewMapper blindReviewMapper,
                                    UserMapper userMapper,
                                    ReadYourWriteMark readYourWriteMark) {
        this.gradingQueryService = gradingQueryService;
        this.paperReader = paperReader;
        this.subjectiveGradeMapper = subjectiveGradeMapper;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.blindReviewMapper = blindReviewMapper;
        this.userMapper = userMapper;
        this.readYourWriteMark = readYourWriteMark;
    }

    /** 待批题目清单：快照简答题 + 各题批改进度（判分未运行时进度为 0）。 */
    public List<SubjectiveQuestionItem> listQuestions(Long examId) {
        gradingQueryService.requireOwnedExam(examId);
        GradingPaper paper = paperReader.readByExamId(examId);

        // 各题批改进度一次查出后内存分组：简答题数量有限，避免逐题 count 查询
        List<SubjectiveGrade> rows = subjectiveGradeMapper.selectList(
                Wrappers.<SubjectiveGrade>lambdaQuery()
                        .eq(SubjectiveGrade::getExamId, examId)
                        .select(SubjectiveGrade::getQuestionId, SubjectiveGrade::getScore));
        Map<Long, List<SubjectiveGrade>> byQuestion = rows.stream()
                .collect(Collectors.groupingBy(SubjectiveGrade::getQuestionId));

        return paper.shortAnswerQuestions().stream().map(question -> {
            List<SubjectiveGrade> questionRows =
                    byQuestion.getOrDefault(question.questionId(), List.of());
            SubjectiveQuestionItem item = new SubjectiveQuestionItem();
            item.setQuestionId(question.questionId());
            item.setNumber(question.number());
            item.setContent(question.content());
            item.setScore(question.score());
            item.setTotalStudents(questionRows.size());
            item.setGradedStudents((int) questionRows.stream()
                    .filter(row -> row.getScore() != null).count());
            return item;
        }).toList();
    }

    /** 服务端分页 size 上限；page/size 只给其一时的另一参数兜底（page=1 / size=上限）。 */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 工作台列表（add-subjective-grading-pagination）：同题学生行，响应恒为分页信封。
     *
     * <ul>
     *   <li>page/size 均缺省 → 全量（rows=该题全部行）；只给其一时另一参数取默认
     *       （page=1 / size=上限）；page≥1、size∈[1,100]，违规 400；</li>
     *   <li>onlyUngraded=true → 服务端过滤未批（score IS NULL，对齐面板既有 !graded 语义）；
     *       name → 学生姓名 LIKE 包含（对齐面板既有 studentName.includes）；</li>
     *   <li>submissionId → 单行取数（至多 1 行），专供 409/1012 冲突回填，替代全量拉取后 find；</li>
     *   <li>total 与 rows 同口径（筛选后、分页前）；graded 与题级进度 gradedStudents 同口径，
     *       不受行筛选影响；排序恒为 student_id 稳定序——翻页不丢行不错行的前提。</li>
     * </ul>
     */
    public SubjectiveGradePageResponse listRows(Long examId, Long questionId, Integer page, Integer size,
                                                Boolean onlyUngraded, String name, Long submissionId) {
        gradingQueryService.requireOwnedExam(examId);
        Integer offset = null;
        Integer limit = null;
        if (page != null || size != null) {
            int pageNumber = page == null ? 1 : page;
            int pageSize = size == null ? MAX_PAGE_SIZE : size;
            if (pageNumber < 1) {
                throw new BusinessException(ResponseCode.BAD_REQUEST, "page 必须 ≥ 1");
            }
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                throw new BusinessException(ResponseCode.BAD_REQUEST,
                        "size 必须在 1.." + MAX_PAGE_SIZE);
            }
            offset = (pageNumber - 1) * pageSize;
            limit = pageSize;
        }
        // 面板提交前会 trim，空白串等价于不过滤（对齐既有客户端筛选语义）
        String nameKeyword = name == null || name.isBlank() ? null : name.trim();
        List<SubjectiveGradeRow> rows = subjectiveGradeMapper.selectWorkbenchRowsPaged(
                examId, questionId, offset, limit, onlyUngraded, nameKeyword, submissionId);
        int total = (int) subjectiveGradeMapper.countWorkbenchRows(
                examId, questionId, onlyUngraded, nameKeyword, submissionId);
        int graded = (int) subjectiveGradeMapper.countWorkbenchGraded(examId, questionId);
        return new SubjectiveGradePageResponse(rows, total, graded);
    }

    /**
     * 提交批改（终分 + 评语，乐观锁防覆盖）。
     *
     * <p>乐观锁语义：UPDATE ... WHERE id=? AND version=?，影响 0 行说明
     * 已被并发批改保存——抛 409 让前端重载最新版本，绝不静默覆盖他人批改。
     *
     * <p>保存成功后同步刷新该答卷的主观分合计与部分批改标记（汇总时还会
     * 以同样口径权威重算，这里刷新只为工作台进度实时可见）。
     */
    @Transactional(rollbackFor = Exception.class)
    public SubjectiveGradeRow saveScore(Long examId, SubjectiveScoreRequest request) {
        gradingQueryService.requireOwnedExam(examId);

        GradingPaper paper = paperReader.readByExamId(examId);
        // 分值上限以考试快照为准（快照锁定后试卷内分值即终局口径）
        GradingQuestion question = paper.questions().stream()
                .filter(q -> q.questionId().equals(request.getQuestionId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResponseCode.NOT_FOUND, "题目不属于该考试"));
        if (question.type() != com.exam.question.entity.QuestionType.SHORT_ANSWER) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "仅简答题支持人工批改");
        }
        if (request.getScore().compareTo(question.score()) > 0) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "批改分数不得超过本题满分 " + question.score().stripTrailingZeros().toPlainString());
        }

        SubjectiveGrade row = subjectiveGradeMapper.selectOne(
                Wrappers.<SubjectiveGrade>lambdaQuery()
                        .eq(SubjectiveGrade::getSubmissionId, request.getSubmissionId())
                        .eq(SubjectiveGrade::getQuestionId, request.getQuestionId()));

        Long graderId = com.exam.auth.security.SecurityUtil.getUserId();
        LocalDateTime now = LocalDateTime.now();
        if (row == null) {
            // 判分尚未运行时教师先批：直接建行落终分（version 从 0 起步，同样受乐观锁保护）
            row = new SubjectiveGrade();
            row.setSubmissionId(request.getSubmissionId());
            row.setExamId(examId);
            GradingSubmission submission = gradingSubmissionMapper.selectById(request.getSubmissionId());
            if (submission == null || !submission.getExamId().equals(examId)) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "答卷不存在或不属于该考试");
            }
            row.setStudentId(submission.getStudentId());
            row.setQuestionId(request.getQuestionId());
            row.setQuestionNumber(question.number());
            row.setStudentAnswer(null);
            row.setScore(request.getScore());
            row.setComment(request.getComment());
            row.setGraderId(graderId);
            row.setGradedTime(now);
            row.setVersion(0);
            // 初次批改并发：两教师同建行，后到者撞 uk_subjective_grades——
            // 转 409 与乐观锁冲突同语义，不把 DAO 异常透出成 500
            try {
                subjectiveGradeMapper.insert(row);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(ResponseCode.STATE_CONFLICT,
                        "批改已被他人更新，请刷新后重试");
            }
        } else {
            // 乐观锁 CAS：仅版本一致才写入（两教师同批一份卷仅一个成功）
            int updated = subjectiveGradeMapper.casSaveScore(row.getId(), request.getScore(),
                    request.getComment(), graderId, request.getExpectedVersion());
            if (updated == 0) {
                throw new BusinessException(ResponseCode.STATE_CONFLICT,
                        "批改已被他人更新，请刷新后重试");
            }
        }

        refreshSubmissionSubjectiveScore(request.getSubmissionId());
        // 读己之写（add-performance-deepening task4）：人工批改是写操作，成功打点，
        // 教师随即刷新工作台（返回结果本身即主库回读）与本线程其他读不因从库延迟失真
        readYourWriteMark.mark();
        // 单行回读（optimize-practice-pool-hot-path）：只取本 submissionId 一行（列与工作台行等价，
        // 响应形状不变），替代「全量学生工作台行 + 内存 filter」；回读失败语义不变
        SubjectiveGradeRow saved = subjectiveGradeMapper.selectWorkbenchRow(
                examId, request.getQuestionId(), request.getSubmissionId());
        if (saved == null) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "批改结果回读失败");
        }
        return saved;
    }

    /**
     * 刷新答卷主观分与部分批改标记：
     * subjective_score = 已批终分之和；存在未批行 → partial_graded=1（§7.5 部分批改）。
     * 注意：仅统计已有批改行的题目——判分未运行的答卷此处不计未批题，
     * 汇总阶段会按快照简答题全集权威重算，两侧口径最终一致。
     */
    private void refreshSubmissionSubjectiveScore(Long submissionId) {
        List<SubjectiveGrade> rows = subjectiveGradeMapper.selectList(
                Wrappers.<SubjectiveGrade>lambdaQuery()
                        .eq(SubjectiveGrade::getSubmissionId, submissionId));
        BigDecimal subjectiveScore = rows.stream()
                .map(SubjectiveGrade::getScore)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean partial = rows.stream().anyMatch(row -> row.getScore() == null);

        gradingSubmissionMapper.update(null, Wrappers.<GradingSubmission>lambdaUpdate()
                .eq(GradingSubmission::getId, submissionId)
                .in(GradingSubmission::getStatus, ExamSubmission.STATUS_SUBMITTED, ExamSubmission.STATUS_GRADED)
                .set(GradingSubmission::getSubjectiveScore, subjectiveScore)
                .set(GradingSubmission::getPartialGraded, partial ? 1 : 0)
                .set(GradingSubmission::getUpdatedTime, LocalDateTime.now()));
    }

    // ==================== 双评盲评与分差仲裁（add-subjective-double-blind-grading） ====================

    /** 分差容差（缺省 2.0 分；spec 预留按题目/考试配置，当前取全局缺省常量）。 */
    public static final BigDecimal BLIND_TOLERANCE = new BigDecimal("2.0");

    /** 盲评脱敏打码名：考生身份对非归属盲评教师不可见 */
    public static final String BLIND_MASKED_NAME = "考生 ***";

    /** 裁决状态：待二评 */
    public static final String STATUS_PENDING_SECOND = "PENDING_SECOND";
    /** 裁决状态：容差内已裁决（均分回填） */
    public static final String STATUS_RESOLVED = "RESOLVED";
    /** 裁决状态：分差超标待仲裁 */
    public static final String STATUS_ARBITRATION_PENDING = "ARBITRATION_PENDING";
    /** 裁决状态：仲裁终审结案 */
    public static final String STATUS_ARBITRATED = "ARBITRATED";

    private static final BigDecimal TWO = new BigDecimal("2");

    /**
     * 双评盲评提交：一评/二评自动分流 + 同教师互斥 + 分差裁决（背靠背盲评入口）。
     *
     * <p>分流规则：该答卷本题无 FIRST 记录 → 本次为一评（终分保持 NULL，待二评）；
     * 已有 FIRST → 本次为二评，且校验与一评教师互斥（同一人 400，spec「背靠背」红线）。
     *
     * <p>分差裁决（仅二评触发）：|S1-S2| ≤ 容差 → 终分 round((S1+S2)/2, 1) 回填
     * subjective_grades.score 并刷新答卷主观总分；超标 → 终分保持 NULL，
     * 流转 ARBITRATION_PENDING 由仲裁员终审。未裁决前绝不向任何一方透出对方分数。
     *
     * <p>不做考试归属校验：二评教师通常非归属人（背靠背防人情分），
     * 考试存在性由快照读取兜底；归属校验仅仲裁端点保留。
     */
    @Transactional(rollbackFor = Exception.class)
    public BlindReviewSubmitResponse blindSubmit(Long examId, BlindReviewSubmitRequest request) {
        GradingPaper paper = paperReader.readByExamId(examId);
        requireGradableShortAnswer(paper, request.getQuestionId(), request.getScore());
        GradingSubmission submission = gradingSubmissionMapper.selectById(request.getSubmissionId());
        if (submission == null || !submission.getExamId().equals(examId)) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "答卷不存在或不属于该考试");
        }
        SubjectiveGrade gradeRow = subjectiveGradeMapper.selectOne(
                Wrappers.<SubjectiveGrade>lambdaQuery()
                        .eq(SubjectiveGrade::getSubmissionId, request.getSubmissionId())
                        .eq(SubjectiveGrade::getQuestionId, request.getQuestionId()));
        if (gradeRow == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "该答卷本题尚无批改行，请先运行判分");
        }

        Long graderId = com.exam.auth.security.SecurityUtil.getUserId();
        List<SubjectiveBlindReview> existing = blindReviewMapper.selectList(
                Wrappers.<SubjectiveBlindReview>lambdaQuery()
                        .eq(SubjectiveBlindReview::getSubmissionId, request.getSubmissionId())
                        .eq(SubjectiveBlindReview::getQuestionId, request.getQuestionId()));
        SubjectiveBlindReview first = existing.stream()
                .filter(row -> SubjectiveBlindReview.ROUND_FIRST.equals(row.getRoundType()))
                .findFirst().orElse(null);

        if (first == null) {
            // 一评：落库即止，终分保持 NULL（spec：一评后 subjective_grades.score 仍为 NULL）
            insertBlindReview(examId, request, SubjectiveBlindReview.ROUND_FIRST, graderId);
            return new BlindReviewSubmitResponse(
                    SubjectiveBlindReview.ROUND_FIRST, STATUS_PENDING_SECOND, null);
        }

        boolean secondExists = existing.stream()
                .anyMatch(row -> SubjectiveBlindReview.ROUND_SECOND.equals(row.getRoundType()));
        if (secondExists) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "该题一评二评均已完成盲评，无需重复提交");
        }
        // 同教师互斥（m1 护栏）：一评与二评必须背靠背，同一人 400 阻断
        if (Objects.equals(first.getGraderId(), graderId)) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "同一教师不能同时担任一评与二评（背靠背盲评）");
        }
        insertBlindReview(examId, request, SubjectiveBlindReview.ROUND_SECOND, graderId);

        BigDecimal diff = first.getScore().subtract(request.getScore()).abs();
        if (diff.compareTo(BLIND_TOLERANCE) <= 0) {
            BigDecimal finalScore = first.getScore().add(request.getScore())
                    .divide(TWO, 1, RoundingMode.HALF_UP);
            subjectiveGradeMapper.backfillFinalScore(gradeRow.getId(), finalScore);
            refreshSubmissionSubjectiveScore(request.getSubmissionId());
            readYourWriteMark.mark();
            return new BlindReviewSubmitResponse(SubjectiveBlindReview.ROUND_SECOND, STATUS_RESOLVED, finalScore);
        }
        // 分差超标（m2 护栏）：不回填（终分保持 NULL），流转待仲裁清单
        return new BlindReviewSubmitResponse(
                SubjectiveBlindReview.ROUND_SECOND, STATUS_ARBITRATION_PENDING, null);
    }

    /**
     * 盲评脱敏行视图（blind=true）：任何 exam:manage 教师可见（含非归属二评教师），
     * 但考生身份打码、教师评分/评语/批改人全部置空——盲评工作台只暴露「作答内容 + 我的盲评状态」。
     * 归属人的普通工作台（不带 blind 参数）走 {@link #listRows}，行为零变化。
     */
    public SubjectiveGradePageResponse listBlindRows(Long examId, Long questionId) {
        // 不做归属校验：考试存在性由快照读取兜底（无快照 404）
        paperReader.readByExamId(examId);
        List<SubjectiveGradeRow> rows = subjectiveGradeMapper.selectWorkbenchRowsPaged(
                examId, questionId, null, null, null, null, null);
        Long graderId = com.exam.auth.security.SecurityUtil.getUserId();
        Set<Long> myReviewed = blindReviewMapper.selectList(
                        Wrappers.<SubjectiveBlindReview>lambdaQuery()
                                .eq(SubjectiveBlindReview::getExamId, examId)
                                .eq(SubjectiveBlindReview::getQuestionId, questionId)
                                .eq(SubjectiveBlindReview::getGraderId, graderId)).stream()
                .map(SubjectiveBlindReview::getSubmissionId)
                .collect(Collectors.toSet());
        for (SubjectiveGradeRow row : rows) {
            row.setStudentName(BLIND_MASKED_NAME);
            row.setStudentId(null);
            row.setScore(null);
            row.setComment(null);
            row.setGraderId(null);
            row.setGradedTime(null);
            row.setVersion(null);
            row.setBlindDone(myReviewed.contains(row.getSubmissionId()));
        }
        return new SubjectiveGradePageResponse(rows, rows.size(), 0);
    }

    /**
     * 待仲裁清单（考试归属人/管理员）：一评二评齐且分差超标且终分未定的题目，
     * 并排透出一评/二评分数与评语（仲裁员是唯一可见双方信息的角色）。
     */
    public List<BlindArbitrationItem> arbitrationList(Long examId) {
        gradingQueryService.requireOwnedExam(examId);
        GradingPaper paper = paperReader.readByExamId(examId);
        Map<Long, BigDecimal> maxScoreByQuestion = paper.questions().stream()
                .collect(Collectors.toMap(GradingQuestion::questionId, GradingQuestion::score));

        List<SubjectiveBlindReview> all = blindReviewMapper.selectList(
                Wrappers.<SubjectiveBlindReview>lambdaQuery()
                        .eq(SubjectiveBlindReview::getExamId, examId));
        if (all.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, SubjectiveBlindReview>> roundBySubQ = all.stream()
                .collect(Collectors.groupingBy(row -> row.getSubmissionId() + ":" + row.getQuestionId(),
                        Collectors.toMap(SubjectiveBlindReview::getRoundType, Function.identity())));
        Map<String, SubjectiveGrade> gradeBySubQ = subjectiveGradeMapper.selectList(
                        Wrappers.<SubjectiveGrade>lambdaQuery()
                                .eq(SubjectiveGrade::getExamId, examId)).stream()
                .collect(Collectors.toMap(
                        row -> row.getSubmissionId() + ":" + row.getQuestionId(), Function.identity()));

        List<PendingPair> pending = new ArrayList<>();
        Set<Long> studentIds = new HashSet<>();
        for (Map.Entry<String, Map<String, SubjectiveBlindReview>> entry : roundBySubQ.entrySet()) {
            SubjectiveBlindReview first = entry.getValue().get(SubjectiveBlindReview.ROUND_FIRST);
            SubjectiveBlindReview second = entry.getValue().get(SubjectiveBlindReview.ROUND_SECOND);
            if (first == null || second == null) {
                continue;
            }
            SubjectiveGrade grade = gradeBySubQ.get(entry.getKey());
            if (grade == null || grade.getScore() != null) {
                continue;
            }
            BigDecimal diff = first.getScore().subtract(second.getScore()).abs();
            // 分差超标（m2 护栏）：仅 |S1-S2| > 容差且未定分进入待仲裁清单
            if (diff.compareTo(BLIND_TOLERANCE) > 0) {
                pending.add(new PendingPair(entry.getKey(), first, second, grade, diff));
                studentIds.add(grade.getStudentId());
            }
        }
        if (pending.isEmpty()) {
            return List.of();
        }
        Map<Long, String> nameByStudent = studentIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(studentIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getName));
        return pending.stream().map(pair -> {
            String[] keys = pair.key().split(":");
            long submissionId = Long.parseLong(keys[0]);
            long questionId = Long.parseLong(keys[1]);
            SubjectiveGrade grade = pair.grade();
            return new BlindArbitrationItem(submissionId, questionId, grade.getQuestionNumber(),
                    nameByStudent.get(grade.getStudentId()),
                    maxScoreByQuestion.get(questionId),
                    pair.first().getScore(), pair.first().getComment(),
                    pair.second().getScore(), pair.second().getComment(),
                    pair.diff(), STATUS_ARBITRATION_PENDING);
        }).toList();
    }

    /**
     * 仲裁终审定分（考试归属人/管理员）：记录 ARBITRATION 轮次，终分以仲裁分为准
     * 强制回填 subjective_grades.score 并刷新答卷主观总分（结案）。
     * 仅对待仲裁状态（双评齐、未定分、无仲裁记录）放行；仲裁分不得超过本题满分。
     */
    @Transactional(rollbackFor = Exception.class)
    public BlindArbitrateResponse arbitrate(Long examId, BlindArbitrateRequest request) {
        gradingQueryService.requireOwnedExam(examId);
        GradingPaper paper = paperReader.readByExamId(examId);
        requireGradableShortAnswer(paper, request.getQuestionId(), request.getScore());
        GradingSubmission submission = gradingSubmissionMapper.selectById(request.getSubmissionId());
        if (submission == null || !submission.getExamId().equals(examId)) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "答卷不存在或不属于该考试");
        }
        SubjectiveGrade gradeRow = subjectiveGradeMapper.selectOne(
                Wrappers.<SubjectiveGrade>lambdaQuery()
                        .eq(SubjectiveGrade::getSubmissionId, request.getSubmissionId())
                        .eq(SubjectiveGrade::getQuestionId, request.getQuestionId()));
        if (gradeRow == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "该答卷本题尚无批改行，请先运行判分");
        }
        List<SubjectiveBlindReview> existing = blindReviewMapper.selectList(
                Wrappers.<SubjectiveBlindReview>lambdaQuery()
                        .eq(SubjectiveBlindReview::getSubmissionId, request.getSubmissionId())
                        .eq(SubjectiveBlindReview::getQuestionId, request.getQuestionId()));
        boolean hasFirst = existing.stream()
                .anyMatch(row -> SubjectiveBlindReview.ROUND_FIRST.equals(row.getRoundType()));
        boolean hasSecond = existing.stream()
                .anyMatch(row -> SubjectiveBlindReview.ROUND_SECOND.equals(row.getRoundType()));
        boolean hasArbitration = existing.stream()
                .anyMatch(row -> SubjectiveBlindReview.ROUND_ARBITRATION.equals(row.getRoundType()));
        if (!hasFirst || !hasSecond || hasArbitration || gradeRow.getScore() != null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "该题不在待仲裁状态");
        }

        Long arbitratorId = com.exam.auth.security.SecurityUtil.getUserId();
        SubjectiveBlindReview arbitration = new SubjectiveBlindReview();
        arbitration.setExamId(examId);
        arbitration.setSubmissionId(request.getSubmissionId());
        arbitration.setQuestionId(request.getQuestionId());
        arbitration.setRoundType(SubjectiveBlindReview.ROUND_ARBITRATION);
        arbitration.setGraderId(arbitratorId);
        arbitration.setScore(request.getScore());
        arbitration.setComment(request.getComment());
        try {
            blindReviewMapper.insert(arbitration);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResponseCode.STATE_CONFLICT, "仲裁记录已存在，请刷新后重试");
        }
        // 仲裁分回填终分（m3 护栏）：终审口径强制落 subjective_grades.score
        subjectiveGradeMapper.backfillFinalScore(gradeRow.getId(), request.getScore());
        refreshSubmissionSubjectiveScore(request.getSubmissionId());
        readYourWriteMark.mark();
        return new BlindArbitrateResponse(STATUS_ARBITRATED, request.getScore());
    }

    /** 盲评轮次落库（一轮一条由 uk_blind_review 兜底，并发重复提交转 409）。 */
    private void insertBlindReview(Long examId, BlindReviewSubmitRequest request,
                                   String roundType, Long graderId) {
        SubjectiveBlindReview row = new SubjectiveBlindReview();
        row.setExamId(examId);
        row.setSubmissionId(request.getSubmissionId());
        row.setQuestionId(request.getQuestionId());
        row.setRoundType(roundType);
        row.setGraderId(graderId);
        row.setScore(request.getScore());
        row.setComment(request.getComment());
        try {
            blindReviewMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResponseCode.STATE_CONFLICT, "该轮盲评已存在，请刷新后重试");
        }
    }

    /**
     * 盲评/仲裁共用的题目校验（对齐 saveScore 既有口径，不改其方法体）：
     * 题目属于考试快照、为简答题、分值不超满分（快照锁定后分值即终局口径）。
     */
    private GradingQuestion requireGradableShortAnswer(GradingPaper paper, Long questionId, BigDecimal score) {
        GradingQuestion question = paper.questions().stream()
                .filter(q -> q.questionId().equals(questionId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResponseCode.NOT_FOUND, "题目不属于该考试"));
        if (question.type() != com.exam.question.entity.QuestionType.SHORT_ANSWER) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "仅简答题支持人工批改");
        }
        if (score.compareTo(question.score()) > 0) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "批改分数不得超过本题满分 " + question.score().stripTrailingZeros().toPlainString());
        }
        return question;
    }

    /** 待仲裁装配中间态：subQ 键 + 一评/二评/批改行/分差。 */
    private record PendingPair(String key, SubjectiveBlindReview first, SubjectiveBlindReview second,
                               SubjectiveGrade grade, BigDecimal diff) {
    }
}
