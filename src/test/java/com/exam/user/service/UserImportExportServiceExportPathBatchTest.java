package com.exam.user.service;

import com.exam.auth.security.RoleHierarchy;
import com.exam.clazz.mapper.ClassMapper;
import com.exam.clazz.mapper.UserClassMapper;
import com.exam.user.entity.Role;
import com.exam.user.entity.User;
import com.exam.user.entity.UserRole;
import com.exam.user.mapper.RoleMapper;
import com.exam.user.mapper.UserMapper;
import com.exam.user.mapper.UserRoleMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户导出「经导出路径」分批断言（harden-review-flow-and-resource-limits M-06）：
 * 仅引用公开 API（{@code exportUsers}）与 Mapper 接口——在实现前（或变异 m2
 * 把 {@code batchSelectUsers} 调用点恢复为一次性 {@code selectBatchIds} 后）
 * 本类可正常编译，仅分批调用次数断言变红，是 m2 击红的指定载体。
 */
@ExtendWith(MockitoExtension.class)
class UserImportExportServiceExportPathBatchTest {

    @Mock
    private UserService userService;
    @Mock
    private UserMapper userMapper;
    @Mock
    private RoleMapper roleMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private ClassMapper classMapper;
    @Mock
    private UserClassMapper userClassMapper;
    @Mock
    private PlatformTransactionManager transactionManager;

    private UserImportExportService service;

    @BeforeEach
    void setUp() {
        service = new UserImportExportService(userService, userMapper, roleMapper,
                userRoleMapper, classMapper, userClassMapper, transactionManager, 1000);
    }

    @Test
    @DisplayName("exportTeachers 遇 1200 名教师时 selectBatchIds 按 3 片（500/500/200）调用且导出完整")
    void exportTeachers_withOver500Users_queriesInBoundedBatches() throws Exception {
        Role teacherRole = new Role();
        teacherRole.setId(2L);
        teacherRole.setCode(RoleHierarchy.TEACHER);
        when(userService.getRoleByCode(RoleHierarchy.TEACHER)).thenReturn(teacherRole);

        List<UserRole> urs = new ArrayList<>();
        for (long id = 1; id <= 1200; id++) {
            UserRole ur = new UserRole();
            ur.setUserId(id);
            ur.setRoleId(2L);
            urs.add(ur);
        }
        when(userRoleMapper.selectList(any())).thenReturn(urs);
        when(userMapper.selectBatchIds(anyList())).thenAnswer(inv -> {
            List<Long> slice = inv.getArgument(0);
            return slice.stream().map(id -> {
                User u = new User();
                u.setId(id);
                u.setUsername("u" + id);
                u.setName("用户" + id);
                return u;
            }).toList();
        });

        byte[] bytes = service.exportUsers("teacher", null);

        // 分批断言：>500 的教师必须触发 3 次 selectBatchIds（m2 变异恢复一次性调用时此处变红）
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(userMapper, times(3)).selectBatchIds(captor.capture());
        assertEquals(500, captor.getAllValues().get(0).size());
        assertEquals(500, captor.getAllValues().get(1).size());
        assertEquals(200, captor.getAllValues().get(2).size());

        // 结果集完整性与顺序：表头 + 1200 行，首行是最小 ID 用户
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals(1200, sheet.getLastRowNum(), "表头 + 1200 行教师");
            Row first = sheet.getRow(1);
            assertEquals("u1", first.getCell(0).getStringCellValue(), "首行为最小 ID 用户");
        }
    }
}
