package com.asanvpn.app;

import com.asanvpn.app.util.AppLogger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class M1RemediationAdversarialTest {

    private static int passedTests = 0;
    private static int failedTests = 0;

    private static void assertTrue(String testName, boolean condition, String details) {
        if (condition) {
            System.out.println("[PASS] " + testName + ": " + details);
            passedTests++;
        } else {
            System.err.println("[FAIL] " + testName + ": " + details);
            failedTests++;
        }
    }

    // Mirror of the remediated PIN masking logic from VpnRepository.kt & MainActivity.kt
    public static String maskPin(String pin) {
        if (pin == null) return "***";
        return pin.length() > 2 ? "***" + pin.substring(pin.length() - 2) : "***";
    }

    // Mirror of the remediated Device ID masking logic from VpnRepository.kt
    public static String maskDeviceId(String deviceId) {
        if (deviceId == null) return "***";
        return deviceId.length() > 4 ? "***" + deviceId.substring(deviceId.length() - 4) : "***";
    }

    // Mirror of the remediated URL masking logic
    public static String maskUrl(String url) {
        if (url == null) return "***";
        return url.replaceAll("://[^/]+", "://***");
    }

    // Mirror of the remediated syncWithWorker exception masking logic
    public static String formatSyncWorkerError(Exception e) {
        String msg = e.getMessage();
        String sanitizedMsg = msg != null ? msg.replaceAll("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+", "***") : "unknown";
        return "syncWithWorker error: " + e.getClass().getSimpleName() + " - " + sanitizedMsg;
    }

    // Mirror of the remediated check_lockout error logging logic
    public static String formatCheckLockoutError(String baseUrl, Exception e) {
        String maskedBaseUrl = maskUrl(baseUrl);
        return "check_lockout error for " + maskedBaseUrl + ": " + e.getClass().getSimpleName();
    }

    @org.junit.Test
    public void testAllM1Suites() throws Exception {
        main(new String[0]);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("   M1 REMEDIATION EMPIRICAL ADVERSARIAL RECHECK TEST SUITE (JDK 17)            ");
        System.out.println("================================================================================");

        testShortAndAdversarialPinMasking();
        testDeviceIdMasking();
        testCheckLockoutUrlAndExceptionMasking();
        testSyncWithWorkerIpRedaction();
        testAppLoggerCappingAndFormattingIntegrity();
        testSourceCodeStaticAudit();

        System.out.println("\n================================================================================");
        System.out.printf("   TEST SUITE EXECUTION COMPLETE: %d PASSED, %d FAILED%n", passedTests, failedTests);
        System.out.println("================================================================================");

        if (failedTests > 0) {
            throw new AssertionError(failedTests + " tests failed");
        }
    }

    /**
     * Requirement: Test short PINs ("1", "12", "123", "12345678", empty "") to ensure zero leakage.
     */
    private static void testShortAndAdversarialPinMasking() {
        System.out.println("\n--- SUITE 1: Adversarial PIN Masking Verification ---");

        String[][] testCases = {
            {"", "***", "Empty PIN"},
            {"1", "***", "Single character PIN ('1')"},
            {"12", "***", "Two character PIN ('12')"},
            {"123", "***23", "Three character PIN ('123')"},
            {"1234", "***34", "Four character PIN ('1234')"},
            {"12345678", "***78", "Eight character standard PIN ('12345678')"},
            {"Arman4237340", "***40", "Alphanumeric 12-character PIN"},
            {"9", "***", "Single digit boundary"},
            {"99", "***", "Two digit boundary"}
        };

        for (String[] tc : testCases) {
            String inputPin = tc[0];
            String expectedMask = tc[1];
            String desc = tc[2];

            String actualMask = maskPin(inputPin);
            assertTrue("PinMask_" + (inputPin.isEmpty() ? "EMPTY" : inputPin),
                actualMask.equals(expectedMask),
                desc + " -> Input: '" + inputPin + "' => Masked: '" + actualMask + "' (Expected: '" + expectedMask + "')");

            // Adversarial assertion: If pin length <= 2, ensure the secret characters are NOT in actualMask
            if (inputPin.length() > 0 && inputPin.length() <= 2) {
                assertTrue("PinLeakPrevention_" + inputPin,
                    !actualMask.contains(inputPin),
                    "Secret short PIN '" + inputPin + "' is 100% concealed and not present in '" + actualMask + "'");
            }

            // Test interaction through AppLogger
            AppLogger.INSTANCE.clear();
            AppLogger.INSTANCE.logAdmin("Toggle connect initiated for PIN: " + actualMask);
            String adminLog = AppLogger.INSTANCE.getAdminLog();
            String userLog = AppLogger.INSTANCE.getUserLog();

            if (!inputPin.isEmpty()) {
                String userLogPayload = userLog.contains("]") ? userLog.substring(userLog.indexOf("]") + 1) : userLog;
                assertTrue("UserLogPinZeroExposure_" + inputPin,
                    !userLogPayload.contains(inputPin),
                    "User log payload contains zero trace of PIN '" + inputPin + "'");
                if (inputPin.length() <= 2) {
                    String adminLogPayload = adminLog.contains("]") ? adminLog.substring(adminLog.lastIndexOf("]") + 1) : adminLog;
                    assertTrue("AdminLogShortPinConcealed_" + inputPin,
                        !adminLogPayload.contains(inputPin),
                        "Admin log payload contains zero trace of short PIN '" + inputPin + "' (Payload: '" + adminLogPayload.trim() + "')");
                }
            }
        }
    }

    /**
     * Test Device ID masking edge cases.
     */
    private static void testDeviceIdMasking() {
        System.out.println("\n--- SUITE 2: Device ID Masking Verification ---");

        String[][] testCases = {
            {"", "***", "Empty Device ID"},
            {"a", "***", "1-char Device ID"},
            {"ab", "***", "2-char Device ID"},
            {"abc", "***", "3-char Device ID"},
            {"abcd", "***", "4-char Device ID (boundary)"},
            {"abcde", "***bcde", "5-char Device ID"},
            {"android_12345678", "***5678", "Standard Android ID"}
        };

        for (String[] tc : testCases) {
            String inputDev = tc[0];
            String expectedMask = tc[1];
            String desc = tc[2];

            String actualMask = maskDeviceId(inputDev);
            assertTrue("DeviceIdMask_" + (inputDev.isEmpty() ? "EMPTY" : inputDev),
                actualMask.equals(expectedMask),
                desc + " -> Input: '" + inputDev + "' => Masked: '" + actualMask + "'");

            if (inputDev.length() > 0 && inputDev.length() <= 4) {
                assertTrue("DeviceIdLeakPrevention_" + inputDev,
                    !actualMask.contains(inputDev),
                    "Secret short Device ID '" + inputDev + "' is 100% concealed");
            }
        }
    }

    /**
     * Requirement: Test error paths in `check_lockout` to ensure zero endpoint URL or IP leakage.
     */
    private static void testCheckLockoutUrlAndExceptionMasking() {
        System.out.println("\n--- SUITE 3: check_lockout Error Path & URL Redaction ---");

        String[] testBaseUrls = {
            "https://worker-subdomain.workers.dev",
            "https://sub.worker.spareone.icu",
            "https://162.159.153.4:8443",
            "http://10.20.30.40:8080",
            "https://user:pass@secret-proxy.workers.dev:9443"
        };

        for (String baseUrl : testBaseUrls) {
            String masked = maskUrl(baseUrl);
            assertTrue("UrlMask_" + baseUrl,
                masked.equals("https://***") || masked.equals("http://***"),
                "Base URL '" + baseUrl + "' masked to authority wildcard '" + masked + "'");
            assertTrue("UrlHostRedacted_" + baseUrl,
                !masked.contains("worker") && !masked.contains("spareone") && !masked.contains("162.159") && !masked.contains("10.20"),
                "No raw hostname or IP present in masked URL '" + masked + "'");
        }

        // Test exception masking: check_lockout MUST log only e.javaClass.simpleName, NOT e.message
        Exception[] testExceptions = {
            new java.net.SocketTimeoutException("failed to connect to 162.159.153.4 (port 8443) from /192.168.1.100 (port 54321) after 5000ms"),
            new java.net.ConnectException("Connection refused to worker.spareone.icu/104.21.23.45:443"),
            new java.net.UnknownHostException("Unable to resolve host \"secret.workers.dev\": No address associated with hostname"),
            new IOException("SSL handshake aborted: Failure in SSL library, error:100004b8:SSL routines:OPENSSL_internal:SSLV3_ALERT_HANDSHAKE_FAILURE to 162.159.153.4")
        };

        for (Exception ex : testExceptions) {
            String baseUrl = "https://sensitive-worker.domain.com:8443";
            String logEntry = formatCheckLockoutError(baseUrl, ex);

            AppLogger.INSTANCE.clear();
            AppLogger.INSTANCE.logAdmin(logEntry);

            String adminLog = AppLogger.INSTANCE.getAdminLog();
            String userLog = AppLogger.INSTANCE.getUserLog();

            assertTrue("CheckLockoutNoRawUrlLeaked_" + ex.getClass().getSimpleName(),
                !adminLog.contains("sensitive-worker.domain.com"),
                "Admin log does not contain raw hostname 'sensitive-worker.domain.com'");

            assertTrue("CheckLockoutNoIpLeaked_" + ex.getClass().getSimpleName(),
                !adminLog.contains("162.159.153.4") && !adminLog.contains("192.168.1.100") && !adminLog.contains("104.21.23.45"),
                "Admin log does not leak IP addresses from exception message");

            assertTrue("CheckLockoutNoPortLeaked_" + ex.getClass().getSimpleName(),
                !adminLog.contains("8443") && !adminLog.contains("54321"),
                "Admin log does not leak network ports");

            assertTrue("CheckLockoutClassNameOnly_" + ex.getClass().getSimpleName(),
                adminLog.contains(ex.getClass().getSimpleName()),
                "Admin log safely includes class name: " + ex.getClass().getSimpleName());

            assertTrue("CheckLockoutUserLogEmpty",
                userLog.contains("گزارشی برای نمایش وجود ندارد"),
                "User log remains completely unaffected by check_lockout admin error");
        }
    }

    /**
     * Requirement: Test error paths in `syncWithWorker` to ensure zero endpoint URL or IP leakage.
     */
    private static void testSyncWithWorkerIpRedaction() {
        System.out.println("\n--- SUITE 4: syncWithWorker IPv4 Address Redaction ---");

        Exception[] testExceptions = {
            new java.net.SocketTimeoutException("failed to connect to 162.159.153.4 (port 8443) from 192.168.1.105 (port 51234) after 5000ms"),
            new java.net.ConnectException("failed to connect to 104.21.23.45:443"),
            new IOException("Network error reaching 10.0.0.1 and gateway 172.16.0.1"),
            new RuntimeException("Plain error without any IP address")
        };

        for (Exception ex : testExceptions) {
            String formattedError = formatSyncWorkerError(ex);

            AppLogger.INSTANCE.clear();
            AppLogger.INSTANCE.logUser("⚠️ در حال ارزیابی مسیرهای ارتباطی شبکه...");
            AppLogger.INSTANCE.logAdmin(formattedError);

            String adminLog = AppLogger.INSTANCE.getAdminLog();
            String userLog = AppLogger.INSTANCE.getUserLog();

            // Verify User log
            assertTrue("SyncWorkerUserLogSanitary",
                userLog.contains("⚠️ در حال ارزیابی مسیرهای ارتباطی شبکه...") &&
                !userLog.contains("162.159") && !userLog.contains("104.21") && !userLog.contains("10.0.0.1"),
                "User log has zero exposure of network error details or IPs");

            // Verify Admin log IPv4 redaction
            assertTrue("SyncWorkerAdminLogNoIp_162",
                !adminLog.contains("162.159.153.4"),
                "IP 162.159.153.4 is redacted from admin log");
            assertTrue("SyncWorkerAdminLogNoIp_192",
                !adminLog.contains("192.168.1.105"),
                "Local IP 192.168.1.105 is redacted from admin log");
            assertTrue("SyncWorkerAdminLogNoIp_104",
                !adminLog.contains("104.21.23.45"),
                "IP 104.21.23.45 is redacted from admin log");
            assertTrue("SyncWorkerAdminLogNoIp_10",
                !adminLog.contains("10.0.0.1"),
                "Private IP 10.0.0.1 is redacted from admin log");
            assertTrue("SyncWorkerAdminLogNoIp_172",
                !adminLog.contains("172.16.0.1"),
                "Private IP 172.16.0.1 is redacted from admin log");

            if (ex.getMessage() != null && ex.getMessage().matches(".*[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+.*")) {
                assertTrue("SyncWorkerReplacementMaskSeen_" + ex.getClass().getSimpleName(),
                    adminLog.contains("***"),
                    "Replacement wildcard '***' present in place of redacted IP");
            }
        }
    }

    /**
     * Requirement: Confirm `AppLogger.kt` continues to enforce 100-entry capping and concise link testing.
     */
    private static void testAppLoggerCappingAndFormattingIntegrity() {
        System.out.println("\n--- SUITE 5: AppLogger 100-Entry Capping & Link Test Integrity ---");

        AppLogger.INSTANCE.clear();

        // Flood 150 entries into user and admin
        for (int i = 0; i < 150; i++) {
            AppLogger.INSTANCE.logUser("FloodUser_" + i);
            AppLogger.INSTANCE.logAdmin("FloodAdmin_" + i);
        }

        String[] userLines = AppLogger.INSTANCE.getUserLog().split("\n");
        String[] adminLines = AppLogger.INSTANCE.getAdminLog().split("\n");

        assertTrue("UserLogCap100", userLines.length == 100,
            "User log contains exactly 100 entries (got: " + userLines.length + ")");
        assertTrue("AdminLogCap100", adminLines.length == 100,
            "Admin log contains exactly 100 entries (got: " + adminLines.length + ")");

        // FIFO verification
        assertTrue("UserLogFifoEviction",
            !AppLogger.INSTANCE.getUserLog().contains("FloodUser_0") &&
            AppLogger.INSTANCE.getUserLog().contains("FloodUser_149"),
            "Oldest entries evicted and newest entries preserved in user log");

        // Link test formatting verification
        AppLogger.INSTANCE.clear();
        AppLogger.INSTANCE.logLinkTest("Node-DE-1", 45L, true);
        AppLogger.INSTANCE.logLinkTest("Node-FI-2", -1L, false);

        String userLog = AppLogger.INSTANCE.getUserLog();
        String adminLog = AppLogger.INSTANCE.getAdminLog();

        assertTrue("LinkTestSuccessFormat",
            userLog.contains("⚡ [Node-DE-1] — پینگ: 45ms (موفق)"),
            "Success format matches concise specification with lightning emoji and ms");
        assertTrue("LinkTestFailureFormat",
            userLog.contains("⚠️ [Node-FI-2] — پینگ: تایم‌اوت (ناموفق)"),
            "Failure format matches concise specification with warning emoji and timeout");
        assertTrue("LinkTestAdminParity",
            adminLog.contains("⚡ [Node-DE-1] — پینگ: 45ms (موفق)") &&
            adminLog.contains("⚠️ [Node-FI-2] — پینگ: تایم‌اوت (ناموفق)"),
            "Admin log mirrors concise link test formatting without raw config dump");
    }

    /**
     * Requirement: Static inspection of MainActivity.kt and VpnRepository.kt source code
     * to ensure no hidden or residual unmasked PIN or URL leakage points exist.
     */
    private static void testSourceCodeStaticAudit() throws IOException {
        System.out.println("\n--- SUITE 6: Source Code Static Security Audit ---");

        File repoFile = new File("android/app/src/main/java/com/asanvpn/app/data/VpnRepository.kt");
        if (!repoFile.exists()) {
            repoFile = new File("src/main/java/com/asanvpn/app/data/VpnRepository.kt");
        }
        if (!repoFile.exists()) {
            repoFile = new File("app/src/main/java/com/asanvpn/app/data/VpnRepository.kt");
        }

        File mainFile = new File("android/app/src/main/java/com/asanvpn/app/MainActivity.kt");
        if (!mainFile.exists()) {
            mainFile = new File("src/main/java/com/asanvpn/app/MainActivity.kt");
        }
        if (!mainFile.exists()) {
            mainFile = new File("app/src/main/java/com/asanvpn/app/MainActivity.kt");
        }

        assertTrue("RepoFileExists", repoFile.exists(), "VpnRepository.kt exists");
        assertTrue("MainFileExists", mainFile.exists(), "MainActivity.kt exists");

        String repoCode = new String(Files.readAllBytes(repoFile.toPath()), StandardCharsets.UTF_8);
        String mainCode = new String(Files.readAllBytes(mainFile.toPath()), StandardCharsets.UTF_8);

        // 1. Audit MainActivity.kt: Toggle connect must not log cleartext pin
        Pattern rawPinPatternMain = Pattern.compile("logAdmin\\(.*\\$pin[^a-zA-Z0-9_]");
        Matcher m1 = rawPinPatternMain.matcher(mainCode);
        boolean hasRawPinMain = m1.find();
        assertTrue("MainNoRawPinLog", !hasRawPinMain,
            "MainActivity.kt does NOT contain 'logAdmin(...$pin...)' logging raw PIN");

        assertTrue("MainUsesMaskedPin",
            mainCode.contains("val maskedPin = if (pin.length > 2) \"***${pin.takeLast(2)}\" else \"***\"") &&
            mainCode.contains("AppLogger.logAdmin(\"Toggle connect initiated for PIN: $maskedPin\")"),
            "MainActivity.kt properly initializes maskedPin and logs only $maskedPin");

        // 2. Audit VpnRepository.kt: check_lockout must not log $baseUrl without masking
        assertTrue("RepoCheckLockoutMasksBaseUrl",
            repoCode.contains("val maskedBaseUrl = baseUrl.replace(Regex(\"://[^/]+\"), \"://***\")") &&
            repoCode.contains("AppLogger.logAdmin(\"check_lockout error for $maskedBaseUrl: ${e.javaClass.simpleName}\")"),
            "VpnRepository.kt check_lockout logs maskedBaseUrl and simpleName only");

        Pattern rawBaseUrlInCheckLockout = Pattern.compile("check_lockout error for \\$baseUrl");
        Matcher m2 = rawBaseUrlInCheckLockout.matcher(repoCode);
        assertTrue("RepoNoRawBaseUrlInCheckLockout", !m2.find(),
            "VpnRepository.kt does NOT contain 'check_lockout error for $baseUrl'");

        // 3. Audit VpnRepository.kt: syncWithWorker catch block must sanitize IPv4 in e.message
        assertTrue("RepoSyncWorkerSanitizesIpv4",
            repoCode.contains("e.message?.replace(Regex(\"[0-9]+\\\\.[0-9]+\\\\.[0-9]+\\\\.[0-9]+\"), \"***\")"),
            "VpnRepository.kt syncWithWorker catch block masks IPv4 addresses in e.message");

        // 4. Audit VpnRepository.kt: sync initiated must use maskedPin with threshold
        assertTrue("RepoSyncInitiatedMasksPin",
            repoCode.contains("val maskedPin = if (pin.length > 2) \"***${pin.takeLast(2)}\" else \"***\"") &&
            repoCode.contains("val maskedDev = if (deviceId.length > 4) \"***${deviceId.takeLast(4)}\" else \"***\"") &&
            repoCode.contains("AppLogger.logAdmin(\"Sync initiated for PIN: $maskedPin (Device: $maskedDev)\")"),
            "VpnRepository.kt sync initiated masks PIN (threshold > 2) and deviceId (threshold > 4)");
    }
}
