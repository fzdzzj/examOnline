package com.exam.exam.dto;

import java.time.LocalDateTime;

/**
 * 缺考学生条目（教师按考试查缺考名单；供筛选进入补考名单用）。
 */
public record AbsenceItemResponse(Long studentId, String studentName, LocalDateTime markedTime) {
}