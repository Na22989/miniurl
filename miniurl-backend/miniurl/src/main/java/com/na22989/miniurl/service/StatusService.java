package com.na22989.miniurl.service;

import com.na22989.miniurl.model.vo.link.LinkStatusVO;

public interface StatusService {

    /**
     * 查询短链接访问统计：总 PV/UV、今日 PV/UV、近 24h 小时趋势
     *
     * @param linkId 链接 ID
     * @param userId 用户 ID（校验归属）
     * @return 访问统计
     * @throws BizException 参数非法 BAD_REQUEST；不存在或非本人 LINK_NOT_FOUND
     */
    LinkStatusVO getLinkStatus(Long linkId, Long userId);
}
