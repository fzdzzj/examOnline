package com.exam.question.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.question.dto.StudentQuestionFavoriteItem;
import com.exam.question.dto.StudentQuestionFavoritePageResponse;
import com.exam.question.dto.StudentQuestionFavoriteResponse;
import com.exam.question.entity.Question;
import com.exam.question.entity.QuestionType;
import com.exam.question.entity.StudentQuestionFavorite;
import com.exam.question.mapper.QuestionMapper;
import com.exam.question.mapper.StudentQuestionFavoriteMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 学生题目个人收藏服务（add-student-question-favorites）。
 */
@Slf4j
@Service
public class StudentQuestionFavoriteService {

    private final StudentQuestionFavoriteMapper favoriteMapper;
    private final QuestionMapper questionMapper;
    private final ObjectMapper objectMapper;

    public StudentQuestionFavoriteService(StudentQuestionFavoriteMapper favoriteMapper,
                                          QuestionMapper questionMapper,
                                          ObjectMapper objectMapper) {
        this.favoriteMapper = favoriteMapper;
        this.questionMapper = questionMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 收藏题目：幂等写入。校验题目有效且未删除（不存在则抛 404 QUESTION_NOT_FOUND）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void favorite(Long questionId) {
        if (questionId == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "题目ID不能为空");
        }
        Long studentId = requireStudentId();

        // 校验题目有效性与软删除（m1 变异测试位）
        Question question = questionMapper.selectById(questionId);
        if (question == null || (question.getIsDeleted() != null && question.getIsDeleted() == 1)) {
            throw new BusinessException(ResponseCode.QUESTION_NOT_FOUND, "题目不存在");
        }

        Long count = favoriteMapper.selectCount(Wrappers.<StudentQuestionFavorite>lambdaQuery()
                .eq(StudentQuestionFavorite::getStudentId, studentId)
                .eq(StudentQuestionFavorite::getQuestionId, questionId));
        if (count != null && count > 0) {
            return;
        }

        StudentQuestionFavorite record = StudentQuestionFavorite.builder()
                .studentId(studentId)
                .questionId(questionId)
                .createdTime(LocalDateTime.now())
                .build();
        try {
            favoriteMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.debug("Concurrent favorite ignored for student {} question {}", studentId, questionId);
        }
    }

    /**
     * 取消收藏题目：幂等移除。
     */
    @Transactional(rollbackFor = Exception.class)
    public void unfavorite(Long questionId) {
        if (questionId == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "题目ID不能为空");
        }
        Long studentId = requireStudentId();
        favoriteMapper.delete(Wrappers.<StudentQuestionFavorite>lambdaQuery()
                .eq(StudentQuestionFavorite::getStudentId, studentId)
                .eq(StudentQuestionFavorite::getQuestionId, questionId));
    }

    /**
     * 分页查询当前登录学生收藏的题目列表。
     */
    public StudentQuestionFavoritePageResponse listFavorites(int page, int size, Integer type, String keyword) {
        Long studentId = requireStudentId();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        int offset = (safePage - 1) * safeSize;
        String trimmedKeyword = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;

        // 学生隔离过滤（m2 变异测试位）
        long total = favoriteMapper.countFavoriteItems(studentId, type, trimmedKeyword);
        if (total == 0) {
            return StudentQuestionFavoritePageResponse.builder()
                    .list(List.of())
                    .total(0)
                    .page(safePage)
                    .size(safeSize)
                    .build();
        }

        List<StudentQuestionFavoriteItem> items = favoriteMapper.selectFavoriteItemsPaged(
                studentId, type, trimmedKeyword, offset, safeSize);

        List<StudentQuestionFavoriteResponse> responses = items.stream().map(item -> {
            QuestionType qType = QuestionType.of(item.getType());
            return StudentQuestionFavoriteResponse.builder()
                    .id(item.getId())
                    .studentId(item.getStudentId())
                    .questionId(item.getQuestionId())
                    .createdTime(item.getCreatedTime())
                    .type(item.getType())
                    .typeName(qType != null ? qType.getLabel() : "")
                    .content(item.getContent())
                    .imageUrl(item.getImageUrl())
                    .choices(parseChoices(item.getChoices()))
                    .correctAnswer(item.getCorrectAnswer())
                    .score(item.getScore())
                    .difficulty(item.getDifficulty())
                    .analysis(item.getAnalysis())
                    .build();
        }).collect(Collectors.toList());

        return StudentQuestionFavoritePageResponse.builder()
                .list(responses)
                .total(total)
                .page(safePage)
                .size(safeSize)
                .build();
    }

    /**
     * 批量检测题目收藏状态。
     */
    public List<Long> checkFavorites(List<Long> questionIds) {
        Long studentId = requireStudentId();
        if (questionIds == null || questionIds.isEmpty()) {
            return List.of();
        }
        if (questionIds.size() > 100) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "单次最多检测100个题目");
        }

        // 学生隔离过滤（m2 变异测试位）
        List<StudentQuestionFavorite> favs = favoriteMapper.selectList(Wrappers.<StudentQuestionFavorite>lambdaQuery()
                .eq(StudentQuestionFavorite::getStudentId, studentId)
                .in(StudentQuestionFavorite::getQuestionId, questionIds));
        return favs.stream().map(StudentQuestionFavorite::getQuestionId).distinct().collect(Collectors.toList());
    }

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

    private Long requireStudentId() {
        Long userId = SecurityUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(ResponseCode.UNAUTHORIZED);
        }
        return userId;
    }
}
