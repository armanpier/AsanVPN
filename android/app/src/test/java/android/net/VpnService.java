package android.net;

import android.content.pm.PackageManager;
import java.util.HashSet;
import java.util.Set;

public class VpnService {

    public class Builder {
        private final Set<String> installedPackages = new HashSet<>();
        private final Set<String> disallowedPackages = new HashSet<>();
        private boolean throwOnEveryPackage = false;
        private String specificFailurePackage = null;
        private RuntimeException customRuntimeException = null;

        public Builder() {}

        public void setSimulatedInstalledPackages(Set<String> packages) {
            this.installedPackages.clear();
            if (packages != null) {
                this.installedPackages.addAll(packages);
            }
        }

        public void setThrowOnEveryPackage(boolean throwAll) {
            this.throwOnEveryPackage = throwAll;
        }

        public void setSpecificFailure(String pkg, RuntimeException ex) {
            this.specificFailurePackage = pkg;
            this.customRuntimeException = ex;
        }

        public Builder addDisallowedApplication(String packageName) throws PackageManager.NameNotFoundException {
            if (specificFailurePackage != null && specificFailurePackage.equals(packageName)) {
                if (customRuntimeException != null) {
                    throw customRuntimeException;
                }
            }
            if (throwOnEveryPackage || !installedPackages.contains(packageName)) {
                throw new PackageManager.NameNotFoundException("Package not installed: " + packageName);
            }
            disallowedPackages.add(packageName);
            return this;
        }

        public Set<String> getDisallowedPackages() {
            return disallowedPackages;
        }
    }
}
