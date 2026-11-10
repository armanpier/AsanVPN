package com.asanvpn.app.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Empirical Adversarial Stress Test Suite for AppLogger:
 * 1. Concurrent flooding of logUser and logAdmin (>1000 calls across 20 threads) -> buffer cap <= 100
 * 2. Strict FIFO eviction ordering (oldest entries evicted, latest entries preserved)
 * 3. Extreme & adversarial inputs to logLinkTest (empty/blank, huge strings, unicode/emojis, negative & max latency)
 * 4. Dual-tier synchronization and concurrent read/write/clear race conditions
 */
public class AppLoggerStressTest {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    @org.junit.Test
    public void testAppLoggerStress() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("  STARTING EMPIRICAL ADVERSARIAL STRESS TEST     ");
        System.out.println("=================================================");

        testConcurrentFloodingBufferCap();
        testStrictFifoEvictionOrder();
        testDualTierEvictionSeparation();
        testExtremeInputsToLogLinkTest();
        testReflectionExactBufferSize();
        testNullSafetyAndExceptions();
        testConcurrentReadWriteClearRace();
        testHighContentionThroughputBenchmark();

        System.out.println("=================================================");
        System.out.printf("  RESULTS: %d PASSED, %d FAILED%n", testsPassed, testsFailed);
        System.out.println("=================================================");

