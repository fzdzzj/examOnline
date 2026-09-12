package com.exam.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.user.entity.Permission;
import com.exam.user.entity.Role;
import com.exam.user.entity.RolePermission;
import com.exam.user.entity.User;
import com.exam.user.entity.UserRole;
import com.exam.user.mapper.PermissionMapper;
import com.exam.user.mapper.RoleMapper;
import com.exam.user.mapper.RolePermissionMapper;
import com.exam.user.mapper.UserMapper;
import com.exam.user.mapper.UserRoleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 账号领域服务：账号查询、角色/权限装载、绑定角色、改密码。
 * 认证模块（auth）依赖本服务完成注册、登录、签发前的数据准备。
 */
@Service
public class UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;

    public UserService(UserMapper userMapper, RoleMapper roleMapper, PermissionMapper permissionMapper,
                       UserRoleMapper userRoleMapper, RolePermissionMapper rolePermissionMapper) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    // ---------- 账号查询 ----------

    public User getById(Long id) {
        return userMapper.selectById(id);
    }

    public User getByUsername(String username) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }

    public User getByEmail(String email) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getEmail, email));
    }

    public boolean existsByUsername(String username) {
        return userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getUsername, username)) > 0;
    }

    public void insert(User user) {
        userMapper.insert(user);
    }

    // ---------- 角色 / 权限 ----------

    public Role getRoleByCode(String code) {
        return roleMapper.selectOne(Wrappers.<Role>lambdaQuery().eq(Role::getCode, code));
    }

    /** 绑定用户-角色（注册/初始化用）。 */
    public void bindRole(Long userId, Long roleId) {
        UserRole ur = new UserRole();
        ur.setUserId(userId);
        ur.setRoleId(roleId);
        userRoleMapper.insert(ur);
    }

    /** 拥有某角色的账号数（管理员初始化幂等判断用）。 */
    public long countUsersWithRole(String roleCode) {
        Role role = getRoleByCode(roleCode);
        if (role == null) {
            return 0;
        }
        return userRoleMapper.selectCount(
                Wrappers.<UserRole>lambdaQuery().eq(UserRole::getRoleId, role.getId()));
    }

    /** 用户的全部角色。 */
    public List<Role> listRoles(Long userId) {
        List<UserRole> urs = userRoleMapper.selectList(
                Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId));
        if (urs.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> roleIds = urs.stream().map(UserRole::getRoleId).toList();
        return roleMapper.selectBatchIds(roleIds);
    }

    /** 用户的全部权限点（经角色-权限关联去重）。 */
    public List<Permission> listPermissions(Long userId) {
        List<Role> roles = listRoles(userId);
        if (roles.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> roleIds = roles.stream().map(Role::getId).toList();
        List<Long> permissionIds = rolePermissionMapper.selectList(
                        Wrappers.<RolePermission>lambdaQuery()
                                .in(RolePermission::getRoleId, roleIds))
                .stream().map(RolePermission::getPermissionId).distinct().toList();
        if (permissionIds.isEmpty()) {
            return Collections.emptyList();
        }
        return permissionMapper.selectBatchIds(permissionIds);
    }

    // ---------- 密码 ----------

    /** 更新密码哈希（BCrypt）。 */
    public void updatePassword(Long userId, String bcryptHash) {
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getPassword, bcryptHash));
    }

    // ---------- 登录上下文装载 ----------

    /**
     * 由账号实体装载登录上下文（角色码集合 + 最高层级 + 权限点集合）。
     * 会话版本与 jti 由令牌层（JwtUtil/TokenStoreService）填充。
     */
    public LoginUser buildLoginUser(User user) {
        LoginUser lu = new LoginUser();
        lu.setId(user.getId());
        lu.setUsername(user.getUsername());
        lu.setName(user.getName());
        lu.setEmail(user.getEmail());

        Set<String> roleCodes = listRoles(user.getId()).stream()
                .map(Role::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        lu.setRoles(roleCodes);
        lu.setRoleLevel(RoleHierarchy.maxLevel(roleCodes));
        lu.setPermissions(listPermissions(user.getId()).stream()
                .map(Permission::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return lu;
    }
}
