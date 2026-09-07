package com.na22989.miniurl.model.dto.link;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 短链缓存值。
 * <p>
 * 缓存不再只存 longUrl 字符串，而是带上 linkId——
 * 使 L1/L2 缓存命中路径也能发布携带 linkId 的访问事件，无需异步反查数据库。
 * <p>
 * expireTime 为写入时刻的业务过期快照：缓存 TTL 只负责资源策略，命中路径以它为准校验过期，
 * 杜绝 L1 固定 TTL 兜住已失效链接。
 * 缓存值对象，Jackson 可直接序列化为 JSON 存入 Redis。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LinkCacheValue {
    private Long linkId;

    private String longUrl;

    private LocalDateTime expireTime;

    // 无过期时间（永久链接）的快捷构造
    public LinkCacheValue(Long linkId, String longUrl) {
        this(linkId, longUrl, null);
    }
}
