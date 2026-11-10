package com.asanvpn.app;

import com.asanvpn.app.data.models.VpnNode;
import com.asanvpn.app.service.v2ray.V2RayConfigBuilder;
import com.asanvpn.app.util.IranAppBypassHelper;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.VpnService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Empirical Adversarial Test Suite for Milestone 2:
 * 1. Verification of V2RayConfigBuilder.buildConfig routing rules and domainStrategy
 * 2. Verification of IranAppBypassHelper.applyDisallowedApplications & IRANIAN_PACKAGES catalog
 * 3. Adversarial stress tests (malformed nodes, empty strings, missing packages, security exceptions)
 */
public class M2RoutingAndBypassEmpiricalTest {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    private static void assertTrue(String testName, boolean condition, String details) {
        if (condition) {
            System.out.println("[PASS] " + testName + ": " + details);
            testsPassed++;
        } else {
            System.err.println("[FAIL] " + testName + ": " + details);
            testsFailed++;
        }
    }

    private static void assertEquals(String testName, Object expected, Object actual, String details) {
        if (Objects.equals(expected, actual)) {
            System.out.println("[PASS] " + testName + ": " + details + " (expected: " + expected + ", got: " + actual + ")");
            testsPassed++;
        } else {
            System.err.println("[FAIL] " + testName + ": " + details + " (expected: " + expected + ", got: " + actual + ")");
            testsFailed++;
        }
    }

    @org.junit.Test
    public void testM2RoutingAndBypass() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        System.out.println("===================================================================");
        System.out.println("  STARTING M2 ROUTING & APP BYPASS EMPIRICAL ADVERSARIAL TEST      ");
        System.out.println("===================================================================");

        // Section 1: V2RayConfigBuilder Routing Rules & DomainStrategy
        testV2RayConfigBuilderStandard();
        testV2RayConfigBuilderAcrossProtocols();
        testV2RayConfigBuilderAdversarialNodes();

        // Section 2: IranAppBypassHelper IRANIAN_PACKAGES Catalog Integrity
        testIranianPackagesCatalog();

        // Section 3: IranAppBypassHelper.applyDisallowedApplications Simulation
        testApplyDisallowedApplicationsCleanCatch();
        testApplyDisallowedApplicationsPartialInstall();
        testApplyDisallowedApplicationsFullInstall();
        testApplyDisallowedApplicationsUnexpectedExceptionResilience();

        System.out.println("===================================================================");
        System.out.printf("  M2 EMPIRICAL TEST RESULTS: %d PASSED, %d FAILED%n", testsPassed, testsFailed);
        System.out.println("===================================================================");

