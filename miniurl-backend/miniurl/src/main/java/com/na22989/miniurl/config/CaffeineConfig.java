package com.na22989.miniurl.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.na22989.miniurl.model.dto.link.LinkCacheValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
  public class CaffeineConfig {

      @Bean
      public Cache<String, LinkCacheValue> shortCodeLocalCache() {
          return Caffeine.newBuilder()
                  .maximumSize(10000)        // 最多缓存 1 万条
                  .expireAfterWrite(5, TimeUnit.MINUTES)  // 写入后 5 分钟过期
                  .recordStats()              // 开启命中率统计
                  .build();
      }
  }