// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;
import android.os.SystemClock;
import android.util.Log;
import com.hkmc.cluster.NavigationCluster;

/** Numeric navigation trace only. Root logcat service handles bounded persistence.
 * No route names, coordinates, raw phone messages, identities, or strings from OEM fields.
 * The trace runs only while the package's ar-trace.enabled file exists (checked every
 * five seconds), so a general release does not log at navigation cadence.
 */
public final class ArHudDebug implements Thread.UncaughtExceptionHandler {
    public static final String VERSION="v110";
    private static long window=-1, sequence, flagCheckedAt=-1;
    private static int count, criticalCount, chatterCount, dropped, criticalDropped;
    private static boolean flagged;
    static boolean enabled=true;
    static String flagPath=ArHudIpc.ROOT+"/ar-trace.enabled"; // null: no file gate (host tests)
    private static boolean watching;
    private final Thread.UncaughtExceptionHandler previous;
    private ArHudDebug(Thread.UncaughtExceptionHandler previous) { this.previous=previous; }

    /** Record why this process died, then hand over to the handler that was there before.
     * The recorder keeps only our tag, so a crash of the projection app was invisible in
     * the exported logs. Class and method names only: never an exception message.
     */
    public static synchronized void watch() {
        if(watching)return;
        watching=true;
        try { Thread.setDefaultUncaughtExceptionHandler(new ArHudDebug(Thread.getDefaultUncaughtExceptionHandler())); }
        catch(RuntimeException unavailable){ /* diagnostics only */ }
    }
    public void uncaughtException(Thread thread,Throwable failure) {
        try {
            StringBuilder text=new StringBuilder(VERSION).append(" uncaught thread=").append(name(thread));
            int depth=0;
            for(Throwable t=failure;t!=null&&depth<4;t=t.getCause(),depth++) {
                text.append(depth==0?" exception=":" cause=").append(t.getClass().getName());
                frames(text,t.getStackTrace(),depth==0?12:6);
            }
            Log.w("EvergreenAR",text.toString(),null);
        } catch(Throwable unavailable){ /* never mask the original failure */ }
        if(previous!=null)previous.uncaughtException(thread,failure);
        else failure.printStackTrace(); // No platform handler (host tests): keep the JVM's own report.
    }
    /** One record of where another thread is: state and top frames, no values. */
    public static void thread(String label,Thread thread) {
        if(thread==null||!on())return;
        try {
            StringBuilder text=new StringBuilder(VERSION).append(' ').append(label).append(" thread=").append(name(thread))
                    .append(" state=").append(thread.getState());
            frames(text,thread.getStackTrace(),10);
            Log.w("EvergreenAR",text.toString(),null);
        } catch(RuntimeException unavailable){ /* diagnostics only */ }
    }
    private static String name(Thread thread) {
        // Thread names come from the OEM app; keep only harmless characters.
        String name=thread==null?"-":thread.getName();
        StringBuilder safe=new StringBuilder();
        for(int i=0;name!=null&&i<name.length()&&i<40;i++) {
            char c=name.charAt(i);
            safe.append(c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||c=='-'||c=='_'||c=='.'?c:'_');
        }
        return safe.length()==0?"-":safe.toString();
    }
    private static void frames(StringBuilder text,StackTraceElement[] stack,int limit) {
        text.append(" frames=");
        if(stack==null||stack.length==0){text.append('-');return;}
        for(int i=0;i<stack.length&&i<limit;i++) {
            if(i>0)text.append('<');
            text.append(stack[i].getClassName()).append('.').append(stack[i].getMethodName()).append(':').append(stack[i].getLineNumber());
        }
    }
    public static synchronized void event(String message) {
        if(!enabled)return;
        long now=SystemClock.elapsedRealtime();
        if(!traceAllowed(now))return;
        if(window<0||now-window>=1000){window=now;count=0;criticalCount=0;chatterCount=0;}
        boolean critical=message.startsWith("icon_gate") || message.startsWith("maneuver_replaced") ||
                message.startsWith("hud_list") || message.startsWith("hud_blank") || message.startsWith("reset") ||
                message.startsWith("sender_prepare") || message.startsWith("tuning") || message.startsWith("rerouting") ||
                message.startsWith("clear_") || message.startsWith("timer_expired");
        // Routine 4 Hz chatter gets its own small budget so it cannot crowd out transitions.
        boolean chatter=message.startsWith("ipc_") || message.startsWith("worker_alive") || message.startsWith("hud_distance");
        if(critical ? criticalCount++>=100 : chatter ? chatterCount++>=20 : count++>=50){dropped++;if(critical)criticalDropped++;return;}
        Log.w("EvergreenAR",VERSION+" t="+now+" seq="+(++sequence)+" dropped="+dropped+" "+message+
                " critical_dropped="+criticalDropped,null);
    }
    /** Host tests: look at the flag file again and start a fresh rate-limit window. */
    /** Cheap check for hot call sites, so a release build does not build trace strings it drops. */
    public static synchronized boolean on() { return enabled && traceAllowed(SystemClock.elapsedRealtime()); }
    static synchronized void recheck() { flagCheckedAt=-1; window=-1; }
    static boolean traceAllowed(long now) {
        if(flagPath==null)return true;
        if(flagCheckedAt<0||now<flagCheckedAt||now-flagCheckedAt>=5000) {
            flagCheckedAt=now;
            try { flagged=new java.io.File(flagPath).isFile(); }
            catch(RuntimeException unavailable){flagged=false;}
        }
        return flagged;
    }
    public static void hud(NavigationCluster c,byte[] data) {
        // Never print the trailing road-name bytes.
        event("hud_list bytes="+(data==null?-1:data.length)+" count="+(data!=null&&data.length>1?(data[1]&255):-1)+
                " icon="+(data!=null&&data.length>2?(data[2]&255):-1)+" next="+(data!=null&&data.length>3?(data[3]&255):-1));
        try { c.updateTPTurnListNew(data);event("hud_list_sent");ArHudRuntime.hudSent(); }
        catch(RuntimeException failure){event("hud_list_failed");throw failure;}
    }
}
