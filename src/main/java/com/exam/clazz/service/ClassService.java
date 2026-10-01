package com.exam.clazz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.OwnershipGuard;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.clazz.dto.ClassCreateRequest;
import com.exam.clazz.dto.ClassStudentItem;
import com.exam.clazz.dto.ClassUpdateRequest;
import com.exam.clazz.entity.ClassEntity;
import com.exam.clazz.entity.UserClass;
import com.exam.clazz.mapper.ClassMapper;
import com.exam.clazz.mapper.UserClassMapper;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 班级服务（spec「班级管理」：班级 CRUD + 学生入班/移除/转班 + 班级学生列表）。
 *
 * <p>数据隔离：班级写操作前经 {@link #getOwnedClass} 做 owner 校验
 * （复用阶段 2 的 {@link OwnershipGuard} 模式）——教师只能管理自己归属的班级，
 * ADMIN 越级放行；分页查询教师仅见个人班级。
 *
 * <p><b>为什么转班只改 user_class.class_id 而成绩随人</b>（决策记录 §12.6）：
 * 答卷 exam_submissions 以 student_id 绑定学生个人，成绩不依赖班级；
 * 班级只是组织归属，因此转班仅更新关联表 class_id，历史成绩天然跟随学生、
 * 无需迁移——这正是 user_class 作为独立关联表（而非 users 上的冗余字段）的价值。
 *
 * <p>事务统一显式 rollbackFor=Exception.class（见 data-consistency 规范），防未来受检异常静默不回滚。
 */
@Slf4j
@Service
public class ClassService {

    private final ClassMapper classMapper;
    private final UserClassMapper userClassMapper;
    private final UserMapper userMapper;

    public ClassService(ClassMapper classMapper, UserClassMapper userClassMapper, UserMapper userMapper) {
        this.classMapper = classMapper;
        this.userClassMapper = userClassMapper;
        this.userMapper = userMapper;
    }

    /** 创建班级：归属教师 = 当前操作者（本阶段教师自己建班），记录创建人。 */
    @Transactional(rollbackFor = Exception.class)
    public ClassEntity create(ClassCreateRequest request) {
        LoginUser operator = requireLogin();
        ClassEntity clazz = new ClassEntity();
        clazz.setName(request.getName().trim());
        clazz.setCourseId(request.getCourseId());
        // 归属教师与创建人一致：教师建班即自己归属；管理员代建场景（两者不同）后续阶段再开放
        clazz.setTeacherId(operator.getId());
        clazz.setCreatedBy(operator.getId());
        classMapper.insert(clazz);
        log.info("教师 {} 创建班级 id={} name={}", operator.getId(), clazz.getId(), clazz.getName());
        return clazz;
    }

    /** 更新班级（部分更新）：字段为空不修改对应列。 */
    @Transactional(rollbackFor = Exception.class)
    public ClassEntity update(Long id, ClassUpdateRequest request) {
        ClassEntity clazz = getOwnedClass(id);
        if (StringUtils.hasText(request.getName())) {
            String name = request.getName().trim();
            if (name.isBlank()) {
                throw new BusinessException(ResponseCode.BAD_REQUEST, "班级名不能为空");
            }
            clazz.setName(name);
        }
        if (request.getCourseId() != null) {
            clazz.setCourseId(request.getCourseId());
        }
        classMapper.updateById(clazz);
        log.info("班级更新: id={}", id);
        return clazz;
    }

    /** 删除班级（软删）：物理清理该班入班关联，避免残留"无主班级"关联（与题目-标签清理同模式）。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        ClassEntity clazz = getOwnedClass(id);
        userClassMapper.delete(Wrappers.<UserClass>lambdaQuery()
                .eq(UserClass::getClassId, id));
        classMapper.deleteById(id);
        log.info("班级删除: id={} name={}", id, clazz.getName());
    }

    /**
     * 加载班级并做水平越权校验（owner 校验，复用 OwnershipGuard 模式）：
     * 不存在或已软删返回 404；非归属教师返回 403，ADMIN 放行。
     */
    public ClassEntity getOwnedClass(Long id) {
        ClassEntity clazz = classMapper.selectById(id);
        if (clazz == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "班级不存在");
        }
        OwnershipGuard.assertOwner(clazz.getTeacherId(), SecurityUtil.getCurrentUser(), "班级");
        return clazz;
    }

    /**
     * 班级分页：非 ADMIN 仅见自己归属的班级，ADMIN 可见全部。
     * size 封顶 100，防止单页拉全表。
     */
    public Page<ClassEntity> page(long page, long size) {
        LoginUser operator = requireLogin();
        LambdaQueryWrapper<ClassEntity> wrapper = Wrappers.<ClassEntity>lambdaQuery()
                // 非 ADMIN 强制限定归属教师，防止跨教师读取班级
                .eq(operator.getRoleLevel() < RoleHierarchy.levelOf(RoleHierarchy.ADMIN),
                        ClassEntity::getTeacherId, operator.getId())
                .orderByDesc(ClassEntity::getId);
        return classMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
    }

    /** 学生入班：教师/管理员指定学生加入本班；已在班内返回 1001。 */
    @Transactional(rollbackFor = Exception.class)
    public void joinStudent(Long classId, Long userId) {
        getOwnedClass(classId);
        requireStudentExists(userId);
        Long exists = userClassMapper.selectCount(Wrappers.<UserClass>lambdaQuery()
                .eq(UserClass::getUserId, userId)
                .eq(UserClass::getClassId, classId));
        if (exists > 0) {
            throw new BusinessException(ResponseCode.DATA_ALREADY_EXISTS, "该学生已在该班级");
        }
        UserClass link = new UserClass();
        link.setUserId(userId);
        link.setClassId(classId);
        // 服务端时间为准（§1.1）：入班时间显式写入而非依赖前端
        link.setJoinedTime(LocalDateTime.now());
        userClassMapper.insert(link);
        log.info("学生 {} 加入班级 {}", userId, classId);
    }

    /** 学生移出班级：关联不存在返回 404。 */
    @Transactional(rollbackFor = Exception.class)
    public void removeStudent(Long classId, Long userId) {
        getOwnedClass(classId);
        int removed = userClassMapper.delete(Wrappers.<UserClass>lambdaQuery()
                .eq(UserClass::getUserId, userId)
                .eq(UserClass::getClassId, classId));
        if (removed == 0) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "该学生不在该班级");
        }
        log.info("学生 {} 移出班级 {}", userId, classId);
    }

    /**
     * 学生转班（A 班 → B 班）：<b>仅更新 user_class.class_id</b>，成绩随人（§12.6）——
     * 答卷已绑 student_id，成绩不依赖班级，无需迁移任何历史成绩；
     * joined_time 同步刷新为转班时间（对 B 班而言是新归属起点）。
     * 操作归属源班级（路径 classId）的教师执行。
     */
    @Transactional(rollbackFor = Exception.class)
    public void transfer(Long classId, Long userId, Long targetClassId) {
        getOwnedClass(classId);
        requireStudentExists(userId);
        if (classMapper.selectById(targetClassId) == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "目标班级不存在");
        }
        // 预检：uk_user_class(user_id, class_id) 唯一索引兜底，先给清晰业务提示
        Long inTarget = userClassMapper.selectCount(Wrappers.<UserClass>lambdaQuery()
                .eq(UserClass::getUserId, userId)
                .eq(UserClass::getClassId, targetClassId));
        if (inTarget > 0) {
            throw new BusinessException(ResponseCode.DATA_ALREADY_EXISTS, "该学生已在目标班级");
        }
        int updated = userClassMapper.update(null, Wrappers.<UserClass>lambdaUpdate()
                .set(UserClass::getClassId, targetClassId)
                .set(UserClass::getJoinedTime, LocalDateTime.now())
                .eq(UserClass::getUserId, userId)
                .eq(UserClass::getClassId, classId));
        if (updated == 0) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "该学生不在当前班级");
        }
        log.info("学生 {} 从班级 {} 转至 {}", userId, classId, targetClassId);
    }

    /**
     * 班级学生列表：返回该班当前全部学生（含基本信息），越权由对外接口层拦截。
     * 供教师端展示，也是"应考名单推导"的人读版本。
     */
    public List<ClassStudentItem> listStudents(Long classId) {
        getOwnedClass(classId);
        List<UserClass> links = userClassMapper.selectList(Wrappers.<UserClass>lambdaQuery()
                .eq(UserClass::getClassId, classId)
                .orderByAsc(UserClass::getUserId));
        if (links.isEmpty()) {
            return List.of();
        }
        // 批量装载学生信息，避免逐条 N+1
        List<Long> userIds = links.stream().map(UserClass::getUserId).toList();
        Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        return links.stream().map(link -> {
            User user = userMap.get(link.getUserId());
            return new ClassStudentItem(link.getUserId(),
                    user == null ? null : user.getUsername(),
                    user == null ? null : user.getName(),
                    link.getJoinedTime());
        }).toList();
    }

    /**
     * 返回该班级当前全部学生 user id（按 user_id 升序），供"应考名单推导"用（Agent 2 契约，签名固定）。
     *
     * <p>刻意不做 owner 校验：缺考标记等内部链路由定时任务/考试状态机在<b>无登录上下文</b>时触发
     * （此时 SecurityUtil 为空，owner 校验会误抛 401）；越权控制留在对外的班级学生列表接口层。
     */
    public List<Long> listStudentIds(Long classId) {
        return userClassMapper.selectList(Wrappers.<UserClass>lambdaQuery()
                        .eq(UserClass::getClassId, classId)
                        .orderByAsc(UserClass::getUserId))
                .stream().map(UserClass::getUserId).toList();
    }

    /** 学生必须存在（教师指定入班的对象得是真实用户）。 */
    private void requireStudentExists(Long userId) {
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "学生不存在");
        }
    }

    private LoginUser requireLogin() {
        LoginUser operator = SecurityUtil.getCurrentUser();
        if (operator == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        return operator;
    }
}
