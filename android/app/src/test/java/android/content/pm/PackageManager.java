package android.content.pm;

public class PackageManager {
    public static class NameNotFoundException extends Exception {
        public NameNotFoundException() {
            super();
        }

        public NameNotFoundException(String name) {
            super(name);
        }
    }
}
