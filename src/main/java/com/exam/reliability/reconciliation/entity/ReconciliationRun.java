package com.exam.reliability.reconciliation.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对账运行记录（spec reliability「成绩链全链路对账」，表 reconciliation_runs）：
 *
 * <p>一次手动或定时对账一行，记录范围（examId）、触发来源、起止时间、三类差异数与状态。
 * 三类差异数恒为本次运行的实际检出数：{@code ungradedCount} = 维度一（交卷 vs 判分）
 * 检出数，{@code mismatchCount} = 维度二（判分明细 vs 总分）检出数，
 * {@code autoFixedCount} = 确定性缺失实际补齐数（≤ ungradedCount）。
 *
 * <p>{@code batchKey} 是定时兜底的批次键（唯一索引 {@code uk_recon_run_batch}）：
 * 多实例在同一时间窗并发触发时只有一格能插入，其余实例收到重复键后静默跳过——
 * 与 reliability 域「正确性由幂等保证、不加分布式调度锁」同口径，零新 Redis 键族。
 * 手动运行该列为 NULL（唯一索引允许多行 NULL）。
 */
@Data
@TableName("reconciliation_runs")
public class ReconciliationRun {

    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对账范围：NULL=按范围扫描（定时兜底） */
    private Long examId;

    /** MANUAL / SCHEDULED */
    private String triggerSource;

    /** 定时批次键（唯一索引护栏）；手动为 NULL */
    private String batchKey;

    /** RUNNING / SUCCESS / FAILED */
    private String status;

    /** 本次实际比对的已交卷答卷数 */
    private Integer scannedCount;

    /** 维度一（交卷 vs 判分）差异检出数 */
    private Integer ungradedCount;

    /** 维度二（判分明细 vs 总分）差异检出数 */
    private Integer mismatchCount;

    /** 确定性缺失（判分完成但总分未回填）实际补齐成功数 */
    private Integer autoFixedCount;

    /** 失败原因（定时对账失败只入记录，不影响下一次运行） */
    private String errorMessage;

    /** 手动触发的操作人；定时为 NULL */
    private Long operatorId;

    private LocalDateTime startedTime;

    private LocalDateTime finishedTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
}
