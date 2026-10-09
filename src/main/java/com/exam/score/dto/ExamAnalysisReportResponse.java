package com.exam.score.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 考试数据分析报告响应（add-exam-analysis-report，创新点4 考试数据分析报告）。
 *
 * <p>只读聚合，四块：班级概览 + 逐题指标 + 知识点薄弱 + 学生关注名单。
 * 逐题指标与题目统计导出共用同一聚合来源（{@code ScoreExportService.aggregateQuestionStats}），
 * 口径严禁与导出分叉。
 */
public class ExamAnalysisReportResponse {

    /** 班级概览：应考/实考/缺席 + 平均分/及格率 + 分数段分布。 */
    private ClassOverview classOverview;
    /** 逐题指标（与题目统计导出同源同口径，含区分度）。 */
    private List<QuestionStatItem> questionStats;
    /** 知识点薄弱：按题目标签聚合的得分率（升序）。 */
    private List<TagWeaknessItem> tagWeakness;
    /** 是否具备知识点维度（试卷题目均无标签时为 false，前端提示「题库未打知识点标签」）。 */
    private boolean hasTagDimension;
    /** 学生关注名单：低于及格线（&lt;60）学生的学号/姓名/总分。 */
    private List<FocusStudentItem> focusList;

    public ClassOverview getClassOverview() {
        return classOverview;
    }

    public void setClassOverview(ClassOverview classOverview) {
        this.classOverview = classOverview;
    }

    public List<QuestionStatItem> getQuestionStats() {
        return questionStats;
    }

    public void setQuestionStats(List<QuestionStatItem> questionStats) {
        this.questionStats = questionStats;
    }

    public List<TagWeaknessItem> getTagWeakness() {
        return tagWeakness;
    }

    public void setTagWeakness(List<TagWeaknessItem> tagWeakness) {
        this.tagWeakness = tagWeakness;
    }

    public boolean isHasTagDimension() {
        return hasTagDimension;
    }

    public void setHasTagDimension(boolean hasTagDimension) {
        this.hasTagDimension = hasTagDimension;
    }

    public List<FocusStudentItem> getFocusList() {
        return focusList;
    }

    public void setFocusList(List<FocusStudentItem> focusList) {
        this.focusList = focusList;
    }

    /** 班级概览聚合结果。 */
    public static class ClassOverview {
        private int expectedCount;
        private int actualCount;
        private int absenceCount;
        private BigDecimal averageScore;
        private Double passRate;
        private List<ScoreBandItem> scoreBands;

        public int getExpectedCount() {
            return expectedCount;
        }

        public void setExpectedCount(int expectedCount) {
            this.expectedCount = expectedCount;
        }

        public int getActualCount() {
            return actualCount;
        }

        public void setActualCount(int actualCount) {
            this.actualCount = actualCount;
        }

        public int getAbsenceCount() {
            return absenceCount;
        }

        public void setAbsenceCount(int absenceCount) {
            this.absenceCount = absenceCount;
        }

        public BigDecimal getAverageScore() {
            return averageScore;
        }

        public void setAverageScore(BigDecimal averageScore) {
            this.averageScore = averageScore;
        }

        public Double getPassRate() {
            return passRate;
        }

        public void setPassRate(Double passRate) {
            this.passRate = passRate;
        }

        public List<ScoreBandItem> getScoreBands() {
            return scoreBands;
        }

        public void setScoreBands(List<ScoreBandItem> scoreBands) {
            this.scoreBands = scoreBands;
        }
    }

    /** 单个分数段。band 文本形如「0-59」「60-69」「70-79」「80-89」「90-100」。 */
    public static class ScoreBandItem {
        private String band;
        private int count;

        public ScoreBandItem() {
        }

        public ScoreBandItem(String band, int count) {
            this.band = band;
            this.count = count;
        }

        public String getBand() {
            return band;
        }

        public void setBand(String band) {
            this.band = band;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    /** 逐题指标（与题目统计导出同源同口径）。 */
    public static class QuestionStatItem {
        private int order;
        private String type;
        private BigDecimal fullScore;
        private BigDecimal averageScore;
        private Double scoreRate;
        private Double correctRate;
        private Double discrimination;
        private int answeredCount;

        public QuestionStatItem() {
        }

        public QuestionStatItem(int order, String type, BigDecimal fullScore, BigDecimal averageScore,
                                Double scoreRate, Double correctRate, Double discrimination,
                                int answeredCount) {
            this.order = order;
            this.type = type;
            this.fullScore = fullScore;
            this.averageScore = averageScore;
            this.scoreRate = scoreRate;
            this.correctRate = correctRate;
            this.discrimination = discrimination;
            this.answeredCount = answeredCount;
        }

        public int getOrder() {
            return order;
        }

        public void setOrder(int order) {
            this.order = order;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public BigDecimal getFullScore() {
            return fullScore;
        }

        public void setFullScore(BigDecimal fullScore) {
            this.fullScore = fullScore;
        }

        public BigDecimal getAverageScore() {
            return averageScore;
        }

        public void setAverageScore(BigDecimal averageScore) {
            this.averageScore = averageScore;
        }

        public Double getScoreRate() {
            return scoreRate;
        }

        public void setScoreRate(Double scoreRate) {
            this.scoreRate = scoreRate;
        }

        public Double getCorrectRate() {
            return correctRate;
        }

        public void setCorrectRate(Double correctRate) {
            this.correctRate = correctRate;
        }

        public Double getDiscrimination() {
            return discrimination;
        }

        public void setDiscrimination(Double discrimination) {
            this.discrimination = discrimination;
        }

        public int getAnsweredCount() {
            return answeredCount;
        }

        public void setAnsweredCount(int answeredCount) {
            this.answeredCount = answeredCount;
        }
    }

    /** 知识点薄弱项：按标签聚合的得分率。 */
    public static class TagWeaknessItem {
        private Long tagId;
        private String tagName;
        private Double scoreRate;

        public TagWeaknessItem() {
        }

        public TagWeaknessItem(Long tagId, String tagName, Double scoreRate) {
            this.tagId = tagId;
            this.tagName = tagName;
            this.scoreRate = scoreRate;
        }

        public Long getTagId() {
            return tagId;
        }

        public void setTagId(Long tagId) {
            this.tagId = tagId;
        }

        public String getTagName() {
            return tagName;
        }

        public void setTagName(String tagName) {
            this.tagName = tagName;
        }

        public Double getScoreRate() {
            return scoreRate;
        }

        public void setScoreRate(Double scoreRate) {
            this.scoreRate = scoreRate;
        }
    }

    /** 学生关注名单：低于及格线学生的轻量信息（不做逐生×逐题交叉矩阵，留二期）。 */
    public static class FocusStudentItem {
        private Long studentId;
        private String username;
        private String studentName;
        private BigDecimal totalScore;

        public FocusStudentItem() {
        }

        public FocusStudentItem(Long studentId, String username, String studentName,
                                BigDecimal totalScore) {
            this.studentId = studentId;
            this.username = username;
            this.studentName = studentName;
            this.totalScore = totalScore;
        }

        public Long getStudentId() {
            return studentId;
        }

        public void setStudentId(Long studentId) {
            this.studentId = studentId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getStudentName() {
            return studentName;
        }

        public void setStudentName(String studentName) {
            this.studentName = studentName;
        }

        public BigDecimal getTotalScore() {
            return totalScore;
        }

        public void setTotalScore(BigDecimal totalScore) {
            this.totalScore = totalScore;
        }
    }
}