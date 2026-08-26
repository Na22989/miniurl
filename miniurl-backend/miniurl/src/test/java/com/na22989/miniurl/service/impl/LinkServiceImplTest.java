package com.na22989.miniurl.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.hash.BloomFilter;
import com.na22989.miniurl.common.DeleteRequest;
import com.na22989.miniurl.common.PageResult;
import com.na22989.miniurl.common.ResultCodeEnum;
import com.na22989.miniurl.event.LinkAccessedEvent;
import com.na22989.miniurl.exception.BizException;
import com.na22989.miniurl.mapper.LinkMapper;
import com.na22989.miniurl.monitor.MetricsRecorder;
import com.na22989.miniurl.model.dto.link.CreateLinkRequest;
import com.na22989.miniurl.model.dto.link.LinkCacheValue;
import com.na22989.miniurl.model.dto.link.PageLinkRequest;
import com.na22989.miniurl.model.entity.Link;
import com.na22989.miniurl.model.vo.link.LinkVO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.na22989.miniurl.util.ShortLinkUtil;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static com.na22989.miniurl.common.RedisKeyConstant.CLICK_COUNT_PREFIX;
import static com.na22989.miniurl.common.RedisKeyConstant.SHORT_CODE_PREFIX;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("LinkServiceImpl 单元测试")
class LinkServiceImplTest {

    @Mock
    private LinkMapper linkMapper;

    @Mock
    private ShortLinkUtil shortLinkUtil;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private BloomFilter<String> bloomFilter;

    @Mock
    private Cache<String, LinkCacheValue> shortCodeLocalCache;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private MetricsRecorder recorder;

    private LinkServiceImpl linkService;

    private CreateLinkRequest createRequest;
    private PageLinkRequest pageRequest;
    private DeleteRequest deleteRequest;
    private Link mockLink;

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long LINK_ID = 100L;
    private static final String SHORT_CODE = "abc12345";

    @BeforeAll
    static void initMybatisPlusCache() {
        // 手动初始化 MyBatis-Plus TableInfo，使 LambdaQueryWrapper.select() 在单元测试中可用
        // 原因：.select() 会立即解析列名（调用 columnsToString → tryInitCache → TableInfoHelper.getTableInfo），
        // 而 .eq()/.orderByDesc() 延迟到 SQL 生成时才解析（被 mock 拦截，不会触发缓存初始化）
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        TableInfoHelper.initTableInfo(assistant, Link.class);
    }

    @BeforeEach
    void setUp() {
        // @RequiredArgsConstructor → LinkServiceImpl(ShortLinkUtil, StringRedisTemplate, BloomFilter,
        //     Cache, ApplicationEventPublisher, ObjectMapper, MetricsRecorder)
        linkService = new LinkServiceImpl(shortLinkUtil, stringRedisTemplate, bloomFilter,
                shortCodeLocalCache, eventPublisher, objectMapper, recorder);
        // 注入父类 ServiceImpl 的 baseMapper（未通过构造器注入）
        ReflectionTestUtils.setField(linkService, "baseMapper", linkMapper);

        // 让 stringRedisTemplate.opsForValue() 返回 mock（lenient：部分测试不走 redirect，用不到）
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        createRequest = new CreateLinkRequest();
        createRequest.setLongUrl("https://www.baidu.com");

        pageRequest = new PageLinkRequest();

        deleteRequest = new DeleteRequest();
        deleteRequest.setId(LINK_ID);

        mockLink = new Link()
                .setId(LINK_ID)
                .setShortCode(SHORT_CODE)
                .setLongUrl("https://www.baidu.com")
                .setUserId(USER_ID)
                .setClickCount(0)
                .setCreateTime(LocalDateTime.now());
    }

    // ─── createLink ───

    @Test
    @DisplayName("createLink() 正常创建应返回 LinkVO")
    void createLink_shouldReturnLinkVO() {
        when(shortLinkUtil.nextId()).thenReturn(LINK_ID);
        when(shortLinkUtil.base62Encode(LINK_ID)).thenReturn(SHORT_CODE);
        when(linkMapper.insert(isA(Link.class))).thenReturn(1);

        LinkVO result = linkService.createLink(USER_ID, createRequest);

        assertNotNull(result);
        assertEquals(LINK_ID, result.getId());
        assertEquals(SHORT_CODE, result.getShortCode());
        assertEquals("https://www.baidu.com", result.getLongUrl());
        assertTrue(result.getShortUrl().contains(SHORT_CODE));

        verify(recorder).recordLinkCreated();
    }

