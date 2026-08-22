package com.na22989.miniurl.model.dto.link;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 短链缓存值
 * <p>
 * 缓存不再只存 longUrl 字符串，而是带上 linkId——
 * 使 L1/L2 缓存命中路径也能发布携带 linkId 的访问事件，无需异步反查数据库。
 * 缓存值对象，Jackson 可直接序列化为 JSON 存入 Redis。
 */
@Data
@AllArgsConstructor
public class LinkCacheValue {
    private Long linkId;

    private String longUrl;
}
