package com.exam.score.dto;

import lombok.Data;

import java.util.List;

/**
 * 错题本分页响应：以已发布考试为粒度分页。
 */
@Data
public class WrongQuestionPageResponse {

    /** 符合条件的已发布考试总数 */
    private long total;

    /** 当前页码（1 起） */
    private int page;

    /** 每页考试组数量（<= 10） */
    private int size;

    /** 当前页内的考试错题分组列表 */
    private List<ExamWrongQuestionsGroup> groups;
}
