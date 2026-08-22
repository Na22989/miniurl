package com.na22989.miniurl.service;

import com.na22989.miniurl.model.vo.link.LinkStatusVO;

public interface StatusService {

    LinkStatusVO getLinkStatus(Long linkId, Long userId);
}
