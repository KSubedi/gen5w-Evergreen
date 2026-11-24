/* SPDX-License-Identifier: GPL-3.0-or-later
 * Ultimate Wireless Projection: starts CarPlay Ultra-Lite (ioniq6.cpclimate) inside DiPO.
 *
 * Our DiPO build (tools/build-carplay-ultra.py) calls, in com.daudio.app.dipo.telechips.CarPlayService:
 *   onCreate()                     -> Ultra.onCreate(this) before each return
 *   onSessionStarted(int)          -> Ultra.started(this, type) at the start, Ultra.startedDone() before each return
 *   onSessionFinalized(int)        -> Ultra.finalized(this, type) at the start, Ultra.finalizedDone() before each return
 * HMG-API calls CarPlay Ultra-Lite's appLoaded once per DiPO process; onCreate does the same here
 * (a second CarPlayService.onCreate in the same process is ignored). Nothing here may throw into DiPO.
 *
 * The library side: libCarPlayCore.so is patched on disk, but our gate in front of CarPlay
 * Ultra-Lite's code (src/carplay-ultra/native/gate.S) only lets it act while DiPO's files/cpc-on
 * exists. It is deleted here at every start and written only once CarPlay Ultra-Lite has started
 * in this process (its appLoaded returned and wrote cpc-dock, which it skips when its own crash
 * guard trips). So, as upstream with its memory patch, the library is stock whenever the Java
 * side isn't running.
 *
 * Off switch without reinstalling: "enabled=false" in DiPO's files/uwp-carplay-ultra.conf, then
 * restart the head unit.
 */
package org.uwp.carplayultra;

import android.app.Service;
import android.content.Context;
import hmgapi.hook.Hooks;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.ModContext;
import ioniq6.cpclimate.CpClimate;
import java.io.File;

public final class Ultra {
    static final String DIPO = "com.daudio.app.dipo";
    static final String STARTED = "dipo.carplay.session_started", FINALIZED = "dipo.carplay.session_finalized";
    /** read by gate.S; written by CarPlay Ultra-Lite's CpClimate.writeDock when it starts */
    static final String ON = "cpc-on", DOCK = "cpc-dock";
    private static boolean loaded;

    private Ultra() {
    }

    public static void onCreate(Service service) {
        synchronized (Ultra.class) {
            if (loaded) return;
            loaded = true;
        }
        ModContext mod = null;
        try {
            Context app = service.getApplicationContext();
            File dir = service.getFilesDir();
            if (dir == null) dir = new File("/data/data/" + DIPO + "/files");
            File on = new File(dir, ON), dock = new File(dir, DOCK);
            on.delete();
            dock.delete();
            mod = new ModContext("cp-climate", "1.3.3", dir);
            if (!mod.settingBoolean("enabled", true)) {
                mod.log("CarPlay Ultra-Lite is off (enabled=false in uwp-carplay-ultra.conf)");
                return;
            }
            Hooks.setMod(mod);
            mod.log("Ultimate Wireless Projection: starting CarPlay Ultra-Lite 1.3.3 in " + processName(service));
            new CpClimate().appLoaded(mod, new LoadedApp(DIPO, processName(service), Ultra.class.getClassLoader(),
                    app != null ? app : service));
            if (!dock.isFile()) {
                mod.log("CarPlay Ultra-Lite didn't start this time (crash guard?): libCarPlayCore.so stays stock");
            } else if (on.createNewFile() || on.isFile()) {
                mod.log("libCarPlayCore.so additions on (" + on + ")");
            } else {
                mod.log("could not write " + on + ": libCarPlayCore.so stays stock");
            }
        } catch (Throwable t) {
            if (mod != null) mod.error("CarPlay Ultra-Lite did not start", t);
            else android.util.Log.e(ModContext.TAG, "CarPlay Ultra-Lite did not start", t);
        }
    }

    private static String processName(Service s) {
        try {
            int pid = android.os.Process.myPid();
            android.app.ActivityManager am = (android.app.ActivityManager) s.getSystemService(Context.ACTIVITY_SERVICE);
            for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                if (p.pid == pid) return p.processName;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return DIPO;
    }

    public static void started(Object carPlayService, int transport) {
        try {
            Hooks.before(STARTED, carPlayService, transport);
        } catch (Throwable ignored) {
            // never into DiPO
        }
    }

    public static void startedDone() {
        try {
            Hooks.after(STARTED);
        } catch (Throwable ignored) {
            // never into DiPO
        }
    }

    public static void finalized(Object carPlayService, int transport) {
        try {
            Hooks.before(FINALIZED, carPlayService, transport);
        } catch (Throwable ignored) {
            // never into DiPO
        }
    }

    public static void finalizedDone() {
        try {
            Hooks.after(FINALIZED);
        } catch (Throwable ignored) {
            // never into DiPO
        }
    }
}
