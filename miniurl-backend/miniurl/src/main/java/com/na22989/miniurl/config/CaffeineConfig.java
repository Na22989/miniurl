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
                  .maximumSize(10000)
                  .expireAfterWrite(5, TimeUnit.MINUTES)
                  .recordStats()
                  .build();
      }
  }