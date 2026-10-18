package com.exam.grading.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批改入参校验单测（change harden-draft-and-grading-boundaries，纯 Bean Validation、无 Spring 上下文）：
 *
 * <ul>
 *   <li>comment 超过 1024 字符必须在参数校验阶段被拦下（对应库列 VARCHAR(1024)），
 *       超长评语不得再走到 SQL 层变成 DataIntegrityViolationException 500；</li>
 *   <li>恰好 1024 字符属边界内（钳制是「超过即拒」，不是大于等于）；</li>
 *   <li>comment 可空（评语非必填），其余字段既有约束零改动。</li>
 * </ul>
 */
class SubjectiveScoreValidationTest {

    private static final String COMMENT_LIMIT_MESSAGE = "批改评语不能超过 1024 个字符";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        factory.close();
    }

    private static SubjectiveScoreRequest validRequest(String comment) {
        SubjectiveScoreRequest request = new SubjectiveScoreRequest();
        request.setSubmissionId(101L);
        request.setQuestionId(21L);
        request.setScore(new BigDecimal("4.5"));
        request.setComment(comment);
        request.setExpectedVersion(0);
        return request;
    }

    @Test
    @DisplayName("超长评语被拦：1025 字符触发 comment 约束校验失败")
    void commentOver1024CharsRejected() {
        Set<ConstraintViolation<SubjectiveScoreRequest>> violations =
                validator.validate(validRequest("x".repeat(1025)));

        assertFalse(violations.isEmpty(), "1025 字符评语必须被参数校验拦截");
        ConstraintViolation<SubjectiveScoreRequest> commentViolation = violations.stream()
                .filter(v -> "comment".equals(v.getPropertyPath().toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("约束必须落在 comment 字段上，实际: " + violations));
        assertEquals(COMMENT_LIMIT_MESSAGE, commentViolation.getMessage());
    }

    @Test
    @DisplayName("恰好 1024 字符属边界内：整请求零约束违规")
    void commentOfExactly1024CharsAccepted() {
        Set<ConstraintViolation<SubjectiveScoreRequest>> violations =
                validator.validate(validRequest("x".repeat(1024)));

        assertTrue(violations.isEmpty(), "恰好 1024 字符不得被拒，实际违规: " + violations);
    }

    @Test
    @DisplayName("评语可空：null comment 不触发 comment 约束")
    void nullCommentStillAccepted() {
        Set<ConstraintViolation<SubjectiveScoreRequest>> violations =
                validator.validate(validRequest(null));

        assertTrue(violations.stream().noneMatch(v -> "comment".equals(v.getPropertyPath().toString())),
                "comment 为空不得报 comment 约束，实际违规: " + violations);
    }
}
