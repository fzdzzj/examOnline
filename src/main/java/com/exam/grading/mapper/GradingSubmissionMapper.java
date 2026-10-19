package com.exam.grading.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.grading.entity.GradingSubmission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 答卷判分 Mapper（映射 exam_submissions 表，判分/成绩域专用）：
 * 常规读写走 MyBatis-Plus Wrapper，状态汇总 CAS 走自定义 SQL。
 */
@Mapper
public interface GradingSubmissionMapper extends BaseMapper<GradingSubmission> {

    /**
     * 成绩汇总 CAS（spec「成绩汇总」场景，成绩域收口动作）：
     * 已交卷(2)/已批改(3) → 已批改(3)，写入主观分/总分/部分批改标记，version 原子自增——
     * 并发汇总同一答卷时仅一个请求影响行数为 1，总分不被交叉覆盖；
     * 重算汇总（已批改→已批改）同样走本 CAS，允许重判后刷新总分。
     */
    @Update("UPDATE exam_submissions SET status = 3, subjective_score = #{subjectiveScore}, "
            + "total_score = #{totalScore}, partial_graded = #{partialGraded}, "
            + "version = version + 1, updated_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status IN (2, 3)")
    int casSummarize(@Param("id") Long id,
                     @Param("subjectiveScore") BigDecimal subjectiveScore,
                     @Param("totalScore") BigDecimal totalScore,
                     @Param("partialGraded") int partialGraded);

    /**
     * 对账确定性缺失的幂等补齐（add-score-chain-reconciliation）：
     * SET 形状与 {@link #casSummarize} 完全一致（同源口径，禁止第二套回填写法），
     * 唯一差别是 WHERE 多一个 {@code total_score IS NULL} 护栏——只有总分尚未回填才写，
     * 重复对账/并发对账/多实例同时补齐都影响 0 行。
     *
     * <p>幂等护栏必须落在 SQL：若改成「先读再判空后写」，重复补齐的护栏在变异断言里
     * 不可被观察，且两个实例仍可能同时通过内存判空各写一次。
     */
    @Update("UPDATE exam_submissions SET status = 3, subjective_score = #{subjectiveScore}, "
            + "total_score = #{totalScore}, partial_graded = #{partialGraded}, "
            + "version = version + 1, updated_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND total_score IS NULL AND status IN (2, 3)")
    int casBackfillTotalScore(@Param("id") Long id,
                              @Param("subjectiveScore") BigDecimal subjectiveScore,
                              @Param("totalScore") BigDecimal totalScore,
                              @Param("partialGraded") int partialGraded);
}
