package com.exam.score.dto;

import lombok.Data;

import java.util.List;

/**
 * 班级跨考试综合学情（spec add-student-learning-analytics）：
 * 教师查询所辖班级的跨考试整体学情与班级薄弱知识点汇总，水平越权由服务层强校验（403）。
 *
 * <p>掌握度口径：班级在各知识点上的累计得分 / 累计总分值（跨学生 × 跨考试聚合），
 * 与 {@link StudentLearningAnalyticsResponse} 同一掌握度公式，便于两端解读对齐。
 */
@Data
public class ClassLearningAnalyticsResponse {

    private Long classId;
    private String className;
    /** 班级当前学生数。 */
    private Integer studentCount;
    /** 纳入统计的已发布考试场次数。 */
    private Integer examCount;

    /** 班级各场已发布考试平均分走势。 */
    private List<ClassTrendItem> trend;

    /** 班级在各知识点上的平均得分率。 */
    private List<ClassTagMasteryItem> tagMastery;

    /** 全班掌握度最低的 Top 5 薄弱知识点。 */
    private List<ClassTagMasteryItem> weakTags;

    /** 班级单场平均分走势行（独立命名避免与学生端 TrendItem 契约名冲突）。 */
    @Data
    public static class ClassTrendItem {
        private Long examId;
        private String examTitle;
        private java.time.LocalDateTime examTime;
        private Double averageScore;
    }

    /** 班级单知识点平均得分率（独立命名避免与学生端 TagMasteryItem 契约名冲突）。 */
    @Data
    public static class ClassTagMasteryItem {
        private Long tagId;
        private String tagName;
        private Double masteryRate;
    }
}