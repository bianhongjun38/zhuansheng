package android.util;

/** 桌面测试桩：Log 直接打到 stderr，方便发现数据加载异常。 */
public class Log {

    public static int e(String tag, String msg) {
        System.err.println("[E/" + tag + "] " + msg);
        return 0;
    }

    public static int e(String tag, String msg, Throwable t) {
        System.err.println("[E/" + tag + "] " + msg + " :: " + t);
        return 0;
    }

    public static int w(String tag, String msg) {
        System.err.println("[W/" + tag + "] " + msg);
        return 0;
    }

    public static int d(String tag, String msg) { return 0; }
    public static int i(String tag, String msg) { return 0; }
}
