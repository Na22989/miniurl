package com.na22989.miniurl.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.hash.BloomFilter;
import com.na22989.miniurl.common.DeleteRequest;
import com.na22989.miniurl.common.PageResult;
import com.na22989.miniurl.common.ResultCodeEnum;
import com.na22989.miniurl.event.LinkAccessedEvent;
import com.na22989.miniurl.exception.BizException;
import com.na22989.miniurl.mapper.LinkMapper;
import com.na22989.miniurl.model.dto.link.AccessMeta;
import com.na22989.miniurl.model.dto.link.CreateLinkRequest;
import com.na22989.miniurl.model.dto.link.LinkCacheValue;
import com.na22989.miniurl.model.dto.link.PageLinkRequest;
import com.na22989.miniurl.model.entity.Link;
import com.na22989.miniurl.model.vo.link.LinkVO;
import com.na22989.miniurl.monitor.MetricsRecorder;
import com.na22989.miniurl.service.LinkService;
import com.na22989.miniurl.task.SyncClickCounts2DBTask;
import com.na22989.miniurl.util.NetUtil;
import com.na22989.miniurl.util.ShortLinkUtil;


import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.na22989.miniurl.common.RedisKeyConstant.CLICK_COUNT_PREFIX;
import static com.na22989.miniurl.common.RedisKeyConstant.SHORT_CODE_PREFIX;


