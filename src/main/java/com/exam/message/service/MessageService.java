package com.exam.message.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.message.entity.UserMessage;
import com.exam.message.mapper.UserMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 学生/教师站内消息服务（add-exam-reservation，消息中心新基础设施）。
 *
 * <p><b>按 user_id 隔离</b>：列表 / 未读数 / 标记已读一律按当前登录用户过滤，绝不跨用户泄出。
 * 鉴权由 AuthenticationInterceptor（全部 /api/**）保证仅登录即可访问——本服务不特判角色，
 * 教师天然可拥有空消息箱，为后续任意站内通知留口径。
 *
 * <p>本期唯一投递方为「开考预约提醒」（ReservationRemindService）；本服务只负责读与已读置位，
 * 不校验收件人是否学生/是否有权读某条消息对应的考试（消息归属即 user_id 隔离，与考试无关）。
 */
@Slf4j
@Service
public class MessageService {

    private final UserMessageMapper messageMapper;

    public MessageService(UserMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    /** 本人消息列表（按 id 倒序 = 最新在前）：仅返回本人（user_id 隔离）。limit 钳制在 [1, 100]。 */
    public List<UserMessage> listMine(int limit) {
        Long userId = requireLogin();
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return messageMapper.selectList(Wrappers.<UserMessage>lambdaQuery()
                .eq(UserMessage::getUserId, userId)
                .orderByDesc(UserMessage::getId)
                .last("LIMIT " + safeLimit));
    }

    /** 本人未读数（user_id 下 read_flag=0 的行数）。 */
    public long unreadCount() {
        Long userId = requireLogin();
        Long count = messageMapper.selectCount(Wrappers.<UserMessage>lambdaQuery()
                .eq(UserMessage::getUserId, userId)
                .eq(UserMessage::getReadFlag, UserMessage.READ_FLAG_UNREAD));
        return count == null ? 0 : count;
    }

    /** 单条标记已读（仅本人且未读；幂等：已读/不存在/非本人返回 false）。 */
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long messageId) {
        Long userId = requireLogin();
        int rows = messageMapper.markReadByOwner(messageId, userId);
        if (rows == 0) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "消息不存在或不属于本人");
        }
        log.debug("用户 {} 标记消息 {} 已读", userId, messageId);
    }

    /**
     * 全部标为已读（add-exam-lifecycle-notifications）：将当前登录用户的所有未读消息
     * （user_id 隔离，read_flag=0）批量置 1，返回标记行数（可为 0）。幂等：
     * 全已读时返回 0 且不报错；绝不触碰他人消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead() {
        Long userId = requireLogin();
        int rows = messageMapper.markAllByOwner(userId);
        if (rows > 0) {
            log.info("用户 {} 全部标为已读 {} 条", userId, rows);
        }
        return rows;
    }

    private Long requireLogin() {
        Long userId = SecurityUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        return userId;
    }
}