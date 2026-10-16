package com.exam.auth.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exam.auth.entity.InviteCode;
import com.exam.auth.mapper.InviteCodeMapper;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InviteCodeService.markUsed 单元测试（harden-auth-edge-cases）：
 * 定向原子 UPDATE（used_count = used_count + 1 且限定 status=0）替代读-改-写整实体回写；
 * 并发「作废 + 使用」时 UPDATE 影响 0 行必须抛 INVITE_CODE_INVALID，杜绝复活已作废码。
 */
class InviteCodeServiceTest {

    private InviteCodeMapper mapper;
    private InviteCodeService service;

    @BeforeEach
    void setUp() {
        // 纯单测无 Spring 容器：显式初始化实体的 TableInfo，让 LambdaUpdateWrapper 能解析列名
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InviteCode.class);
        mapper = mock(InviteCodeMapper.class);
        service = new InviteCodeService(mapper);
    }

    @Test
    void markUsedIssuesTargetedAtomicIncrementUpdateNotWholeEntity() {
        InviteCode ic = new InviteCode();
        ic.setId(7L);
        ic.setCode("ABC23456");
        ic.setStatus(0);
        ic.setUsedCount(2);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.markUsed(ic);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), wrapperCaptor.capture());
        LambdaUpdateWrapper<InviteCode> wrapper = wrapperCaptor.getValue();
        assertTrue(wrapper.getSqlSet().contains("used_count"), "必须定向 SQL 自增 used_count，而非整实体回写");
        assertTrue(wrapper.getSqlSet().contains("+"), "自增表达式必须存在（used_count = used_count + 1）");
        // MP 3.5.9 惰性物化：先取 SQL 片段触发参数格式化，再读条件参数值
        String where = wrapper.getSqlSegment();
        assertTrue(where.contains("id"), "UPDATE 必须按 id 定向，where=" + where);
        assertTrue(where.contains("status"), "UPDATE 必须限定 status=0，where=" + where);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(7L),
                "UPDATE 必须按 id 定向，params=" + wrapper.getParamNameValuePairs());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(0),
                "UPDATE 必须限定 status=0（仅有效码可增量），params=" + wrapper.getParamNameValuePairs());
        verify(mapper, never()).updateById(any(InviteCode.class));
    }

    @Test
    void markUsedThrowsInviteCodeInvalidWhenNoActiveRowUpdated() {
        InviteCode ic = new InviteCode();
        ic.setId(7L);
        ic.setCode("ABC23456");
        ic.setStatus(0);
        when(mapper.update(isNull(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.markUsed(ic));
        assertEquals(ResponseCode.INVITE_CODE_INVALID.getCode(), ex.getCode());
    }
}
