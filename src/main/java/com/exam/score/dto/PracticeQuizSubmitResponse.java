package com.exam.score.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 错题强化自测批量交卷响应（spec add-student-wrong-question-mastery）：
 * 汇总本次自测的总题数、答对数、正确率与新消灭数，并携带逐题作答结果。
 */
@Data
public class PracticeQuizSubmitResponse {

    /** 本次自测总题数（= 作答列表长度） */
    private Integer total;

    /** 答对题数（得满分即答对，多选部分对不算） */
    private Integer correctCount;

    /** 正确率（百分数 0~100，保留两位小数） */
    private BigDecimal scoreRate;

    /** 本次自测中新达成已掌握状态（连对首次达到 3）的题目数 */
    private Integer newlyMasteredCount;

    /** 逐题作答结果（含对错、标准答案、解析与最新掌握度三态） */
    private List<PracticeQuizResultItem> results;
}
