// IdGenBench — Snowflake 生成器并发正确性 + 吞吐微测（脱离 HTTP / Spring）
//
// 为什么脱离 HTTP：创建接口挂着全局令牌桶（rate:global:create，100/s）和用户滑动窗口
// （50 次/60s），HTTP 压创建量测到的是限流器，不是生成器。生成器的并发正确性与吞吐
// 必须在 JVM 内直接压 nextId()。
//
// 为什么不放进 src/test：本项目把单元测试数当回归基线（当前 158 个），新增测试类会
// 破坏这个可对照的数字。本文件用 JDK 17 单文件源码启动，不进 Maven 测试集。
//
// 运行（先确保 target/classes 已编译；下例从仓库根目录开始）：
//   cd miniurl-backend/miniurl && ./mvnw -q clean compile -DskipTests && cd ../..
//   java -cp miniurl-backend/miniurl/target/classes JmeterTest/pressure-p46/IdGenBench.java 16 200000
//
// 参数：<线程数> <总 ID 数>，默认 16 / 200000

import com.na22989.miniurl.util.ShortLinkUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class IdGenBench {

    public static void main(String[] args) throws Exception {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 16;
        int total = args.length > 1 ? Integer.parseInt(args[1]) : 200_000;
        int perThread = total / threads;
        total = perThread * threads;

        ShortLinkUtil util = new ShortLinkUtil();

        // 预热：让 JIT 把 nextId/base62Encode 编译掉，否则前几万次测的是解释执行
        for (int i = 0; i < 50_000; i++) {
            util.base62Encode(util.nextId());
        }

        List<long[]> perThreadIds = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            perThreadIds.add(new long[perThread]);
        }

        AtomicInteger clockBackFailures = new AtomicInteger();
        AtomicInteger otherFailures = new AtomicInteger();

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            Thread th = new Thread(() -> {
                long[] mine = perThreadIds.get(idx);
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < mine.length; i++) {
                    try {
                        mine[i] = util.nextId();
                    } catch (RuntimeException e) {
                        // 生成器唯一的主动拒绝路径就是时钟回拨（BizException(INTERNAL_ERROR)）
                        String msg = String.valueOf(e.getMessage());
                        if (msg.contains("时钟回拨")) {
                            clockBackFailures.incrementAndGet();
                        } else {
                            otherFailures.incrementAndGet();
                        }
                        mine[i] = -1L;
                    }
                }
                done.countDown();
            }, "idgen-" + t);
            th.start();
        }

        ready.await();
        long t0 = System.nanoTime();
        start.countDown();
        done.await();
        long elapsedNs = System.nanoTime() - t0;

        // ── 断言 1：零重复 ──────────────────────────────────────────
        // 用 HashMap 而不是 Set，重复时能报出是哪两个线程撞的
        Map<Long, Integer> seen = new HashMap<>(total * 2);
        int duplicates = 0;
        long minId = Long.MAX_VALUE;
        long maxId = Long.MIN_VALUE;
        for (int t = 0; t < threads; t++) {
            for (long id : perThreadIds.get(t)) {
                if (id < 0) continue;
                Integer prev = seen.put(id, t);
                if (prev != null) {
                    duplicates++;
                    if (duplicates <= 5) {
                        System.out.printf("  [重复] id=%d 线程 %d 与 %d%n", id, prev, t);
                    }
                }
                if (id < minId) minId = id;
                if (id > maxId) maxId = id;
            }
        }

        // ── 断言 2：单调性 ──────────────────────────────────────────
        // nextId() 是 synchronized 的，同一线程的后一次调用必然发生在前一次之后，
        // 所以每个线程自己的序列必须严格递增。跨线程顺序不可观测，不做断言。
        int monotonicViolations = 0;
        for (int t = 0; t < threads; t++) {
            long[] mine = perThreadIds.get(t);
            for (int i = 1; i < mine.length; i++) {
                if (mine[i] >= 0 && mine[i - 1] >= 0 && mine[i] <= mine[i - 1]) {
                    monotonicViolations++;
                }
            }
        }

        // ── 观测 3：同毫秒并发密度（序列号 12 位 = 4096/ms 上限是否被打到）──
        Map<Long, Integer> perMs = new HashMap<>();
        for (Long id : seen.keySet()) {
            long ms = id >>> 22;                      // 41 位时间戳，左移 22 位（10 机器位 + 12 序列位）
            perMs.merge(ms, 1, Integer::sum);
        }
        int maxPerMs = perMs.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        // ── 观测 4：短码长度分布（link.short_code 是 varchar(10)）──
        Map<Integer, Integer> lenHist = new HashMap<>();
        for (Long id : seen.keySet()) {
            lenHist.merge(util.base62Encode(id).length(), 1, Integer::sum);
        }

        double seconds = elapsedNs / 1_000_000_000.0;
        System.out.println();
        System.out.println("======== IdGenBench 结果 ========");
        System.out.printf("线程数            : %d%n", threads);
        System.out.printf("生成 ID 总数      : %d（成功 %d）%n", total, seen.size());
        System.out.printf("耗时              : %.3f s%n", seconds);
        System.out.printf("吞吐              : %.0f ids/s%n", seen.size() / seconds);
        System.out.printf("重复 ID 数        : %d      ← 判据：必须为 0%n", duplicates);
        System.out.printf("时钟回拨拒绝次数  : %d      ← 判据：必须为 0（机器未调时钟时）%n", clockBackFailures.get());
        System.out.printf("其他异常次数      : %d      ← 判据：必须为 0%n", otherFailures.get());
        System.out.printf("单调性违例（线程内）: %d    ← 判据：必须为 0%n", monotonicViolations);
        System.out.printf("单毫秒最大生成量  : %d / 4096（序列位上限）%n", maxPerMs);
        System.out.printf("ID 区间           : %d .. %d%n", minId, maxId);
        System.out.println("短码长度分布      : " + lenHist);

        // ── 观测 5：短码何时会溢出 varchar(10) ──
        // base62 满 10 位的上界是 62^10；id = timestampOffset << 22，反推 offset 与日历时间
        double pow62_10 = Math.pow(62, 10);
        long offsetAt11Chars = (long) (pow62_10 / (1L << 22));
        long epochMs = 1767196800000L;               // ShortLinkUtil.CUSTOM_EPOCH = 2026-01-01 00:00:00
        java.time.Instant overflowAt = java.time.Instant.ofEpochMilli(epochMs + offsetAt11Chars);
        System.out.printf("短码转 11 位的时点 : %s（此后 link.short_code varchar(10) 会插入失败）%n",
                java.time.LocalDateTime.ofInstant(overflowAt, java.time.ZoneId.of("Asia/Shanghai")));

        boolean pass = duplicates == 0 && clockBackFailures.get() == 0
                && otherFailures.get() == 0 && monotonicViolations == 0;
        System.out.println(pass ? "结论：PASS" : "结论：FAIL");
        System.exit(pass ? 0 : 1);
    }
}
