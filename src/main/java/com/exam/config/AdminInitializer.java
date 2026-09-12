package com.exam.config;

import com.exam.auth.security.RoleHierarchy;
import com.exam.user.entity.User;
import com.exam.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 管理员初始化（add-authentication）：
 * 启动时检测数据库中不存在任何 ADMIN 角色账号，则创建默认管理员并绑定 ADMIN 角色与全量权限
 * （角色/权限/角色-权限绑定由 RolePermissionInitializer 先行完成，本组件 @Order(2) 依赖其 @Order(1)）。
 * 已存在管理员时不重复创建（幂等）。
 */
@Slf4j
@Component
@Order(2)
public class AdminInitializer implements CommandLineRunner {

    private final UserService userService;
    private final AuthProperties props;

    public AdminInitializer(UserService userService, AuthProperties props) {
        this.userService = userService;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        // 已存在 ADMIN 角色账号 → 跳过（防重复创建）
        if (userService.countUsersWithRole(RoleHierarchy.ADMIN) > 0) {
            log.info("已存在管理员账号，跳过管理员初始化");
            return;
        }

        String username = props.getAdmin().getUsername();
        if (userService.existsByUsername(username)) {
            // 账号名被普通账号占用：不覆盖既有账号，仅告警（人工介入）
            log.warn("默认管理员账号 {} 已被占用，跳过管理员初始化（请人工处理）", username);
            return;
        }

        User admin = new User();
        admin.setUsername(username);
        admin.setPassword(BCrypt.hashpw(props.getAdmin().getPassword(), BCrypt.gensalt()));
        admin.setName("系统管理员");
        admin.setStatus(0);
        admin.setMustChangePassword(0);
        userService.insert(admin);
        userService.bindRole(admin.getId(), userService.getRoleByCode(RoleHierarchy.ADMIN).getId());

        log.info("首次启动：已创建默认管理员 username={}（请尽快通过环境变量 ADMIN_PASSWORD 修改）", username);
    }
}
