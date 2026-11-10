package android.util;

public class Log {
    public static int d(String tag, String msg) {
        System.out.println("[Log.d] [" + tag + "] " + msg);
        return 0;
    }
    public static int i(String tag, String msg) {
        System.out.println("[Log.i] [" + tag + "] " + msg);
        return 0;
    }
    public static int w(String tag, String msg) {
        System.out.println("[Log.w] [" + tag + "] " + msg);
        return 0;
    }
    public static int w(String tag, String msg, Throwable tr) {
        System.out.println("[Log.w] [" + tag + "] " + msg + " (Exception: " + tr.getMessage() + ")");
        return 0;
    }
    public static int e(String tag, String msg) {
        System.err.println("[Log.e] [" + tag + "] " + msg);
        return 0;
    }
    public static int e(String tag, String msg, Throwable tr) {
        System.err.println("[Log.e] [" + tag + "] " + msg + " (Exception: " + tr.getMessage() + ")");
        return 0;
    }
}
