package com.exam.config;

import com.exam.user.entity.User;
import com.exam.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * prod 管理员默认口令启动闸（add-prod-profile-enforcements，安全专项发现 3）：
 * prod 启动时检测配置的管理员账号（exam.auth.admin.username）是否仍为公开已知默认口令
 * （application.yml {@code ${ADMIN_PASSWORD:admin123}} 的缺省值）——是则<b>拒绝启动</b>（fail-fast），
 * 杜绝「admin123 未强制改密前全功能可用」（mustChangePassword 仅前端引导，服务端此前不校验）。
 *
 * <p><b>检查口径</b>：对存量哈希做 {@code BCrypt.checkpw(公开缺省口令, 存储哈希)}——
 * 直接命中「口令仍为公开已知值」这一安全事实，与哈希如何产生（初始创建/历史遗留）无关。
 * 只查配置的默认管理员账号：其他 ADMIN 角色账号非「默认账号」不在本闸范围。
 *
 * <p><b>豁免方式（exam.auth.admin.allow-default-password，显式 opt-in）</b>：存量库过渡场景——
 * 置 true 启动（打 WARN），登录后走强制改密流改密，再移除豁免并重启复核。新库无此需要：
 * prod 编排强制要求 ADMIN_PASSWORD 环境变量（docker-compose.prod.yml {@code :?} 必填校验），
 * 初始创建即为强口令、本闸天然通过。
 *
 * <p><b>处置路径（启动中止时按日志执行）</b>：①新库：设置 ADMIN_PASSWORD 后重启；
 * ②存量库：ADMIN_ALLOW_DEFAULT_PASSWORD=true 临时豁免 → 登录改密 → 移除豁免重启复核。
 *
 * <p><b>时序</b>：{@code @Order(3)} 晚于 RolePermissionInitializer(1)/AdminInitializer(2)——
 * 首次启动时管理员已创建，本闸检查的是创建后的最终状态。
 *
 * <p><b>不降级面</b>：仅 prod profile 装配（dev/test 档零影响，本地默认口令是既定便利）；
 * 哈希格式损坏（checkpw 抛 IllegalArgumentException）按非默认口令处理并 WARN（账号本就无法用
 * 该哈希登录，不应由本闸改成另一形态的启动失败）。
 */
@Slf4j
@Component
@Profile("prod")
@Order(3)
public class ProdAdminPasswordGuard implements CommandLineRunner {

    /** 公开已知缺省口令（application.yml ${ADMIN_PASSWORD:admin123} 的缺省段；改缺省值须同笔改此处） */
    static final String KNOWN_DEFAULT_PASSWORD = "admin123";

    private final UserService userService;
    private final AuthProperties props;

    public ProdAdminPasswordGuard(UserService userService, AuthProperties props) {
        this.userService = userService;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        if (props.getAdmin().isAllowDefaultPassword()) {
            log.warn("管理员默认口令检查已显式豁免（exam.auth.admin.allow-default-password=true）——"
                            + "仅限存量库过渡，登录改密后必须移除豁免并重启复核, username={}",
                    props.getAdmin().getUsername());
            return;
        }

        User admin = userService.getByUsername(props.getAdmin().getUsername());
        if (admin == null || admin.getPassword() == null) {
            log.info("管理员默认口令检查通过：配置的管理员账号不存在（或口令未设）, username={}",
                    props.getAdmin().getUsername());
            return;
        }

        boolean stillDefault;
        try {
            stillDefault = BCrypt.checkpw(KNOWN_DEFAULT_PASSWORD, admin.getPassword());
        } catch (IllegalArgumentException e) {
            log.warn("管理员口令哈希格式损坏（非 BCrypt 或盐无效），按非默认口令放行本闸——"
                            + "该账号本就无法以正常口令登录，请人工核查: username={}",
                    props.getAdmin().getUsername());
            return;
        }
        if (stillDefault) {
            log.error("启动中止：prod 检测到管理员 {} 仍使用公开已知默认口令。"
                            + "处置：①新库——设置 ADMIN_PASSWORD 环境变量后重启；"
                            + "②存量库——ADMIN_ALLOW_DEFAULT_PASSWORD=true 临时豁免，登录改密后移除豁免并重启复核。",
                    props.getAdmin().getUsername());
            throw new IllegalStateException(
                    "prod 管理员 " + props.getAdmin().getUsername()
                            + " 仍为公开默认口令，拒绝启动（处置路径见启动日志）");
        }        log.info("管理员默认口令检查通过, username={}", props.getAdmin().getUsername());
    }
}
