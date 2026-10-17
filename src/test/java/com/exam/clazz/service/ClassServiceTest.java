package com.exam.clazz.service;

import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import com.exam.clazz.entity.ClassEntity;
import com.exam.clazz.mapper.ClassMapper;
import com.exam.clazz.mapper.UserClassMapper;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.mapper.ExamMapper;
import com.exam.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 班级服务单测（harden-cross-cutting-edge-cases 阶段 2）：
 * 验证班级删除的前置考试引用检查阻断与放行。
 */
@ExtendWith(MockitoExtension.class)
class ClassServiceTest {

    @Mock
    private ClassMapper classMapper;
    @Mock
    private UserClassMapper userClassMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ExamMapper examMapper;

    @InjectMocks
    private ClassService classService;

    @AfterEach
    void clearSecurity() {
        SecurityUtil.clear();
    }

    private void setTeacher(Long teacherId) {
        LoginUser user = new LoginUser();
        user.setId(teacherId);
        user.setRoleLevel(2); // TEACHER
        SecurityUtil.set(user);
    }

    @Test
    @DisplayName("删除班级：无关联考试时放行，清理 user_class 关联并软删班级")
    void deleteSucceedsWhenNoExamReferences() {
        setTeacher(10L);
        ClassEntity clazz = new ClassEntity();
        clazz.setId(100L);
        clazz.setTeacherId(10L);
        clazz.setName("一班");
        when(classMapper.selectById(100L)).thenReturn(clazz);
        when(examMapper.selectCount(any())).thenReturn(0L);

        classService.delete(100L);

        verify(examMapper).selectCount(any());
        verify(userClassMapper).delete(any());
        verify(classMapper).deleteById(100L);
    }

    @Test
    @DisplayName("删除班级：存在关联考试时阻断，抛 400 且不执行删除")
    void deleteBlockedWhenExamReferencesExist() {
        setTeacher(10L);
        ClassEntity clazz = new ClassEntity();
        clazz.setId(100L);
        clazz.setTeacherId(10L);
        clazz.setName("一班");
        when(classMapper.selectById(100L)).thenReturn(clazz);
        when(examMapper.selectCount(any())).thenReturn(2L);

        BusinessException e = assertThrows(BusinessException.class, () -> classService.delete(100L));

        assertEquals(ResponseCode.BAD_REQUEST.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("该班级已被考试引用，不允许删除"));
        verify(userClassMapper, never()).delete(any());
        verify(classMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除班级：班级不存在时抛 404")
    void deleteFailsWhenClassNotFound() {
        setTeacher(10L);
        when(classMapper.selectById(999L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class, () -> classService.delete(999L));

        assertEquals(ResponseCode.NOT_FOUND.getCode(), e.getCode());
        verify(examMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("删除班级：非归属教师且非 ADMIN 抛 403 水平越权")
    void deleteFailsWhenNotOwner() {
        setTeacher(20L); // 当前登录教师 20
        ClassEntity clazz = new ClassEntity();
        clazz.setId(100L);
        clazz.setTeacherId(10L); // 班级归属教师 10
        clazz.setName("一班");
        when(classMapper.selectById(100L)).thenReturn(clazz);

        BusinessException e = assertThrows(BusinessException.class, () -> classService.delete(100L));

        assertEquals(ResponseCode.FORBIDDEN.getCode(), e.getCode());
        verify(examMapper, never()).selectCount(any());
    }
}
