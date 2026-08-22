package com.na22989.miniurl.model.vo.link;

import com.na22989.miniurl.model.dto.link.HourlyStatus;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LinkStatusVO {

    private Long linkId;

    private String shortCode;

    // 总点击
    private Long pv;

    // 总独立访客
    private Long uv;

    // 今日点击
    private Long todayPv;

    // 今日独立访客
    private Long todayUv;

    // 近 24h，按小时升序
    private List<HourlyStatus> hourlyTrend;
}
