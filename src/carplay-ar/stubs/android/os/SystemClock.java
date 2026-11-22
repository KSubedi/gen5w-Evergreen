package android.os;
/** Compile/test only. Android supplies the real monotonic clock. */
public final class SystemClock {
    /** Host tests: a fixed time in milliseconds; -1 (default) uses the host's clock. */
    public static long now = -1;
    public static long elapsedRealtime() { return now >= 0 ? now : System.nanoTime() / 1000000L; }
}