    @Test
    @DisplayName("createLink() 的 shortUrl 应使用可配置的 base-url")
    void createLink_shortUrl_usesConfiguredBaseUrl() {
        ReflectionTestUtils.setField(linkService, "baseUrl", "http://example.com");
        when(shortLinkUtil.nextId()).thenReturn(LINK_ID);
        when(shortLinkUtil.base62Encode(LINK_ID)).thenReturn(SHORT_CODE);
        when(linkMapper.insert(isA(Link.class))).thenReturn(1);

        LinkVO result = linkService.createLink(USER_ID, createRequest);

        assertEquals("http://example.com/s/" + SHORT_CODE, result.getShortUrl());
    }

    @Test
    @DisplayName("createLink() expireTime 已过期 → 跳过 Redis 缓存写，本地缓存照写")
    void createLink_shouldSkipRedisCacheWhenExpired() {
        when(shortLinkUtil.nextId()).thenReturn(LINK_ID);
        when(shortLinkUtil.base62Encode(LINK_ID)).thenReturn(SHORT_CODE);
        when(linkMapper.insert(isA(Link.class))).thenReturn(1);

        createRequest.setExpireTime(LocalDateTime.now().minusMinutes(5)); // 已过期 → calcCacheTtl 返回负 TTL

        LinkVO result = linkService.createLink(USER_ID, createRequest);

        assertNotNull(result);
        // 负 TTL → Redis 缓存写入被跳过（否则会把已过期短链缓存起来，永远命中到过期链接）
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        // 本地缓存不设 TTL，照写（布隆/本地不判过期，靠 DB 兜底）
        verify(shortCodeLocalCache).put(eq(SHORT_CODE), any(LinkCacheValue.class));
    }

    // ─── listUserLinks ───

    @Test
    @DisplayName("listUserLinks() 应返回正确的分页结果")
    @SuppressWarnings("unchecked")
    void listUserLinks_shouldReturnPageResult() {
        IPage<Link> mockPage = mock(IPage.class);
        when(mockPage.getRecords()).thenReturn(List.of(mockLink));
        when(mockPage.getTotal()).thenReturn(1L);
        when(mockPage.getCurrent()).thenReturn(1L);
        when(mockPage.getSize()).thenReturn(10L);
        when(mockPage.getPages()).thenReturn(1L);

        doReturn(mockPage).when(linkMapper).selectPage(any(Page.class), any(LambdaQueryWrapper.class));

        PageResult<LinkVO> result = linkService.listUserLinks(USER_ID, pageRequest);

        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        assertEquals(1L, result.getTotal());
        assertEquals(SHORT_CODE, result.getRecords().get(0).getShortCode());
    }

    @Test
    @DisplayName("listUserLinks() 空结果应返回空列表")
    @SuppressWarnings("unchecked")
    void listUserLinks_shouldReturnEmptyPage() {
        IPage<Link> mockPage = mock(IPage.class);
        when(mockPage.getRecords()).thenReturn(List.of());
        when(mockPage.getTotal()).thenReturn(0L);
        when(mockPage.getCurrent()).thenReturn(1L);
        when(mockPage.getSize()).thenReturn(10L);
        when(mockPage.getPages()).thenReturn(0L);

        doReturn(mockPage).when(linkMapper).selectPage(any(Page.class), any(LambdaQueryWrapper.class));

        PageResult<LinkVO> result = linkService.listUserLinks(USER_ID, pageRequest);

        assertNotNull(result);
        assertEquals(0, result.getRecords().size());
        assertEquals(0L, result.getTotal());
    }

    // ─── getLinkDetail ───

    @Test
    @DisplayName("getLinkDetail() 正常应返回 LinkVO")
    void getLinkDetail_shouldReturnLinkVO() {
        when(linkMapper.selectById(LINK_ID)).thenReturn(mockLink);

        LinkVO result = linkService.getLinkDetail(USER_ID, LINK_ID);

        assertNotNull(result);
        assertEquals(LINK_ID, result.getId());
        assertEquals(SHORT_CODE, result.getShortCode());
    }

