package com.exam.score.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.model.GradingQuestion;
import com.exam.question.entity.Question;
import com.exam.question.entity.QuestionType;
import com.exam.question.mapper.QuestionMapper;
import com.exam.score.dto.ExamReviewQuestionItem;
import com.exam.score.dto.ExamReviewResponse;
import com.exam.score.dto.ExamWrongQuestionsGroup;
import com.exam.score.dto.WrongQuestionItem;
import com.exam.score.dto.WrongQuestionPageResponse;
import com.exam.score.support.QuestionScoreResolver;
import com.exam.submission.entity.ExamSubmission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 学生错题本与单场逐题回顾服务（独立 Service，对齐 MakeupScoreService 独立类先例）：
 *
 * <p>核心约束（前案硬伤收口）：
 * <ul>
 *   <li>逐题判分同源：唯一事实源为 {@link ScoreExportService#getPersonalReport(Exam, Long)} 现场重算，
 *       绝不另造第二套判分口径；</li>
 *   <li>考试粒度分页：仅对页内 N 场（N &le; 10）已发布考试调用重算，严禁全量重算后内存分页；</li>
 *   <li>语义隔离：analysis 严格取自题库实体 {@code questions.analysis}，非判分依据 detail；</li>
 *   <li>错题判定：得分 &lt; 满分（含零分与多选/主观题部分得分）；</li>
 *   <li>发布门控：未发布考试统一拒绝，与 /api/scores/my 门控口径严格一致。</li>
 * </ul>
 */
@Slf4j
@Service
public class StudentWrongQuestionService {

    private final ExamMapper examMapper;
    private final GradingSubmissionMapper gradingSubmissionMapper;
    private final QuestionMapper questionMapper;
    private final ScoreExportService scoreExportService;

    public StudentWrongQuestionService(ExamMapper examMapper,
                                       GradingSubmissionMapper gradingSubmissionMapper,
                                       QuestionMapper questionMapper,
                                       ScoreExportService scoreExportService) {
        this.examMapper = examMapper;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.questionMapper = questionMapper;
        this.scoreExportService = scoreExportService;
    }

    /**
     * 查询学生错题本列表（按已发布考试粒度分页，每页最多 10 场考试组）。
     */
    public WrongQuestionPageResponse listWrongQuestions(Long studentId, int page, int size) {
        if (size <= 0 || size > 10) {
            size = 10;
        }
        if (page <= 0) {
            page = 1;
        }

        // 1. 查询该学生所有已批改且已产生总分的答卷
        List<GradingSubmission> submissions = gradingSubmissionMapper.selectList(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getStudentId, studentId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED)
                        .isNotNull(GradingSubmission::getTotalScore)
                        .select(GradingSubmission::getExamId));

        if (submissions.isEmpty()) {
            WrongQuestionPageResponse emptyResp = new WrongQuestionPageResponse();
            emptyResp.setTotal(0);
            emptyResp.setPage(page);
            emptyResp.setSize(size);
            emptyResp.setGroups(Collections.emptyList());
            return emptyResp;
        }

        List<Long> examIds = submissions.stream()
                .map(GradingSubmission::getExamId)
                .distinct()
                .toList();

        // 2. 批量查出对应考试并过滤出已发布考试（与 myScore / requirePublishedRoot 同门控口径）
        List<Exam> exams = examMapper.selectBatchIds(examIds);
        List<Exam> publishedExams = exams.stream()
                .filter(this::isExamPublished)
                .sorted(Comparator.comparing(Exam::getStartTime, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Exam::getId, Comparator.reverseOrder()))
                .toList();

        int totalExams = publishedExams.size();
        int fromIndex = (page - 1) * size;
        if (fromIndex >= totalExams) {
            WrongQuestionPageResponse resp = new WrongQuestionPageResponse();
            resp.setTotal(totalExams);
            resp.setPage(page);
            resp.setSize(size);
            resp.setGroups(Collections.emptyList());
            return resp;
        }

        int toIndex = Math.min(fromIndex + size, totalExams);
        List<Exam> pageExams = publishedExams.subList(fromIndex, toIndex);

        // 3. 仅对当前页内的 N 场考试做 PersonalReport 现场重算与错题归集
        List<ExamWrongQuestionsGroup> groups = new ArrayList<>();
        for (Exam exam : pageExams) {
            ScoreExportService.PersonalReport report;
            try {
                report = scoreExportService.getPersonalReport(exam, studentId);
            } catch (Exception e) {
                log.warn("错题本加载个人成绩单异常，跳过考试 {}: {}", exam.getId(), e.getMessage());
                continue;
            }

            if (report == null || report.questions() == null) {
                continue;
            }

            // 批量查询当前考试题目的 analysis 字段
            List<Long> qIds = report.questions().stream().map(GradingQuestion::questionId).toList();
            Map<Long, Question> questionMap = loadQuestionMap(qIds);

            List<WrongQuestionItem> wrongItems = new ArrayList<>();
            for (GradingQuestion q : report.questions()) {
                QuestionScoreResolver.ResolvedQuestionScore rs = report.scores().get(q.questionId());
                if (rs == null || rs.score() == null || rs.max() == null) {
                    continue;
                }

                // 错题判定：得分严格小于满分（包括 0 分以及部分对的情况）
                if (rs.score().compareTo(rs.max()) < 0) {
                    WrongQuestionItem item = new WrongQuestionItem();
                    item.setExamId(exam.getId());
                    item.setExamTitle(exam.getTitle());
                    item.setExamTime(exam.getStartTime());
                    item.setQuestionId(q.questionId());
                    item.setQuestionNumber(q.number());
                    item.setQuestionType(labelOf(q.type()));
                    item.setQuestionContent(q.content());
                    item.setChoices(q.choices());

                    // 学生答案与正确答案真实映射（非空串占位）
                    String myAns = report.answers() != null ? report.answers().get(q.questionId()) : null;
                    item.setMyAnswer(myAns != null ? myAns : "");
                    item.setCorrectAnswer(q.correctAnswer() != null ? q.correctAnswer() : "");

                    item.setMyScore(rs.score());
                    item.setFullScore(rs.max());

                    // analysis 严格取自题库实体
                    Question entity = questionMap.get(q.questionId());
                    item.setAnalysis(entity != null && entity.getAnalysis() != null ? entity.getAnalysis() : null);

                    // 判分依据独立映射
                    item.setScoreDetail(rs.comment() != null ? rs.comment() : rs.detail());

                    wrongItems.add(item);
                }
            }

            ExamWrongQuestionsGroup group = new ExamWrongQuestionsGroup();
            group.setExamId(exam.getId());
            group.setExamTitle(exam.getTitle());
            group.setExamTime(exam.getStartTime());
            group.setWrongQuestions(wrongItems);
            groups.add(group);
        }

        WrongQuestionPageResponse response = new WrongQuestionPageResponse();
        response.setTotal(totalExams);
        response.setPage(page);
        response.setSize(size);
        response.setGroups(groups);
        return response;
    }

    /**
     * 单场考试逐题回顾（卷面全部题目，包括答对与答错题）。
     */
    public ExamReviewResponse reviewExam(Long examId, Long studentId) {
        // 1. 发布门控校验（与 myScore / requirePublishedRoot 保持一致）
        Exam exam = requirePublishedRoot(examId);

        // 2. 本人成绩记录校验：未参加或未批改完成抛 404
        GradingSubmission submission = gradingSubmissionMapper.selectOne(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStudentId, studentId));

        if (submission == null || submission.getTotalScore() == null
                || !Objects.equals(submission.getStatus(), ExamSubmission.STATUS_GRADED)) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "暂无本人成绩记录");
        }

        // 3. 现场重算与装配
        ScoreExportService.PersonalReport report = scoreExportService.getPersonalReport(exam, studentId);
        List<Long> qIds = report.questions().stream().map(GradingQuestion::questionId).toList();
        Map<Long, Question> questionMap = loadQuestionMap(qIds);

        List<ExamReviewQuestionItem> questionItems = new ArrayList<>();
        for (GradingQuestion q : report.questions()) {
            QuestionScoreResolver.ResolvedQuestionScore rs = report.scores().get(q.questionId());
            ExamReviewQuestionItem item = new ExamReviewQuestionItem();
            item.setQuestionId(q.questionId());
            item.setQuestionNumber(q.number());
            item.setQuestionType(labelOf(q.type()));
            item.setQuestionContent(q.content());
            item.setChoices(q.choices());

            String myAns = report.answers() != null ? report.answers().get(q.questionId()) : null;
            item.setMyAnswer(myAns != null ? myAns : "");
            item.setCorrectAnswer(q.correctAnswer() != null ? q.correctAnswer() : "");

            if (rs != null) {
                item.setMyScore(rs.score());
                item.setFullScore(rs.max());
                item.setGraded(rs.graded());
                item.setComment(rs.comment());
                item.setScoreDetail(rs.detail());
            } else {
                item.setMyScore(BigDecimal.ZERO);
                item.setFullScore(q.score());
                item.setGraded(false);
            }

            Question entity = questionMap.get(q.questionId());
            item.setAnalysis(entity != null && entity.getAnalysis() != null ? entity.getAnalysis() : null);

            questionItems.add(item);
        }

        ExamReviewResponse response = new ExamReviewResponse();
        response.setExamId(exam.getId());
        response.setExamTitle(exam.getTitle());
        response.setExamTime(exam.getStartTime());
        response.setStudentName(report.studentName());
        response.setObjectiveScore(report.objective());
        response.setSubjectiveScore(report.subjective());
        response.setTotalScore(report.total());
        response.setRank(report.rank());
        response.setPartialGraded(report.partial());
        response.setQuestions(questionItems);
        return response;
    }

    // ==================== 辅助方法 ====================

    /** 题目解析批量加载（防软删丢失 fallback 为空）。 */
    private Map<Long, Question> loadQuestionMap(List<Long> qIds) {
        if (qIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return questionMapper.selectBatchIds(qIds).stream()
                .collect(Collectors.toMap(Question::getId, Function.identity(), (a, b) -> a));
    }

    /**
     * 判断考试是否已发布（沿 parent_exam_id 找回主考，主考必须 STATUS_PUBLISHED）。
     */
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
        return root.getStatus() != null && root.getStatus() == Exam.STATUS_PUBLISHED;
    }

    /**
     * 强校验主考已发布，未发布抛 400「成绩待发布」，考试不存在抛 404。
     */
    private Exam requirePublishedRoot(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        Exam root = exam;
        while (root.getParentExamId() != null) {
            Exam parent = examMapper.selectById(root.getParentExamId());
            if (parent == null) {
                break;
            }
            root = parent;
        }
        if (root.getStatus() == null || root.getStatus() != Exam.STATUS_PUBLISHED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "成绩待发布");
        }
        return exam;
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
        };
    }
}
