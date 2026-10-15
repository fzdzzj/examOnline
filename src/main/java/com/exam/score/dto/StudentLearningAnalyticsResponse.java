package com.exam.score.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 学生跨考试学情综合画像（spec add-student-learning-analytics）：
 * 基于该学生历史全部已发布考试答卷与题目标签做内存聚合，零 DDL。
 *
 * <ul>
 *   <li>{@code overview}：历史总览（参测场次/平均分/最高分/最低分/及格率）；</li>
 *   <li>{@code trend}：按考试时间排序的成绩走势列表；</li>
 *   <li>{@code tagMastery}：各知识点累计掌握度（总分值 vs 实际得分）；</li>
 *   <li>{@code weakTags}：按掌握度升序的 Top 5 薄弱知识点。</li>
 * </ul>
 *
 * <p>学生零答卷/零已发布考试时，返回参测场次为 0 的空态对象（不得 500/404）。
 */
@Data
public class StudentLearningAnalyticsResponse {

    /** 历史总览指标。 */
    private Overview overview;

    /** 按考试时间排序的历史成绩趋势（近→远由服务端裁决，前端只渲染）。 */
    private List<TrendItem> trend;

    /** 跨考试知识点掌握度列表。 */
    private List<TagMasteryItem> tagMastery;

    /** 按掌握度升序排列的 Top 5 薄弱知识点。 */
    private List<TagMasteryItem> weakTags;

    /** 历史总览。 */
    @Data
    public static class Overview {
        /** 参测场次（已发布考试且已有答卷记录）。 */
        private Integer totalExams;
        /** 平均分（满分同试卷总分口径，仅对已有答卷场次统计）。 */
        private BigDecimal averageScore;
        /** 最高分。 */
        private BigDecimal highestScore;
        /** 最低分。 */
        private BigDecimal lowestScore;
        /** 及格率（0~1，无答卷为 null）。 */
        private Double passRate;
    }

    /** 单场考试成绩趋势。 */
    @Data
    public static class TrendItem {
        private Long examId;
        private String examTitle;
        /** 考试成绩时间（快照实际开考时间）。 */
        private java.time.LocalDateTime examTime;
        private BigDecimal studentScore;
        private BigDecimal totalScore;
        /** 得分率（0~1）。 */
        private Double scoreRate;
    }

    /** 单知识点跨考试累计掌握度。 */
    @Data
    public static class TagMasteryItem {
        private Long tagId;
        private String tagName;
        /** 携带该标签的答题记录数（同题跨考试/一题多标签按出现次数计）。 */
        private Integer questionCount;
        private BigDecimal totalAvailableScore;
        private BigDecimal studentAchievedScore;
        /** 掌握度（0~1）。 */
        private Double masteryRate;
        /** 是否薄弱（掌握度 &lt; 75%）。 */
        private Boolean weak;
    }
}