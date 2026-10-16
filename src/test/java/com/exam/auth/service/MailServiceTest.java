package com.exam.auth.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
