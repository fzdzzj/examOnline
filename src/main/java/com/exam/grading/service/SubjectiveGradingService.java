package com.exam.grading.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.ReadYourWriteMark;
import com.exam.grading.dto.SubjectiveGradePageResponse;
import com.exam.grading.dto.SubjectiveGradeRow;
import com.exam.grading.dto.SubjectiveQuestionItem;
import com.exam.grading.dto.SubjectiveScoreRequest;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.support.GradingPaperReader;
import com.exam.submission.entity.ExamSubmission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    private final ReadYourWriteMark readYourWriteMark;

    public SubjectiveGradingService(GradingQueryService gradingQueryService,
                                    GradingPaperReader paperReader,
                                    SubjectiveGradeMapper subjectiveGradeMapper,
                                    GradingSubmissionMapper gradingSubmissionMapper,
                                    ReadYourWriteMark readYourWriteMark) {
        this.gradingQueryService = gradingQueryService;
        this.paperReader = paperReader;
        this.subjectiveGradeMapper = subjectiveGradeMapper;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
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
            subjectiveGradeMapper.insert(row);
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
        return subjectiveGradeMapper.selectWorkbenchRows(examId, request.getQuestionId()).stream()
                .filter(candidate -> candidate.getSubmissionId().equals(request.getSubmissionId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResponseCode.INTERNAL_ERROR, "批改结果回读失败"));
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
}
