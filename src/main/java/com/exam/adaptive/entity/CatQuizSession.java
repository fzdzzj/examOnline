package com.exam.adaptive.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CAT 自适应测验会话（spec add-irt-adaptive-testing）。
 *
 * <p>会话正确性落 DB 而非 Redis（runbook 口径：Redis 是加速器不是正确性后盾；
 * 本案零新 Redis 键族）。{@code answeredQuestionIds} 与 {@code itemTrail} 存 JSON 文本，
 * 前者用于"排除已答题"的选题门控，后者是 θ 轨迹与逐题结果的能力报告数据源；
 * 逐题作答明细同源落 {@code practice_attempt}（判分口径零分叉）。
 */
@Data
@TableName("cat_quiz_sessions")
public class CatQuizSession {

    /** 会话状态：进行中 */
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    /** 会话状态：已终止（可查能力报告） */
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** 终止原因：SE 收敛（达到精度要求） */
    public static final String TERMINATED_SE_CONVERGED = "SE_CONVERGED";
    /** 终止原因：达到最大题数 */
    public static final String TERMINATED_MAX_ITEMS = "MAX_ITEMS";
    /** 终止原因：可用题池耗尽 */
    public static final String TERMINATED_POOL_EXHAUSTED = "POOL_EXHAUSTED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话归属学生（唯一可见人，越权按既有口径拒绝） */
    private Long studentId;

    /** 会话状态：IN_PROGRESS / COMPLETED */
    private String status;

    /** 当前能力估计 θ（每次作答后即时更新） */
    private BigDecimal theta;

    /** 当前能力估计标准误 SE（低于阈值即终止） */
    private BigDecimal se;

    /** 最大题数（请求可配，钳制 1~20，缺省 10） */
    private Integer maxItems;

    /** 终止阈值 SE（请求可配，缺省 0.30） */
    private BigDecimal seThreshold;

    /** 已答题数 */
    private Integer answeredCount;

    /** 答对数 */
    private Integer correctCount;

    /** 当前待作答题目 ID（终止后为 null） */
    private Long currentQuestionId;

    /** 已答题目 ID 集（JSON 数组文本，选题排除依据） */
    private String answeredQuestionIds;

    /** θ 轨迹（JSON 数组文本：逐题 questionId/answer/correct/theta/se） */
    private String itemTrail;

    /** 终止原因：SE_CONVERGED / MAX_ITEMS / POOL_EXHAUSTED；进行中为 null */
    private String terminationReason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
}
