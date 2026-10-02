package com.fc.v2;

import com.fc.v2.mapper.auto.TItemCatalogVerMapper;
import com.fc.v2.model.auto.TItemCatalogVer;
import com.fc.v2.service.impl.TItemCatalogVerServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 事项目录条目版次单步调单测：一次一档，与实施清单配置卡冻结步调对齐。
 */
@ExtendWith(MockitoExtension.class)
class TItemCatalogVerServiceImplTest {

    @Mock
    private TItemCatalogVerMapper mapper;
    @InjectMocks
    private TItemCatalogVerServiceImpl service;

    private TItemCatalogVer ver(int stage, int status) {
        TItemCatalogVer v = new TItemCatalogVer();
        v.setId(1L);
        v.setStage(stage);
        v.setStatus(status);
        v.setDelFlag(0);
        return v;
    }

    @Test
    void advance_goes_one_step_not_two() {
        when(mapper.selectById(1L)).thenReturn(ver(0, 1));
        TItemCatalogVer r = service.advance(1L, "推");
        assertNotNull(r);
        assertEquals(Integer.valueOf(1), r.getStage());
        verify(mapper).updateById(any());
    }

    @Test
    void advance_blocked_at_top_or_terminal() {
        when(mapper.selectById(1L)).thenReturn(ver(3, 1));
        assertNull(service.advance(1L, "推"));
        when(mapper.selectById(1L)).thenReturn(ver(1, 2));
        assertNull(service.advance(1L, "推"));
        verify(mapper, never()).updateById(any());
    }

    @Test
    void rollback_goes_one_step_back_not_to_zero() {
        when(mapper.selectById(1L)).thenReturn(ver(2, 1));
        TItemCatalogVer r = service.rollback(1L, "退");
        assertNotNull(r);
        assertEquals(Integer.valueOf(1), r.getStage());
    }

    @Test
    void rollback_blocked_at_bottom_or_terminal() {
        when(mapper.selectById(1L)).thenReturn(ver(0, 1));
        assertNull(service.rollback(1L, "退"));
        when(mapper.selectById(1L)).thenReturn(ver(1, 2));
        assertNull(service.rollback(1L, "退"));
        verify(mapper, never()).updateById(any());
    }
}
