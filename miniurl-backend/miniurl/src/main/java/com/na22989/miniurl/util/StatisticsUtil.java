package com.na22989.miniurl.util;

import java.time.LocalDateTime;

/**
 * 日统计时间窗口工具。
 * <p>日窗口统一取半开区间 [startOfDay, endOfDay)：结束时间恒为次日 0 点，
 * 供 Mapper SQL 的 access_time >= start AND access_time < end 使用。</p>
 */
public class StatisticsUtil {

    public static LocalDateTime getStartOfDay() {
        return LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
    }

    public static LocalDateTime getEndOfDay() {
        return getStartOfDay().plusDays(1);
    }

    public static LocalDateTime getStartOfDay(LocalDateTime date) {
        return date.withHour(0).withMinute(0).withSecond(0).withNano(0);
    }

    public static LocalDateTime getEndOfDay(LocalDateTime date) {
        return getStartOfDay(date).plusDays(1);
    }
}
