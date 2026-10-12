package com.exam.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内消息（add-exam-reservation，消息中心新基础设施）。
 *
 * <p>按 {@code user_id} 通用建模（exam_id 可空关联），本期唯一投递方为「开考预约提醒」，
 * 为后续任意站内通知留口径。端点仅要求登录鉴权，不特判角色（教师天然可拥有空消息箱）。
 *
 * <p><b>未读机制</b>：read_flag 0=未读 1=已读；未读数 = user_id 下 read_flag=0 的行数，
 * 单条标记已读 = 置位 read_flag=1。跨设备不本地持久化，以服务端为准。
 */
@Data
@TableName("user_messages")
public class UserMessage {

    /** 未读 */
    public static final int READ_FLAG_UNREAD = 0;
    /** 已读 */
    public static final int READ_FLAG_READ = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 收件人 user id */
    private Long userId;

    /** 关联考试（可为空，通用消息预留） */
    private Long examId;

    private String title;

    private String content;

    /** 见 READ_FLAG_* 常量 */
    @TableField("read_flag")
    private Integer readFlag;

    private LocalDateTime createdTime;
}