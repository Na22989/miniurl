package com.na22989.miniurl.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.na22989.miniurl.common.ResultCodeEnum;
import com.na22989.miniurl.exception.BizException;
import com.na22989.miniurl.mapper.LinkAccessLogMapper;
import com.na22989.miniurl.model.dto.link.HourlyStatus;
import com.na22989.miniurl.model.entity.Link;
import com.na22989.miniurl.model.entity.LinkAccessLog;
import com.na22989.miniurl.model.vo.link.LinkStatusVO;
import com.na22989.miniurl.service.LinkService;
import com.na22989.miniurl.service.StatusService;
import com.na22989.miniurl.util.StatisticsUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatusServiceImpl implements StatusService {

    private final LinkAccessLogMapper linkAccessLogMapper;

    private final LinkService linkService;

    @Override
    public LinkStatusVO getLinkStatus(Long linkId, Long userId) {
        if (linkId == null || linkId <= 0L) {
            throw new BizException(ResultCodeEnum.BAD_REQUEST);
        }

        // 一次查询校验存在性 + 归属（越权/不存在统一返回 LINK_NOT_FOUND，不暴露存在性）
        Link link = linkService.getById(linkId);
        if (link == null || !link.getUserId().equals(userId)) {
            throw new BizException(ResultCodeEnum.LINK_NOT_FOUND);
        }

        // 总 PV：click_count 是权威值（Redis INCR → 定时落库），link_access_log 是异步尽力而为
        long pv = link.getClickCount() == null ? 0L : link.getClickCount();

        // 总 UV / 今日 PV / 今日 UV（都走 idx_link_id_time 索引）
        LocalDateTime startOfDay = StatisticsUtil.getStartOfDay();
        LocalDateTime endOfDay = StatisticsUtil.getEndOfDay();

        long uv = linkAccessLogMapper.countDistinctIpByLinkId(linkId);

        long todayPv = linkAccessLogMapper.selectCount(new LambdaQueryWrapper<LinkAccessLog>()
                .eq(LinkAccessLog::getLinkId, linkId)
                .ge(LinkAccessLog::getAccessTime, startOfDay)
                .lt(LinkAccessLog::getAccessTime, endOfDay)
        );

        Long todayUv = linkAccessLogMapper.countDistinctIpByLinkIdAndAccessTimeBetween(linkId,
                startOfDay, endOfDay);

        // 近 24h 趋势：滑动窗口 [now-24h, now]
        LocalDateTime now = LocalDateTime.now();
        List<HourlyStatus> hourlyTrend = linkAccessLogMapper.countHourlyTrendByLinkIdAndAccessTimeBetween(linkId,
                now.minusHours(24), now);

        return LinkStatusVO.builder()
                .linkId(link.getId())
                .shortCode(link.getShortCode())
                .hourlyTrend(hourlyTrend)
                .pv(pv)
                .uv(uv)
                .todayUv(todayUv)
                .todayPv(todayPv)
                .build();
    }
}