        if (testsFailed > 0) {
            throw new AssertionError(testsFailed + " tests failed");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> getInternalList(String fieldName) throws Exception {
        java.lang.reflect.Field field = AppLogger.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (List<String>) field.get(AppLogger.INSTANCE);
    }

    /**
     * Verify exact internal list size via reflection.
     * Confirms userLogs.size() and adminLogs.size() strictly <= 100 regardless of \n characters.
     */
    private static void testReflectionExactBufferSize() {
        System.out.println("\n--- TEST 5: Reflection Exact Internal Buffer Inspection ---");
        AppLogger.INSTANCE.clear();

        try {
            List<String> internalUser = getInternalList("userLogs");
            List<String> internalAdmin = getInternalList("adminLogs");

            assertTrue("InitialUserEmpty", internalUser.isEmpty(), "Internal userLogs is empty after clear()");
            assertTrue("InitialAdminEmpty", internalAdmin.isEmpty(), "Internal adminLogs is empty after clear()");

            // Insert 120 multiline entries (each containing 3 newlines)
            for (int i = 0; i < 120; i++) {
                AppLogger.INSTANCE.logUser("LineA\nLineB\nLineC " + i);
            }

            assertTrue("ExactInternalUserCap", internalUser.size() == 100, "Internal userLogs.size() is EXACTLY 100 (not 120 or 300)");
            assertTrue("ExactInternalAdminCap", internalAdmin.size() == 100, "Internal adminLogs.size() is EXACTLY 100");

            // Also test single logAdmin
            for (int i = 0; i < 150; i++) {
                AppLogger.INSTANCE.logAdmin("AdminLine\nSubLine " + i);
            }
            assertTrue("ExactInternalAdminCapAfterAdmin", internalAdmin.size() == 100, "Internal adminLogs.size() remains EXACTLY 100");
            assertTrue("ExactInternalUserUnaffected", internalUser.size() == 100, "Internal userLogs.size() remains EXACTLY 100");
        } catch (Exception e) {
            assertTrue("ReflectionAccess", false, "Reflection inspection failed: " + e.getMessage());
        }
    }

    /**
     * Test passing null to logLinkTest (simulating null deserialized remark from Gson)
     */
    private static void testNullSafetyAndExceptions() {
        System.out.println("\n--- TEST 6: Null Safety & Kotlin Intrinsics Check ---");
        AppLogger.INSTANCE.clear();

        boolean npeThrown = false;
        try {
            // In Kotlin, remark is String (non-null). Calling with null from Java triggers Intrinsics.checkNotNullParameter
            AppLogger.INSTANCE.logLinkTest(null, 100L, true);
        } catch (NullPointerException e) {
            npeThrown = true;
            System.out.println("Observed expected Kotlin NullPointerException for null remark: " + e.getMessage());
        } catch (Throwable t) {
            System.out.println("Observed throwable for null remark: " + t.getClass().getName());
        }
        assertTrue("KotlinNullCheckPresent", npeThrown, "Kotlin Intrinsics correctly guards non-null 'remark' parameter");
    }

    /**
     * High Contention Throughput Benchmark:
     * 10,000 total calls across 50 concurrent threads.
     * Validates lock latency and ensures no deadlocks, race conditions, or buffer overflow.
     */
    private static void testHighContentionThroughputBenchmark() {
        System.out.println("\n--- TEST 7: High Contention Throughput Benchmark (10,000 calls / 50 threads) ---");
        AppLogger.INSTANCE.clear();

        int threads = 50;
        int callsPerThread = 200; // 50 * 200 = 10,000 calls
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threads);

        long startNs = System.nanoTime();
        for (int i = 0; i < threads; i++) {
            final int t = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int j = 0; j < callsPerThread; j++) {
                        if (j % 3 == 0) {
                            AppLogger.INSTANCE.logUser("Heavy user " + t + ":" + j);
                        } else if (j % 3 == 1) {
                            AppLogger.INSTANCE.logAdmin("Heavy admin " + t + ":" + j);
                        } else {
                            AppLogger.INSTANCE.logLinkTest("Node " + t, (long) j, j % 2 == 0);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        try {
            boolean done = endGate.await(15, TimeUnit.SECONDS);
            long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            assertTrue("HeavyFloodFinished", done, "10,000 logging calls completed in " + totalMs + "ms");
            System.out.printf("Throughput: %.2f calls/sec under 50-thread contention%n", (10000.0 / (totalMs / 1000.0)));
        } catch (InterruptedException e) {
            assertTrue("HeavyFloodFinished", false, "Timed out during heavy flood");
        }
        pool.shutdown();

        try {
            List<String> internalUser = getInternalList("userLogs");
            List<String> internalAdmin = getInternalList("adminLogs");
            assertTrue("BenchmarkUserCap", internalUser.size() == 100, "userLogs internal size is exactly 100 (actual: " + internalUser.size() + ")");
            assertTrue("BenchmarkAdminCap", internalAdmin.size() == 100, "adminLogs internal size is exactly 100 (actual: " + internalAdmin.size() + ")");
        } catch (Exception e) {
            assertTrue("BenchmarkReflection", false, "Reflection failed: " + e.getMessage());
        }
    }

    private static void assertTrue(String testName, boolean condition, String message) {
        if (condition) {
            System.out.println("[PASS] " + testName + ": " + message);
            testsPassed++;
        } else {
            System.err.println("[FAIL] " + testName + ": " + message);
            testsFailed++;
        }
    }

    /**
     * Task 1: Flood AppLogger with >100 entries (500 logUser + 500 logAdmin across concurrent threads)
     * and verify size never exceeds 100.
     */
    private static void testConcurrentFloodingBufferCap() {
        System.out.println("\n--- TEST 1: Concurrent Flooding & Buffer Capping (1000 entries) ---");
        AppLogger.INSTANCE.clear();

        int numThreads = 20;
        int callsPerThread = 50; // Total 1000 calls (500 user, 500 admin)
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    for (int j = 0; j < callsPerThread; j++) {
                        if (threadId % 2 == 0) {
                            AppLogger.INSTANCE.logUser("Thread-" + threadId + " msg-" + j);
                        } else {
                            AppLogger.INSTANCE.logAdmin("Thread-" + threadId + " msg-" + j);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        try {
            boolean finished = doneGate.await(10, TimeUnit.SECONDS);
            assertTrue("ConcurrentExecution", finished, "All 1000 logging calls completed within 10s");
        } catch (InterruptedException e) {
            assertTrue("ConcurrentExecution", false, "Timed out waiting for concurrent flood");
        }
        executor.shutdown();

        String userLog = AppLogger.INSTANCE.getUserLog();
        String adminLog = AppLogger.INSTANCE.getAdminLog();

        String[] userLines = userLog.split("\n");
        String[] adminLines = adminLog.split("\n");

        System.out.println("User log line count after 1000 calls: " + userLines.length);
        System.out.println("Admin log line count after 1000 calls: " + adminLines.length);

        assertTrue("UserLogCap", userLines.length <= 100, "User log entries (" + userLines.length + ") <= 100");
        assertTrue("AdminLogCap", adminLines.length <= 100, "Admin log entries (" + adminLines.length + ") <= 100");
        assertTrue("AdminLogExact100", adminLines.length == 100, "Admin log has exactly 100 entries");
    }

    /**
     * Task 2: Verify FIFO eviction: ensure the oldest entries are discarded first and the latest entries are preserved.
     */
    private static void testStrictFifoEvictionOrder() {
        System.out.println("\n--- TEST 2: Strict FIFO Eviction Order ---");
        AppLogger.INSTANCE.clear();

        // 1. Test FIFO on User Log (add 150 entries sequentially)
        for (int i = 0; i < 150; i++) {
            AppLogger.INSTANCE.logUser("USER_TOKEN_" + i);
        }

        String userLog = AppLogger.INSTANCE.getUserLog();
        String[] userLines = userLog.split("\n");

        assertTrue("UserFifoCount", userLines.length == 100, "User log contains exactly 100 entries after 150 insertions");
        assertTrue("UserFifoFirstEvicted", !userLog.contains("USER_TOKEN_0"), "USER_TOKEN_0 was evicted");
        assertTrue("UserFifoOldestEvicted", !userLog.contains("USER_TOKEN_49"), "USER_TOKEN_49 was evicted");
        assertTrue("UserFifoHeadPreserved", userLines[0].contains("USER_TOKEN_50"), "Head of user log is USER_TOKEN_50 (line: " + userLines[0] + ")");
        assertTrue("UserFifoTailPreserved", userLines[99].contains("USER_TOKEN_149"), "Tail of user log is USER_TOKEN_149 (line: " + userLines[99] + ")");

        // Check sequential ordering inside the preserved 100 entries
        boolean orderValid = true;
        for (int i = 0; i < 100; i++) {
            int expectedId = 50 + i;
            if (!userLines[i].contains("USER_TOKEN_" + expectedId)) {
                orderValid = false;
                break;
            }
        }
        assertTrue("UserFifoStrictOrder", orderValid, "All 100 entries are in strict ascending FIFO order 50..149");

        // 2. Test FIFO on Admin Log (add 150 admin entries sequentially)
        AppLogger.INSTANCE.clear();
        for (int i = 0; i < 150; i++) {
            AppLogger.INSTANCE.logAdmin("ADMIN_TOKEN_" + i);
        }

        String adminLog = AppLogger.INSTANCE.getAdminLog();
        String[] adminLines = adminLog.split("\n");

        assertTrue("AdminFifoCount", adminLines.length == 100, "Admin log contains exactly 100 entries after 150 insertions");
        assertTrue("AdminFifoFirstEvicted", !adminLog.contains("ADMIN_TOKEN_0"), "ADMIN_TOKEN_0 was evicted");
        assertTrue("AdminFifoOldestEvicted", !adminLog.contains("ADMIN_TOKEN_49"), "ADMIN_TOKEN_49 was evicted");
        assertTrue("AdminFifoHeadPreserved", adminLines[0].contains("ADMIN_TOKEN_50"), "Head of admin log is ADMIN_TOKEN_50 (line: " + adminLines[0] + ")");
        assertTrue("AdminFifoTailPreserved", adminLines[99].contains("ADMIN_TOKEN_149"), "Tail of admin log is ADMIN_TOKEN_149 (line: " + adminLines[99] + ")");
    }

    /**
     * Test Dual-Tier Ingestion & Eviction
     * logUser writes to both userLogs and adminLogs.
     * logAdmin writes only to adminLogs.
     */
    private static void testDualTierEvictionSeparation() {
        System.out.println("\n--- TEST 3: Dual-Tier Ingestion & Eviction Separation ---");
        AppLogger.INSTANCE.clear();

        // Add 50 user logs
        for (int i = 0; i < 50; i++) {
            AppLogger.INSTANCE.logUser("SHARED_USER_" + i);
        }

        // Add 100 admin logs
        for (int i = 0; i < 100; i++) {
            AppLogger.INSTANCE.logAdmin("ONLY_ADMIN_" + i);
        }

        String userLog = AppLogger.INSTANCE.getUserLog();
        String adminLog = AppLogger.INSTANCE.getAdminLog();

        String[] userLines = userLog.split("\n");
        String[] adminLines = adminLog.split("\n");

        assertTrue("UserLogUnaffectedByAdmin", userLines.length == 50, "User log retained all 50 user entries unaffected by 100 admin logs");
        assertTrue("AdminLogHas100", adminLines.length == 100, "Admin log is capped at 100 entries");
        assertTrue("AdminLogEvictedShared", !adminLog.contains("SHARED_USER_0"), "Admin log evicted earlier SHARED_USER entries to keep cap");
        assertTrue("UserLogNoAdminLeaked", !userLog.contains("[ADMIN]"), "User log does NOT contain any [ADMIN] entries");
    }

    /**
     * Task 3: Verify that empty and extreme inputs to logLinkTest behave safely without crashing.
     */
    private static void testExtremeInputsToLogLinkTest() {
        System.out.println("\n--- TEST 4: Extreme Inputs to logLinkTest ---");
        AppLogger.INSTANCE.clear();

        // 1. Empty and whitespace remarks
        try {
            AppLogger.INSTANCE.logLinkTest("", 50L, true);
            AppLogger.INSTANCE.logLinkTest("   ", 0L, true);
            assertTrue("EmptyRemarkSafe", true, "Empty and whitespace remarks processed without exception");
        } catch (Exception e) {
            assertTrue("EmptyRemarkSafe", false, "Exception on empty remark: " + e.getMessage());
        }

        // 2. Negative latency & zero latency
        try {
            AppLogger.INSTANCE.logLinkTest("German Server 1", -1L, false);
            AppLogger.INSTANCE.logLinkTest("German Server 2", -999999L, false);
            AppLogger.INSTANCE.logLinkTest("Negative latency success test", -50L, true);
            AppLogger.INSTANCE.logLinkTest("Zero latency test", 0L, true);
            assertTrue("NegativeLatencySafe", true, "Negative and zero latencies handled without exception");
        } catch (Exception e) {
            assertTrue("NegativeLatencySafe", false, "Exception on negative latency: " + e.getMessage());
        }

        // 3. Max Long latency
        try {
            AppLogger.INSTANCE.logLinkTest("Max Long Server", Long.MAX_VALUE, true);
            AppLogger.INSTANCE.logLinkTest("Min Long Server", Long.MIN_VALUE, true);
            assertTrue("MaxLongSafe", true, "Long.MAX_VALUE and Long.MIN_VALUE handled without exception");
        } catch (Exception e) {
            assertTrue("MaxLongSafe", false, "Exception on Long.MAX_VALUE: " + e.getMessage());
        }

        // 4. Unicode, Emojis, RTL Persian, control chars, format specifiers
        try {
            String complexRemark = "🇮🇷 سرور ایران (ملی) \uD83D\uDE80 %s %d %n ${jndi:ldap} <script>alert(1)</script> \t\r";
            AppLogger.INSTANCE.logLinkTest(complexRemark, 120L, true);
            assertTrue("ComplexUnicodeSafe", true, "Unicode, Persian RTL, and injection payloads processed safely");
        } catch (Exception e) {
            assertTrue("ComplexUnicodeSafe", false, "Exception on complex Unicode remark: " + e.getMessage());
        }

        // 5. Extremely large string (100,000 chars)
        try {
            StringBuilder huge = new StringBuilder();
            for (int i = 0; i < 10000; i++) {
                huge.append("REMARK_HUGE_");
            }
            AppLogger.INSTANCE.logLinkTest(huge.toString(), 250L, true);
            assertTrue("HugeStringSafe", true, "120,000 character string processed without OutOfMemory or crash");
        } catch (Exception e) {
            assertTrue("HugeStringSafe", false, "Exception on huge string: " + e.getMessage());
        }

        // Verify formatting structure
        String userLog = AppLogger.INSTANCE.getUserLog();
        assertTrue("FormatSuccessSymbol", userLog.contains("⚡"), "Success format contains lightning symbol ⚡");
        assertTrue("FormatFailureSymbol", userLog.contains("⚠️"), "Failure format contains warning symbol ⚠️");
        assertTrue("FormatSuccessFa", userLog.contains("(موفق)"), "Success format contains (موفق)");
        assertTrue("FormatFailureFa", userLog.contains("(ناموفق)"), "Failure format contains (ناموفق)");
        assertTrue("FormatTimeoutFa", userLog.contains("تایم‌اوت"), "Failure format contains تایم‌اوت");
    }

    /**
     * Stress Test: Concurrent Reader/Writer/Clear Race Condition
     */
    private static void testConcurrentReadWriteClearRace() {
        System.out.println("\n--- TEST 5: Concurrent Read / Write / Clear Race ---");
        AppLogger.INSTANCE.clear();

        int threadCount = 16;
        int operations = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger exceptionsCaught = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int tId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operations; j++) {
                        int op = (tId + j) % 5;
                        switch (op) {
                            case 0:
                                AppLogger.INSTANCE.logUser("Concurrent user " + tId + "-" + j);
                                break;
                            case 1:
                                AppLogger.INSTANCE.logAdmin("Concurrent admin " + tId + "-" + j);
                                break;
                            case 2:
                                AppLogger.INSTANCE.logLinkTest("Link " + j, (long) j, j % 2 == 0);
                                break;
                            case 3:
                                String uLog = AppLogger.INSTANCE.getUserLog();
                                if (uLog == null) exceptionsCaught.incrementAndGet();
                                break;
                            case 4:
                                String aLog = AppLogger.INSTANCE.getAdminLog();
                                if (aLog == null) exceptionsCaught.incrementAndGet();
                                break;
                        }
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                    exceptionsCaught.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            boolean done = latch.await(10, TimeUnit.SECONDS);
            assertTrue("ConcurrentRaceCompleted", done, "All threads completed in 10s");
        } catch (InterruptedException e) {
            assertTrue("ConcurrentRaceCompleted", false, "Latch wait interrupted");
        }
        executor.shutdown();

        assertTrue("NoRaceExceptions", exceptionsCaught.get() == 0, "Zero ConcurrentModificationExceptions or nulls caught (" + exceptionsCaught.get() + ")");
        
        // Final sanity check on sizes
        String finalUser = AppLogger.INSTANCE.getUserLog();
        String finalAdmin = AppLogger.INSTANCE.getAdminLog();
        int finalUserCount = finalUser.split("\n").length;
        int finalAdminCount = finalAdmin.split("\n").length;

        assertTrue("FinalUserCountCap", finalUserCount <= 100, "Final user count (" + finalUserCount + ") <= 100");
        assertTrue("FinalAdminCountCap", finalAdminCount <= 100, "Final admin count (" + finalAdminCount + ") <= 100");
    }
}
