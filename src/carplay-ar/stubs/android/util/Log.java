package android.util;
/** Compile/test only. */
public final class Log {
    public static int calls;
    public static String last;
    public static final java.util.List<String> messages=new java.util.ArrayList<String>();
    public static int w(String tag,String message,Throwable error) { calls++;last=message;messages.add(message);return 0; }
}
