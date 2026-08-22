package com.na22989.miniurl.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ShortLinkUtil单元测试")
class ShortLinkUtilTest {

    private ShortLinkUtil util;

    @BeforeEach
    void setUp() {
        util = new ShortLinkUtil();
    }

    @Test
    @DisplayName("nextId() 连续生成 1000 个 ID 应全部唯一")
    void nextId_shouldGenerateUniqueIds() {
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            long id = util.nextId();
            assertFalse(ids.contains(id), "ID 出现重复: " + id + " (第 " + (i + 1) + " 次)");
            ids.add(id);
        }
        assertEquals(1000, ids.size());
    }

    @Test
    @DisplayName("nextId() 生成的 ID 应单调递增")
    void nextId_shouldGenerateMonotonicallyIncreasingIds() {
        long prev = util.nextId();
        for (int i = 0; i < 100; i++) {
            long current = util.nextId();
            assertTrue(current > prev,
                    "ID 不是递增: prev=" + prev + ", current=" + current + " (第 " + (i + 1) + " 次)");
            prev = current;
        }
    }

    @Test
    @DisplayName("base62Encode() 正常编码")
    void base62Encode_shouldReturnNonEmptyString() {
        String code = util.base62Encode(12345L);
        assertNotNull(code);
        assertFalse(code.isEmpty());
    }

    @Test
    @DisplayName("base62Encode() 编码 0 应返回 '0'")
    void base62Encode_shouldReturnZeroForZero() {
        assertEquals("0", util.base62Encode(0L));
    }

    @Test
    @DisplayName("base62Encode() 固定输入应产生固定输出（幂等性）")
    void base62Encode_shouldBeDeterministic() {
        assertEquals(util.base62Encode(12345L), util.base62Encode(12345L));
    }

    @Test
    @DisplayName("base62Encode() 不同输入应产生不同输出")
    void base62Encode_differentInputs_shouldProduceDifferentOutputs() {
        String a = util.base62Encode(100L);
        String b = util.base62Encode(200L);
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("base62Encode() 输出只包含 Base62 字符集")
    void base62Encode_shouldOnlyContainBase62Characters() {
        for (long i = 1; i <= 100; i++) {
            String code = util.base62Encode(i);
            assertTrue(code.matches("^[0-9a-zA-Z]+$"),
                    "编码包含非法字符: " + code);
        }
    }
}
