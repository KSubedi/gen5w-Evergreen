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
        System.out.println("AR debug: "+checks+" checks passed");
    }
}
