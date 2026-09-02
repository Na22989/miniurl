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
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.na22989.miniurl.common.RedisKeyConstant.CLICK_COUNT_PREFIX;
import static com.na22989.miniurl.common.RedisKeyConstant.SHORT_CODE_LOCK_PREFIX;
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

    private final RedisScript<Long> releaseLockScript;

    private volatile boolean bloomReady = false;

    private static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(2);
    private static final long SPIN_BUDGET_MS = 500;
    private static final long SPIN_BACKOFF_BASE_MS = 10;
    private static final long SPIN_BACKOFF_CAP_MS = 160;

    // 可信代理层级：记录访问日志真实客户端 IP 用（与 RateLimitInterceptor 同一配置源，非 final → 不进构造器）
    @Value("${rate-limit.proxies-to-trust:0}")
    private int proxiesToTrust;

    // 短链对外 base-url：部署事实（开发 localhost / VM 填 IP / nginx 后填域名）。
    // 非 final → 不进构造器；初始化器兜底纯 Mockito 单测（无 Spring 注入 @Value）
    @Value("${miniurl.base-url:http://localhost:9191}")
    private String baseUrl = "http://localhost:9191";


    // 计数全部走 Micrometer（MetricsRecorder），这里仅留上次累计快照，logCacheStats 按窗口算增量（定时任务单线程访问，无需原子）
    private long prevL1;
    private long prevL2;
    private long prevDb;
    private long prevBloom;
    private long prevNotFound;
    private long prevExpired;

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
            bloomReady = true;
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
        LinkCacheValue cacheValue = new LinkCacheValue(link.getId(), link.getLongUrl(), expireTime);
        if (ttl.getSeconds() > 0) {
            try {
                stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(cacheValue), ttl);
            } catch (JsonProcessingException e) {
                log.warn("[短链] Redis 缓存序列化失败，shortCode={}，本次不缓存", shortCode, e);
            } catch (Exception e) {
                // 链接已写入 DB（save 已成功），缓存预热失败只丢缓存，不能反报"创建失败"制造重复创建
                log.warn("[短链] Redis 缓存写入失败 shortCode={}，链接已创建，本次不缓存", shortCode, e);
                recorder.recordRedisDegraded("l2_set");
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
        if (bloomReady && !bloomFilter.mightContain(shortCode)) {
            recorder.recordRedirectFail("bloom_reject");
            throw new BizException(ResultCodeEnum.LINK_NOT_FOUND);
        }

        AccessMeta accessMeta = NetUtil.getAccessMeta(request, proxiesToTrust);

        // 快速路径：L1 本地缓存 → L2 共享缓存（Cache-Aside 读），命中即返回
        String longUrl = resolveFromLocalCache(shortCode, accessMeta);
        if (longUrl != null) return longUrl;

        longUrl = resolveFromRedisCache(shortCode, accessMeta);
        if (longUrl != null) return longUrl;

        // 两级缓存都 miss：Redis 分布式锁单飞重建（击穿防御），让热点 key 失效瞬间只有一人查 DB
        String lockKey = SHORT_CODE_LOCK_PREFIX + shortCode;
        String lockVal = UUID.randomUUID().toString();

        boolean got;
        try {
            // 抢锁失败视为"未抢到"：走自旋分支，自旋内部会再次经过 L2 读的降级逻辑
            got = Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockVal, REBUILD_LOCK_TTL));
        } catch (Exception e) {
            log.warn("[短链] Redis 抢锁失败 shortCode={}，降级走自旋/DB", shortCode, e);
            recorder.recordRedisDegraded("lock_acquire");
            got = false;
        }
        if (got) {
            try {
                // 双检：抢到锁不代表要自己重建，锁持有期间可能已有别的实例把缓存写好了
                String rebuilt = resolveFromRedisCache(shortCode, accessMeta);
                if (rebuilt != null) return rebuilt;
                return resolveFromDbAndRebuild(shortCode, accessMeta);
            } finally {
                try {
                    Long result = stringRedisTemplate.execute(
                            releaseLockScript,
                            Collections.singletonList(lockKey),  // KEYS
                            lockVal                              // ARGV
                    );

                    if (result == null || result == 0) {
                        // 值不匹配：锁已过期消失或被他人覆盖，跳过删除——正是 TOCTOU 防护的目标行为
                        log.debug("[短链] 锁已释放或被接管 shortCode={} lockVal={}", shortCode, lockVal);
                    }
                    // result == 1：锁仍归自己，Lua 内已原子删除，正常路径静默

                } catch (Exception e) {
                    // Redis 不可用：锁会在 TTL 后自然过期，不影响已返回的响应
                    log.warn("[短链] Redis 释放锁失败 shortCode={}，等待 TTL 自然过期", shortCode, e);
                    recorder.recordRedisDegraded("lock_release");
                }
            }
        } else {
            long deadline = System.nanoTime() + SPIN_BUDGET_MS * 1000000L;
            long backoff = SPIN_BACKOFF_BASE_MS;

            while (true) {
                String spinValue;
                try {
                    spinValue = resolveFromRedisCacheOrThrow(shortCode, accessMeta);
                } catch (Exception e) {
                    // 已确认 Redis 不可用：继续自旋会把 1 次 timeout 放大成 N 次并占死 servlet 线程
                    log.warn("[短链] 自旋期间 Redis 不可用 shortCode={}，停止自旋直查 DB", shortCode, e);
                    recorder.recordRedisDegraded("l2_get_spin");
                    break;
                }
                if (spinValue != null) {
                    return spinValue;
                }
                if (System.nanoTime() >= deadline) {
                    break;
                }
                if (!sleepQuietly(withJitter(backoff))) {
                    break;
                }
                backoff = Math.min(backoff * 2, SPIN_BACKOFF_CAP_MS);
            }

            // 自旋超时：持锁者重建异常慢，宁可多查一次 DB 也不丢请求
            return resolveFromDbAndRebuild(shortCode, accessMeta);
        }
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

        try {
            stringRedisTemplate.delete(SHORT_CODE_PREFIX + link.getShortCode());
            stringRedisTemplate.delete(CLICK_COUNT_PREFIX + link.getShortCode());
        } catch (Exception e) {
            log.warn("[短链] 删除缓存失败 shortCode={}，缓存将在 TTL 后自愈", link.getShortCode(), e);
            recorder.recordRedisDegraded("delete");
        }
        shortCodeLocalCache.invalidate(link.getShortCode());

        this.removeById(linkId);
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

    // TTL 是资源策略（内存卫生 + 雪崩抖动），正确性由命中路径的过期校验保证（resolveFrom*）——两层刻意共存、各司其职
    private Duration calcCacheTtl(Link link) {
        Duration ttl = Duration.ofHours(1);
        if (link.getExpireTime() != null) {
            Duration remaining = Duration.between(LocalDateTime.now(), link.getExpireTime());
            if (remaining.compareTo(ttl) < 0) {
                ttl = remaining;
            }
            // 已过期：返回负/零 TTL，调用侧 getSeconds()>0 守卫跳过缓存写
            if (ttl.getSeconds() <= 0) {
                return ttl;
            }
        }
        // 雪崩防御：+1~5min 抖动，不 clamp 回 remaining——否则 remaining ≤ 60min 时抖动 100% 被抵消成死代码。
        // 死 key 可滞留 ≤5min，正确性由命中路径 isExpired 校验兜底（TTL 只是资源策略）。
        return ttl.plusSeconds(60 + ThreadLocalRandom.current().nextInt(240));
    }

    // 缓存值自带业务过期快照：null 表示永久链接；命中路径用它做正确性校验（TTL 只是资源策略）
    private boolean isExpired(LinkCacheValue value) {
        return value.getExpireTime() != null && !value.getExpireTime().isAfter(LocalDateTime.now());
    }

    private String resolveFromLocalCache(String shortCode, AccessMeta accessMeta) {
        LinkCacheValue localValue = shortCodeLocalCache.getIfPresent(shortCode);
        if (localValue != null) {
            // 正确性契约：L1 固定 TTL 不感知单链过期，命中值须过业务期限，过期即失效（不按命中计）
            if (isExpired(localValue)) {
                // 过期命中可观测：缓存值已过业务期限按未命中处理，与 L2 分开计量
                recorder.recordExpiredReject("l1");
                shortCodeLocalCache.invalidate(shortCode);
                return null;
            }
            // 命中缓存：点击数也不能丢，事件发布
            eventPublisher.publishEvent(new LinkAccessedEvent(shortCode, localValue.getLinkId(), accessMeta));
            recorder.recordRedirect("l1");
            return localValue.getLongUrl();
        }
        return null;
    }

    private String resolveFromRedisCache(String shortCode, AccessMeta accessMeta) {
        try {
            return resolveFromRedisCacheOrThrow(shortCode, accessMeta);
        } catch (Exception e) {
            log.warn("[短链] Redis L2 读取失败 shortCode={}，降级按未命中处理", shortCode, e);
            recorder.recordRedisDegraded("l2_get");
            return null;
        }
    }

    private String resolveFromRedisCacheOrThrow(String shortCode, AccessMeta accessMeta) {
        String cacheKey = SHORT_CODE_PREFIX + shortCode;
        // 查 Redis 缓存（Cache-Aside 读，值 = LinkCacheValue 的 JSON）
        // 无 try/catch：连接异常直接上抛（自旋据此 abort；快路径由包装层吞掉）
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
            // 正确性契约：命中值须过业务期限（TTL 只是资源策略），过期即失效并清理，不按命中计
            if (isExpired(redisValue)) {
                // 过期命中可观测：缓存值已过业务期限按未命中处理并清理，与 L1 分开计量
                recorder.recordExpiredReject("l2");
                try {
                    stringRedisTemplate.delete(cacheKey);
                } catch (Exception e) {
                    // 过期判定已在内存完成，delete 失败只是缓存残留，下次命中仍会被 isExpired 再次拦截
                    log.warn("[短链] Redis 过期缓存删除失败 shortCode={}", shortCode, e);
                    recorder.recordRedisDegraded("l2_delete");
                }
                shortCodeLocalCache.invalidate(shortCode);
                return null;
            }
            // 命中 L2 回填 L1：后续同 key 请求直接走本地缓存，省一次 Redis RTT
            shortCodeLocalCache.put(shortCode, redisValue);
            // 命中缓存：点击数也不能丢，事件发布
            eventPublisher.publishEvent(new LinkAccessedEvent(shortCode, redisValue.getLinkId(), accessMeta));
            recorder.recordRedirect("l2");
            return redisValue.getLongUrl();
        }
        return null;
    }

    private String resolveFromDbAndRebuild(String shortCode, AccessMeta accessMeta) {
        Link link = this.getOne(new LambdaQueryWrapper<Link>()
                .select(Link::getId, Link::getLongUrl, Link::getExpireTime)
                .eq(Link::getShortCode, shortCode));


        if (link == null) {
            recorder.recordRedirectFail("not_found");
            throw new BizException(ResultCodeEnum.LINK_NOT_FOUND);
        }

        if (link.getExpireTime() != null && !link.getExpireTime().isAfter(LocalDateTime.now())) {
            recorder.recordRedirectFail("expired");
            throw new BizException(ResultCodeEnum.LINK_EXPIRED);
        }

        recorder.recordRedirect("db");
        // 重建 L2：TTL 由 calcCacheTtl 计算（资源策略；命中过期由 resolveFrom* 校验兜底）
        Duration ttl = calcCacheTtl(link);
        LinkCacheValue dbValue = new LinkCacheValue(link.getId(), link.getLongUrl(), link.getExpireTime());
        // TTL ≤ 0 说明已过期，跳过缓存写（防御：不缓存已失效链接）
        if (ttl.getSeconds() > 0) {
            try {
                String cacheKey = SHORT_CODE_PREFIX + shortCode;
                stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(dbValue), ttl);
            } catch (JsonProcessingException e) {
                log.warn("[短链] Redis 缓存序列化失败，shortCode={}，本次不缓存", shortCode, e);
            } catch (Exception e) {
                // DB 已查到 longUrl，写回缓存失败只丢缓存，正确结果已在手，不能反报 500
                log.warn("[短链] Redis 缓存回写失败 shortCode={}，本次不缓存", shortCode, e);
                recorder.recordRedisDegraded("l2_set");
            }
        }

        shortCodeLocalCache.put(shortCode, dbValue);

        eventPublisher.publishEvent(new LinkAccessedEvent(shortCode, link.getId(), accessMeta));

        return link.getLongUrl();
    }

    private boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();  // 恢复中断标志，是否终止由容器决定
            return false;
        }
    }

    // 同一热点 key 的等待者同步唤醒会每轮打一批并发 GET，±20% 抖动错开
    private long withJitter(long base) {
        long span = Math.max(1, base / 5);
        return base - span + ThreadLocalRandom.current().nextLong(2 * span + 1);
    }

    @Scheduled(fixedDelay = 300_000)
    public void logCacheStats() {
        // 累计值读自 Micrometer（记录由 MetricsRecorder 负责），减上次快照得 5min 窗口增量
        long l1 = recorder.count("miniurl.redirect.total", "source", "l1");
        long l2 = recorder.count("miniurl.redirect.total", "source", "l2");
        long db = recorder.count("miniurl.redirect.total", "source", "db");
        long bloom = recorder.count("miniurl.redirect.fail", "reason", "bloom_reject");
        long notFound = recorder.count("miniurl.redirect.fail", "reason", "not_found");
        long expired = recorder.count("miniurl.redirect.fail", "reason", "expired");

        long wL1 = l1 - prevL1;
        long wL2 = l2 - prevL2;
        long wDb = db - prevDb;
        long wBloom = bloom - prevBloom;
        long wNotFound = notFound - prevNotFound;
        long wExpired = expired - prevExpired;

        prevL1 = l1;
        prevL2 = l2;
        prevDb = db;
        prevBloom = bloom;
        prevNotFound = notFound;
        prevExpired = expired;

        long total = wL1 + wL2 + wDb + wBloom + wNotFound + wExpired;
        if (total == 0) return;  // 这 5 分钟没有请求，跳过

        long cacheHit = wL1 + wL2;
        double hitRate = (cacheHit + wDb) > 0 ? (double) cacheHit / (cacheHit + wDb) * 100 : 0;

        CacheStats stats = shortCodeLocalCache.stats();
        log.info("[Caffeine内置] 累计命中率={}% 平均加载耗时={}ns 驱逐数={}",
                String.format("%.2f", stats.hitRate() * 100),
                stats.averageLoadPenalty(),
                stats.evictionCount());

        log.info("[缓存统计] 5min窗口 总请求={} L1命中={} L2命中={} DB命中={} Bloom拒绝={} 不存在={} | 5min窗口命中率={}%",
                total, wL1, wL2, wDb, wBloom, wNotFound,
                String.format("%.1f", hitRate));
    }
}
