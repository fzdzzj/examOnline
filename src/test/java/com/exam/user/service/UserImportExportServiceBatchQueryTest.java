package com.exam.user.service;

import com.exam.clazz.mapper.ClassMapper;
import com.exam.clazz.mapper.UserClassMapper;
import com.exam.user.entity.User;
import com.exam.user.mapper.RoleMapper;
import com.exam.user.mapper.UserMapper;
import com.exam.user.mapper.UserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户导出有界分批查询护栏（harden-review-flow-and-resource-limits M-06）：
 *
 * <p>构造性实证——超过 500 个 ID 时 {@code userMapper.selectBatchIds} 按
 * {@value UserImportExportService#BATCH_SIZE} 一片分多次调用（每片至多 500），
 * 杜绝单条巨型 {@code WHERE id IN (...)}（MySQL 报文上限 + 大对象堆积）；
 *
 * <p>结果集顺序与既有「一次 selectBatchIds + 按 ID 升序」口径完全一致。
 * 经导出路径（exportTeachers）断言分批次数的用例见
 * {@link UserImportExportServiceExportPathBatchTest}（变异 m2 敏感）。
 */
@ExtendWith(MockitoExtension.class)
class UserImportExportServiceBatchQueryTest {

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

    private static List<Long> ids(int count) {
        List<Long> ids = new ArrayList<>(count);
        for (long i = 1; i <= count; i++) {
            ids.add(i);
        }
        return ids;
    }

    /** selectBatchIds 按传入切片返回对应用户（id=切片元素，username=u+id）。 */
    private void stubBatchReturnsUsers() {
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
    }

    // ==================== ① 分批切片（直接调用 batchSelectUsers） ====================

    @Test
    @DisplayName("1200 个 ID 分 3 片（500/500/200）查询，结果按 ID 升序且完整")
    void batchSelectUsers_over500_splitsIntoBoundedSlices() {
        stubBatchReturnsUsers();

        List<User> users = service.batchSelectUsers(ids(1200));

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(userMapper, times(3)).selectBatchIds(captor.capture());
        List<List<Long>> slices = captor.getAllValues();
        assertEquals(500, slices.get(0).size(), "第 1 片至多 500");
        assertEquals(500, slices.get(1).size(), "第 2 片至多 500");
        assertEquals(200, slices.get(2).size(), "尾片 = 1200 - 1000");

        assertEquals(1200, users.size(), "分批累加结果完整");
        assertEquals("u1", users.get(0).getUsername(), "按 ID 升序首元素");
        assertEquals("u1200", users.get(users.size() - 1).getUsername(), "按 ID 升序尾元素");
        for (int i = 1; i < users.size(); i++) {
            assertTrue(users.get(i - 1).getId() < users.get(i).getId(), "全程按 ID 升序");
        }
    }

    @Test
    @DisplayName("≤500 个 ID 时单次查询，不拆分")
    void batchSelectUsers_underOrEqual500_singleCall() {
        stubBatchReturnsUsers();

        List<User> users = service.batchSelectUsers(ids(300));

        verify(userMapper, times(1)).selectBatchIds(anyList());
        assertEquals(300, users.size());
    }

    @Test
    @DisplayName("空 ID 列表零查询，返回空结果")
    void batchSelectUsers_empty_noCalls() {
        List<User> users = service.batchSelectUsers(List.of());

        verify(userMapper, times(0)).selectBatchIds(anyList());
        assertTrue(users.isEmpty());
    }

    @Test
    @DisplayName("BATCH_SIZE 常量为 500：分批上限有界且与文档一致")
    void batchSizeConstant_is500() {
        assertEquals(500, UserImportExportService.BATCH_SIZE);
    }
}