    @Test
    @DisplayName("getLinkDetail() 短链不存在应抛出 LINK_NOT_FOUND")
    void getLinkDetail_shouldThrowWhenNotFound() {
        when(linkMapper.selectById(LINK_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> linkService.getLinkDetail(USER_ID, LINK_ID));
        assertEquals(ResultCodeEnum.LINK_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("getLinkDetail() 越权访问应抛出 LINK_NOT_FOUND（安全：不暴露存在性）")
    void getLinkDetail_shouldThrowLinkNotFoundForOtherUser() {
        when(linkMapper.selectById(LINK_ID)).thenReturn(mockLink);

        BizException ex = assertThrows(BizException.class,
                () -> linkService.getLinkDetail(OTHER_USER_ID, LINK_ID));
        assertEquals(ResultCodeEnum.LINK_NOT_FOUND.getCode(), ex.getCode(),
                "越权访问应返回 LINK_NOT_FOUND，而非 FORBIDDEN");
    }

    @Test
    @DisplayName("getLinkDetail() linkId ≤ 0 应抛出 BAD_REQUEST")
    void getLinkDetail_shouldThrowForInvalidId() {
        BizException ex = assertThrows(BizException.class,
                () -> linkService.getLinkDetail(USER_ID, 0L));
        assertEquals(ResultCodeEnum.BAD_REQUEST.getCode(), ex.getCode());
    }

    // ─── deleteLink ───

    @Test
    @DisplayName("deleteLink() 正常删除应成功")
    void deleteLink_shouldSucceed() {
        when(linkMapper.selectById(LINK_ID)).thenReturn(mockLink);
        when(linkMapper.deleteById(LINK_ID)).thenReturn(1);

        assertDoesNotThrow(() -> linkService.deleteLink(USER_ID, deleteRequest));
    }

    @Test
    @DisplayName("deleteLink() 短链不存在应抛出 LINK_NOT_FOUND")
    void deleteLink_shouldThrowWhenNotFound() {
        when(linkMapper.selectById(LINK_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> linkService.deleteLink(USER_ID, deleteRequest));
        assertEquals(ResultCodeEnum.LINK_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("deleteLink() 越权删除应抛出 LINK_NOT_FOUND（安全）")
    void deleteLink_shouldThrowLinkNotFoundForOtherUser() {
        when(linkMapper.selectById(LINK_ID)).thenReturn(mockLink);

        BizException ex = assertThrows(BizException.class,
                () -> linkService.deleteLink(OTHER_USER_ID, deleteRequest));
        assertEquals(ResultCodeEnum.LINK_NOT_FOUND.getCode(), ex.getCode(),
                "越权删除应返回 LINK_NOT_FOUND，而非 FORBIDDEN");
    }

    @Test
    @DisplayName("deleteLink() 应清理 Redis 与 Caffeine 缓存（删除失效）")
    void deleteLink_shouldCleanupCaches() {
        when(linkMapper.selectById(LINK_ID)).thenReturn(mockLink);
        when(linkMapper.deleteById(LINK_ID)).thenReturn(1);

        linkService.deleteLink(USER_ID, deleteRequest);

        // Redis 短链缓存 + 计数 key + 本地 Caffeine 全部失效，防止删了还在缓存里被访问
        verify(stringRedisTemplate).delete(SHORT_CODE_PREFIX + SHORT_CODE);
        verify(stringRedisTemplate).delete(CLICK_COUNT_PREFIX + SHORT_CODE);
        verify(shortCodeLocalCache).invalidate(SHORT_CODE);
    }

    // ─── redirect ───

    @Test
    @DisplayName("redirect() 正常应返回 longUrl")
    void redirect_shouldReturnLongUrl() {
        // Bloom → Caffeine → Redis 全 miss，DB hit
        when(bloomFilter.mightContain(SHORT_CODE)).thenReturn(true);
        when(shortCodeLocalCache.getIfPresent(SHORT_CODE)).thenReturn(null);
        when(valueOperations.get(anyString())).thenReturn(null);
        // MP getOne() → baseMapper.selectOne(wrapper, true)
        when(linkMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(mockLink);

        String result = linkService.redirect(SHORT_CODE, null);

        assertEquals("https://www.baidu.com", result);

        verify(recorder).recordRedirect("db");
    }

    @Test
    @DisplayName("redirect() 短链不存在应抛出 LINK_NOT_FOUND")
    void redirect_shouldThrowWhenNotFound() {
        when(bloomFilter.mightContain(SHORT_CODE)).thenReturn(true);
        when(shortCodeLocalCache.getIfPresent(SHORT_CODE)).thenReturn(null);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(linkMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> linkService.redirect(SHORT_CODE, null));
        assertEquals(ResultCodeEnum.LINK_NOT_FOUND.getCode(), ex.getCode());

        verify(recorder).recordRedirectFail("not_found");
    }

    @Test
    @DisplayName("redirect() 布隆过滤器拒绝应抛出 LINK_NOT_FOUND 并打 bloom_reject 指标")
    void redirect_shouldThrowWhenBloomReject() {
        when(bloomFilter.mightContain(SHORT_CODE)).thenReturn(false);

        BizException ex = assertThrows(BizException.class,
                () -> linkService.redirect(SHORT_CODE, null));
        assertEquals(ResultCodeEnum.LINK_NOT_FOUND.getCode(), ex.getCode());

        verify(recorder).recordRedirectFail("bloom_reject");
    }

    @Test
    @DisplayName("redirect() 短链已过期应抛出 LINK_EXPIRED")
    void redirect_shouldThrowWhenExpired() {
        mockLink.setExpireTime(LocalDateTime.now().minusDays(1));
        when(bloomFilter.mightContain(SHORT_CODE)).thenReturn(true);
        when(shortCodeLocalCache.getIfPresent(SHORT_CODE)).thenReturn(null);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(linkMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(mockLink);

        BizException ex = assertThrows(BizException.class,
                () -> linkService.redirect(SHORT_CODE, null));
        assertEquals(ResultCodeEnum.LINK_EXPIRED.getCode(), ex.getCode());

        verify(recorder).recordRedirectFail("expired");
    }

    @Test
    @DisplayName("redirect() L1 缓存命中应返回 longUrl 并发布带 linkId 的事件")
    void redirect_shouldUseL1CacheWithLinkId() {
        when(bloomFilter.mightContain(SHORT_CODE)).thenReturn(true);
        when(shortCodeLocalCache.getIfPresent(SHORT_CODE))
                .thenReturn(new LinkCacheValue(LINK_ID, "https://www.baidu.com"));

        String result = linkService.redirect(SHORT_CODE, null);

        assertEquals("https://www.baidu.com", result);

        ArgumentCaptor<LinkAccessedEvent> captor = ArgumentCaptor.forClass(LinkAccessedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(LINK_ID, captor.getValue().getLinkId(), "L1 命中事件必须携带 linkId");
        assertEquals(SHORT_CODE, captor.getValue().getShortCode());

        verify(recorder).recordRedirect("l1");
    }

    @Test
    @DisplayName("redirect() L2 缓存命中应返回 longUrl 并发布带 linkId 的事件")
    void redirect_shouldUseL2CacheWithLinkId() throws Exception {
        when(bloomFilter.mightContain(SHORT_CODE)).thenReturn(true);
        when(shortCodeLocalCache.getIfPresent(SHORT_CODE)).thenReturn(null);
        when(valueOperations.get(anyString())).thenReturn("{\"linkId\":100,\"longUrl\":\"https://www.baidu.com\"}");
        when(objectMapper.readValue(anyString(), eq(LinkCacheValue.class)))
                .thenReturn(new LinkCacheValue(LINK_ID, "https://www.baidu.com"));

        String result = linkService.redirect(SHORT_CODE, null);

        assertEquals("https://www.baidu.com", result);

        ArgumentCaptor<LinkAccessedEvent> captor = ArgumentCaptor.forClass(LinkAccessedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(LINK_ID, captor.getValue().getLinkId(), "L2 命中事件必须携带 linkId");

        verify(recorder).recordRedirect("l2");
    }

    @Test
    @DisplayName("redirect() DB 命中应把带 linkId 的 LinkCacheValue 写入 Redis 缓存")
    void redirect_shouldCacheLinkCacheValueJson() throws Exception {
        when(bloomFilter.mightContain(SHORT_CODE)).thenReturn(true);
        when(shortCodeLocalCache.getIfPresent(SHORT_CODE)).thenReturn(null);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(linkMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(mockLink);
        when(objectMapper.writeValueAsString(any(LinkCacheValue.class)))
                .thenReturn("{\"linkId\":100,\"longUrl\":\"https://www.baidu.com\"}");

        String result = linkService.redirect(SHORT_CODE, null);

        assertEquals("https://www.baidu.com", result);

        ArgumentCaptor<LinkCacheValue> cacheCaptor = ArgumentCaptor.forClass(LinkCacheValue.class);
        verify(shortCodeLocalCache).put(eq(SHORT_CODE), cacheCaptor.capture());
        assertEquals(LINK_ID, cacheCaptor.getValue().getLinkId(), "缓存值必须内嵌 linkId");

        ArgumentCaptor<LinkAccessedEvent> eventCaptor = ArgumentCaptor.forClass(LinkAccessedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(LINK_ID, eventCaptor.getValue().getLinkId(), "DB 命中事件必须携带 linkId");

        verify(recorder).recordRedirect("db");
    }
}
