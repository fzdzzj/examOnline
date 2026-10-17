package com.exam.auth.service;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 邮件服务（找回密码验证码）：
 * SMTP 未配置（本地开发）时验证码走日志兜底，保证流程可跑通；
 * 发送失败记录日志并抛出业务异常，管理员可兜底重置。
 *
 * <p><b>日志兜底受配置门禁（add-prod-profile-enforcements，安全专项发现 4）</b>：
 * {@code exam.auth.reset-code.allow-log-fallback}——base 默认 false（安全缺省：验证码明文落日志，
 * 5 分钟 TTL 内有日志读权限者可接管任意账号）；dev/test 档显式 true 保留本地便利；prod 未覆盖
 * 即继承 false——SMTP 未配置时<b>不再明文落日志，改为功能明确报错不可用</b>（诚实的 500 级
 * 业务异常，绝不泄漏验证码）。
 */
@Slf4j
@Service
public class MailService {

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;
    private final boolean allowLogFallback;

    public MailService(@Autowired(required = false) JavaMailSender mailSender,
                       @Autowired(required = false) MailProperties mailProperties,
                       @Value("${exam.auth.reset-code.allow-log-fallback:false}") boolean allowLogFallback) {
        this.mailSender = mailSender;
        this.mailProperties = mailProperties;
        this.allowLogFallback = allowLogFallback;
    }

    /**
     * 发送单次重置验证码；SMTP 未配置则日志兜底（不阻塞找回流程）。
     * 文案有效期用调用方传入的 {@code ttlMinutes} 动态生成，与配置不漂移。
     *
     * <p><b>捕面 = {@code MessagingException | MailException} 系（勿收窄）</b>：Spring 发送链路上
     * 连接/读/写超时、认证失败、SMTP 拒绝等最终都以 {@code MailException} 系（{@code MailSendException}
     * extends {@code MailException}，运行时异常）从 {@link JavaMailSender#send} 冒出——超时由
     * {@code spring.mail.properties} 的三向超时配置（fix-mail-smtp-timeout-and-catch，缺省各 10000ms）
     * 触发。只捕 {@code MessagingException} 的旧形态罩不住运行时的 MailException 系，异常直穿
     * GlobalExceptionHandler 的 {@code Exception} 兜底裸 500「系统内部错误」。两个分支处置语义
     * 完全一致：记 ERROR 日志（不吞不打折）+ 转同一业务异常，走既有诚实失败口径。
     */
    public void sendResetCode(String email, String code, int ttlMinutes) {
        boolean smtpReady = mailSender != null && mailProperties != null
                && StringUtils.hasText(mailProperties.getHost());
        if (!smtpReady) {
            if (!allowLogFallback) {
                // prod（及一切未显式开启兜底的档）：验证码绝不明文落日志——功能明确报错不可用。
                // 注意此处与下方日志兜底分支的 WARN 不同：不含 code（防 5 分钟 TTL 内日志接管账号）
                log.warn("SMTP 未配置且日志兜底未启用（exam.auth.reset-code.allow-log-fallback=false），"
                        + "找回密码功能不可用: email={}", email);
                throw new BusinessException(ResponseCode.INTERNAL_ERROR,
                        "邮件服务未配置，找回密码功能暂不可用，请联系管理员");
            }
            log.warn("SMTP 未配置，找回密码验证码走日志兜底: email={}, code={}", email, code);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setTo(email);
            helper.setSubject("examOnline 密码重置验证码");
            helper.setText("您的密码重置验证码是：" + code + "，" + ttlMinutes + " 分钟内有效，请勿泄露。", false);
            mailSender.send(message);
            log.info("找回密码验证码已发送: email={}", email);
        } catch (MessagingException | MailException e) {
            log.error("找回密码验证码发送失败: email={}", email, e);
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "验证码发送失败，请稍后重试或联系管理员");
        }
    }
}
