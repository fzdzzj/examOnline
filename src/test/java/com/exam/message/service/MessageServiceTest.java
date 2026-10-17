package com.exam.message.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.message.entity.UserMessage;
import com.exam.message.mapper.UserMessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站内消息服务单元测试（harden-low-risk-security-residues）：
 * <ul>
 *   <li>limit 上界与下界安全钳制（[1, 100]）；</li>
 *   <li>未登录防护（requireLogin）；</li>
 *   <li>未读计数与标记已读。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private UserMessageMapper messageMapper;

    @InjectMocks
    private MessageService messageService;

    private static final Long TEST_USER_ID = 42L;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserMessage.class);
        LoginUser loginUser = new LoginUser();
        loginUser.setId(TEST_USER_ID);
        loginUser.setUsername("test-user");
        SecurityUtil.set(loginUser);
    }

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    @DisplayName("正常 limit 传参时按原值拼接 LIMIT")
    @SuppressWarnings("unchecked")
    void listMineNormalLimit() {
        when(messageMapper.selectList(any())).thenReturn(Collections.emptyList());

        messageService.listMine(20);

        ArgumentCaptor<LambdaQueryWrapper<UserMessage>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(messageMapper).selectList(captor.capture());
        LambdaQueryWrapper<UserMessage> wrapper = captor.getValue();
        assertTrue(wrapper.getCustomSqlSegment().contains("LIMIT 20"),
                "正常范围内的 limit 应该原样生效");
    }

    @Test
    @DisplayName("超大 limit（如 1000）安全截断为 100")
    @SuppressWarnings("unchecked")
    void listMineClampsOverLimitTo100() {
        when(messageMapper.selectList(any())).thenReturn(Collections.emptyList());

        messageService.listMine(1000);

        ArgumentCaptor<LambdaQueryWrapper<UserMessage>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(messageMapper).selectList(captor.capture());
        LambdaQueryWrapper<UserMessage> wrapper = captor.getValue();
        assertTrue(wrapper.getCustomSqlSegment().contains("LIMIT 100"),
                "超大 limit 必须被钳制在 100，防止慢查询与 OOM");
    }

    @Test
    @DisplayName("非正数 limit（0 或负数）回退为 1，杜绝无界全表扫描")
    @SuppressWarnings("unchecked")
    void listMineClampsNonPositiveLimitTo1() {
        when(messageMapper.selectList(any())).thenReturn(Collections.emptyList());

        messageService.listMine(0);

        ArgumentCaptor<LambdaQueryWrapper<UserMessage>> captor0 = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(messageMapper).selectList(captor0.capture());
        assertTrue(captor0.getValue().getCustomSqlSegment().contains("LIMIT 1"),
                "0 必须回退为 LIMIT 1，杜绝无界查询");

        messageService.listMine(-5);
        ArgumentCaptor<LambdaQueryWrapper<UserMessage>> captorNeg = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(messageMapper, times(2)).selectList(captorNeg.capture());
        assertTrue(captorNeg.getValue().getCustomSqlSegment().contains("LIMIT 1"),
                "负数必须回退为 LIMIT 1");
    }

    @Test
    @DisplayName("未登录访问 listMine 抛出 TOKEN_INVALID")
    void listMineUnauthenticatedThrows() {
        SecurityUtil.clear();

        BusinessException ex = assertThrows(BusinessException.class, () -> messageService.listMine(10));
        assertEquals(ResponseCode.TOKEN_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("unreadCount 正常统计未读消息数")
    void unreadCountReturnsCount() {
        when(messageMapper.selectCount(any())).thenReturn(5L);

        long count = messageService.unreadCount();
        assertEquals(5L, count);

        when(messageMapper.selectCount(any())).thenReturn(null);
        assertEquals(0L, messageService.unreadCount());
    }

    @Test
    @DisplayName("markRead 标记本人未读消息为已读")
    void markReadSuccess() {
        when(messageMapper.markReadByOwner(100L, TEST_USER_ID)).thenReturn(1);

        messageService.markRead(100L);
        verify(messageMapper).markReadByOwner(100L, TEST_USER_ID);
    }

    @Test
    @DisplayName("markRead 消息不存在或非本人抛出 NOT_FOUND")
    void markReadNotFoundThrows() {
        when(messageMapper.markReadByOwner(999L, TEST_USER_ID)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> messageService.markRead(999L));
        assertEquals(ResponseCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("markAllRead 批量标记本人全部消息为已读")
    void markAllReadSuccess() {
        when(messageMapper.markAllByOwner(TEST_USER_ID)).thenReturn(3);

        int updated = messageService.markAllRead();
        assertEquals(3, updated);
        verify(messageMapper).markAllByOwner(TEST_USER_ID);
    }
}
