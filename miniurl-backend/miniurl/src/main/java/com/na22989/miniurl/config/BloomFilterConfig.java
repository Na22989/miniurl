package com.na22989.miniurl.config;


import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

@Configuration
public class BloomFilterConfig {

    /**
     * 预期插入的数据量（后期通过配置文件注入）
     * 这里直接设为 10 万
     */
    private static final int EXPECTED_INSERTIONS = 100000;

    /**
     * 期望的误判率（1%），数值越小，占用的内存空间越大
     */
    private static final double FPP = 0.01;

    @Bean
    public BloomFilter<String> bloomFilter() {
        // 使用 Funnels.stringFunnel(StandardCharsets.UTF_8) 确保字符串序列化方式
        return BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                EXPECTED_INSERTIONS,
                FPP
        );
    }
}
