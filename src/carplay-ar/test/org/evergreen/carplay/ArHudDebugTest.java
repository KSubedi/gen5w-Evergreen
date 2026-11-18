package org.evergreen.carplay;
import android.util.Log;
import com.hkmc.cluster.NavigationCluster;
public final class ArHudDebugTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static final class Cluster extends NavigationCluster {
        byte[] sent;
        public void updateTPTurnListNew(byte[] b){sent=b;}
    }
    static void write(java.io.File file,String text) throws Exception {
        java.io.FileWriter out=new java.io.FileWriter(file);
        try { out.write(text); } finally { out.close(); }
    }
    static void remove(java.io.File file) {
        java.io.File[] children=file.listFiles();
        for(int i=0;children!=null&&i<children.length;i++)remove(children[i]);
        file.delete();
    }
    public static void main(String[] args) throws Exception {
        java.io.File flag=java.io.File.createTempFile("evergreen-ar-","-trace");
        ArHudDebug.flagPath=flag.getPath();ArHudDebug.recheck();
        Cluster c=new Cluster();byte[] payload={-14,1,81,0,0,83,69,67,82,69,84};
        ArHudDebug.hud(c,payload);
        check(c.sent==payload,"diagnostics preserve the exact HUD payload");
        check(Log.last.contains("hud_list_sent"),"API return recorded");
        boolean leaked=false;for(String message:Log.messages)if(message.contains("SECRET"))leaked=true;
        check(!leaked,"no road-name content logged in any record");
        int normalBefore=Log.calls;
        for(int i=0;i<1000;i++)ArHudDebug.event("flood");
        check(Log.calls-normalBefore<=50,"bounded event rate prevents runaway logger volume");
        int priorityBefore=Log.calls;ArHudDebug.event("icon_gate critical fixture");
        check(Log.calls==priorityBefore+1,"critical transition survives normal budget exhaustion");
        for(int i=0;i<1000;i++)ArHudDebug.event("icon_gate flood");
        check(Log.calls-priorityBefore<=100,"critical traffic also stays bounded");
        ArHudDebug.recheck();int chatterBefore=Log.calls;
        for(int i=0;i<200;i++)ArHudDebug.event("ipc_written chatter");
        check(Log.calls-chatterBefore<=20,"routine chatter has its own small budget");
        int routineBefore=Log.calls;ArHudDebug.event("main_sent after chatter");
        check(Log.calls==routineBefore+1,"chatter cannot exhaust the routine budget");
        check(ArHudDebug.on(),"on() true while flagged");
        ArHudDebug.enabled=false;int before=Log.calls;ArHudDebug.event("disabled");
        check(!ArHudDebug.on(),"on() false when disabled");
        check(Log.calls==before,"logger disable gate");
        ArHudDebug.enabled=true;flag.delete();ArHudDebug.recheck();ArHudDebug.event("icon_gate unflagged");
        check(Log.calls==before,"no trace without the ar-trace.enabled file");
        check(Log.last.contains(ArHudDebug.VERSION),"records carry the helper version");
        ArHudDebug.flagPath=null;ArHudDebug.recheck();ArHudDebug.event("ungated");check(Log.calls==before+1,"host tests run without the file gate");
        // Crash and quiet-thread records: names only, and the earlier handler still gets the failure.
        final Throwable[] passed=new Throwable[1];
        Thread.UncaughtExceptionHandler earlier=new Thread.UncaughtExceptionHandler(){
            public void uncaughtException(Thread t,Throwable e){passed[0]=e;}};
        Thread.setDefaultUncaughtExceptionHandler(earlier);
        ArHudDebug.watch();ArHudDebug.watch();
        Thread.UncaughtExceptionHandler installed=Thread.getDefaultUncaughtExceptionHandler();
        check(installed instanceof ArHudDebug,"crash recorder installed once in front of the earlier handler");
        Throwable crash=new IllegalStateException("SECRET road",new java.io.IOException("SECRET cause"));
        Thread odd=new Thread("worker SECRET/7");
        installed.uncaughtException(odd,crash);
        check(passed[0]==crash,"the earlier handler still receives the failure");
        check(Log.last.contains("uncaught thread=worker_SECRET_7")&&Log.last.contains("exception=java.lang.IllegalStateException")
                &&Log.last.contains("cause=java.io.IOException")&&Log.last.contains("ArHudDebugTest.main:"),"crash record names the classes and frames");
        check(!Log.last.contains("SECRET road")&&!Log.last.contains("SECRET cause"),"crash record never carries exception messages");
        before=Log.calls;ArHudDebug.thread("input_quiet",Thread.currentThread());
        check(Log.calls==before+1&&Log.last.contains("input_quiet thread=main state=RUNNABLE frames=")&&Log.last.contains("ArHudDebugTest.main:"),"quiet-thread record");
        ArHudDebug.thread("input_quiet",null);check(Log.calls==before+1,"no thread, no record");
        Thread.setDefaultUncaughtExceptionHandler(null);
        // Health record from a fixture /proc: counters and safe names only, as differences between samples.
        java.io.File proc=java.io.File.createTempFile("evergreen-ar-","-proc");proc.delete();
        new java.io.File(proc,"self/task/11").mkdirs();new java.io.File(proc,"self/task/12").mkdirs();new java.io.File(proc,"net").mkdirs();
        write(new java.io.File(proc,"stat"),"cpu  100 0 100 800 0 0 0 0 0 0\ncpu0 100 0 100 800 0 0 0 0 0 0\n");
        write(new java.io.File(proc,"loadavg"),"1.50 1.20 1.00 2/300 1234\n");
        write(new java.io.File(proc,"meminfo"),"MemTotal:        2000000 kB\nMemFree:          345678 kB\n");
        write(new java.io.File(proc,"self/task/11/stat"),"11 (Dipo Player/1) S 1 1 0 0 -1 4194624 10 0 0 0 30 10 0 0 20 0 5 0 100 0 0\n");
        write(new java.io.File(proc,"self/task/12/stat"),"12 (Binder_1) S 1 1 0 0 -1 4194624 10 0 0 0 5 5 0 0 20 0 5 0 100 0 0\n");
        String header="Inter-|   Receive                |  Transmit\n face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets\n";
        write(new java.io.File(proc,"net/dev"),header+"    lo: 500 5 0 0 0 0 0 0 500 5 0 0 0 0 0 0\n wlan0: 1000 10 0 0 0 0 0 0 200 4 0 0 0 0 0 0\n  p2p0: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n");
        String radio="Inter-| sta-|   Quality        |   Discarded packets               | Missed | WE\n face | tus | link level noise |  nwid  crypt   frag  retry   misc | beacon | 22\n";
        write(new java.io.File(proc,"net/wireless"),radio+" wlan0: 0000   60.  -40.  -256        0      0      0    100      5        2\n");
        write(new java.io.File(proc,"net/snmp"),"Ip: Forwarding DefaultTTL\nIp: 1 64\nTcp: RtoAlgorithm RtoMin RtoMax MaxConn ActiveOpens PassiveOpens AttemptFails EstabResets CurrEstab InSegs OutSegs RetransSegs InErrs OutRsts\nTcp: 1 200 120000 -1 10 20 0 0 3 1000 500 10 0 0\n");
        ArHudDebug.procRoot=proc.getPath();ArHudDebug.recheck();before=Log.calls;
        ArHudDebug.health(100000);check(Log.calls==before,"first health sample only sets the baselines");
        write(new java.io.File(proc,"stat"),"cpu  150 0 150 880 20 0 0 0 0 0\n");
        write(new java.io.File(proc,"self/task/11/stat"),"11 (Dipo Player/1) S 1 1 0 0 -1 4194624 10 0 0 0 60 20 0 0 20 0 5 0 100 0 0\n");
        new java.io.File(proc,"self/task/13").mkdirs();
        write(new java.io.File(proc,"self/task/13/stat"),"13 (new) R 1 1 0 0 -1 4194624 10 0 0 0 3 1 0 0 20 0 5 0 100 0 0\n");
        write(new java.io.File(proc,"net/dev"),header+"    lo: 900 9 0 0 0 0 0 0 900 9 0 0 0 0 0 0\n wlan0: 51000 90 0 0 0 0 0 0 1200 9 0 0 0 0 0 0\n  p2p0: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n");
        write(new java.io.File(proc,"net/wireless"),radio+" wlan0: 0000   55.  -45.  -256        0      0      0    130      5        3\n");
        write(new java.io.File(proc,"net/snmp"),"Ip: Forwarding DefaultTTL\nIp: 1 64\nTcp: RtoAlgorithm RtoMin RtoMax MaxConn ActiveOpens PassiveOpens AttemptFails EstabResets CurrEstab InSegs OutSegs RetransSegs InErrs OutRsts\nTcp: 1 200 120000 -1 10 20 0 0 3 4000 1500 25 0 0\n");
        ArHudDebug.health(105000);
        check(Log.calls==before+1&&Log.last.contains(" health dt=5000 busy=50 wait=10 load=1.50 free_kb=345678 threads=3 proc=44 top=Dipo_Player_1:40,new:4 net=wlan0:50000/1000 wifi=wlan0:55/-45/-256/30/0/1 tcp=3000/1000/15 critical_dropped="),
                "health record: CPU share, load, free memory, busiest threads, bytes per interface, radio quality, TCP counters");
        for(int i=0;i<200;i++)ArHudDebug.event("flood");
        ArHudDebug.health(110000);
        check(Log.last.contains(" health dt=5000 busy=-1 wait=-1 ")&&Log.last.contains(" proc=0 top=- net=wlan0:0/0 wifi=wlan0:55/-45/-256/0/0/0 tcp=0/0/0 "),"an idle interval is recorded as such, and survives a busy second");
        ArHudDebug.procRoot=proc.getPath()+"-missing";before=Log.calls;ArHudDebug.health(115000);
        check(Log.calls==before+1&&Log.last.contains(" load=- free_kb=- threads=0 proc=0 top=- net=- wifi=- tcp=- "),"unreadable /proc gives an empty record, never a failure");
        ArHudDebug.procRoot=null;before=Log.calls;ArHudDebug.health(120000);check(Log.calls==before,"no health record without a /proc root");
        ArHudDebug.procRoot="/proc";remove(proc);
        System.out.println("AR debug: "+checks+" checks passed");
    }
}
