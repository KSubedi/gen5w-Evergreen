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
    public static final String VERSION="v113";
    private static long window=-1, sequence, flagCheckedAt=-1;
    private static int count, criticalCount, chatterCount, dropped, criticalDropped;
    private static boolean flagged;
    static boolean enabled=true;
    static String flagPath=ArHudIpc.ROOT+"/ar-trace.enabled"; // null: no file gate (host tests)
    private static boolean watching;
    static String procRoot="/proc"; // null: no health record (host tests point it at a fixture)
    private static final int TOP_THREADS=6, MAX_THREADS=400, MAX_INTERFACES=6;
    private static long healthAt=-1, cpuTotal, cpuIdle, cpuWait;
    private static java.util.HashMap<String,long[]> threadTicks=new java.util.HashMap<String,long[]>();
    private static java.util.HashMap<String,long[]> interfaceBytes=new java.util.HashMap<String,long[]>();
    private static java.util.HashMap<String,long[]> radioCounts=new java.util.HashMap<String,long[]>();
    private static long[] tcpCounts;
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
    /** One record every five seconds while tracing: how busy the unit and this process's threads
     * were and how many bytes each network interface moved. The owner saw projection freeze on
     * the 110 drive while our records show guidance arriving throughout; this tells a silent
     * link from a stalled thread or a starved CPU the next time. Counters, thread and interface names
     * only: no addresses and no payload. Called from the worker thread alone.
     */
    public static void health(long now) {
        if(procRoot==null||!on())return;
        try { String text=sample(now);if(text!=null)event(text); }
        catch(RuntimeException unavailable){ /* diagnostics only */ }
    }
    private static String sample(long now) {
        long elapsed=healthAt<0||now<=healthAt?-1:now-healthAt;
        healthAt=now;
        StringBuilder text=new StringBuilder("health dt=").append(elapsed);
        // "cpu  user nice system idle iowait irq softirq steal": all cores together.
        String stat=read(procRoot+"/stat",256);
        long total=0,idle=0,wait=0;
        if(stat!=null&&stat.startsWith("cpu ")) {
            int end=stat.indexOf('\n');
            String[] fields=(end<0?stat:stat.substring(0,end)).trim().split(" +");
            for(int i=1;i<fields.length&&i<=8;i++){long value=number(fields[i]);total+=value;if(i==4)idle=value;if(i==5)wait=value;}
        }
        long span=total-cpuTotal;
        text.append(" busy=").append(span>0?100*(span-(idle-cpuIdle)-(wait-cpuWait))/span:-1)
                .append(" wait=").append(span>0?100*(wait-cpuWait)/span:-1);
        cpuTotal=total;cpuIdle=idle;cpuWait=wait;
        text.append(" load=").append(token(read(procRoot+"/loadavg",64),0));
        String memory=read(procRoot+"/meminfo",256);
        int free=memory==null?-1:memory.indexOf("MemFree:");
        text.append(" free_kb=").append(free<0?"-":token(memory.substring(free+8),0));
        // Per thread: "tid (name) state ... utime stime" in clock ticks.
        String[] tids=new java.io.File(procRoot+"/self/task").list();
        java.util.HashMap<String,long[]> seen=new java.util.HashMap<String,long[]>();
        String[] names=new String[TOP_THREADS];long[] used=new long[TOP_THREADS];
        long process=0;int threads=0;
        for(int i=0;tids!=null&&i<tids.length&&i<MAX_THREADS;i++) {
            String line=read(procRoot+"/self/task/"+tids[i]+"/stat",512);
            int open=line==null?-1:line.indexOf('('),close=line==null?-1:line.lastIndexOf(')');
            if(open<0||close<open)continue;
            String[] fields=line.substring(close+1).trim().split(" +");
            if(fields.length<13)continue;
            long ticks=number(fields[11])+number(fields[12]);
            threads++;
            long[] before=threadTicks.get(tids[i]);
            seen.put(tids[i],new long[]{ticks});
            long delta=before==null?ticks:ticks-before[0];
            if(delta<=0)continue;
            process+=delta;
            String name=safe(line.substring(open+1,close));
            for(int slot=0;slot<TOP_THREADS;slot++) {
                if(delta<=used[slot])continue;
                long movedTicks=used[slot];String movedName=names[slot];
                used[slot]=delta;names[slot]=name;delta=movedTicks;name=movedName;
            }
        }
        threadTicks=seen;
        text.append(" threads=").append(threads).append(" proc=").append(process).append(" top=");
        for(int slot=0;slot<TOP_THREADS&&used[slot]>0;slot++)text.append(slot>0?",":"").append(names[slot]).append(':').append(used[slot]);
        if(used[0]<=0)text.append('-');
        // "  wlan0: rx_bytes packets errs drop fifo frame compressed multicast tx_bytes ..."
        String net=read(procRoot+"/net/dev",4096);
        String[] lines=net==null?new String[0]:net.split("\n");
        int shown=0;
        text.append(" net=");
        for(int i=0;i<lines.length&&shown<MAX_INTERFACES;i++) {
            int colon=lines[i].indexOf(':');
            if(colon<0)continue;
            String name=safe(lines[i].substring(0,colon).trim());
            String[] fields=lines[i].substring(colon+1).trim().split(" +");
            if(name.equals("lo")||fields.length<9)continue;
            long rx=number(fields[0]),tx=number(fields[8]);
            long[] before=interfaceBytes.get(name);
            interfaceBytes.put(name,new long[]{rx,tx});
            if(rx==0&&tx==0)continue;
            text.append(shown++>0?",":"").append(name).append(':').append(before==null?rx:rx-before[0]).append('/').append(before==null?tx:tx-before[1]);
        }
        if(shown==0)text.append('-');
        // "wlan0: status link level noise nwid crypt frag retry misc missed": the radio's quality
        // and the kernel's discard counters (111 drive: the picture stopped while the rest of the
        // session continued; this tells a lossy link from a phone that sends nothing).
        String wireless=read(procRoot+"/net/wireless",1024);
        String[] radios=wireless==null?new String[0]:wireless.split("\n");
        int listed=0;
        text.append(" wifi=");
        for(int i=0;i<radios.length&&listed<MAX_INTERFACES;i++) {
            int colon=radios[i].indexOf(':');
            if(colon<0||radios[i].indexOf('|')>=0)continue;
            String name=safe(radios[i].substring(0,colon).trim());
            String[] fields=radios[i].substring(colon+1).trim().split(" +");
            if(fields.length<10)continue;
            long retry=number(fields[7]),misc=number(fields[8]),missed=number(fields[9]);
            long[] before=radioCounts.get(name);
            radioCounts.put(name,new long[]{retry,misc,missed});
            text.append(listed++>0?",":"").append(name).append(':').append(signed(fields[1])).append('/').append(signed(fields[2]))
                    .append('/').append(signed(fields[3])).append('/').append(before==null?retry:retry-before[0])
                    .append('/').append(before==null?misc:misc-before[1]).append('/').append(before==null?missed:missed-before[2]);
        }
        if(listed==0)text.append('-');
        // System-wide TCP segments in, out and retransmitted: a stalled picture stream with
        // retransmissions climbing is a link that loses packets.
        String snmp=read(procRoot+"/net/snmp",4096);
        int header=snmp==null?-1:snmp.indexOf("Tcp:"),values=header<0?-1:snmp.indexOf("Tcp:",header+4);
        text.append(" tcp=");
        long[] tcp=null;
        if(values>=0) {
            int headerEnd=snmp.indexOf('\n',header),valuesEnd=snmp.indexOf('\n',values);
            String[] labels=snmp.substring(header+4,headerEnd<0?snmp.length():headerEnd).trim().split(" +");
            String[] counts=snmp.substring(values+4,valuesEnd<0?snmp.length():valuesEnd).trim().split(" +");
            tcp=new long[3];
            for(int i=0;i<labels.length&&i<counts.length;i++) {
                if(labels[i].equals("InSegs"))tcp[0]=number(counts[i]);
                else if(labels[i].equals("OutSegs"))tcp[1]=number(counts[i]);
                else if(labels[i].equals("RetransSegs"))tcp[2]=number(counts[i]);
            }
        }
        if(tcp==null)text.append('-');
        else {
            long[] before=tcpCounts;
            text.append(before==null?tcp[0]:tcp[0]-before[0]).append('/').append(before==null?tcp[1]:tcp[1]-before[1]).append('/').append(before==null?tcp[2]:tcp[2]-before[2]);
        }
        tcpCounts=tcp;
        // The first sample only sets the baselines: its counters are totals since boot.
        return elapsed<0?null:text.toString();
    }
    private static String read(String path,int limit) {
        java.io.FileInputStream in=null;
        try {
            in=new java.io.FileInputStream(path);
            byte[] data=new byte[limit];int size=0,got;
            while(size<limit&&(got=in.read(data,size,limit-size))>0)size+=got;
            return new String(data,0,size,"ISO-8859-1");
        } catch(java.io.IOException unavailable){return null;}
        finally { if(in!=null)try{in.close();}catch(java.io.IOException ignored){ /* read-only */ } }
    }
    /** Whitespace-separated token of a /proc line, kept only if it is a plain number. */
    private static String token(String text,int index) {
        if(text==null)return "-";
        String[] fields=text.trim().split("\\s+");
        if(index>=fields.length||fields[index].length()==0||fields[index].length()>12)return "-";
        for(int i=0;i<fields[index].length();i++){char c=fields[index].charAt(i);if((c<'0'||c>'9')&&c!='.')return "-";}
        return fields[index];
    }
    /** A /proc number that may be negative and may carry iwstats' trailing dot ("-40."); "-" otherwise. */
    private static String signed(String text) {
        if(text.endsWith("."))text=text.substring(0,text.length()-1);
        int start=text.startsWith("-")?1:0;
        if(text.length()==start||text.length()>12)return "-";
        for(int i=start;i<text.length();i++)if(text.charAt(i)<'0'||text.charAt(i)>'9')return "-";
        return text;
    }
    private static long number(String text) {
        long value=0;
        for(int i=0;i<text.length()&&i<18;i++){char c=text.charAt(i);if(c<'0'||c>'9')return 0;value=value*10+(c-'0');}
        return value;
    }
    private static String name(Thread thread) { return safe(thread==null?"-":thread.getName()); }
    private static String safe(String name) {
        // Thread and interface names come from the OEM system; keep only harmless characters.
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
                message.startsWith("clear_") || message.startsWith("timer_expired") || message.startsWith("health") ||
                message.startsWith("screen") || message.startsWith("recover") || message.startsWith("guidance_restart");
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
