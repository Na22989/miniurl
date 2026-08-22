package com.na22989.miniurl.service;

import com.na22989.miniurl.common.ResultCodeEnum;
import com.na22989.miniurl.exception.BizException;
import com.na22989.miniurl.mapper.LinkAccessLogMapper;
import com.na22989.miniurl.model.dto.link.HourlyStatus;
import com.na22989.miniurl.model.entity.Link;
import com.na22989.miniurl.model.vo.link.LinkStatusVO;
import com.na22989.miniurl.service.impl.StatusServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatusServiceImplTest {

    private static final Long LINK_ID = 1L;
    private static final Long USER_ID = 10L;

    @Mock
    private LinkAccessLogMapper linkAccessLogMapper;

    @Mock
    private LinkService linkService;

    @InjectMocks
    private StatusServiceImpl statusService;

    private Link buildLink() {
        Link link = new Link();
        link.setId(LINK_ID);
        link.setUserId(USER_ID);
        link.setShortCode("abc123");
        link.setClickCount(5);
        return link;
    }

    @Test
    void getLinkStatus_shouldReturnAllFields() {
        Link link = buildLink();
        when(linkService.getById(LINK_ID)).thenReturn(link);
        when(linkAccessLogMapper.countDistinctIpByLinkId(LINK_ID)).thenReturn(3L);
        when(linkAccessLogMapper.selectCount(any())).thenReturn(2L);
        when(linkAccessLogMapper.countDistinctIpByLinkIdAndAccessTimeBetween(eq(LINK_ID), any(), any()))
                .thenReturn(1L);
        when(linkAccessLogMapper.countHourlyTrendByLinkIdAndAccessTimeBetween(eq(LINK_ID), any(), any()))
                .thenReturn(List.of(new HourlyStatus()));

        LinkStatusVO vo = statusService.getLinkStatus(LINK_ID, USER_ID);

        assertEquals(LINK_ID, vo.getLinkId());
        assertEquals("abc123", vo.getShortCode());
        assertEquals(5L, vo.getPv());
        assertEquals(3L, vo.getUv());
        assertEquals(2L, vo.getTodayPv());
        assertEquals(1L, vo.getTodayUv());
        assertEquals(1, vo.getHourlyTrend().size());
    }

    @Test
    void getLinkStatus_shouldThrowBadRequestWhenInvalidId() {
        assertThrows(BizException.class, () -> statusService.getLinkStatus(0L, USER_ID),
                "linkId<=0 应为 BAD_REQUEST");

        assertThrows(BizException.class, () -> statusService.getLinkStatus(null, USER_ID),
                "linkId=null 应为 BAD_REQUEST");
    }

    @Test
    void getLinkStatus_shouldThrowNotFoundWhenLinkNotExist() {
        when(linkService.getById(LINK_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> statusService.getLinkStatus(LINK_ID, USER_ID));
        assertEquals(ResultCodeEnum.LINK_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void getLinkStatus_shouldThrowNotFoundWhenNotOwner() {
        Link link = buildLink();
        link.setUserId(999L); // 别人的链接
        when(linkService.getById(LINK_ID)).thenReturn(link);

        BizException ex = assertThrows(BizException.class,
                () -> statusService.getLinkStatus(LINK_ID, USER_ID));
        assertEquals(ResultCodeEnum.LINK_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void getLinkStatus_shouldQueryTrendByTrailing24Hours() {
        Link link = buildLink();
        when(linkService.getById(LINK_ID)).thenReturn(link);
        when(linkAccessLogMapper.countDistinctIpByLinkId(LINK_ID)).thenReturn(0L);
        when(linkAccessLogMapper.selectCount(any())).thenReturn(0L);
        when(linkAccessLogMapper.countDistinctIpByLinkIdAndAccessTimeBetween(eq(LINK_ID), any(), any()))
                .thenReturn(0L);
        when(linkAccessLogMapper.countHourlyTrendByLinkIdAndAccessTimeBetween(eq(LINK_ID), any(), any()))
                .thenReturn(List.of());

        statusService.getLinkStatus(LINK_ID, USER_ID);

        ArgumentCaptor<LocalDateTime> minCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> maxCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(linkAccessLogMapper).countHourlyTrendByLinkIdAndAccessTimeBetween(
                eq(LINK_ID), minCaptor.capture(), maxCaptor.capture());

        LocalDateTime min = minCaptor.getValue();
        LocalDateTime max = maxCaptor.getValue();
        assertEquals(max.minusHours(24), min, "趋势窗口应为 [now-24h, now] 滑动窗口");
    }
}
