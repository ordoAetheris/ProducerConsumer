import com.ordoAetheris.drafts.solution.WorkQueue;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.BitSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

public class WorkQueueTest {
    @Nested
    class ExceptionsTests {
        @Test
        public void put_null_IAE() {
            WorkQueue<Integer> queue = new WorkQueue<>();

            assertThrows(IllegalArgumentException.class, () -> queue.put(null));
        }

        @Test
        public void put_after_close_ISE() {
            WorkQueue<Integer> queue = new WorkQueue<>();
            queue.close();

            assertThrows(IllegalStateException.class, () -> queue.put(1));
        }

        /*

    T take() throws InterruptedException
    если очередь пуста и НЕ закрыта → ждёт
    если очередь закрыта и пуста → возвращает null (это “EOF”)
    иначе возвращает элемент
         */
        @Test
        public void closeUnblocksTake_returnNullOnEmpty() throws Exception {
            WorkQueue<Integer> queue = new WorkQueue<>();

            CountDownLatch waiterLatch = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();

            Future<Integer> f = executor.submit( () -> {
                waiterLatch.countDown();
                return queue.take();
            });

            waiterLatch.await();
            Thread.sleep(20);
            queue.close();

            Integer res = getOrDump(f, 1, executor, "CloseUnblocksTake");
            executor.shutdownNow();

            assertNull(res, "expected EOF null after close on empty queue");
        }

        @Test
        void closeDoesNotDropAlreadyEnqueuedItems() throws Exception {
            // =====================================================================
            // ЭМУЛИРУЕМ: shutdown в момент, когда в очереди ещё есть накопленные задачи.
            // Требование:
            // - consumers должны ДОЧИТАТЬ всё, что уже было принято (иначе потеря задач)
            // - и только потом получить EOF (null)
            // =====================================================================
            WorkQueue<Integer> q = new WorkQueue<>();
            int n = 10_000;
            for (int i = 0; i < n; i++) q.put(i);

            q.close(); // закрыли "вход", но очередь ещё не пуста

            int consumed = 0;
            while (true) {
                Integer x = q.take();
                if (x == null) break;
                consumed++;
            }
            assertEquals(n, consumed, "close must not drop already enqueued items");
        }

        @Nested
        class NonFunctionalTests {
            @Test
            void closeDoesNotDropAlreadyEnqueuedItems() throws Exception {
                // =====================================================================
                // ЭМУЛИРУЕМ: shutdown в момент, когда в очереди ещё есть накопленные задачи.
                // Требование:
                // - consumers должны ДОЧИТАТЬ всё, что уже было принято (иначе потеря задач)
                // - и только потом получить EOF (null)
                // =====================================================================
                WorkQueue<Integer> q = new WorkQueue<>();
                int n = 10_000;
                for (int i = 0; i < n; i++) q.put(i);

                q.close(); // закрыли "вход", но очередь ещё не пуста

                int consumed = 0;
                while (true) {
                    Integer x = q.take();
                    if (x == null) break;
                    consumed++;
                }
                assertEquals(n, consumed, "close must not drop already enqueued items");
            }

            @Test
            void spsc_strict_noLoss_noDuplicates() throws Exception {
                // =====================================================================
                // ЭМУЛИРУЕМ: SPSC (single producer, single consumer).
                // Это базовый режим любой очереди задач/пайплайна.
                //
                // Ловим два класса продовых багов:
                // 1) lost items (потери)
                // 2) duplicates (двойная обработка)
                //
                // Почему важно:
                // - потеря = потерянное сообщение / событие / заказ
                // - дубликат = двойной платёж / повторная команда роботу
                // =====================================================================
                int n = 50_000;
                WorkQueue<Integer> q = new WorkQueue<>();

                BitSet seen = new BitSet(n);
                AtomicInteger consumed = new AtomicInteger();

                CountDownLatch start = new CountDownLatch(1);
                ExecutorService pool = Executors.newFixedThreadPool(2);

                Future<?> prod = pool.submit(() -> { await(start); call(() -> {
                    for (int i = 0; i < n; i++) q.put(i);
                    q.close(); // EOF
                });});

                Future<?> cons = pool.submit(() -> { await(start); call(() -> {
                    while (true) {
                        Integer x = q.take();
                        if (x == null) break;

                        synchronized (seen) {
                            if (seen.get(x)) fail("duplicate item: " + x);
                            seen.set(x);
                        }
                        consumed.incrementAndGet();
                    }
                    });
                });

                start.countDown();

                getOrDump(prod, 3, pool, "spsc_strict:producer");
                getOrDump(cons, 3, pool, "spsc_strict:consumer");

                pool.shutdownNow();

                assertEquals(n, consumed.get(), "lost items suspected");
                synchronized (seen) {
                    assertEquals(n, seen.cardinality(), "not all items were observed");
                }
            }

