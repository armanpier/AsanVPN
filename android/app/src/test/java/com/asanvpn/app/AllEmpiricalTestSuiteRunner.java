package com.asanvpn.app;

import com.asanvpn.app.util.AppLoggerStressTest;
import org.junit.Test;

public class AllEmpiricalTestSuiteRunner {

    @Test
    public void runAllEmpiricalTestSuites() throws Exception {
        main(new String[0]);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("   ASANVPN ALL EMPIRICAL & ADVERSARIAL TEST SUITES RUNNER                       ");
        System.out.println("================================================================================");

        int failures = 0;

        System.out.println("\n>>> [1/5] Running AppLoggerEmpiricalTest...");
        try {
            AppLoggerEmpiricalTest.main(new String[0]);
        } catch (Throwable t) {
            t.printStackTrace();
            failures++;
        }

        System.out.println("\n>>> [2/5] Running AppLoggerStressTest...");
        try {
            AppLoggerStressTest.main(new String[0]);
        } catch (Throwable t) {
            t.printStackTrace();
            failures++;
        }

        System.out.println("\n>>> [3/5] Running M1RemediationAdversarialTest...");
        try {
            M1RemediationAdversarialTest.main(new String[0]);
        } catch (Throwable t) {
            t.printStackTrace();
            failures++;
        }

        System.out.println("\n>>> [4/5] Running M2RoutingAndBypassEmpiricalTest...");
        try {
            M2RoutingAndBypassEmpiricalTest.main(new String[0]);
        } catch (Throwable t) {
            t.printStackTrace();
            failures++;
        }

        System.out.println("\n>>> [5/5] Running GeoAssetManagerAdversarialTest...");
        try {
            GeoAssetManagerAdversarialTest.main(new String[0]);
        } catch (Throwable t) {
            t.printStackTrace();
            failures++;
        }

        System.out.println("\n================================================================================");
        if (failures > 0) {
            System.err.println("ALL EMPIRICAL TESTS FAILED: " + failures + " suites failed");
            throw new AssertionError(failures + " test suites failed");
        } else {
            System.out.println("ALL EMPIRICAL TESTS PASSED SUCCESSFULLY (5/5 SUITES)!");
        }
        System.out.println("================================================================================");
    }
}
