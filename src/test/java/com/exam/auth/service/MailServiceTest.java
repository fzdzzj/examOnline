package com.exam.auth.service;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MailService 单元测试（harden-auth-edge-cases）：找回密码邮件文案必须用调用方传入的
 * ttlMinutes 动态生成（「X 分钟内有效」），不得硬编码固定分钟数——配置改 10 分钟后文案不撒谎。
 */
class MailServiceTest {

    private JavaMailSender mailSender;
    private MailService mailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        MailProperties props = new MailProperties();
        props.setHost("smtp.test.local");
        mailService = new MailService(mailSender, props);
        when(mailSender.createMimeMessage())
                .thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void resetCodeBodyCarriesConfiguredTtlMinutes() throws Exception {
        mailService.sendResetCode("a@b.c", "123456", 10);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        String body = (String) captor.getValue().getContent();
        assertTrue(body.contains("10 分钟内有效"), "文案必须使用传入 TTL=10，实际=" + body);
    }

    @Test
    void resetCodeBodyTtlIsNotHardcodedToFive() throws Exception {
        mailService.sendResetCode("b@c.d", "654321", 3);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        String body = (String) captor.getValue().getContent();
        assertTrue(body.contains("3 分钟内有效"), "文案必须使用传入 TTL=3（不得硬编码 5 分钟），实际=" + body);
    }

    /**
     * 捕面核心用例（fix-mail-smtp-timeout-and-catch）：发送链路抛出的 Spring MailException 系
     * （连接/读/写超时、认证失败、SMTP 拒绝最终都以 MailSendException 运行时异常从
     * JavaMailSender.send 冒出）必须与 MessagingException 同口径处置——记 ERROR 日志 +
     * 转 BusinessException(INTERNAL_ERROR) 走 GlobalExceptionHandler 既有语义；
     * 只捕 MessagingException 的旧形态下本用例红（MailSendException 裸穿落 500 兜底）。
     */
    @Test
    void mailSendExceptionIsConvertedToBusinessException() {
        doThrow(new MailSendException("Mail server connection failed: connect timed out"))
                .when(mailSender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> mailService.sendResetCode("a@b.c", "123456", 10));
        assertEquals(ResponseCode.INTERNAL_ERROR.getCode(), ex.getCode(),
                "MailException 系必须转业务异常（INTERNAL_ERROR），不得裸穿 500 兜底");
        assertEquals("验证码发送失败，请稍后重试或联系管理员", ex.getMessage(),
                "失败文案与既有 MessagingException 分支同语义（诚实失败，不吞不打 500 裸奔）");
    }

    /**
     * <b>MessagingException 路径不设运行期回归用例</b>（两次尝试后如实裁撤）：
     * ①jakarta Mail 对 "not-an-email" 这类输入在 helper.setTo 处并不抛（宽松解析，实测 nothing
     * was thrown），天然触发不可靠；②在 mock MimeMessage 的 addRecipient 处显式注入打桩，
     * 与 Spring MimeMessageHelper 内部实际委派路径（setRecipients 系）不匹配，同样空转。
     * 该臂不需要运行期护栏：multi-catch 摘掉 MessagingException 即受检异常未捕获的编译错误
     * （编译器守护）；catch 体语义（码/文案）已由上一用例经 MailException 臂覆盖——两个臂共享
     * 同一 catch 子句体。
     */
}
