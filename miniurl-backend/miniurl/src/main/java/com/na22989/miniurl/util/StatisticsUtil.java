package com.na22989.miniurl.util;

import java.time.LocalDateTime;

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
