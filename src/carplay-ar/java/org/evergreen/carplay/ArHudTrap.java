// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

import android.os.SystemClock;
import com.hkmc.cluster.NavigationCluster;
import com.hkmc.cluster.NavigationCluster.NaviTbtTrapInfo;

/** Navigation-app-side overlay. Only barGraphLevel changes, in that app's own
 * most recent OEM frame. No speed-limit echo, hardcoded unit or invented limit.
 * The firmware verified legacy bar argument is #12 (zero-based array index 11).
 */
public final class ArHudTrap implements Runnable {
    public static final int BAR_INDEX = 11;
    private static NavigationCluster cluster;
    private static int[] originalInts;
    private static NaviTbtTrapInfo originalNew;
    private static int applied = -1;
    private static boolean running;
    static boolean background = true; // Host tests drive tick() deterministically.

    private ArHudTrap() {}

    private static void start() {
        if (running || !background) return;
        Thread t = new Thread(new ArHudTrap(), "Evergreen-AR-progress");
        t.setDaemon(true); t.start(); running = true;
    }

    public static NaviTbtTrapInfo copy(NaviTbtTrapInfo a) {
        if (a == null) return null;
        NaviTbtTrapInfo b = new NaviTbtTrapInfo();
        b.fixedSpeed=a.fixedSpeed; b.mobileSpeed=a.mobileSpeed; b.overSpeedAlarm=a.overSpeedAlarm;
        b.redlineSpeed=a.redlineSpeed; b.nonSpeed=a.nonSpeed; b.warningZone=a.warningZone;
        b.curveWarning=a.curveWarning; b.mergeWarning=a.mergeWarning; b.tollGateCurrency=a.tollGateCurrency;
        b.tollGate=a.tollGate; b.displayTG=a.displayTG; b.barGraphLevel=a.barGraphLevel;
        b.speedLimit=a.speedLimit; b.busSpeed=a.busSpeed; b.speedLimitType=a.speedLimitType;
        b.speedLimitUnit=a.speedLimitUnit; b.speedInfoType=a.speedInfoType; b.warningZone2=a.warningZone2;
        b.warningZone3=a.warningZone3; b.warningZone4=a.warningZone4; b.displayCountry=a.displayCountry;
        b.cameraSafetyAlert=a.cameraSafetyAlert;
        return b;
    }

    public static synchronized void updateNew(NavigationCluster receiver, NaviTbtTrapInfo original) {
        if (original == null) { receiver.updateTBTTrapInfoNew(null); return; }
        if (ArHudDebug.on()) ArHudDebug.event("trap_input modern limit="+original.speedLimit+" unit="+original.speedLimitUnit+" bar="+original.barGraphLevel);
        cluster=receiver; originalNew=copy(original); originalInts=null;
        emit(ArHudIpc.read(SystemClock.elapsedRealtime())); start();
    }

    public static synchronized void update(NavigationCluster receiver, int a0,int a1,int a2,int a3,
            int a4,int a5,int a6,int a7,int a8,int a9,int a10,int a11,int a12,int a13,int a14,int a15,int a16) {
        if (ArHudDebug.on()) ArHudDebug.event("trap_input legacy limit="+a12+" unit="+a15+" bar="+a11);
        cluster=receiver; originalNew=null;
        originalInts=new int[] {a0,a1,a2,a3,a4,a5,a6,a7,a8,a9,a10,a11,a12,a13,a14,a15,a16};
        emit(ArHudIpc.read(SystemClock.elapsedRealtime())); start();
    }

    private static void emit(int bar) {
        if (originalNew != null) {
            NaviTbtTrapInfo frame=copy(originalNew);
            if (bar >= 0) frame.barGraphLevel=bar;
            cluster.updateTBTTrapInfoNew(frame);
        } else if (originalInts != null) {
            int[] a=originalInts.clone(); if(bar>=0)a[BAR_INDEX]=bar;
            cluster.updateTBTTrapInfo(a[0],a[1],a[2],a[3],a[4],a[5],a[6],a[7],a[8],a[9],a[10],a[11],a[12],a[13],a[14],a[15],a[16]);
        }
        if (ArHudDebug.on()) ArHudDebug.event("trap_sent overlay="+bar+" modern="+(originalNew!=null));
        applied=bar;
    }

    static synchronized void tick(int bar) {
        if (cluster != null && bar != applied) emit(bar);
    }

    public void run() {
        while (true) {
            try { Thread.sleep(250); }
            catch (InterruptedException stopped) { synchronized(ArHudTrap.class){running=false;tick(-1);} return; }
            try { tick(ArHudIpc.read(SystemClock.elapsedRealtime())); }
            catch (RuntimeException unavailable) { /* Next OEM update still remains authoritative. */ }
        }
    }
}
