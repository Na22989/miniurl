package com.na22989.miniurl.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TraceIdUtil 单元测试。
 * <p>
 * 验证两条契约：generate 产物格式与唯一性（两个请求绝不能共享 traceId）；isValid 白名单
 * 上下界（8~64）两侧都测，封 CRLF 日志伪造与长度膨胀。resolve 是 Filter 的取值入口，双分支
 * 各验一次。
 */
class TraceIdUtilTest {

    /** 自生成 traceId 的格式：32 位无横杠小写 hex */
    private static final String UUID_PATTERN = "^[0-9a-f]{32}$";

    @Test
    @DisplayName("generate 返回 32 位无横杠小写 hex")
    void generate_format() {
        String id = TraceIdUtil.generate();
        assertTrue(id.matches(UUID_PATTERN), "生成的 traceId 必须定长 32 位小写 hex，便于双击选中与 grep");
        assertFalse(id.contains("-"), "不得含横杠，与 nginx $request_id 同构");
    }

    @Test
    @DisplayName("generate 大量生成不重复")
    void generate_unique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10000; i++) {
            ids.add(TraceIdUtil.generate());
        }
        assertEquals(10000, ids.size(), "10000 次生成必须全部唯一，重复会让两个请求共享 traceId，日志开始撒谎");
    }

    @Test
    @DisplayName("isValid 拒绝 7 字符（低于下界）")
    void sevenChars_rejected() {
        assertFalse(TraceIdUtil.isValid("a".repeat(7)), "7 字符低于白名单下界 8，必须拒绝");
    }

    @Test
    @DisplayName("isValid 接受 8 字符（下界）")
    void eightChars_accepted() {
        assertTrue(TraceIdUtil.isValid("a".repeat(8)), "下界 8 字符必须放行");
    }

    @Test
    @DisplayName("isValid 接受 64 字符（上界）")
    void sixtyFourChars_accepted() {
        assertTrue(TraceIdUtil.isValid("a".repeat(64)), "上界 64 字符必须放行");
    }

    @Test
    @DisplayName("isValid 拒绝 65 字符（高于上界）")
    void sixtyFiveChars_rejected() {
        assertFalse(TraceIdUtil.isValid("a".repeat(65)), "65 字符超出上界 64，必须拒绝以防单头膨胀日志");
    }

    @Test
    @DisplayName("isValid 拒绝 null 与空串")
    void nullAndEmpty_rejected() {
        assertFalse(TraceIdUtil.isValid(null), "null 不能进日志格式化，必须拒绝");
        assertFalse(TraceIdUtil.isValid(""), "空串无区分度，必须拒绝");
    }

    @Test
    @DisplayName("resolve 合法头返回原值，非法头回退生成")
    void resolve_bothBranches() {
        String inbound = "legit-inbound-0001";
        assertEquals(inbound, TraceIdUtil.resolve(inbound), "合法入站头必须原样复用，保证与上游日志对拼");

        String regenerated = TraceIdUtil.resolve("bad input!!");
        assertTrue(regenerated.matches(UUID_PATTERN), "非法入站头必须回退为自生成的 32 hex");
    }
}
