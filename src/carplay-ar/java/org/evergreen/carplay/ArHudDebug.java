// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;
import android.os.SystemClock;
import android.util.Log;
import com.hkmc.cluster.NavigationCluster;

/** Numeric navigation trace only. Root logcat service handles bounded persistence.
 * No route names, coordinates, raw phone messages, identities, or strings from OEM fields.
 */
public final class ArHudDebug {
    private static long window=-1, sequence;
    private static int count, criticalCount, dropped, criticalDropped;
    static boolean enabled=true;
    private ArHudDebug() {}
    public static synchronized void event(String message) {
        if(!enabled)return;
        long now=SystemClock.elapsedRealtime();
        if(window<0||now-window>=1000){window=now;count=0;criticalCount=0;}
        boolean critical=message.startsWith("icon_gate") || message.startsWith("maneuver_replaced") ||
                message.startsWith("hud_") || message.startsWith("reset") || message.startsWith("sender_prepare");
        if(critical ? criticalCount++>=100 : count++>=50){dropped++;if(critical)criticalDropped++;return;}
        Log.w("EvergreenAR","v105 t="+now+" seq="+(++sequence)+" dropped="+dropped+" "+message+
                " critical_dropped="+criticalDropped,null);
    }
    public static void hud(NavigationCluster c,byte[] data) {
        // Never print the trailing road-name bytes.
        event("hud_list bytes="+(data==null?-1:data.length)+" count="+(data!=null&&data.length>1?(data[1]&255):-1)+
                " icon="+(data!=null&&data.length>2?(data[2]&255):-1)+" next="+(data!=null&&data.length>3?(data[3]&255):-1));
        try { c.updateTPTurnListNew(data);event("hud_list_sent"); }
        catch(RuntimeException failure){event("hud_list_failed");throw failure;}
    }
}