        if (testsFailed > 0) {
            throw new AssertionError(testsFailed + " tests failed");
        }
    }

    // =========================================================================
    // SECTION 1: V2RayConfigBuilder Routing Tests
    // =========================================================================

    private static void testV2RayConfigBuilderStandard() {
        System.out.println("\n--- TEST 1: Standard V2RayConfigBuilder Routing Rules Verification ---");

        VpnNode node = new VpnNode(
                "vless", "🇳🇴 Norway Fast", "185.190.140.10", 443,
                "a1b2c3d4-e5f6-7890-abcd-ef1234567890", null, "tcp", "reality",
                "speedtest.net", null, "xtls-rprx-vision", "pubKey123", "short1",
                null, "chrome", null, null, null, null, -1L, null, null, 1
        );

        String configJson = V2RayConfigBuilder.INSTANCE.buildConfig(node);
        assertTrue("ConfigJsonNotEmpty", configJson != null && !configJson.trim().isEmpty(), "Generated JSON must not be empty");

        JsonObject root = JsonParser.parseString(configJson).getAsJsonObject();
        assertTrue("HasRoutingSection", root.has("routing"), "Root JSON must contain 'routing' object");

        JsonObject routing = root.getAsJsonObject("routing");

        // 1. Check domainStrategy
        String domainStrategy = routing.has("domainStrategy") ? routing.get("domainStrategy").getAsString() : null;
        assertEquals("DomainStrategyExactMatch", "IPIfNonMatch", domainStrategy, "routing.domainStrategy must strictly equal 'IPIfNonMatch'");

        // 2. Check rules array
        assertTrue("HasRulesArray", routing.has("rules") && routing.get("rules").isJsonArray(), "Routing must contain 'rules' array");
        JsonArray rules = routing.getAsJsonArray("rules");
        assertTrue("RulesCountAtLeast4", rules.size() >= 4, "Rules must contain at least 4 rules (dns, iran-domain, iran-ip, proxy-fallback)");

        // Rule 0: DNS hijack rule
        JsonObject rule0 = rules.get(0).getAsJsonObject();
        assertEquals("Rule0Type", "field", rule0.get("type").getAsString(), "Rule 0 type");
        assertEquals("Rule0OutboundTag", "dns-out", rule0.get("outboundTag").getAsString(), "Rule 0 outboundTag");
        assertEquals("Rule0Port", "53", rule0.get("port").getAsString(), "Rule 0 port");

        // Rule 1: Iranian domains direct
        JsonObject rule1 = rules.get(1).getAsJsonObject();
        assertEquals("Rule1Type", "field", rule1.get("type").getAsString(), "Rule 1 type");
        assertEquals("Rule1OutboundTag", "direct", rule1.get("outboundTag").getAsString(), "Rule 1 outboundTag");
        assertTrue("Rule1HasDomain", rule1.has("domain") && rule1.get("domain").isJsonArray(), "Rule 1 must have 'domain' array");

        JsonArray domainArr = rule1.getAsJsonArray("domain");
        List<String> domains = new ArrayList<>();
        for (JsonElement el : domainArr) {
            domains.add(el.getAsString());
        }

        assertEquals("Rule1DomainCount", 2, domains.size(), "Rule 1 domain list must have exactly 2 entries");
        assertTrue("Rule1ContainsGeositeCategoryIr", domains.contains("geosite:category-ir"), "Rule 1 contains 'geosite:category-ir'");
        assertTrue("Rule1ContainsDomainIr", domains.contains("domain:ir"), "Rule 1 contains 'domain:ir'");

        // Strict exact equality of array contents
        List<String> expectedDomains = Arrays.asList("geosite:category-ir", "domain:ir");
        assertEquals("Rule1ExactDomainList", expectedDomains, domains, "Rule 1 exact domain array sequence");

        // Rule 2: Iranian IPs & Private direct
        JsonObject rule2 = rules.get(2).getAsJsonObject();
        assertEquals("Rule2Type", "field", rule2.get("type").getAsString(), "Rule 2 type");
        assertEquals("Rule2OutboundTag", "direct", rule2.get("outboundTag").getAsString(), "Rule 2 outboundTag");
        assertTrue("Rule2HasIp", rule2.has("ip") && rule2.get("ip").isJsonArray(), "Rule 2 must have 'ip' array");

        JsonArray ipArr = rule2.getAsJsonArray("ip");
        List<String> ips = new ArrayList<>();
        for (JsonElement el : ipArr) {
            ips.add(el.getAsString());
        }

        assertEquals("Rule2IpCount", 2, ips.size(), "Rule 2 ip list must have exactly 2 entries");
        assertTrue("Rule2ContainsGeoipPrivate", ips.contains("geoip:private"), "Rule 2 contains 'geoip:private'");
        assertTrue("Rule2ContainsGeoipIr", ips.contains("geoip:ir"), "Rule 2 contains 'geoip:ir'");

        // Strict exact equality of array contents
        List<String> expectedIps = Arrays.asList("geoip:private", "geoip:ir");
        assertEquals("Rule2ExactIpList", expectedIps, ips, "Rule 2 exact ip array sequence");

        // Rule 3: General fallback to proxy
        JsonObject rule3 = rules.get(3).getAsJsonObject();
        assertEquals("Rule3Type", "field", rule3.get("type").getAsString(), "Rule 3 type");
        assertEquals("Rule3OutboundTag", "proxy", rule3.get("outboundTag").getAsString(), "Rule 3 outboundTag");
        assertEquals("Rule3Network", "tcp,udp", rule3.get("network").getAsString(), "Rule 3 network");

        // Verify direct outbound definition has domainStrategy UseIP
        JsonArray outbounds = root.getAsJsonArray("outbounds");
        JsonObject directOutbound = null;
        for (JsonElement ob : outbounds) {
            if ("direct".equals(ob.getAsJsonObject().get("tag").getAsString())) {
                directOutbound = ob.getAsJsonObject();
                break;
            }
        }
        assertTrue("DirectOutboundExists", directOutbound != null, "Direct outbound must exist");
        if (directOutbound != null) {
            assertEquals("DirectProtocol", "freedom", directOutbound.get("protocol").getAsString(), "Direct protocol");
            JsonObject settings = directOutbound.getAsJsonObject("settings");
            assertEquals("DirectSettingsDomainStrategy", "UseIP", settings.get("domainStrategy").getAsString(), "Direct settings domainStrategy");
        }
    }

    private static void testV2RayConfigBuilderAcrossProtocols() {
        System.out.println("\n--- TEST 2: V2RayConfigBuilder Routing Consistency Across Protocols ---");

        List<VpnNode> protocolNodes = Arrays.asList(
                // VMess node
                new VpnNode("vmess", "VMess Test", "1.2.3.4", 8080, "uuid-123", null, "ws", "none",
                        "test.com", "/ws", null, null, null, null, null, "test.com", null, null, null, -1L, null, null, 1),
                // Trojan node
                new VpnNode("trojan", "Trojan Test", "5.6.7.8", 443, null, "pass123", "tcp", "tls",
                        "trojan.com", null, null, null, null, null, null, null, null, null, null, -1L, null, null, 1),
                // Shadowsocks node
                new VpnNode("shadowsocks", "SS Test", "9.10.11.12", 8388, null, "sspass", "tcp", null,
                        null, null, null, null, null, null, null, null, null, null, "chacha20-ietf-poly1305", -1L, null, null, 1),
                // VLESS gRPC
                new VpnNode("vless", "VLESS gRPC", "13.14.15.16", 443, "uuid-grpc", null, "grpc", "reality",
                        "speedtest.net", "grpc-service", null, "pubkey-grpc", "sid-grpc", null, "chrome", null, "grpc-service", null, null, -1L, null, null, 1)
        );

        for (VpnNode node : protocolNodes) {
            String configJson = V2RayConfigBuilder.INSTANCE.buildConfig(node);
            JsonObject root = JsonParser.parseString(configJson).getAsJsonObject();
            JsonObject routing = root.getAsJsonObject("routing");
            JsonArray rules = routing.getAsJsonArray("rules");

            String domainStrategy = routing.get("domainStrategy").getAsString();
            assertEquals("Proto_" + node.getProtocol() + "_DomainStrategy", "IPIfNonMatch", domainStrategy,
                    "domainStrategy invariant for protocol: " + node.getProtocol());

            JsonObject rule1 = rules.get(1).getAsJsonObject();
            assertEquals("Proto_" + node.getProtocol() + "_Rule1Outbound", "direct", rule1.get("outboundTag").getAsString(),
                    "Rule 1 outboundTag invariant for protocol: " + node.getProtocol());

            JsonArray domains = rule1.getAsJsonArray("domain");
            assertTrue("Proto_" + node.getProtocol() + "_Rule1Entries",
                    domains.size() == 2 &&
                            "geosite:category-ir".equals(domains.get(0).getAsString()) &&
                            "domain:ir".equals(domains.get(1).getAsString()),
                    "Rule 1 domains invariant for protocol: " + node.getProtocol());

            JsonObject rule2 = rules.get(2).getAsJsonObject();
            assertEquals("Proto_" + node.getProtocol() + "_Rule2Outbound", "direct", rule2.get("outboundTag").getAsString(),
                    "Rule 2 outboundTag invariant for protocol: " + node.getProtocol());

            JsonArray ips = rule2.getAsJsonArray("ip");
            assertTrue("Proto_" + node.getProtocol() + "_Rule2Entries",
                    ips.size() == 2 &&
                            "geoip:private".equals(ips.get(0).getAsString()) &&
                            "geoip:ir".equals(ips.get(1).getAsString()),
                    "Rule 2 ips invariant for protocol: " + node.getProtocol());
        }
    }

    private static void testV2RayConfigBuilderAdversarialNodes() {
        System.out.println("\n--- TEST 3: V2RayConfigBuilder Adversarial Edge Cases ---");

        // Edge Case 1: All optional fields null or blank
        VpnNode minimalNode = new VpnNode(
                "vless", "", "0.0.0.0", 0, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, -1L, null, null, null
        );

        try {
            String configJson = V2RayConfigBuilder.INSTANCE.buildConfig(minimalNode);
            JsonObject root = JsonParser.parseString(configJson).getAsJsonObject();
            JsonObject routing = root.getAsJsonObject("routing");
            assertEquals("MinimalNodeDomainStrategy", "IPIfNonMatch", routing.get("domainStrategy").getAsString(),
                    "Minimal node generates valid IPIfNonMatch");
            assertTrue("MinimalNodeRoutingRules", routing.getAsJsonArray("rules").size() >= 4,
                    "Minimal node generates complete routing rules");
        } catch (Exception e) {
            assertTrue("MinimalNodeException", false, "Minimal node threw unexpected exception: " + e.getMessage());
        }

        // Edge Case 2: Extreme values in remark and server
        VpnNode extremeNode = new VpnNode(
                "vless", "🚀".repeat(50) + "🇮🇷 ایران <script>alert(1)</script>", "255.255.255.255", 65535,
                "00000000-0000-0000-0000-000000000000", null, "ws", "tls",
                "attacker.example.com", "/".repeat(100), null, null, null, null, null, null, null, null, null, -1L, null, null, 1
        );

        try {
            String configJson = V2RayConfigBuilder.INSTANCE.buildConfig(extremeNode);
            JsonObject root = JsonParser.parseString(configJson).getAsJsonObject();
            JsonObject routing = root.getAsJsonObject("routing");
            assertEquals("ExtremeNodeDomainStrategy", "IPIfNonMatch", routing.get("domainStrategy").getAsString(),
                    "Extreme node preserves IPIfNonMatch");
        } catch (Exception e) {
            assertTrue("ExtremeNodeException", false, "Extreme node threw unexpected exception: " + e.getMessage());
        }
    }

    // =========================================================================
    // SECTION 2: IRANIAN_PACKAGES Catalog Tests
    // =========================================================================

    private static void testIranianPackagesCatalog() {
        System.out.println("\n--- TEST 4: IRANIAN_PACKAGES Catalog Integrity Verification ---");

        List<String> packages = IranAppBypassHelper.INSTANCE.getIRANIAN_PACKAGES();
        assertTrue("CatalogNotEmpty", packages != null && !packages.isEmpty(), "Catalog must not be null or empty");

        int count = packages.size();
        System.out.println("Total Iranian packages cataloged: " + count);
        assertTrue("CatalogSizeAdequate", count >= 50, "Catalog should cover at least 50 domestic applications (got " + count + ")");

        // Check required packages specified in DISPATCH and ORIGINAL_REQUEST
        List<String> mandatoryRequired = Arrays.asList(
                "app.nobitex",                   // Nobitex crypto
                "com.sibche.aspardproject.app", // Up / Asan Pardakht
                "com.farsitel.bazaar"           // Cafe Bazaar
        );

        for (String req : mandatoryRequired) {
            assertTrue("ContainsMandatory_" + req, packages.contains(req), "Must contain mandatory required package: " + req);
        }

        // Check additional high-profile Iranian domestic apps
        List<String> additionalChecks = Arrays.asList(
                "cab.snapp.passenger",          // Snapp ride
                "taxi.tap30.passenger",         // Tapsi ride
                "ir.divar",                     // Divar classifieds
                "com.digikala.app",             // Digikala marketplace
                "com.torob",                    // Torob price comparison
                "ir.ble.messenger",             // Bale messenger
                "ir.eitaa.messenger",           // Eitaa messenger
                "ir.resaneh1.iptv",             // Rubika messenger/platform
                "com.sb24.blubank",             // BluBank
                "ir.bankmellat.mobile",         // Bank Mellat
                "com.bmi.mobilebank",           // Bank Melli
                "ir.mci.ecareapp",              // Hamrah Aval
                "ir.mtn.irancell"               // Irancell
        );

        for (String app : additionalChecks) {
            assertTrue("ContainsDomestic_" + app, packages.contains(app), "Catalog should contain domestic package: " + app);
        }

        // Validate package naming convention and absence of duplicates
        Pattern packagePattern = Pattern.compile("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$");
        Set<String> uniqueSet = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        List<String> invalidFormat = new ArrayList<>();

        for (String pkg : packages) {
            if (pkg == null || pkg.trim().isEmpty() || !packagePattern.matcher(pkg).matches()) {
                invalidFormat.add(pkg);
            }
            if (!uniqueSet.add(pkg)) {
                duplicates.add(pkg);
            }
        }

        assertTrue("NoInvalidPackageFormats", invalidFormat.isEmpty(),
                "All package names must be valid Android package identifiers (invalid: " + invalidFormat + ")");
        assertTrue("NoDuplicatePackages", duplicates.isEmpty(),
                "Catalog must not contain duplicate package identifiers (duplicates: " + duplicates + ")");
    }

    // =========================================================================
    // SECTION 3: applyDisallowedApplications Simulation & Catch Verification
    // =========================================================================

    private static class DummyContext extends Context {
        @Override
        public String getPackageName() {
            return "com.asanvpn.app";
        }
    }

    private static void testApplyDisallowedApplicationsCleanCatch() {
        System.out.println("\n--- TEST 5: applyDisallowedApplications Clean NameNotFoundException Catch ---");

        // Scenario: Device where NONE of the Iranian packages are installed
        // Builder will throw NameNotFoundException for every single package
        VpnService vpnService = new VpnService();
        VpnService.Builder builder = vpnService.new Builder();
        builder.setThrowOnEveryPackage(true);

        Context context = new DummyContext();

        try {
            int bypassedCount = IranAppBypassHelper.INSTANCE.applyDisallowedApplications(builder, context);
            assertEquals("CleanCatchZeroInstalled", 0, bypassedCount,
                    "When no packages installed, return count must be 0");
            assertEquals("NoDisallowedAdded", 0, builder.getDisallowedPackages().size(),
                    "Disallowed packages set must remain empty");
            assertTrue("CleanCatchNoThrow", true,
                    "PackageManager.NameNotFoundException was cleanly caught for all missing packages without throwing");
        } catch (Exception e) {
            assertTrue("UnexpectedExceptionCleanCatch", false,
                    "applyDisallowedApplications threw unexpected exception: " + e);
        }
    }

    private static void testApplyDisallowedApplicationsPartialInstall() {
        System.out.println("\n--- TEST 6: applyDisallowedApplications Partial Installation ---");

        // Scenario: User has exactly 3 Iranian apps installed: Nobitex, Up, Cafe Bazaar
        Set<String> installed = new HashSet<>(Arrays.asList(
                "app.nobitex",
                "com.sibche.aspardproject.app",
                "com.farsitel.bazaar"
        ));

        VpnService vpnService = new VpnService();
        VpnService.Builder builder = vpnService.new Builder();
        builder.setSimulatedInstalledPackages(installed);

        Context context = new DummyContext();

        try {
            int bypassedCount = IranAppBypassHelper.INSTANCE.applyDisallowedApplications(builder, context);
            assertEquals("PartialInstallCount", 3, bypassedCount,
                    "Should successfully bypass exactly 3 installed packages");
            assertEquals("DisallowedSizeMatches", 3, builder.getDisallowedPackages().size(),
                    "Disallowed set size must equal 3");
            assertTrue("ContainsNobitex", builder.getDisallowedPackages().contains("app.nobitex"),
                    "Disallowed set contains app.nobitex");
            assertTrue("ContainsUp", builder.getDisallowedPackages().contains("com.sibche.aspardproject.app"),
                    "Disallowed set contains com.sibche.aspardproject.app");
            assertTrue("ContainsBazaar", builder.getDisallowedPackages().contains("com.farsitel.bazaar"),
                    "Disallowed set contains com.farsitel.bazaar");
        } catch (Exception e) {
            assertTrue("PartialInstallUnexpectedException", false,
                    "applyDisallowedApplications threw unexpected exception: " + e);
        }
    }

    private static void testApplyDisallowedApplicationsFullInstall() {
        System.out.println("\n--- TEST 7: applyDisallowedApplications Full Installation ---");

        // Scenario: Device has ALL Iranian packages installed
        List<String> allPackages = IranAppBypassHelper.INSTANCE.getIRANIAN_PACKAGES();
        Set<String> allInstalled = new HashSet<>(allPackages);

        VpnService vpnService = new VpnService();
        VpnService.Builder builder = vpnService.new Builder();
        builder.setSimulatedInstalledPackages(allInstalled);

        Context context = new DummyContext();

        try {
            int bypassedCount = IranAppBypassHelper.INSTANCE.applyDisallowedApplications(builder, context);
            assertEquals("FullInstallCount", allPackages.size(), bypassedCount,
                    "Should successfully bypass all " + allPackages.size() + " packages");
            assertEquals("FullDisallowedSize", allPackages.size(), builder.getDisallowedPackages().size(),
                    "Disallowed set size must equal total package count");
        } catch (Exception e) {
            assertTrue("FullInstallUnexpectedException", false,
                    "applyDisallowedApplications threw unexpected exception: " + e);
        }
    }

    private static void testApplyDisallowedApplicationsUnexpectedExceptionResilience() {
        System.out.println("\n--- TEST 8: applyDisallowedApplications Resilience to Unexpected Exceptions ---");

        // Scenario: One package triggers a SecurityException or IllegalArgumentException
        // The helper must catch generic Exception, log warning, and continue to next packages
        Set<String> installed = new HashSet<>(Arrays.asList(
                "app.nobitex",
                "com.sibche.aspardproject.app",
                "com.farsitel.bazaar"
        ));

        VpnService vpnService = new VpnService();
        VpnService.Builder builder = vpnService.new Builder();
        builder.setSimulatedInstalledPackages(installed);
        builder.setSpecificFailure("com.sibche.aspardproject.app", new SecurityException("Permission denied for testing"));

        Context context = new DummyContext();

        try {
            int bypassedCount = IranAppBypassHelper.INSTANCE.applyDisallowedApplications(builder, context);
            // 2 packages should succeed (nobitex and bazaar), 1 package failed with SecurityException
            assertEquals("ResilienceCount", 2, bypassedCount,
                    "Should bypass 2 packages when 1 package triggers SecurityException");
            assertTrue("NobitexStillAdded", builder.getDisallowedPackages().contains("app.nobitex"),
                    "Nobitex successfully added");
            assertTrue("BazaarStillAdded", builder.getDisallowedPackages().contains("com.farsitel.bazaar"),
                    "Bazaar successfully added after faulted package");
            assertTrue("FailedPackageNotAdded", !builder.getDisallowedPackages().contains("com.sibche.aspardproject.app"),
                    "Faulted package omitted from disallowed set");
        } catch (Exception e) {
            assertTrue("ResilienceUnexpectedThrow", false,
                    "applyDisallowedApplications allowed unexpected exception to escape: " + e);
        }
    }
}
