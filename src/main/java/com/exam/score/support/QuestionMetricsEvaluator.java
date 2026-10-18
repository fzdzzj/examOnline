package com.exam.score.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 试题测量学评级与命题质量诊断评估器（add-exam-question-discrimination，CTT 经典模型）。
 *
 * <p>纯函数无状态：输入 {@code ScoreExportService.aggregateQuestionStats} 的逐题聚合口径
 * （区分度 D=前后 27% 高低分组法、平均分/满分），输出定性评级与命题质量诊断建议，
 * 供 JSON 分析报告与多 Sheet Excel 导出共用（同源同口径，禁止另造第二套公式）。
 * 评级阈值与诊断文案以本类为唯一出处。
 */
public final class QuestionMetricsEvaluator {

    private QuestionMetricsEvaluator() {
    }

    /**
     * 区分度五档定性评级：D≥0.40 优良；0.30≤D&lt;0.40 良好；0.20≤D&lt;0.30 尚可；
     * 0≤D&lt;0.20 劣质；D&lt;0 反常预警；null（实考 &lt;4 或分组空）→ 样本不足。
     * 评级分支保持单行形态——变异校验按行级 sed 注入定位。
     */
    public static String discriminationLevelOf(Double discrimination) {
        if (discrimination == null) return "样本不足";
        if (discrimination < 0) return "反常预警";
        if (discrimination >= 0.40) return "优良";
        if (discrimination >= 0.30) return "良好";
        if (discrimination >= 0.20) return "尚可";
        return "劣质";
    }

    /**
     * 难度系数 P = 平均分 / 满分（保留 2 位小数，HALF_UP）。平均分取报告口径（1 位小数）
     * 的同源值；作答 0 人、平均分缺失或满分非正 → null。
     */
    public static Double difficultyOf(BigDecimal averageScore, double fullScore, int answeredCount) {
        if (answeredCount == 0 || fullScore <= 0 || averageScore == null) {
            return null;
        }
        return averageScore.divide(BigDecimal.valueOf(fullScore), 2, RoundingMode.HALF_UP).doubleValue();
    }

    /** 难度等级：P≥0.70 容易；0.40≤P&lt;0.70 中等；P&lt;0.40 较难；P 为 null → null。 */
    public static String difficultyLevelOf(Double difficulty) {
        if (difficulty == null) {
            return null;
        }
        if (difficulty >= 0.70) return "容易";
        if (difficulty >= 0.40) return "中等";
        return "较难";
    }

    /**
     * 命题质量诊断建议：结合区分度评级与难度等级生成专业建议文本；
     * 样本不足与负区分度反常有专属警示，难度偏易/偏难追加考核定位提示。
     */
    public static String qualityEvaluationOf(Double discrimination, Double difficulty) {
        if (discrimination == null) {
            return "实考样本不足（需至少 4 人作答），暂无法评估命题质量，请扩大实考样本后复核";
        }
        String base;
        if (discrimination < 0) {
            base = "反常预警：负区分度（高分组均分低于低分组），请紧急核查题干是否存在严重歧义或参考答案配置错误";
        } else if (discrimination >= 0.40) {
            base = "区分度优良，试题区分力强，建议入选精品题库";
        } else if (discrimination >= 0.30) {
            base = "区分度良好，命题质量较好，可直接使用";
        } else if (discrimination >= 0.20) {
            base = "区分度尚可，建议微调题干表述或优化选项";
        } else {
            base = "区分度偏低，建议优化干扰项诱答力或修订题干";
        }
        if (difficulty != null) {
            if (difficulty >= 0.70) {
                base = base + "；试题偏易，请结合考核定位评估命题意图";
            } else if (difficulty < 0.40) {
                base = base + "；试题偏难，建议复核难度定位与作答时间";
            }
        }
        return base;
    }
}
