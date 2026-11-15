package android.os;
/** Compile/test only. Android supplies the real monotonic clock. */
public final class SystemClock {
    public static long elapsedRealtime() { return System.nanoTime() / 1000000L; }
}
