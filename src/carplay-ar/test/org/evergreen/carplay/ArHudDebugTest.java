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
    public static void main(String[] args) {
        Cluster c=new Cluster();byte[] payload={-14,1,81,0,0,83,69,67,82,69,84};
        ArHudDebug.hud(c,payload);
        check(c.sent==payload,"diagnostics preserve the exact HUD payload");
        check(Log.last.contains("hud_list_sent"),"API return recorded");
        boolean leaked=false;for(String message:Log.messages)if(message.contains("SECRET"))leaked=true;
        check(!leaked,"no road-name content logged in any record");
        for(int i=0;i<1000;i++)ArHudDebug.event("flood");
        check(Log.calls<=50,"bounded event rate prevents runaway logger volume");
        ArHudDebug.enabled=false;int before=Log.calls;ArHudDebug.event("disabled");
        check(Log.calls==before,"logger disable gate");
        System.out.println("AR debug: "+checks+" checks passed");
    }
}
