package com.exam.submission.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 答卷：学生进入考试即创建（一人一场一行，uk_exam_student 唯一），承载个人快照与最终答案。
 *
 * <p>生命周期：进行中 → 已交卷 → 已批改（判分阶段 6 消费）。状态迁移一律走 CAS
 * （{@code UPDATE ... WHERE status=1}），与 SETNX 一次性锁、防重表共同构成三路竞态
 * （手动 / 前端归零 / 后端兜底）"仅提交一次"的兜底保障。
 *
 * <p>字段说明：
 * <ul>
 *   <li>start_time 为学生点击"开始"的服务端时间，个人倒计时以此为起点（§7.9）；</li>
 *   <li>paper_json 为个人快照：题序/选项乱序在进入时洗牌锁定，刷新/重进不换题（§3.4）；</li>
 *   <li>answers 为最终答案 JSON，交卷经 MQ 削峰由消费者批量落库，NULL 表示尚未落库
 *       （对账补发扫描依据），成功后不再被覆盖（消费端幂等）。</li>
 * </ul>
 */
@Data
@TableName("exam_submissions")
public class ExamSubmission {

    /** 进行中：可答题、可自动保存，交卷后迁出 */
    public static final int STATUS_IN_PROGRESS = 1;

    /** 已交卷：终态之一，重复交卷幂等返回首次结果 */
    public static final int STATUS_SUBMITTED = 2;

    /** 已批改：判分引擎（阶段 6）消费 */
    public static final int STATUS_GRADED = 3;

    /** 提交来源：学生手动点击交卷 */
    public static final int SUBMIT_TYPE_MANUAL = 1;

    /** 提交来源：前端倒计时归零自动提交 */
    public static final int SUBMIT_TYPE_COUNTDOWN_ZERO = 2;

    /** 提交来源：后端定时兜底强制交卷 */
    public static final int SUBMIT_TYPE_BACKEND = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long examId;

    private Long studentId;

    /** 个人开始时间：点击开始才计时（服务端记录） */
    private LocalDateTime startTime;

    /** 个人截止时间：min(开始+时长, 考试 end_time)，超时兜底扫描依据 */
    private LocalDateTime deadlineTime;

    private LocalDateTime submitTime;

    /** 提交来源：见 SUBMIT_TYPE_* 常量 */
    private Integer submitType;

    /** 个人快照：锁定题序/选项顺序的试卷内容副本（含答案，仅供服务端判分用） */
    private String paperJson;

    /** 最终答案 JSON（questionId→答案）；NULL=尚未落库 */
    private String answers;

    /** 答卷状态：见 STATUS_* 常量 */
    private Integer status;

    /** 乐观锁版本号：状态 CAS 护栏 */
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
}
