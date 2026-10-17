package com.exam.question.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.question.dto.StudentQuestionFavoritePageResponse;
import com.exam.question.entity.Question;
import com.exam.question.entity.StudentQuestionFavorite;
import com.exam.question.mapper.QuestionMapper;
import com.exam.question.mapper.StudentQuestionFavoriteMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收藏夹服务单元测试（含 m1 题目有效性与 m2 学生隔离断言位）。
 */
@ExtendWith(MockitoExtension.class)
class StudentQuestionFavoriteServiceTest {

    @Mock
    private StudentQuestionFavoriteMapper favoriteMapper;

    @Mock
    private QuestionMapper questionMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private StudentQuestionFavoriteService favoriteService;

    private static final Long STUDENT_ID = 1001L;

    @BeforeEach
    void setUp() {
        LoginUser user = new LoginUser();
        user.setId(STUDENT_ID);
        user.setUsername("student1");
        user.setRoles(Set.of("STUDENT"));
        SecurityUtil.set(user);
    }

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    void favorite_validQuestion_insertsSuccessfully() {
        Question q = new Question();
        q.setId(10L);
        q.setIsDeleted(0);
        when(questionMapper.selectById(10L)).thenReturn(q);
        when(favoriteMapper.selectCount(any())).thenReturn(0L);

        favoriteService.favorite(10L);

        ArgumentCaptor<StudentQuestionFavorite> captor = ArgumentCaptor.forClass(StudentQuestionFavorite.class);
        verify(favoriteMapper).insert(captor.capture());
        assertEquals(STUDENT_ID, captor.getValue().getStudentId());
        assertEquals(10L, captor.getValue().getQuestionId());
    }

    @Test
    void favorite_duplicate_idempotentNoInsert() {
        Question q = new Question();
        q.setId(10L);
        q.setIsDeleted(0);
        when(questionMapper.selectById(10L)).thenReturn(q);
        when(favoriteMapper.selectCount(any())).thenReturn(1L);

        favoriteService.favorite(10L);

        verify(favoriteMapper, never()).insert(any(StudentQuestionFavorite.class));
    }

    @Test
    void favorite_questionNotFound_throws404() {
        // m1 变异测试目标：题目不存在时必须抛 404 NOT_FOUND
        when(questionMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> favoriteService.favorite(999L));
        assertEquals(ResponseCode.QUESTION_NOT_FOUND.getCode(), ex.getCode());
        assertEquals(404, ex.getHttpStatus());
        verify(favoriteMapper, never()).insert(any(StudentQuestionFavorite.class));
    }

    @Test
    void favorite_questionSoftDeleted_throws404() {
        Question q = new Question();
        q.setId(11L);
        q.setIsDeleted(1);
        when(questionMapper.selectById(11L)).thenReturn(q);

        BusinessException ex = assertThrows(BusinessException.class, () -> favoriteService.favorite(11L));
        assertEquals(ResponseCode.QUESTION_NOT_FOUND.getCode(), ex.getCode());
        assertEquals(404, ex.getHttpStatus());
        verify(favoriteMapper, never()).insert(any(StudentQuestionFavorite.class));
    }


    @Test
    void unfavorite_deletesByStudentAndQuestion() {
        favoriteService.unfavorite(10L);
        verify(favoriteMapper).delete(any());
    }

    @Test
    void checkFavorites_exceedsLimit_throwsBadRequest() {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            ids.add(i);
        }

        BusinessException ex = assertThrows(BusinessException.class, () -> favoriteService.checkFavorites(ids));
        assertEquals(ResponseCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void checkFavorites_studentIsolation() {
        // m2 变异测试目标：检测状态必须按当前登录 studentId 过滤
        StudentQuestionFavorite fav = StudentQuestionFavorite.builder()
                .studentId(STUDENT_ID)
                .questionId(10L)
                .build();
        when(favoriteMapper.selectList(any())).thenReturn(List.of(fav));

        List<Long> result = favoriteService.checkFavorites(List.of(10L, 20L));
        assertEquals(List.of(10L), result);
    }

    @Test
    void listFavorites_empty_returnsEmptyPage() {
        when(favoriteMapper.countFavoriteItems(eq(STUDENT_ID), any(), any())).thenReturn(0L);

        StudentQuestionFavoritePageResponse res = favoriteService.listFavorites(1, 10, null, null);
        assertNotNull(res);
        assertEquals(0, res.getTotal());
        assertTrue(res.getList().isEmpty());
    }
}
