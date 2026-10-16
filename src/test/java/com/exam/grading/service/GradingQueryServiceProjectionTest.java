package com.exam.grading.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.dto.GradingProgressResponse;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.submission.entity.ExamSubmission;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 判分进度两处统计查询窄投影单测（spec changes/optimize-practice-pool-hot-path，
 * Mockito 隔离、无 Spring 上下文，与 GradingProgressAlgorithmTest 同法驱动真实端点）：
 *
 * <ul>
 *   <li>答卷统计查询只投影计数消费列（id + grading_status），不得载 answers 等答案大列；</li>
 *   <li>主观批改行统计查询只投影统计消费列（submission_id + score），不得载 comment 等重列；</li>
 *   <li>进度计数口径与读写分离（@DS("slave")）零改动。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GradingQueryServiceProjectionTest {

    private static final Long TEACHER_ID = 7L;
    private static final Long EXAM_ID = 10L;

    @Mock
    private ExamMapper examMapper;
    @Mock
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Mock
    private SubjectiveGradeMapper subjectiveGradeMapper;

    private GradingQueryService gradingQueryService;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 环境无 MyBatis 启动流程：为 lambda 投影初始化实体元数据
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GradingSubmission.class);
        TableInfoHelper.initTableInfo(assistant, SubjectiveGrade.class);
        gradingQueryService = new GradingQueryService(examMapper, gradingSubmissionMapper, subjectiveGradeMapper);
        LoginUser teacher = new LoginUser();
        teacher.setId(TEACHER_ID);
        teacher.setRoleLevel(RoleHierarchy.levelOf(RoleHierarchy.TEACHER));
        SecurityUtil.set(teacher);
    }

    @AfterEach
    void clearSecurity() {
        SecurityUtil.clear();
    }

    @Test
    @DisplayName("答卷统计查询投影 id+grading_status，不载 answers")
    void submissionsQueryProjectsOnlyConsumedColumns() {
        stubOwnedExam();
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of());
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of());

        gradingQueryService.progress(EXAM_ID);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<GradingSubmission>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(gradingSubmissionMapper).selectList(captor.capture());
        String sqlSelect = captor.getValue().getSqlSelect();
        assertNotNull(sqlSelect, "答卷统计查询必须启用列投影（禁止载 answers 等答案大列）");
        assertEquals("id,grading_status", sqlSelect);
    }

    @Test
    @DisplayName("主观批改行统计查询投影 submission_id+score，不载 comment")
    void subjectiveRowsQueryProjectsOnlySubmissionIdAndScore() {
        stubOwnedExam();
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of());
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of());

        gradingQueryService.progress(EXAM_ID);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<SubjectiveGrade>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(subjectiveGradeMapper).selectList(captor.capture());
        String sqlSelect = captor.getValue().getSqlSelect();
        assertNotNull(sqlSelect, "主观批改行统计查询必须启用列投影（禁止载 comment 等无关重列）");
        assertEquals("submission_id,score", sqlSelect);
    }

    @Test
    @DisplayName("窄投影下进度计数口径不变（已判/失败/待判/主观已批/部分批改）")
    void progressCountsUnchangedWithNarrowProjections() {
        stubOwnedExam();
        List<GradingSubmission> submissions = List.of(
                submission(1L, ObjectiveGradingService.GRADING_OK),
                submission(2L, ObjectiveGradingService.GRADING_OK),
                submission(3L, ObjectiveGradingService.GRADING_FAILED),
                submission(4L, 0));
        List<SubjectiveGrade> rows = List.of(
                row(1L, new BigDecimal("5.0")), row(1L, null),
                row(3L, null), row(4L, null));
        when(gradingSubmissionMapper.selectList(any())).thenReturn(submissions);
        when(subjectiveGradeMapper.selectList(any())).thenReturn(rows);

        GradingProgressResponse response = gradingQueryService.progress(EXAM_ID);

        assertEquals(4, response.getSubmittedCount());
        assertEquals(2, response.getGradedCount(), "grading_status=1 两卷");
        assertEquals(1, response.getFailedCount(), "grading_status=2 一卷");
        assertEquals(1, response.getPendingCount(), "grading_status=0 一卷");
        assertEquals(4, response.getSubjectiveTotal());
        assertEquals(1, response.getSubjectiveGraded(), "仅一行有终分");
        assertEquals(3, response.getPartialGradedCount(), "答卷 1/3/4 均存在未批简答行");
    }

    // ==================== 造数工具 ====================

    private void stubOwnedExam() {
        Exam exam = new Exam();
        exam.setId(EXAM_ID);
        exam.setCreatedBy(TEACHER_ID);
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam);
    }

    private GradingSubmission submission(Long id, int gradingStatus) {
        GradingSubmission submission = new GradingSubmission();
        submission.setId(id);
        submission.setExamId(EXAM_ID);
        submission.setStatus(ExamSubmission.STATUS_SUBMITTED);
        submission.setGradingStatus(gradingStatus);
        return submission;
    }

    private SubjectiveGrade row(Long submissionId, BigDecimal score) {
        SubjectiveGrade row = new SubjectiveGrade();
        row.setSubmissionId(submissionId);
        row.setExamId(EXAM_ID);
        row.setScore(score);
        return row;
    }
}
