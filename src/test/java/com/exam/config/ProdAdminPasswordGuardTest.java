package com.exam.config;

import com.exam.user.entity.User;
import com.exam.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * prod 管理员默认口令启动闸单测（add-prod-profile-enforcements，安全专项发现 3）：
 * <ul>
 *   <li>默认口令哈希 → fail-fast（IllegalStateException，含处置路径）；</li>
 *   <li>已改密哈希 / 无管理员账号 → 通过；</li>
 *   <li>显式豁免（存量库过渡）→ 放行；</li>
 *   <li>装配契约：仅 prod profile、@Order(3) 晚于 AdminInitializer(2)、CommandLineRunner
 *       （runner 抛出 = Spring 启动中止）。</li>
 * </ul>
 * 单测直连构造（@Profile 由装配契约断言钉住，不起 Spring 上下文——prod yml 依赖外部环境变量）。
 */
class ProdAdminPasswordGuardTest {

    private UserService userService;
    private AuthProperties props;
    private ProdAdminPasswordGuard guard;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        props = new AuthProperties();
        props.getAdmin().setUsername("admin");
        guard = new ProdAdminPasswordGuard(userService, props);
    }

    private User adminWithPassword(String raw) {
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setPassword(BCrypt.hashpw(raw, BCrypt.gensalt()));
        return admin;
    }

    @Test
    void defaultPasswordFailsStartup() {
        when(userService.getByUsername("admin")).thenReturn(adminWithPassword("admin123"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard.run(),
                "prod 检测到公开默认口令必须拒绝启动（fail-fast）");
        assertTrue(ex.getMessage().contains("admin"), "异常信息应指明账号");
    }

    @Test
    void changedPasswordPasses() {
        when(userService.getByUsername("admin")).thenReturn(adminWithPassword("Str0ngPass9"));

        assertDoesNotThrow(() -> guard.run(), "已改密的管理员通过检查");
    }

    @Test
    void explicitExemptionBypasses() {
        when(userService.getByUsername("admin")).thenReturn(adminWithPassword("admin123"));
        props.getAdmin().setAllowDefaultPassword(true);

        assertDoesNotThrow(() -> guard.run(),
                "存量库过渡豁免（显式 opt-in）放行，靠 WARN 与人工复核兜底");
    }

    @Test
    void absentAdminAccountPasses() {
        when(userService.getByUsername("admin")).thenReturn(null);

        assertDoesNotThrow(() -> guard.run(), "无管理员账号时无事可查");
    }

    @Test
    void wiringPinsProdOnlyRunnerAfterInitializer() {
        assertTrue(CommandLineRunner.class.isAssignableFrom(ProdAdminPasswordGuard.class),
                "必须是 CommandLineRunner（runner 抛出即 Spring 启动中止）");
        Profile profile = ProdAdminPasswordGuard.class.getAnnotation(Profile.class);
        assertEquals("prod", String.join(",", profile.value()),
                "必须仅 prod profile 装配（dev/test 本地默认口令是既定便利，不得受影响）");
        Order order = ProdAdminPasswordGuard.class.getAnnotation(Order.class);
        assertEquals(3, order.value(), "必须晚于 AdminInitializer(@Order(2))——检查创建后的最终状态");
    }
}
