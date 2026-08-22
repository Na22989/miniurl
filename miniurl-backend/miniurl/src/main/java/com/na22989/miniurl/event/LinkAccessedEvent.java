package com.na22989.miniurl.event;

import com.na22989.miniurl.model.dto.link.AccessMeta;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 短链访问事件（不可变值对象，发布后不应被修改）
 */
@Getter
@AllArgsConstructor
public class LinkAccessedEvent {

    private final String shortCode;

    private final Long linkId;

    private final String ip;

    private final String userAgent;

    private final String referer;

    public LinkAccessedEvent(String shortCode, Long linkId, AccessMeta accessMeta) {
        this(shortCode, linkId, accessMeta.getIp(), accessMeta.getUserAgent(), accessMeta.getReferer());
    }
}
