package com.exam.clazz.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 班级响应。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClassResponse {

    private Long id;

    private String name;

    /** 课程 ID（课程实体后续阶段提供，先存 ID） */
    private Long courseId;

    /** 归属教师 ID */
    private Long teacherId;

    private Long createdBy;

    private LocalDateTime createdTime;
}
