package com.exam.auth.service;

import com.exam.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * SMTP 三向超时绑定断言（fix-mail-smtp-timeout-and-catch）：
 *
 * <p>词法护栏（{@link MailSmtpTimeoutGuardTest}）只保证 yml 里声明存在；本用例在真实 Spring
 * 上下文里断言 base {@code application.yml} 的三键真的被 Boot 装配进 {@link MailProperties}
 * （即 {@code JavaMailSenderImpl} 运行期消费的同一个配置对象）——「配置生效」的证据到绑定层为止，
 * dev/prod 档的绑定不在测试上下文装载范围，由护栏防删行回退（见该类 javadoc 局限声明）。
 */
class MailSmtpTimeoutConfigTest extends IntegrationTestBase {

    @Autowired
    private MailProperties mailProperties;

    @Autowired
    private Environment environment;

    @Test
    void smtpTimeoutsBoundIntoMailProperties() {
        for (String key : new String[]{
                "mail.smtp.connectiontimeout", "mail.smtp.timeout", "mail.smtp.writetimeout"}) {
            String bound = mailProperties.getProperties().get(key);
            assertNotNull(bound, "spring.mail.properties." + key + " 未被装配进 MailProperties");
            assertEquals("10000", bound, "spring.mail.properties." + key + " 缺省应为 10000ms");
            assertEquals("10000", environment.getProperty("spring.mail.properties." + key),
                    "Environment 层应可见该键（base application.yml）");
        }
    }
}
