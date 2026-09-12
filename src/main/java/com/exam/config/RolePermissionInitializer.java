package com.exam.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.user.entity.Permission;
import com.exam.user.entity.Role;
import com.exam.user.entity.RolePermission;
import com.exam.user.mapper.PermissionMapper;
import com.exam.user.mapper.RoleMapper;
import com.exam.user.mapper.RolePermissionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 角色/权限预置：启动时幂等插入 ADMIN/TEACHER/STUDENT 角色与初始权限点，并绑定角色-权限。
 */
@Slf4j
@Component
@Order(1)
public class RolePermissionInitializer implements CommandLineRunner {

    private static final Map<String, Integer> ROLE_LEVELS = Map.of(
            "ADMIN", 3,
            "TEACHER", 2,
            "STUDENT", 1
    );

    /** 权限点 -> 说明 */
    private static final Map<String, String> PERMISSIONS = Map.ofEntries(
            Map.entry("user:view", "查看用户"),
            Map.entry("user:manage", "管理用户（禁用/启用/踢人）"),
            Map.entry("invite:manage", "管理教师邀请码"),
            Map.entry("question:manage", "题库管理"),
            Map.entry("paper:manage", "组卷管理"),
            Map.entry("exam:manage", "考试管理"),
            Map.entry("exam:take", "参加考试"),
            Map.entry("score:view", "查看成绩")
    );

    /** 角色 -> 权限点 */
    private static final Map<String, List<String>> ROLE_PERMISSIONS = Map.of(
            "ADMIN", List.of("user:view", "user:manage", "invite:manage",
                    "question:manage", "paper:manage", "exam:manage", "exam:take", "score:view"),
            "TEACHER", List.of("question:manage", "paper:manage", "exam:manage", "score:view"),
            "STUDENT", List.of("exam:take", "score:view")
    );

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;

    public RolePermissionInitializer(RoleMapper roleMapper, PermissionMapper permissionMapper,
                                     RolePermissionMapper rolePermissionMapper) {
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    @Override
    public void run(String... args) {
        for (Map.Entry<String, Integer> e : ROLE_LEVELS.entrySet()) {
            if (roleMapper.selectCount(Wrappers.<Role>lambdaQuery().eq(Role::getCode, e.getKey())) == 0) {
                Role role = new Role();
                role.setCode(e.getKey());
                role.setName(e.getKey());
                role.setLevel(e.getValue());
                roleMapper.insert(role);
            }
        }

        for (Map.Entry<String, String> e : PERMISSIONS.entrySet()) {
            if (permissionMapper.selectCount(
                    Wrappers.<Permission>lambdaQuery().eq(Permission::getCode, e.getKey())) == 0) {
                Permission p = new Permission();
                p.setCode(e.getKey());
                p.setName(e.getValue());
                permissionMapper.insert(p);
            }
        }

        for (Map.Entry<String, List<String>> e : ROLE_PERMISSIONS.entrySet()) {
            Role role = roleMapper.selectOne(Wrappers.<Role>lambdaQuery().eq(Role::getCode, e.getKey()));
            if (role == null) {
                continue;
            }
            for (String permCode : e.getValue()) {
                Permission p = permissionMapper.selectOne(
                        Wrappers.<Permission>lambdaQuery().eq(Permission::getCode, permCode));
                if (p == null) {
                    continue;
                }
                long exists = rolePermissionMapper.selectCount(Wrappers.<RolePermission>lambdaQuery()
                        .eq(RolePermission::getRoleId, role.getId())
                        .eq(RolePermission::getPermissionId, p.getId()));
                if (exists == 0) {
                    RolePermission rp = new RolePermission();
                    rp.setRoleId(role.getId());
                    rp.setPermissionId(p.getId());
                    rolePermissionMapper.insert(rp);
                }
            }
        }
        log.info("RBAC 角色/权限预置完成");
    }
}
