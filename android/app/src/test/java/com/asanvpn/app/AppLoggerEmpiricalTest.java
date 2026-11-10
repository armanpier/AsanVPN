package com.asanvpn.app;

import com.asanvpn.app.util.AppLogger;

public class AppLoggerEmpiricalTest {
    @org.junit.Test
    public void testAppLoggerEmpirical() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        System.out.println("=== STARTING APPLOGGER EMPIRICAL ADVERSARIAL TEST ===");
        int failures = 0;

        // Test 1: Empty state
        AppLogger.INSTANCE.clear();
        String initialUser = AppLogger.INSTANCE.getUserLog();
        String initialAdmin = AppLogger.INSTANCE.getAdminLog();
        if (!initialUser.contains("گزارشی برای نمایش وجود ندارد") || !initialAdmin.contains("گزارشی برای نمایش وجود ندارد")) {
            System.err.println("FAIL Test 1: Expected placeholder message for empty logs");
            failures++;
        } else {
            System.out.println("PASS Test 1: Empty state returns expected placeholder");
        }

        // Test 2: Buffer capping to 100
        AppLogger.INSTANCE.clear();
        for (int i = 0; i < 150; i++) {
            AppLogger.INSTANCE.logUser("User message " + i);
        }
        String userLog = AppLogger.INSTANCE.getUserLog();
        String[] userLines = userLog.split("\n");
        if (userLines.length != 100) {
            System.err.println("FAIL Test 2: userLog lines count is " + userLines.length + ", expected 100");
            failures++;
        } else if (!userLines[0].contains("User message 50") || !userLines[99].contains("User message 149")) {
            System.err.println("FAIL Test 2: FIFO order violated. First line: " + userLines[0] + ", Last line: " + userLines[99]);
            failures++;
        } else {
            System.out.println("PASS Test 2: User log buffer capped strictly at 100 with FIFO eviction");
        }

        // Test 3: Admin log capping
        AppLogger.INSTANCE.clear();
        for (int i = 0; i < 200; i++) {
            AppLogger.INSTANCE.logAdmin("Admin diagnostic " + i);
        }
        String adminLog = AppLogger.INSTANCE.getAdminLog();
        String[] adminLines = adminLog.split("\n");
        if (adminLines.length != 100) {
            System.err.println("FAIL Test 3: adminLog lines count is " + adminLines.length + ", expected 100");
            failures++;
        } else if (!adminLines[0].contains("Admin diagnostic 100") || !adminLines[99].contains("Admin diagnostic 199")) {
            System.err.println("FAIL Test 3: FIFO order violated in admin log. First line: " + adminLines[0] + ", Last line: " + adminLines[99]);
            failures++;
        } else {
            System.out.println("PASS Test 3: Admin log buffer capped strictly at 100 with FIFO eviction");
        }

        // Test 4: Link test logging format
        AppLogger.INSTANCE.clear();
        AppLogger.INSTANCE.logLinkTest("🇳🇴 Norway Fast", 35, true);
        AppLogger.INSTANCE.logLinkTest("🇩🇪 Germany Fail", -1, false);
        String linkUserLog = AppLogger.INSTANCE.getUserLog();
        String linkAdminLog = AppLogger.INSTANCE.getAdminLog();

        boolean passUserSuccess = linkUserLog.contains("⚡ [🇳🇴 Norway Fast] — پینگ: 35ms (موفق)");
        boolean passUserFail = linkUserLog.contains("⚠️ [🇩🇪 Germany Fail] — پینگ: تایم‌اوت (ناموفق)");
        boolean passAdminSuccess = linkAdminLog.contains("⚡ [🇳🇴 Norway Fast] — پینگ: 35ms (موفق)");
        boolean passAdminFail = linkAdminLog.contains("⚠️ [🇩🇪 Germany Fail] — پینگ: تایم‌اوت (ناموفق)");

        if (!passUserSuccess || !passUserFail || !passAdminSuccess || !passAdminFail) {
            System.err.println("FAIL Test 4: Link test log formatting mismatch.");
            System.err.println("User log:\n" + linkUserLog);
            failures++;
        } else {
            System.out.println("PASS Test 4: Link test logs correctly formatted for both user and admin");
        }

        // Test 5: Concurrency stress test
        AppLogger.INSTANCE.clear();
        Thread[] threads = new Thread[10];
        for (int t = 0; t < 10; t++) {
            final int tid = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    if (i % 2 == 0) {
                        AppLogger.INSTANCE.logUser("Thread " + tid + " user log " + i);
                    } else {
                        AppLogger.INSTANCE.logAdmin("Thread " + tid + " admin log " + i);
                    }
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException e) {}
        }
        String[] finalUserLines = AppLogger.INSTANCE.getUserLog().split("\n");
        String[] finalAdminLines = AppLogger.INSTANCE.getAdminLog().split("\n");
        if (finalUserLines.length > 100 || finalAdminLines.length > 100) {
            System.err.println("FAIL Test 5: Buffer overflow under concurrency! User: " + finalUserLines.length + ", Admin: " + finalAdminLines.length);
            failures++;
        } else {
            System.out.println("PASS Test 5: Concurrency stress test passed without buffer overflow (User size: " + finalUserLines.length + ", Admin size: " + finalAdminLines.length + ")");
        }

        System.out.println("=== APPLOGGER TEST COMPLETE: FAILURES = " + failures + " ===");
        if (failures > 0) throw new AssertionError(failures + " tests failed");
    }
}
