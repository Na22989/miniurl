package com.na22989.miniurl.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import static com.na22989.miniurl.common.RedisKeyConstant.CLICK_COUNT_PREFIX;

@Slf4j
@Component
@RequiredArgsConstructor
public class LinkAccessedListener {

    private final StringRedisTemplate stringRedisTemplate;

    @EventListener
    @Async("clickCountExecutor")
    public void onLinkAccessed(LinkAccessedEvent event) {
        stringRedisTemplate.opsForValue().increment(CLICK_COUNT_PREFIX + event.getShortCode());
    }
}
