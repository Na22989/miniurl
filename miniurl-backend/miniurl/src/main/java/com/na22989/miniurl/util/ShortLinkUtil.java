package com.na22989.miniurl.util;

import com.na22989.miniurl.common.ResultCodeEnum;
import com.na22989.miniurl.exception.BizException;
import org.springframework.stereotype.Component;

/**
 * 基于 Snowflake 算法的短链接 ID 生成器（修复版）
 * <p>
 * === 使用场景 ===
 * 完整流程：生成唯一 ID → Base62 编码 → 生成短链接
 * <pre>
 * 示例：
 * 1. long id = nextId()              → 123456789012345
 * 2. String shortCode = base62Encode(id) → "dBvJjXh"
 * 3. 短链接 URL                       → "http://localhost:9191/s/dBvJjXh"
 * </pre>
 * <p>
 * === ID 结构（64 位）===
 * - 1 位符号位（固定为 0）
 * - 41 位时间戳（毫秒级，相对于 CUSTOM_EPOCH，可用 69 年）
 * - 10 位机器 ID（预留，当前为 0，支持 1024 个节点）
 * - 12 位序列号（同一毫秒内支持 4096 个 ID）
 * <p>
 * === 性能特性 ===
 * - 单机 QPS：理论峰值 409.6 万/秒（4096 * 1000）
 * - 实际受限于数据库写入性能
 * - 趋势递增：新生成的 ID 总是大于旧 ID（除非时钟回拨）
 * <p>
 * === 线程安全 ===
 * 通过 synchronized 保证并发安全，适用于单机部署
 * 如需分布式部署，需要引入机器 ID 配置（修改第 10 位）
 * <p>
 * === 修复内容（相比原始版本）===
 * 1. 增加 lastTimestamp 跟踪上次生成时间
 * 2. 新毫秒到来时重置序列号为 0（标准 Snowflake 行为）
 * 3. 检测并拒绝时钟回拨（防止重复 ID）
 * 4. 整个 ID 生成过程加锁保证原子性
 * 5. 修复 base62Encode 对 0 的处理
 */
@Component
public class ShortLinkUtil {

    private static final String BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    // 2026/01/01 00:00:00 毫秒时间戳
    private static final long CUSTOM_EPOCH = 1767196800000L;

    private static final long TIMESTAMP_BITS = 41L;

    private static final long WORKER_ID_BITS = 10L;

    private static final long SEQUENCE_BITS = 12L;

    // 序列号掩码（4095，即 2^12 - 1）
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;

    // 时间戳左移位数（12 位序列号 + 10 位机器 ID）
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    // 最大可用时间戳（防止溢出 41 位）
    private static final long MAX_TIMESTAMP_OFFSET = (1L << TIMESTAMP_BITS) - 1;

    // 上次生成 ID 的时间戳（相对 CUSTOM_EPOCH 的偏移量）
    private long lastTimestamp = -1L;

    // 当前毫秒内的序列号（0-4095）
    private long sequence = 0L;

    /**
     * 生成全局唯一 ID（线程安全）
     * <p>
     * 调用示例：
     * <pre>
     * long id = shortLinkUtil.nextId();  // → 123456789012345
     * </pre>
     * <p>
     * 该 ID 会：
     * 1. 作为数据库主键（Link.id 使用 @TableId(type = IdType.INPUT)）
     * 2. 经过 Base62 编码后成为短码（shortCode）
     *
     * @return 64 位长整型 ID，保证全局唯一且趋势递增
     * @throws BizException 当发生时钟回拨时抛出
     */
    public synchronized long nextId() {
        long currentTimestamp = getTimestampOffset();

        // 1. 检测时钟回拨
        if (currentTimestamp < lastTimestamp) {
            long offset = lastTimestamp - currentTimestamp;
            throw new BizException(ResultCodeEnum.INTERNAL_ERROR,
                    String.format("时钟回拨 %d 毫秒，拒绝生成 ID", offset));
        }

        // 2. 处理同一毫秒内的并发请求
        if (currentTimestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;

            // 序列号用完（达到 4096），等待下一毫秒
            if (sequence == 0) {
                currentTimestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        // 3. 检查时间戳是否溢出（理论上 69 年后才会发生）
        if (currentTimestamp > MAX_TIMESTAMP_OFFSET) {
            throw new BizException(ResultCodeEnum.INTERNAL_ERROR,
                    "时间戳超出最大值，系统无法继续生成 ID");
        }

        lastTimestamp = currentTimestamp;

        // 4. 组装 ID：时间戳（左移 22 位）| 机器 ID（左移 12 位，当前为 0）| 序列号
        return (currentTimestamp << TIMESTAMP_LEFT_SHIFT) | sequence;
    }

    private long getTimestampOffset() {
        return System.currentTimeMillis() - CUSTOM_EPOCH;
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = getTimestampOffset();

        while (timestamp <= lastTimestamp) {
            Thread.onSpinWait();
            timestamp = getTimestampOffset();
        }

        return timestamp;
    }

    /**
     * Base62 编码（将长整型转为短字符串）
     * <p>
     * 用途：将 Snowflake ID 转换为 URL 友好的短码
     * <pre>
     * 示例：
     * base62Encode(123456789012345L) → "dBvJjXh" (7 字符)
     * base62Encode(0L)               → "0"
     * </pre>
     * <p>
     * Base62 字符集：0-9, a-z, A-Z（62 个字符）
     * - 比 Base64 少 2 个特殊字符（+/），更适合 URL
     * - 编码长度：约 log62(2^64) ≈ 11 字符（最大值）
     *
     * @param num 待编码的数字
     * @return Base62 编码字符串（短码）
     */
    public String base62Encode(long num) {
        if (num == 0) {
            return "0";
        }

        StringBuilder sb = new StringBuilder();

        while (num > 0) {
            sb.append(BASE62.charAt((int) (num % 62)));
            num /= 62;
        }

        // 反转字符串（因为是从低位到高位追加的）
        return sb.reverse().toString();
    }

    /**
     * Base62 解码（将短字符串还原为长整型）
     * <p>
     * 用途：从短码还原原始 ID
     * <pre>
     * 示例：
     * base62Decode("dBvJjXh") → 123456789012345L
     * base62Decode("0")       → 0L
     * </pre>
     * <p>
     * 注意：当前系统中，数据库查询使用 shortCode 字段而非解码后的 ID
     * 该方法主要用于调试或未来的优化场景
     *
     * @param str Base62 编码字符串（短码）
     * @return 原始数字 ID
     * @throws IllegalArgumentException 如果字符串包含非法字符
     */
    public long base62Decode(String str) {
        long result = 0;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            int value = BASE62.indexOf(c);
            if (value == -1) {
                throw new IllegalArgumentException("非法的 Base62 字符: " + c);
            }
            result = result * 62 + value;
        }
        return result;
    }
}
