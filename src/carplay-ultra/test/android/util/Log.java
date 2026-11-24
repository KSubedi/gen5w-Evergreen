// Test stand-in for android.util.Log (the JVM has no Android): prints to stdout.
package android.util;

public final class Log {
    private Log() {
    }

    public static int i(String tag, String msg) {
        System.out.println("I/" + tag + ": " + msg);
        return 0;
    }

    public static int e(String tag, String msg, Throwable t) {
        System.out.println("E/" + tag + ": " + msg + (t != null ? " (" + t + ")" : ""));
        return 0;
    }
}
