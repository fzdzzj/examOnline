package com.exam.question.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 标签响应。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagResponse {

    private Long id;

    private String name;

    /** SUBJECT/DIFFICULTY/QUESTION_TYPE/CUSTOM */
    private String type;
}
