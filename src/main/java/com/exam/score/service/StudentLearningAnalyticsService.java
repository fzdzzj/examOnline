package com.exam.score.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.clazz.entity.ClassEntity;
import com.exam.clazz.service.ClassService;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.model.GradingQuestion;
import com.exam.question.entity.Question;
import com.exam.question.entity.QuestionTag;
import com.exam.question.entity.QuestionType;
import com.exam.question.mapper.QuestionMapper;
import com.exam.question.mapper.QuestionTagMapper;
import com.exam.question.mapper.TagMapper;
import com.exam.score.dto.ClassLearningAnalyticsResponse;
import com.exam.score.dto.RecommendedPracticeItem;
import com.exam.score.dto.StudentLearningAnalyticsResponse;
import com.exam.score.dto.StudentLearningAnalyticsResponse.Overview;
import com.exam.score.dto.StudentLearningAnalyticsResponse.TagMasteryItem;
import com.exam.score.dto.StudentLearningAnalyticsResponse.TrendItem;
import com.exam.score.support.QuestionScoreResolver;
import com.exam.submission.entity.ExamSubmission;
import com.exam.question.entity.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 学习分析服务（spec add-student-learning-analytics，零 DDL）：
 * <ul>
 *   <li>{@link #getStudentAnalytics(Long)}：学生跨考试学情画像（总览/趋势/知识点掌握度/薄弱点）；</li>
 *   <li>{@link #getRecommendedPractice(Long, Integer)}：基于最薄弱知识点从题库靶向推荐机判客观题；</li>
 *   <li>{@link #getClassAnalytics(Long, Long)}：教师所辖班级跨考试综合学情（强校验班级所有权）。</li>
 * </ul>
 *
 * <p>掌握度口径：某知识点累计得分 / 累计题目分值 ×100%。答题记录以学生实际参加的
 * <b>已发布</b>考试为准（{@code status >= STATUS_PUBLISHED}），逐题判分复用
 * {@link ScoreExportService#getPersonalReport}（判分同源，绝不另造第二套得分口径）。
 * 薄弱 = 掌握度 &lt; 75%，薄弱点按掌握度升序取 Top 5。
 *
 * <p><b>推荐题目「启用状态」口径</b>：questions 表无 de-facto `status` 列（零 DDL），
 * 推荐仅拉取题库中未逻辑删除（@TableLogic 自动过滤 is_deleted=0）的机判客观题，即等价于
 * spec「status=1、is_deleted=0」的启用态；已消灭题目通过重练进度排除。
 */
@Slf4j
@Service
public class StudentLearningAnalyticsService {

    /** 薄弱判定阈值：掌握度低于该值标为薄弱点。 */
    static final BigDecimal WEAK_THRESHOLD = new BigDecimal("0.75");

    /** 薄弱点返回上限。 */
    static final int WEAK_TAG_LIMIT = 5;

    /** 推荐条数默认值与上限。 */
    static final int RECOMMEND_DEFAULT_LIMIT = 5;
    static final int RECOMMEND_MAX_LIMIT = 10;

    /** 机判客观题型（可自动判分，推荐候选）。 */
    private static final Set<QuestionType> RECOMMENDABLE_TYPES = Set.of(
            QuestionType.SINGLE, QuestionType.MULTIPLE, QuestionType.JUDGE, QuestionType.FILL_BLANK);

    private final ExamMapper examMapper;
    private final GradingSubmissionMapper gradingSubmissionMapper;
    private final ScoreExportService scoreExportService;
    private final ClassService classService;
    private final QuestionMapper questionMapper;
    private final QuestionTagMapper questionTagMapper;
    private final TagMapper tagMapper;
    private final StudentPracticeService studentPracticeService;

    public StudentLearningAnalyticsService(ExamMapper examMapper,
                                           GradingSubmissionMapper gradingSubmissionMapper,
                                           ScoreExportService scoreExportService,
                                           ClassService classService,
                                           QuestionMapper questionMapper,
                                           QuestionTagMapper questionTagMapper,
                                           TagMapper tagMapper,
                                           StudentPracticeService studentPracticeService) {
        this.examMapper = examMapper;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.scoreExportService = scoreExportService;
        this.classService = classService;
        this.questionMapper = questionMapper;
        this.questionTagMapper = questionTagMapper;
        this.tagMapper = tagMapper;
        this.studentPracticeService = studentPracticeService;
    }

    // ==================== 学生学情画像 ====================

    /**
     * 聚合学生跨考试学情画像。零已发布考试/零答卷时优雅返回参测 0 的空态对象，
     * 绝不抛 500/404。
     */
    public StudentLearningAnalyticsResponse getStudentAnalytics(Long studentId) {
        List<Exam> publishedExams = loadPublishedExams(studentId);
        StudentLearningAnalyticsResponse response = new StudentLearningAnalyticsResponse();

        Overview overview = new Overview();
        overview.setTotalExams(0);
        List<TrendItem> trend = new ArrayList<>();
        Map<Long, TagAccumulator> tagAcc = new HashMap<>();

        List<BigDecimal> totals = new ArrayList<>();
        for (Exam exam : publishedExams) {
            ScoreExportService.PersonalReport report = loadReportSafely(exam, studentId);
            if (report == null || report.scores() == null || report.total() == null) {
                continue;
            }
            // 趋势行
            TrendItem item = new TrendItem();
            item.setExamId(exam.getId());
            item.setExamTitle(exam.getTitle());
            item.setExamTime(exam.getStartTime());
            item.setStudentScore(report.total());
            item.setTotalScore(examTotalOf(report, exam));
            item.setScoreRate(rateOf(report.total(), item.getTotalScore()));
            trend.add(item);
            totals.add(report.total());

            // 知识点掌握度累计
            accumulateTagScores(tagAcc, report.questions(), report.scores());
        }

        overview.setTotalExams(trend.size());
        if (!totals.isEmpty()) {
            BigDecimal sum = totals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            overview.setAverageScore(sum.divide(BigDecimal.valueOf(totals.size()),
                    2, RoundingMode.HALF_UP));
            BigDecimal max = totals.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            BigDecimal min = totals.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            overview.setHighestScore(max);
            overview.setLowestScore(min);
            // 及格率：得分 ≥ 该场总分 ×60% 记为及格（按场次独立判定）
            long pass = 0;
            for (int i = 0; i < trend.size(); i++) {
                TrendItem t = trend.get(i);
                if (t.getTotalScore() != null && BigDecimal.ZERO.compareTo(t.getTotalScore()) < 0
                        && t.getStudentScore() != null
                        && t.getStudentScore().compareTo(t.getTotalScore().multiply(
                                new BigDecimal("0.60"))) >= 0) {
                    pass++;
                }
            }
            overview.setPassRate((double) pass / trend.size());
        }

        List<TagMasteryItem> tagMastery = toTagMasteryList(tagAcc);
        response.setOverview(overview);
        response.setTrend(trend);
        response.setTagMastery(tagMastery);
        response.setWeakTags(weakTagsOf(tagMastery));
        return response;
    }

    // ==================== 靶向练习推荐 ====================

    /**
     * 基于学生最薄弱知识点从题库推荐机判客观题，排除错题重练中已消灭题目，
     * 返回带推荐原因（知识点名称 + 当前薄弱掌握度）的题目列表，条数 ≤ limit（默认 5，上限 10）。
     * 无可推荐题目时优雅返回空列表。
     */
    public List<RecommendedPracticeItem> getRecommendedPractice(Long studentId, Integer limit) {
        int capped = limit == null ? RECOMMEND_DEFAULT_LIMIT
                : Math.max(1, Math.min(limit, RECOMMEND_MAX_LIMIT));

        StudentLearningAnalyticsResponse analytics = getStudentAnalytics(studentId);
        List<TagMasteryItem> weakTags = analytics.getWeakTags();
        if (weakTags == null || weakTags.isEmpty()) {
            return Collections.emptyList();
        }
        // 最薄弱知识点 = 掌握度最低（weakTags 已按掌握度升序）
        TagMasteryItem weakest = weakTags.get(0);

        // 检索该标签下的题目（question_tags → questions，@TableLogic 自动过滤 is_deleted=0）
        List<Long> questionIds = questionTagMapper.selectList(
                        Wrappers.<QuestionTag>lambdaQuery().eq(QuestionTag::getTagId, weakest.getTagId()))
                .stream().map(QuestionTag::getQuestionId).distinct().toList();
        if (questionIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Question> questions = questionMapper.selectBatchIds(questionIds).stream()
                .filter(q -> q != null && isRecommendableType(q.getType()))
                .sorted(Comparator.comparing(Question::getId))
                .toList();
        if (questions.isEmpty()) {
            return Collections.emptyList();
        }

        // 排除错题重练中已消灭的题目
        Set<Long> mastered = masteredQuestionIds(studentId, questions.stream()
                .map(Question::getId).toList());

        List<RecommendedPracticeItem> result = new ArrayList<>();
        for (Question q : questions) {
            if (result.size() >= capped) {
                break;
            }
            if (mastered.contains(q.getId())) {
                continue;
            }
            RecommendedPracticeItem item = new RecommendedPracticeItem();
            item.setQuestionId(q.getId());
            item.setStem(q.getContent());
            item.setType(labelOf(QuestionType.of(q.getType())));
            item.setScore(q.getScore());
            item.setDifficulty(q.getDifficulty());
            item.setTagId(weakest.getTagId());
            item.setTagName(weakest.getTagName());
            item.setTagMasteryRate(weakest.getMasteryRate());
            item.setRecommendReason(String.format(
                    "「%s」掌握度 %.1f%%，当前为你的薄弱点，建议针对性巩固",
                    weakest.getTagName() == null ? "该知识点" : weakest.getTagName(),
                    weakest.getMasteryRate() == null ? 0 : weakest.getMasteryRate() * 100));
            result.add(item);
        }
        return result;
    }

    // ==================== 班级学情 ====================

    /**
     * 教师查询所辖班级跨考试综合学情。班级所有权强校验（复用 getOwnedClass：
     * 不存在 404 / 非归属且非 ADMIN 403），防止水平越权读取他人班级。
     */
    public ClassLearningAnalyticsResponse getClassAnalytics(Long teacherId, Long classId) {
        // 强校验班级所有权（404/403），水平越权在此拦截
        ClassEntity clazz = classService.getOwnedClass(classId);
        List<Long> studentIds = classService.listStudentIds(classId);

        List<Exam> publishedExams = loadClassPublishedExams(classId);
        int examCount = publishedExams.size();

        ClassLearningAnalyticsResponse response = new ClassLearningAnalyticsResponse();
        response.setClassId(clazz.getId());
        response.setClassName(clazz.getName());
        response.setStudentCount(studentIds.size());
        response.setExamCount(examCount);

        List<ClassLearningAnalyticsResponse.ClassTrendItem> trend = new ArrayList<>();
        Map<Long, TagAccumulator> tagAcc = new HashMap<>();

        for (Exam exam : publishedExams) {
            List<BigDecimal> classTotals = new ArrayList<>();
            for (Long studentId : studentIds) {
                ScoreExportService.PersonalReport report = loadReportSafely(exam, studentId);
                if (report == null || report.scores() == null || report.total() == null) {
                    continue;
                }
                classTotals.add(report.total());
                accumulateTagScores(tagAcc, report.questions(), report.scores());
            }
            if (!classTotals.isEmpty()) {
                ClassLearningAnalyticsResponse.ClassTrendItem item =
                        new ClassLearningAnalyticsResponse.ClassTrendItem();
                item.setExamId(exam.getId());
                item.setExamTitle(exam.getTitle());
                item.setExamTime(exam.getStartTime());
                item.setAverageScore(classTotals.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(classTotals.size()), 2, RoundingMode.HALF_UP).doubleValue());
                trend.add(item);
            }
        }

        List<ClassLearningAnalyticsResponse.ClassTagMasteryItem> tagMastery = toClassTagMastery(tagAcc);

        response.setTrend(trend);
        response.setTagMastery(tagMastery);
        response.setWeakTags(weakClassTagsOf(tagMastery));
        return response;
    }

    // ==================== 知识点聚合 ====================

    /** 每标签累计器（跨考试/跨学生累计可得总分与实际得分）。 */
    private static final class TagAccumulator {
        int questionCount;
        BigDecimal available = BigDecimal.ZERO;
        BigDecimal achieved = BigDecimal.ZERO;

        void add(BigDecimal max, BigDecimal score) {
            questionCount++;
            if (max != null) {
                available = available.add(max);
            }
            if (score != null) {
                achieved = achieved.add(score);
            }
        }
    }

    /**
     * 把一份成绩单的逐题得分按题目标签累计到 tagAcc。
     * 一题多标签时，该题的得分/分值计入每个标签（与单场分析报告知识点口径一致）。
     */
    private void accumulateTagScores(Map<Long, TagAccumulator> tagAcc,
                                     List<GradingQuestion> questions,
                                     Map<Long, QuestionScoreResolver.ResolvedQuestionScore> scores) {
        if (questions == null || questions.isEmpty() || scores == null || scores.isEmpty()) {
            return;
        }
        List<Long> questionIds = questions.stream()
                .map(GradingQuestion::questionId).filter(Objects::nonNull).toList();
        if (questionIds.isEmpty()) {
            return;
        }
        Map<Long, List<QuestionTag>> tagsByQuestion = questionTagMapper.selectList(
                        Wrappers.<QuestionTag>lambdaQuery().in(QuestionTag::getQuestionId, questionIds))
                .stream().collect(Collectors.groupingBy(QuestionTag::getQuestionId));
        for (GradingQuestion q : questions) {
            if (q.questionId() == null) {
                continue;
            }
            QuestionScoreResolver.ResolvedQuestionScore rs = scores.get(q.questionId());
            if (rs == null) {
                continue;
            }
            List<QuestionTag> links = tagsByQuestion.getOrDefault(q.questionId(), List.of());
            for (QuestionTag link : links) {
                tagAcc.computeIfAbsent(link.getTagId(), k -> new TagAccumulator())
                        .add(rs.max(), rs.score());
            }
        }
    }

    /** 汇总为掌握度列表（按掌握度升序，同掌握度按 tagId 升序，便于稳定输出）。 */
    private List<TagMasteryItem> toTagMasteryList(Map<Long, TagAccumulator> tagAcc) {
        if (tagAcc.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> tagIds = new ArrayList<>(tagAcc.keySet());
        Map<Long, Tag> tagById = loadTags(tagIds);
        List<TagMasteryItem> items = new ArrayList<>();
        for (Map.Entry<Long, TagAccumulator> entry : tagAcc.entrySet()) {
            TagAccumulator acc = entry.getValue();
            Tag tag = tagById.get(entry.getKey());
            TagMasteryItem item = new TagMasteryItem();
            item.setTagId(entry.getKey());
            item.setTagName(tag == null ? "未知知识点" : tag.getName());
            item.setQuestionCount(acc.questionCount);
            item.setTotalAvailableScore(acc.available);
            item.setStudentAchievedScore(acc.achieved);
            Double rate = rateOf(acc.achieved, acc.available);
            item.setMasteryRate(rate);
            item.setWeak(rate != null && BigDecimal.valueOf(rate).compareTo(WEAK_THRESHOLD) < 0);
            items.add(item);
        }
        items.sort(Comparator
                .comparingDouble((TagMasteryItem t) -> t.getMasteryRate() == null ? 0 : t.getMasteryRate())
                .thenComparing(TagMasteryItem::getTagId));
        return items;
    }

    /** 薄弱点：掌握度 &lt; 75% 且升序取 Top 5。 */
    private List<TagMasteryItem> weakTagsOf(List<TagMasteryItem> tagMastery) {
        if (tagMastery == null || tagMastery.isEmpty()) {
            return Collections.emptyList();
        }
        return tagMastery.stream()
                .filter(t -> Boolean.TRUE.equals(t.getWeak()))
                .limit(WEAK_TAG_LIMIT)
                .toList();
    }

    /** 班级维度掌握度列表（仅 tagId/tagName/masteryRate 三字段）。 */
    private List<ClassLearningAnalyticsResponse.ClassTagMasteryItem> toClassTagMastery(Map<Long, TagAccumulator> tagAcc) {
        if (tagAcc.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> tagIds = new ArrayList<>(tagAcc.keySet());
        Map<Long, Tag> tagById = loadTags(tagIds);
        List<ClassLearningAnalyticsResponse.ClassTagMasteryItem> items = new ArrayList<>();
        for (Map.Entry<Long, TagAccumulator> entry : tagAcc.entrySet()) {
            TagAccumulator acc = entry.getValue();
            Tag tag = tagById.get(entry.getKey());
            ClassLearningAnalyticsResponse.ClassTagMasteryItem item =
                    new ClassLearningAnalyticsResponse.ClassTagMasteryItem();
            item.setTagId(entry.getKey());
            item.setTagName(tag == null ? "未知知识点" : tag.getName());
            item.setMasteryRate(rateOf(acc.achieved, acc.available));
            items.add(item);
        }
        items.sort(Comparator
                .comparingDouble((ClassLearningAnalyticsResponse.ClassTagMasteryItem t) ->
                        t.getMasteryRate() == null ? 0 : t.getMasteryRate())
                .thenComparing(ClassLearningAnalyticsResponse.ClassTagMasteryItem::getTagId));
        return items;
    }

    /** 班级薄弱点：掌握度 &lt; 75% 且升序取 Top 5。 */
    private List<ClassLearningAnalyticsResponse.ClassTagMasteryItem> weakClassTagsOf(
            List<ClassLearningAnalyticsResponse.ClassTagMasteryItem> tagMastery) {
        if (tagMastery == null || tagMastery.isEmpty()) {
            return Collections.emptyList();
        }
        return tagMastery.stream()
                .filter(t -> t.getMasteryRate() != null
                        && BigDecimal.valueOf(t.getMasteryRate()).compareTo(WEAK_THRESHOLD) < 0)
                .limit(WEAK_TAG_LIMIT)
                .toList();
    }

    // ==================== 查询辅助 ====================

    /** 加载该学生已交卷、已发布且已汇总成绩的考试（口径同错题本/重练池）。 */
    private List<Exam> loadPublishedExams(Long studentId) {
        List<GradingSubmission> submissions = gradingSubmissionMapper.selectList(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getStudentId, studentId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED)
                        .isNotNull(GradingSubmission::getTotalScore)
                        .select(GradingSubmission::getExamId));
        if (submissions.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> examIds = submissions.stream()
                .map(GradingSubmission::getExamId).distinct().toList();
        return examMapper.selectBatchIds(examIds).stream()
                .filter(this::isExamPublished)
                .sorted(Comparator.comparing(Exam::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Exam::getId, Comparator.naturalOrder()))
                .toList();
    }

    /** 加载某班级全部已发布考试（容纳该班所有学生历史的跨考试聚合）。 */
    private List<Exam> loadClassPublishedExams(Long classId) {
        List<Exam> exams = examMapper.selectList(
                Wrappers.<Exam>lambdaQuery().eq(Exam::getClassId, classId));
        if (exams.isEmpty()) {
            return Collections.emptyList();
        }
        return exams.stream()
                .filter(this::isExamPublished)
                .sorted(Comparator.comparing(Exam::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Exam::getId, Comparator.naturalOrder()))
                .toList();
    }

    private boolean isExamPublished(Exam exam) {
        if (exam == null) {
            return false;
        }
        Exam root = exam;
        while (root.getParentExamId() != null) {
            Exam parent = examMapper.selectById(root.getParentExamId());
            if (parent == null) {
                break;
            }
            root = parent;
        }
        return root.getStatus() != null && root.getStatus() >= Exam.STATUS_PUBLISHED;
    }

    /** 安全装配个人成绩单：单场异常（如答卷缺失）跳过不阻断整体聚合。 */
    private ScoreExportService.PersonalReport loadReportSafely(Exam exam, Long studentId) {
        try {
            return scoreExportService.getPersonalReport(exam, studentId);
        } catch (Exception e) {
            log.warn("学情聚合装配个人成绩单异常，跳过 考试={} 学生={}: {}", exam.getId(), studentId, e.getMessage());
            return null;
        }
    }

    /** 个人成绩单可用总分：判分报告未携带总分线时退回考试快照总分语义（趋势用）。 */
    private BigDecimal examTotalOf(ScoreExportService.PersonalReport report, Exam exam) {
        // report.total() 是该生总分，试卷满分需从逐题 max 求和（快照锁定的分值）
        BigDecimal sum = BigDecimal.ZERO;
        if (report.scores() != null && report.questions() != null) {
            for (GradingQuestion q : report.questions()) {
                QuestionScoreResolver.ResolvedQuestionScore rs = report.scores().get(q.questionId());
                if (rs != null && rs.max() != null) {
                    sum = sum.add(rs.max());
                }
            }
        }
        return sum;
    }

    /** 得分率（0~1）；得分或满分不可得返回 null。 */
    private Double rateOf(BigDecimal achieved, BigDecimal available) {
        if (available == null || achieved == null || available.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return achieved.divide(available, 4, RoundingMode.HALF_UP).doubleValue();
    }

    /** 批量加载标签。 */
    private Map<Long, Tag> loadTags(Collection<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return tagMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, Function.identity(), (a, b) -> a));
    }

    /** 是否为可机判客观推荐题型（单选/多选/判断/填空）。 */
    private boolean isRecommendableType(Integer type) {
        QuestionType t = QuestionType.of(type);
        return t != null && RECOMMENDABLE_TYPES.contains(t);
    }

    /** 计算该学生在给定题目上的已消灭集合（错题重练连对 3 次视为已消灭，推荐排除）。 */
    private Set<Long> masteredQuestionIds(Long studentId, List<Long> questionIds) {
        if (questionIds.isEmpty()) {
            return Collections.emptySet();
        }
        Map<Long, StudentPracticeService.PracticeProgress> progress =
                studentPracticeService.calculateProgressMap(studentId, questionIds);
        Set<Long> mastered = new HashSet<>();
        for (Map.Entry<Long, StudentPracticeService.PracticeProgress> e : progress.entrySet()) {
            if (e.getValue().mastered()) {
                mastered.add(e.getKey());
            }
        }
        return mastered;
    }

    private String labelOf(QuestionType type) {
        if (type == null) {
            return "未知题型";
        }
        return switch (type) {
            case SINGLE -> "单选";
            case MULTIPLE -> "多选";
            case JUDGE -> "判断";
            case SHORT_ANSWER -> "简答";
            case FILL_BLANK -> "填空";
        };
    }
}