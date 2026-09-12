package com.exam.auth.service;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 邮件服务（找回密码验证码）：
 * SMTP 未配置（本地开发）时验证码走日志兜底，保证流程可跑通；
 * 发送失败记录日志并抛出业务异常，管理员可兜底重置。
 */
@Slf4j
@Service
public class MailService {

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;

    public MailService(@Autowired(required = false) JavaMailSender mailSender,
                       @Autowired(required = false) MailProperties mailProperties) {
        this.mailSender = mailSender;
        this.mailProperties = mailProperties;
    }

    /** 发送单次重置验证码；SMTP 未配置则日志兜底（不阻塞找回流程）。 */
    public void sendResetCode(String email, String code) {
        boolean smtpReady = mailSender != null && mailProperties != null
                && StringUtils.hasText(mailProperties.getHost());
        if (!smtpReady) {
            log.warn("SMTP 未配置，找回密码验证码走日志兜底: email={}, code={}", email, code);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setTo(email);
            helper.setSubject("examOnline 密码重置验证码");
            helper.setText("您的密码重置验证码是：" + code + "，5 分钟内有效，请勿泄露。", false);
            mailSender.send(message);
            log.info("找回密码验证码已发送: email={}", email);
        } catch (MessagingException e) {
            log.error("找回密码验证码发送失败: email={}", email, e);
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "验证码发送失败，请稍后重试或联系管理员");
        }
    }
}
