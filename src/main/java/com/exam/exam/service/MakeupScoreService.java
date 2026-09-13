package com.exam.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 补考最终成绩合并服务（spec「补考成绩规则」，§5.1，独立类避免污染 ScoreService）：
 *
 * <p><b>为什么合并逻辑放独立类而不改 ScoreService</b>——score 包由成绩复核（Agent 3）并行演进，
 * 本阶段只新增补考合并能力：在不触碰 ScoreService 现有方法体的前提下，按考试配置
 * （makeup_score_rule）在"取最终成绩"阶段聚合主考与各次补考的成绩。
 *
 * <p><b>合并不污染主考答卷</b>：本类只"读"主考与补考各份答卷的 total_score 并做纯计算，
 * 从不 update 任一答卷——各次历史成绩斤毫不动（§5.1「历史成绩保留不覆盖」），最终分数是读到时的派生值。
 *
 * <p>规则三态（§5.1）：
 * <ul>
 *   <li>takeHighest：主考与补考取最高分；</li>
 *   <li>takeLatest：按应考时间轴取最近一次（答卷 submit_time 最晚）；</li>
 *   <li>takeAverage：主考与补考平均值。</li>
 * </ul>
 * 未配置规则的教育考试沿用 {@link Exam#MAKEUP_DEFAULT_RULE}（取最高，对学生最有利）。
 */
@Slf4j
@Service
public class MakeupScoreService {

    private final ExamMapper examMapper;
    private final GradingSubmissionMapper gradingMapper;

    public MakeupScoreService(ExamMapper examMapper, GradingSubmissionMapper gradingMapper) {
        this.examMapper = examMapper;
        this.gradingMapper = gradingMapper;
    }

    /** 一次成绩样本：总分 + 答卷时间（作"最近一次"的时间轴依据）。 */
    public record ScoreRecord(BigDecimal score, LocalDateTime takenAt) {
    }

    /**
     * 计算某学生在某场考试（主考或补考）的最终成绩（§5.1）：
     * 沿 parent_exam_id 定位到主考，收集"主考 + 其全部补考"中该学生已批改的总分，
     * 按规则合并；历史各次成绩均保留（只读不做任何 update）。
     *
     * @return 合并后的最终成绩；该学生一场都没应考（无记录）时为 null。
     */
    public BigDecimal finalScore(Long examId, Long studentId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null || studentId == null) {
            return null;
        }
        Exam root = resolveRoot(exam);
        String rule = exam.getMakeupScoreRule() != null ? exam.getMakeupScoreRule()
                : (root.getMakeupScoreRule() != null ? root.getMakeupScoreRule()
                : Exam.MAKEUP_DEFAULT_RULE);
        return mergeFinalScore(rule, collectFamilyScores(root, studentId));
    }

    /**
     * 按规则合并多份成绩（纯计算，三态供单测直接覆盖）：空记录返回 null，否则按规则取最终值。
     * 平均分四舍五入保留 1 位小数（与卷面分值精度一致）。
     */
    public BigDecimal mergeFinalScore(String rule, List<ScoreRecord> records) {
        List<ScoreRecord> valid = records == null ? List.of()
                : records.stream().filter(r -> r != null && r.score() != null).toList();
        if (valid.isEmpty()) {
            return null;
        }
        if (Exam.MAKEUP_TAKE_HIGHEST.equals(rule)) {
            return valid.stream().map(ScoreRecord::score)
                    .max(BigDecimal::compareTo).orElse(null);
        }
        if (Exam.MAKEUP_TAKE_LATEST.equals(rule)) {
            // 最近一次：取应考时间轴（submit_time）最晚；无提交时间的记录按其分数兜底参与
            List<ScoreRecord> withTime = valid.stream()
                    .filter(r -> r.takenAt() != null)
                    .sorted(Comparator.comparing(ScoreRecord::takenAt))
                    .toList();
            if (withTime.isEmpty()) {
                return mergeFinalScore(Exam.MAKEUP_TAKE_HIGHEST, valid);
            }
            return withTime.get(withTime.size() - 1).score();
        }
        // takeAverage（且规则未知时按平均规避丢分）：
        return valid.stream().map(ScoreRecord::score)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(valid.size()), 1, RoundingMode.HALF_UP);
    }

    // ==================== 私有 ====================

    /** 找回主考：沿 parent_exam_id 一路向上到无父的顶端。 */
    private Exam resolveRoot(Exam exam) {
        Exam cur = exam;
        while (cur.getParentExamId() != null) {
            Exam parent = examMapper.selectById(cur.getParentExamId());
            if (parent == null) {
                break;   // 父考已删（软删/物理）时以当前为准，不无限追链
            }
            cur = parent;
        }
        return cur;
    }

    /** 收集"主考 + 其全部补考"中该学生的已批改总分（保留各次历史成绩，供合并）。 */
    private List<ScoreRecord> collectFamilyScores(Exam root, Long studentId) {
        List<Exam> family = new ArrayList<>();
        family.add(root);
        family.addAll(examMapper.selectList(Wrappers.<Exam>lambdaQuery()
                .eq(Exam::getParentExamId, root.getId())));

        List<ScoreRecord> records = new ArrayList<>();
        for (Exam exam : family) {
            GradingSubmission grade = gradingMapper.selectOne(Wrappers.<GradingSubmission>lambdaQuery()
                    .eq(GradingSubmission::getExamId, exam.getId())
                    .eq(GradingSubmission::getStudentId, studentId)
                    .isNotNull(GradingSubmission::getTotalScore)
                    .last("LIMIT 1"));
            if (grade != null && grade.getTotalScore() != null) {
                records.add(new ScoreRecord(grade.getTotalScore(), grade.getSubmitTime()));
            }
        }
        return records;
    }
}