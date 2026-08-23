package com.na22989.miniurl.config;


import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

/**
 * 短码存在性预检布隆过滤器。
 * <p>重定向入口先 mightContain 判定：false 一定不存在，直接返回 LINK_NOT_FOUND；
 * true 仅代表可能存在（有误判），仍需走 L1/L2/DB 查询。</p>
 */
@Configuration
public class BloomFilterConfig {

    private static final int EXPECTED_INSERTIONS = 100000;

    /**
     * 误判率：值越小，占用的内存空间越大
     */
    private static final double FPP = 0.01;

    @Bean
    public BloomFilter<String> bloomFilter() {
        return BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                EXPECTED_INSERTIONS,
                FPP
        );
    }
}