            @Test
            void spsc_withJitter_manyRuns200_raceHunting() throws Exception {
                // =====================================================================
                // ЭМУЛИРУЕМ: нестабильный планировщик / случайные задержки:
                // - contention
                // - preemption
                // - случайные переключения между потоками
                //
                // Это нужно, чтобы ловить:
                // - lost wakeups
                // - неправильные while/if
                // - гонки на close()
                // =====================================================================
                int runs = 200;
                int n = 20_000;
                ThreadLocalRandom rnd = ThreadLocalRandom.current();

                for (int r = 0; r < runs; r++) {
                    WorkQueue<Integer> q = new WorkQueue<>();
                    BitSet seen = new BitSet(n);
                    AtomicInteger consumed = new AtomicInteger();

                    CountDownLatch start = new CountDownLatch(1);
                    ExecutorService pool = Executors.newFixedThreadPool(2);

                    Future<?> prod = pool.submit(() -> { await(start); call(() -> {
                        for (int i = 0; i < n; i++) {
                            jitter(rnd);
                            q.put(i);
                        }
                        q.close();
                    });});

                    int finalR = r;
                    Future<?> cons = pool.submit(() -> { await(start); call(() -> {
                        while (true) {
                            jitter(rnd);
                            Integer x = q.take();
                            if (x == null) break;
                            synchronized (seen) {
                                if (seen.get(x)) fail("duplicate: " + x + " run=" + finalR);
                                seen.set(x);
                            }
                            consumed.incrementAndGet();
                        }
                    });});

                    start.countDown();

                    try {
                        getOrDump(prod, 5, pool, "run=" + r + ":producer");
                        getOrDump(cons, 5, pool, "run=" + r + ":consumer");
                    } catch (AssertionError e) {
                        throw new AssertionError("Failed on run=" + r + " consumed=" + consumed.get(), e);
                    } finally {
                        pool.shutdownNow();
                    }

                    assertEquals(n, consumed.get(), "run=" + r);
                    synchronized (seen) {
                        assertEquals(n, seen.cardinality(), "run=" + r);
                    }
                }
            }
            @Test
            void closeUnblocksTake_noDeadlock() throws Exception {
                // =====================================================================
                // ЭМУЛИРУЕМ: consumer стоит в take() на пустой очереди.
                // В проде это воркер, который "ждёт работу".
                // Если при shutdown мы не разбудим этот take(),
                // сервис зависнет при остановке навсегда.
                // =====================================================================
                WorkQueue<Integer> q = new WorkQueue<>();

                CountDownLatch start = new CountDownLatch(1);
                ExecutorService pool = Executors.newFixedThreadPool(1);

                Future<?> cons = pool.submit(() -> { await(start); call(() -> {
                    Integer x = q.take(); // должен ждать
                    assertNull(x);        // после close обязан выйти
                });});

                start.countDown();
                Thread.sleep(20);
                q.close();

                getOrDump(cons, 1, pool, "closeUnblocksTake");
                pool.shutdownNow();
            }
        }
    }



    // ============== helpers ============

    private static void jitter(ThreadLocalRandom rnd) {
        // 1/64 вероятности дать планировщику шанс
        if ((rnd.nextInt() & 63) != 0) return;

        // микропаузa, не миллисекунды!
        LockSupport.parkNanos(rnd.nextInt(50_000)); // до 50µs
    }


    private static <T> T getOrDump(Future<T> f, int sec, ExecutorService pool, String tag) throws Exception {
        try {
            return f.get(sec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.err.println("=== TIMEOUT [" + tag + "] thread dump ===");
            dumpThreads();
            pool.shutdownNow();
            throw new AssertionError("Timeout: " + tag, e);
        }
    }

    private static void dumpThreads() {
        // Практически "jstack" внутри теста.
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = mx.dumpAllThreads(true, true);
        for (ThreadInfo ti : infos) {
            System.err.println(ti.toString());
        }
        long[] dead = mx.findDeadlockedThreads();
        if (dead != null && dead.length > 0) {
            System.err.println("=== DEADLOCK DETECTED ===");
            ThreadInfo[] di = mx.getThreadInfo(dead, true, true);
            for (ThreadInfo ti : di) System.err.println(ti.toString());
        }
    }


    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static void call(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
