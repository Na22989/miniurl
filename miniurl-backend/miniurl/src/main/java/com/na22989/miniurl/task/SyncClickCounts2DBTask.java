package com.na22989.miniurl.task;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.na22989.miniurl.service.LinkService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.na22989.miniurl.common.RedisKeyConstant.CLICK_COUNT_PREFIX;

@Component
@Slf4j
@RequiredArgsConstructor
public class SyncClickCounts2DBTask {

    private final StringRedisTemplate stringRedisTemplate;

    private final LinkService linkService;

    private static final int BATCH = 100;

    // 每次 SCAN 返回的条数
    private static final int SCAN_COUNT = 100;

    @Scheduled(fixedDelay = 30000)
    public void syncClickCounts2DB() {
        log.info("[点击计数] 开始同步点击数到数据库...");

        long startTime = System.currentTimeMillis();
        int totalSynced = 0;
        List<ClickSyncDTO> clickSyncDTOS = new ArrayList<>();

        // SCAN 配置
        ScanOptions options = ScanOptions.scanOptions()
                .match(CLICK_COUNT_PREFIX + "*")
                .count(SCAN_COUNT)
                .build();

        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();

                String shortCode = key.substring(CLICK_COUNT_PREFIX.length());
                // GETDEL：读即取走，原子地返回旧值并删除 key，消除「读-删」窗口的竞态
                String clickCountStr = stringRedisTemplate.opsForValue().getAndDelete(key);

                if (clickCountStr == null) continue;

                int clickCount = Integer.parseInt(clickCountStr);

                if (clickCount <= 0) continue;

                clickSyncDTOS.add(new ClickSyncDTO(shortCode, clickCount));

                // 达到批量阈值，批量更新到数据库，避免一次性读取过多keys导致oom
                if (clickSyncDTOS.size() >= BATCH) {
                    totalSynced += batchUpdateAndClear(clickSyncDTOS);
                }
            }
        }

        if (!clickSyncDTOS.isEmpty()) {
            totalSynced += batchUpdateAndClear(clickSyncDTOS);
        }

        log.info("[点击计数] 同步完成，共更新 {} 条记录，耗时 {} ms",
                totalSynced, System.currentTimeMillis() - startTime);
    }

    public int batchUpdateAndClear(List<ClickSyncDTO> clickSyncDTOS) {
        if (clickSyncDTOS.isEmpty()) {
            return 0;
        }

        // 这里不向上抛错，留到log中解决，避免因为一批次的错误导致整个定时任务宕机
        try {
            int synced = linkService.batchUpdateClickCount(clickSyncDTOS);
            log.debug("[点击计数] 批量更新成功，影响 {} 行，共 {} 条记录", synced, clickSyncDTOS.size());
            return synced;
        } catch (Exception e) {
            log.error("[点击计数] 批量更新失败，待重试数据量：{}", clickSyncDTOS.size(), e);
            // GETDEL 已在读取时删除了 key，失败时必须把计数加回 Redis，否则永久丢失
            for (ClickSyncDTO dto : clickSyncDTOS) {
                try {
                    stringRedisTemplate.opsForValue()
                            .increment(CLICK_COUNT_PREFIX + dto.getShortCode(), dto.getClickCount());
                } catch (Exception ex) {
                    log.error("[点击计数] 补偿写回失败 shortCode={} delta={}",
                            dto.getShortCode(), dto.getClickCount(), ex);
                }
            }
            return 0;
        } finally {
            clickSyncDTOS.clear();
        }
    }

    @Data
    @AllArgsConstructor
    public static class ClickSyncDTO {
        private String shortCode;
        private Integer clickCount;

    }
}
