package com.exam.exam.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 考试：绑定试卷/课程/班级的考试实体，是连接"组卷"与"在线答题"的枢纽。
 *
 * <p>生命周期状态机（spec「考试状态机」）：未开始 → 进行中 → 已结束 → 已批改 → 已发布，
 * 流转一律通过乐观锁 CAS（UPDATE ... WHERE status=? AND version=?）保证并发安全；
 * 已批改/已发布两态由后续判分与成绩发布阶段消费，本阶段只到"已结束"。
 *
 * <p>关键约束：
 * <ul>
 *   <li>发布（published=1）是考试快照生成的唯一时机，快照与试卷从此解耦；</li>
 *   <li>进入进行中后锁定绑定试卷，禁止改题/删题/改分值（§4.1）；</li>
 *   <li>时间推进以服务端时间为准（§1.1），定时任务扫表推进而非外部调度中间件。</li>
 * </ul>
 */
@Data
@TableName("exams")
public class Exam {

    /** 未开始：可编辑（未发布时）、可删除；已发布的考试到达 start_time 自动迁入进行中 */
    public static final int STATUS_NOT_STARTED = 0;

    /** 进行中：绑定试卷被锁定（只读）；学生答题窗口开放（阶段 5） */
    public static final int STATUS_IN_PROGRESS = 1;

    /** 已结束：到达 end_time 自然结束，或教师提前结束（force_end=1） */
    public static final int STATUS_ENDED = 2;

    /** 已批改：判分完成（阶段 5+ 消费） */
    public static final int STATUS_GRADED = 3;

    /** 成绩已发布：学生可查成绩（阶段 5+ 消费） */
    public static final int STATUS_PUBLISHED = 4;

    // ===== 补考成绩规则（makeup_score_rule，§5.1，考后闭环补考阶段）=====

    /** 取最高分：主考与各次补考成绩取最高者为该生最终成绩 */
    public static final String MAKEUP_TAKE_HIGHEST = "takeHighest";

    /** 取最近一次：按答卷时间（submit_time）最晚的成绩作为最终成绩 */
    public static final String MAKEUP_TAKE_LATEST = "takeLatest";

    /** 取平均分：主考与各次补考成绩的平均作为最终成绩 */
    public static final String MAKEUP_TAKE_AVERAGE = "takeAverage";

    /** 补考成绩默认规则：未显式配置时取最高分（对学生最有利，也是 §5.1 的默认语义） */
    public static final String MAKEUP_DEFAULT_RULE = MAKEUP_TAKE_HIGHEST;

    /** 补考成绩规则取值集合（创建补考时校验） */
    public static final Set<String> MAKEUP_RULES = Set.of(
            MAKEUP_TAKE_HIGHEST, MAKEUP_TAKE_LATEST, MAKEUP_TAKE_AVERAGE);

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String description;

    /** 绑定试卷 ID（创建后发布前可换绑；发布后试卷内容以考试快照为准） */
    private Long paperId;

    /** 课程 ID（课程实体后续阶段提供，先存 ID） */
    private Long courseId;

    /** 班级 ID（同上） */
    private Long classId;

    /**
     * 关联的主考考试 ID（§12.5）：补考是独立考试记录，本字段指向其主考；
     * 非补考考试为 NULL（向后兼容，旧考试不受影响）。
     */
    private Long parentExamId;

    /** 补考成绩规则（takeHighest/takeLatest/takeAverage，见 MAKEUP_* 常量）；非补考为 NULL */
    private String makeupScoreRule;

    /** 时间窗起点：定时发布的触发点（服务端时间为准） */
    private LocalDateTime startTime;

    /** 时间窗终点：到达即自然结束 */
    private LocalDateTime endTime;

    /** 个人答题时长（分钟）：学生点击"开始考试"后倒计时（阶段 5 消费） */
    private Integer durationMinutes;

    /** 允许迟到分钟数：超过开始时间多久仍可进入 */
    private Integer allowLateMinutes;

    /** 状态机：见 STATUS_* 常量 */
    private Integer status;

    /** 0=未发布（学生不可见） 1=已发布（发布即生成考试快照） */
    private Integer published;

    /** 1=教师提前结束标记：阶段 5 强制交卷（按最后自动保存）据此识别 */
    private Integer forceEnd;

    /** 防作弊配置 JSON 字符串（切屏检测/禁复制等开关；null=使用默认配置） */
    private String antiCheatConfig;

    /** 考试快照 ID（发布时回填） */
    private Long snapshotId;

    /** 乐观锁版本号：CAS 状态流转的并发护栏 */
    private Integer version;

    /** 创建教师 ID（owner 校验依据） */
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;

    @TableLogic
    @TableField(select = false)
    private Integer isDeleted;
}
