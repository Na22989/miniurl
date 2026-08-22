package com.na22989.miniurl.event;

import com.na22989.miniurl.mapper.LinkAccessLogMapper;
import com.na22989.miniurl.model.dto.link.AccessMeta;
import com.na22989.miniurl.model.entity.LinkAccessLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("LinkAccessLogListener 单元测试")
class LinkAccessLogListenerTest {

    @Mock
    private LinkAccessLogMapper linkAccessLogMapper;

    @InjectMocks
    private LinkAccessLogListener listener;

    private static final Long LINK_ID = 100L;
    private static final String SHORT_CODE = "abc12345";

    private LinkAccessedEvent buildEvent() {
        AccessMeta meta = AccessMeta.builder()
                .ip("1.2.3.4")
                .userAgent("Mozilla/5.0")
                .referer("https://x.com")
                .build();
        return new LinkAccessedEvent(SHORT_CODE, LINK_ID, meta);
    }

    @Test
    @DisplayName("onLinkAccess() 正常应插入访问日志记录")
    void shouldInsertRecord() {
        // 直接调用方法体（不走 Spring 代理，@Async/@EventListener 注解不生效，正好测纯逻辑）
        listener.onLinkAccess(buildEvent());

        ArgumentCaptor<LinkAccessLog> captor = ArgumentCaptor.forClass(LinkAccessLog.class);
        verify(linkAccessLogMapper).insert(captor.capture());

        LinkAccessLog record = captor.getValue();
        assertEquals(LINK_ID, record.getLinkId());
        assertEquals(SHORT_CODE, record.getShortCode());
        assertEquals("1.2.3.4", record.getIp());
        assertEquals("Mozilla/5.0", record.getUserAgent());
        assertEquals("https://x.com", record.getReferer());
        assertNull(record.getAccessTime(), "access_time 应交给 DB 的 DEFAULT CURRENT_TIMESTAMP 填充");
    }

    @Test
    @DisplayName("onLinkAccess() 插入抛异常不应向上传播（尽力而为）")
    void shouldNotThrowWhenInsertFails() {
        doThrow(new RuntimeException("db down")).when(linkAccessLogMapper).insert(any(LinkAccessLog.class));

        assertDoesNotThrow(() -> listener.onLinkAccess(buildEvent()));
    }
}