@Service
@Slf4j
@RequiredArgsConstructor
public class LinkServiceImpl  extends ServiceImpl<LinkMapper, Link>
        implements LinkService {

    private final ShortLinkUtil shortLinkUtil;

    private final StringRedisTemplate stringRedisTemplate;

    private final BloomFilter<String> bloomFilter;

    private final Cache<String, LinkCacheValue> shortCodeLocalCache;

    private final ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper;

    private final MetricsRecorder recorder;

    // 可信代理层级：记录访问日志真实客户端 IP 用（与 RateLimitInterceptor 同一配置源，非 final → 不进构造器）
    @Value("${rate-limit.proxies-to-trust:0}")
    private int proxiesToTrust;

    // 短链对外 base-url：部署事实（开发 localhost / VM 填 IP / nginx 后填域名）。
    // 非 final → 不进构造器；初始化器兜底纯 Mockito 单测（无 Spring 注入 @Value）
    @Value("${miniurl.base-url:http://localhost:9191}")
    private String baseUrl = "http://localhost:9191";


    // 计数器
    private final AtomicLong totalRequests   = new AtomicLong(0);
    private final AtomicLong l1HitCount      = new AtomicLong(0);  // Caffeine 命中
    private final AtomicLong l2HitCount      = new AtomicLong(0);  // Redis 命中
    private final AtomicLong dbHitCount      = new AtomicLong(0);  // DB 命中（缓存都 miss）
    private final AtomicLong bloomRejectCount = new AtomicLong(0); // Bloom 拒绝
    private final AtomicLong notFoundCount   = new AtomicLong(0);  // 真正不存在

    @PostConstruct
    public void initBloomFilter() {
        log.info("[短链] 开始加载 shortCode 到布隆过滤器...");

        try {
            // 分批加载，避免 OOM，主键 id 游标
            long batch = 10000;
            long totalLoaded = 0;
            long lastId = 0L;

            while (true) {
                LambdaQueryWrapper<Link> queryWrapper = new LambdaQueryWrapper<Link>()
                        .select(Link::getId, Link::getShortCode)
                        .gt(Link::getId, lastId)
                        .orderByAsc(Link::getId)
                        .last("limit " + batch);

                List<Link> records = this.list(queryWrapper);

                if (records.isEmpty()) {
                    break;
                }

                for (Link link : records) {
                    bloomFilter.put(link.getShortCode());
                }

                totalLoaded+= records.size();
                lastId = records.get(records.size() - 1).getId();

                log.info("[短链] 已加载 {} 条，当前游标 id={}", totalLoaded, lastId);

                // 如果本批次数量小于 batch，说明已经到末尾
                if (records.size() < batch) {
                    break;
                }
            }

            log.info("[短链] 布隆过滤器初始化完成！");

        } catch (Exception e) {
            // 可以降级：记录错误，但不影响服务启动
            log.error("[短链] 布隆过滤器初始化失败，服务将继续启动但可能受到缓存穿透影响", e);
        }
    }

    @Override
    public LinkVO createLink(Long userId, CreateLinkRequest request) {

        String longUrl = request.getLongUrl();
        LocalDateTime expireTime = request.getExpireTime();

        long id = shortLinkUtil.nextId();

        String shortCode = shortLinkUtil.base62Encode(id);

        Link link = new Link()
                .setId(id)
                .setShortCode(shortCode)
                .setLongUrl(longUrl)
                .setUserId(userId)
                .setCreateTime(LocalDateTime.now())
                .setExpireTime(expireTime)
                .setClickCount(0);

        this.save(link);

        String cacheKey = SHORT_CODE_PREFIX + shortCode;
        Duration ttl = calcCacheTtl(link);
        LinkCacheValue cacheValue = new LinkCacheValue(link.getId(), link.getLongUrl());
        if (ttl.getSeconds() > 0) {
            try {
                stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(cacheValue), ttl);
            } catch (JsonProcessingException e) {
                log.warn("[短链] Redis 缓存序列化失败，shortCode={}，本次不缓存", shortCode, e);
            }
        }

        shortCodeLocalCache.put(shortCode, cacheValue);

        recorder.recordLinkCreated();

        bloomFilter.put(shortCode);

        return LinkVO.builder()
                .createTime(link.getCreateTime())
                .expireTime(link.getExpireTime())
                .id(link.getId())
                .longUrl(link.getLongUrl())
                .shortCode(link.getShortCode())
                .shortUrl(buildShortUrl(link.getShortCode()))
                .userId(userId)
                .build();
    }

    @Override
    public String redirect(String shortCode, HttpServletRequest request) {
        totalRequests.incrementAndGet();

        if (!bloomFilter.mightContain(shortCode)) {
            bloomRejectCount.incrementAndGet();
            recorder.recordRedirectFail("bloom_reject");
            throw new BizException(ResultCodeEnum.LINK_NOT_FOUND);
        }

        AccessMeta accessMeta = NetUtil.getAccessMeta(request, proxiesToTrust);

        // 检查本地缓存（方案 B：缓存值携带 linkId）
        LinkCacheValue localValue = shortCodeLocalCache.getIfPresent(shortCode);
        if (localValue != null) {
            // 命中缓存：点击数也不能丢，事件发布
            eventPublisher.publishEvent(new LinkAccessedEvent(shortCode, localValue.getLinkId(), accessMeta));
            l1HitCount.incrementAndGet();
            recorder.recordRedirect("l1");
            return localValue.getLongUrl();
        }


        String cacheKey = SHORT_CODE_PREFIX + shortCode;

        // 查 Redis 缓存（Cache-Aside 读，值 = LinkCacheValue 的 JSON）
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        LinkCacheValue redisValue = null;
        if (cachedJson != null) {
            try {
                redisValue = objectMapper.readValue(cachedJson, LinkCacheValue.class);
            } catch (JsonProcessingException e) {
                // 反序列化失败按缓存未命中处理，降级走 DB
                log.warn("[短链] Redis 缓存反序列化失败 shortCode={}，按缓存未命中处理", shortCode, e);
            }
        }
        if (redisValue != null) {
            shortCodeLocalCache.put(shortCode, redisValue);
            // 命中缓存：点击数也不能丢，事件发布
            eventPublisher.publishEvent(new LinkAccessedEvent(shortCode, redisValue.getLinkId(), accessMeta));
            recorder.recordRedirect("l2");
            l2HitCount.incrementAndGet();
            return redisValue.getLongUrl();
        }

        // 缓存未命中，查 DB（select 带上 id：方案 B 需要 linkId 写缓存 + 发布事件）
        Link link = this.getOne(new LambdaQueryWrapper<Link>()
                .select(Link::getId, Link::getLongUrl, Link::getExpireTime)
                .eq(Link::getShortCode, shortCode));


        if (link == null) {
            notFoundCount.incrementAndGet();
            recorder.recordRedirectFail("not_found");
            throw new BizException(ResultCodeEnum.LINK_NOT_FOUND);
        }

        if (link.getExpireTime() != null && !link.getExpireTime().isAfter(LocalDateTime.now())) {
            recorder.recordRedirectFail("expired");
            throw new BizException(ResultCodeEnum.LINK_EXPIRED);
        }

        recorder.recordRedirect("db");
        dbHitCount.incrementAndGet();
        // 写 Redis 缓存（TTL 上限 1 小时，取 expireTime 剩余时间的较小值）
        Duration ttl = calcCacheTtl(link);
        LinkCacheValue dbValue = new LinkCacheValue(link.getId(), link.getLongUrl());
        if (ttl.getSeconds() > 0) {
            try {
                stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(dbValue), ttl);
            } catch (JsonProcessingException e) {
                log.warn("[短链] Redis 缓存序列化失败，shortCode={}，本次不缓存", shortCode, e);
            }
        }

        shortCodeLocalCache.put(shortCode, dbValue);

        eventPublisher.publishEvent(new LinkAccessedEvent(shortCode, link.getId(), accessMeta));

        return link.getLongUrl();
    }

    @Override
    public PageResult<LinkVO> listUserLinks(Long userId, PageLinkRequest request) {
        Page<Link> page = new Page<>(request.getCurrent(), request.getSize());

        LambdaQueryWrapper<Link> queryWrapper = new LambdaQueryWrapper<Link>()
                .eq(Link::getUserId, userId)
                .orderByDesc(Link::getCreateTime);

        IPage<Link> linkIPage = this.page(page, queryWrapper);

        List<LinkVO> linkVOList = linkIPage.getRecords().stream().map(this::toLinkVO).toList();

        return new PageResult<>(linkVOList, linkIPage.getTotal(),
                linkIPage.getCurrent(), linkIPage.getSize(), linkIPage.getPages());
    }

    @Override
    public void deleteLink(Long userId, DeleteRequest request) {
        Long linkId = request.getId();

        Link link = this.getById(linkId);
        if (link == null) {
            throw new BizException(ResultCodeEnum.LINK_NOT_FOUND);
        }

        if (!link.getUserId().equals(userId)) {
            throw new BizException(ResultCodeEnum.LINK_NOT_FOUND);
        }

        this.removeById(linkId);

        stringRedisTemplate.delete(SHORT_CODE_PREFIX + link.getShortCode());
        stringRedisTemplate.delete(CLICK_COUNT_PREFIX + link.getShortCode());
        shortCodeLocalCache.invalidate(link.getShortCode());
    }

    @Override
    public LinkVO getLinkDetail(Long userId, Long linkId) {
        if (linkId <= 0) {
            throw new BizException(ResultCodeEnum.BAD_REQUEST, "链接ID必须大于0");
        }

        Link link = this.getById(linkId);
        if (link == null) {
            throw new BizException(ResultCodeEnum.LINK_NOT_FOUND);
        }

        // 3. 仅本人可以操作链接，相同错误码可以避免攻击者区分出有效链接
        if (!link.getUserId().equals(userId)) {
            throw new BizException(ResultCodeEnum.LINK_NOT_FOUND);
        }

        return toLinkVO(link);
    }

    @Override
    public int batchUpdateClickCount(List<SyncClickCounts2DBTask.ClickSyncDTO> clickSyncDTOS) {
        return this.baseMapper.batchUpdateClickCount(clickSyncDTOS);
    }

    private String buildShortUrl(String shortCode) {
        return baseUrl + "/s/" + shortCode;
    }

    private LinkVO toLinkVO(Link link) {
        return LinkVO.builder()
                .createTime(link.getCreateTime())
                .expireTime(link.getExpireTime())
                .id(link.getId())
                .longUrl(link.getLongUrl())
                .shortCode(link.getShortCode())
                .shortUrl(buildShortUrl(link.getShortCode()))
                .userId(link.getUserId())
                .build();
    }

    private Duration calcCacheTtl(Link link) {
        Duration ttl = Duration.ofHours(1);
        if (link.getExpireTime() != null) {
            Duration remaining = Duration.between(LocalDateTime.now(), link.getExpireTime());
            if (remaining.compareTo(ttl) < 0) {
                ttl = remaining;
            }
        }
        return ttl;
    }

    @Scheduled(fixedDelay = 300_000)
    public void logCacheStats() {
        long total = totalRequests.getAndSet(0);
        if (total == 0) return;  // 这 5 分钟没有请求，跳过

        long l1 = l1HitCount.getAndSet(0);
        long l2 = l2HitCount.getAndSet(0);
        long db = dbHitCount.getAndSet(0);
        long bloom = bloomRejectCount.getAndSet(0);
        long notFound = notFoundCount.getAndSet(0);

        long cacheHit = l1 + l2;
        double hitRate = (cacheHit + db) > 0 ? (double) cacheHit / (cacheHit + db) * 100 : 0;

        CacheStats stats = shortCodeLocalCache.stats();
        log.info("[Caffeine内置] 命中率={}% 平均加载耗时={}ns 驱逐数={}",
                String.format("%.2f", stats.hitRate() * 100),
                stats.averageLoadPenalty(),
                stats.evictionCount());

        log.info("[缓存统计] 总请求={} L1命中={} L2命中={} DB命中={} Bloom拒绝={} 不存在={} | 缓存命中率={}%",
                total, l1, l2, db, bloom, notFound,
                String.format("%.1f", hitRate));
    }
}
