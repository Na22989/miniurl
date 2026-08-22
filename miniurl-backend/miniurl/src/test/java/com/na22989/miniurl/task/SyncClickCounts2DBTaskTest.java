package com.na22989.miniurl.task;

import com.na22989.miniurl.service.LinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;

import static com.na22989.miniurl.common.RedisKeyConstant.CLICK_COUNT_PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SyncClickCounts2DBTask 单元测试")
class SyncClickCounts2DBTaskTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private LinkService linkService;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private Cursor<String> cursor;

    private SyncClickCounts2DBTask task;

    @BeforeEach
    void setUp() {
        task = new SyncClickCounts2DBTask(stringRedisTemplate, linkService);
        // lenient：部分测试（成功路径）不走 opsForValue()，避免 strict stubs 误报
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("batchUpdateAndClear() 成功路径：返回 synced，不做补偿写回，也不删 key")
    void batchUpdateAndClear_shouldReturnSyncedWithoutReAddOrDelete() {
        List<SyncClickCounts2DBTask.ClickSyncDTO> dtos = new ArrayList<>(List.of(
                new SyncClickCounts2DBTask.ClickSyncDTO("abc", 5),
                new SyncClickCounts2DBTask.ClickSyncDTO("xyz", 3)
        ));
        when(linkService.batchUpdateClickCount(dtos)).thenReturn(2);

        int result = task.batchUpdateAndClear(dtos);

        assertEquals(2, result);
        // GETDEL 已在读取时删 key，成功路径不再有 delete，也不补偿 increment
        verify(stringRedisTemplate, never()).delete(anyList());
        verify(valueOperations, never()).increment(anyString(), anyLong());
        // finally 应清空缓冲区（供循环复用）
        assertEquals(0, dtos.size());
    }

    @Test
    @DisplayName("batchUpdateAndClear() 失败路径：DB 更新抛异常，计数应加回 Redis 并返回 0")
    void batchUpdateAndClear_shouldReAddCountsOnFailure() {
        List<SyncClickCounts2DBTask.ClickSyncDTO> dtos = new ArrayList<>(List.of(
                new SyncClickCounts2DBTask.ClickSyncDTO("abc", 5),
                new SyncClickCounts2DBTask.ClickSyncDTO("xyz", 3)
        ));
        when(linkService.batchUpdateClickCount(dtos)).thenThrow(new RuntimeException("DB down"));

        int result = task.batchUpdateAndClear(dtos);

        assertEquals(0, result);
        // GETDEL 已删 key，失败时必须把计数原子加回（increment），否则永久丢失
        verify(valueOperations).increment(CLICK_COUNT_PREFIX + "abc", 5L);
        verify(valueOperations).increment(CLICK_COUNT_PREFIX + "xyz", 3L);
    }

    @Test
    @DisplayName("syncClickCounts2DB() 用 GETDEL 读即取走，而非 get()，并正确累积到批量")
    void syncClickCounts2DB_shouldUseGetAndDelete() {
        when(stringRedisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(CLICK_COUNT_PREFIX + "abc", CLICK_COUNT_PREFIX + "xyz");
        when(valueOperations.getAndDelete(CLICK_COUNT_PREFIX + "abc")).thenReturn("5");
        when(valueOperations.getAndDelete(CLICK_COUNT_PREFIX + "xyz")).thenReturn("3");
        when(linkService.batchUpdateClickCount(anyList())).thenReturn(2);

        task.syncClickCounts2DB();

        // 核心断言：读即取走（原子），不再用旧的 get()
        verify(valueOperations, times(2)).getAndDelete(anyString());
        verify(valueOperations, never()).get(anyString());
        // 用 anyList() 而非内容匹配：batchUpdateAndClear 的 finally 会 clear 传入的 list，
        // verify 时（方法已返回）list 已被清空，内容匹配会误判为空列表。
        verify(linkService).batchUpdateClickCount(anyList());
    }
}
