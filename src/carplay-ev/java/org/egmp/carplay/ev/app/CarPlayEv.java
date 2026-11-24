package org.egmp.carplay.ev.app;
// SPDX-License-Identifier: GPL-3.0-or-later
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import com.tcc.iap2.IIap2Listener;
import com.tcc.iap2.IIap2Service;
import com.tcc.iap2.Iap2ListenerInfo;
import com.tcc.iap2.Iap2NotiMsg;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;

/**
 * Apple Maps EV routing over CarPlay, inside the head unit's iAP2 app (Iap2ServerApp).
 *
 * Ultimate Wireless Projection's replacement for egmp-carplay-ev-routing's EvRouting and
 * AppProvider (by Isaac Wisdom), which run as an HMG-API mod: the same steps, called from
 * three calls patched into the app (tools/build-carplay-ev.py) instead of HMG-API hooks:
 *   Iap2Service.onCreate (end)        start(): identity, the iAP2 listener, the sender
 *   Iap2Service.onDestroy (start)     stop()
 *   Iap2InitInformationMsg.checkSupportsFeatures (end)
 *                                     arm the identity if it wasn't yet, and turn on the
 *                                     vehicle information and status groups (HMG-API
 *                                     Wireless's enableGroups)
 * The vehicle identity (electric, its name, CCS and J1772 with their power) is built by
 * libcarplayev.so's identity part (from Open-HMG/hmgapi-wireless-cp-aa) through the gate
 * patched into libiap2session.so; the live data (battery, range, charging and consumption
 * contracts) by egmp-carplay-ev-routing's native bridge, every 10 s while the iPhone is
 * subscribed. Anything that goes wrong is logged and leaves CarPlay as it was: no call
 * here throws into the app.
 */
public final class CarPlayEv {
    static final String TAG = "CarPlayEV";
    /** Also logged under our AR recorder's tag, so the drive logs show it. */
    static final String REC = "EvergreenAR";
    static final String LIB = "/system/lib/libcarplayev.so";
    static final String SESSION = "/system/lib/libiap2session.so";
    /** An empty file here (control kit carplay-ev-off) turns it off at the next start. */
    static final String OFF = "/data/nemesis/apps/wireless.carplay.aa/carplay-ev.disabled";
    /**
     * CarPlay Ultra-Lite's theme (test build, src/carplay-ultra): part 3_bridge writes the theme asset
     * id ("id:version:sdk") here only when the CarPlay Ultra-Lite build of DiPO is installed. It goes
     * into the wireless transport group (24) of the identification, with the empty parameter 5 iOS
     * needs beside it (identity.c). No file: the identification is the same as without it.
     */
    static final String THEME = "/data/nemesis/apps/wireless.carplay.aa/carplay-ultra.theme";
    static final int ENGINE_ELECTRIC = 2, CONNECTOR_CCS = 0, CONNECTOR_AC = 2;
    static final long PERIOD_MS = 10000;

    private static Service service;
    private static VehicleSource source;
    private static boolean loaded, armed, groupsLogged;
    private static IIap2Service binder;
    private static final Subscription subscription = new Subscription();
    private static Thread worker;
    private static long workerToken;
    private static int lastRc = Integer.MIN_VALUE;
    private static long lastRecorded;

    private CarPlayEv() {}

    private static final IIap2Listener listener = new IIap2Listener.Stub() {
        public void connected(int component, String serial) { subscription.connected(component); }
        public void disconnected(int component) { if (subscription.disconnected(component)) release(); }
        public void notify(int what, int a, int b, Iap2NotiMsg msg) {
            if (what == 15) begin();
            else if (what == 16) { subscription.stop(); release(); }
        }
    };

    // ---- the three patched calls -------------------------------------------------

    public static void onCreate(Service s) {
        try { start(s); } catch (Throwable t) { error("start failed; CarPlay continues without EV routing", t); }
    }

    public static void onDestroy() {
        try { stop(); } catch (Throwable t) { error("stop", t); }
    }

    public static void afterCheckSupportsFeatures() {
        try {
            if (service == null) return;   // off, unsupported, or not started yet
            if (!armed) arm();
            if (armed) enableGroups();
        } catch (Throwable t) { error("identification groups", t); }
    }

    // ---- start and stop ----------------------------------------------------------

    private static synchronized void start(Service s) throws Exception {
        if (service != null) return;
        if (new File(OFF).exists()) { log("off (carplay-ev.disabled)"); return; }
        String md5 = md5(SESSION);
        if (!Generated.SESSION_MD5.equals(md5)) {
            log("inactive: libiap2session.so md5=" + md5 + " is not this build's patched library");
            return;
        }
        String product = prop("ro.build.product");
        String profile = profileFor(product);
        if (profile == null) { log("inactive: no EV profile for " + product); return; }
        int nominal = nominalWh();
        if (nominal > 0 && nominal != 77400) {
            log("inactive: the profiles are for the 77.4 kWh battery; this car reports " + nominal + " Wh");
            return;
        }
        if (!loaded) { NativeBridge.load(LIB); loaded = true; }
        source = new VehicleSource(s, profile);
        service = s;
        arm();
        if (armed) enableGroups();
        int opened = NativeBridge.open();
        if (opened != 0) throw new IllegalStateException("native open=" + opened);
        binder = IIap2Service.Stub.asInterface((IBinder) s.onBind(new Intent()));
        Iap2ListenerInfo info = new Iap2ListenerInfo();
        info.onSetListenerName("CarPlayEV");
        info.onSetSupportType(0x100);
        if (!binder.Iap2SvcInit(listener, info)) throw new IllegalStateException("listener refused");
        log("started: " + source.displayName + ", identity " + (armed ? "armed" : "not armed yet"));
    }

    /** The vehicle identity: electric, its name, one DC (CCS) and one AC (J1772) connector. */
    private static synchronized void arm() throws Exception {
        if (armed || source == null) return;
        String theme = theme();
        int rc = NativeIdentity.configure(source.displayName.getBytes("UTF-8"), ENGINE_ELECTRIC,
                CONNECTOR_CCS, source.dcW, CONNECTOR_AC, source.acW, theme.getBytes("UTF-8"));
        if (rc != 0 && theme.length() > 0) {   // the theme must never cost the EV identity
            log("CarPlay theme " + theme + " not accepted (native=" + rc + "); identity without it");
            theme = "";
            rc = NativeIdentity.configure(source.displayName.getBytes("UTF-8"), ENGINE_ELECTRIC,
                    CONNECTOR_CCS, source.dcW, CONNECTOR_AC, source.acW, new byte[0]);
        }
        if (rc == 0) {
            armed = true;
            log("identity armed: electric, CCS " + source.dcW + " W, J1772 " + source.acW + " W"
                    + (theme.length() > 0 ? ", CarPlay theme " + theme : ""));
            // with a theme (CarPlay Ultra-Lite installed): answer the phone's CarPlayAvailability with
            // CarPlayStartSession, without which the phone keeps the car in plain CarPlay
            if (theme.length() > 0 && service != null) StartSession.start(service, theme);
        }
        else log("identity not armed: native=" + rc);
    }

    /** The CarPlay Ultra-Lite theme id from THEME, or "" (no file, or not a plain printable id). */
    static String theme() {
        File f = new File(THEME);
        if (!f.isFile() || f.length() > 96) return "";
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[(int) f.length()];
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
                String t = new String(b, 0, n, "US-ASCII").trim();
                for (int i = 0; i < t.length(); i++) if (t.charAt(i) < 0x21 || t.charAt(i) > 0x7e) return "";
                return t;
            } finally {
                in.close();
            }
        } catch (Exception e) {
            log("CarPlay theme file not read: " + e);
            return "";
        }
    }

    private static void enableGroups() throws Exception {
        Class<?> c = Class.forName("com.tcc.iap2server.Iap2InitInformationMsg");
        for (String name : new String[] {"iAP2UsedVehicelInformation", "iAP2UsedVehicleStatus"}) {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(null, 1);
        }
        if (!groupsLogged) { groupsLogged = true; log("vehicle information and status groups on"); }
    }

    private static synchronized void stop() {
        if (service == null) return;
        subscription.close();
        if (worker != null) worker.interrupt();
        release();
        try { synchronized (nativeLock()) { NativeBridge.shutdown(); } } catch (Throwable t) { error("native shutdown", t); }
        if (binder != null) try { binder.Iap2SvcStopMain(listener); } catch (Throwable t) { error("listener unregister", t); }
        if (armed) try { NativeIdentity.shutdown(); } catch (Throwable t) { error("identity shutdown", t); }
        armed = false; binder = null; service = null;
        log("stopped");
    }

    // ---- the sender (egmp-carplay-ev-routing's AppProvider.begin) ---------------------

    private static synchronized void begin() {
        final long token = subscription.start();
        if (token == 0) return;
        if (worker != null && worker.isAlive() && workerToken == token) return;
        if (worker != null) worker.interrupt();
        workerToken = token;
        worker = new Thread(new Runnable() { public void run() {
            try {
                while (subscription.current(token)) {
                    try {
                        VehicleSource.Snapshot snapshot = source.poll();
                        synchronized (nativeLock()) {
                            if (!subscription.current(token)) return;
                            int rc = NativeBridge.send(nativeHandle(), snapshot.values, snapshot.charging, snapshot.consumption);
                            sent(rc, snapshot);
                        }
                    } catch (Throwable t) { error("send (will retry while subscribed)", t); }
                    Thread.sleep(PERIOD_MS);
                }
            } catch (InterruptedException ended) { Thread.currentThread().interrupt(); }
        }}, "CarPlayEV");
        worker.setDaemon(true);
        worker.start();
        log("Apple Maps subscribed to vehicle status");
    }

    private static void sent(int rc, VehicleSource.Snapshot s) {
        String line = "send=" + rc + " soc_e3=" + s.values[3] + " range_km=" + s.values[4] + " current_wh=" + s.values[2]
                + " full_range_km=" + (s.values[8] == 1 ? String.valueOf(s.values[6]) : "omitted")
                + " auxiliary_w=" + s.auxiliaryW + " (" + s.auxiliarySource + ") live=" + s.values[7];
        Log.i(TAG, line);
        long now = System.currentTimeMillis();
        if (rc != lastRc || now - lastRecorded >= 60000) { lastRc = rc; lastRecorded = now; Log.w(REC, "cpev " + line); }
    }

    private static void release() {
        try { synchronized (nativeLock()) { NativeBridge.reset(); } } catch (Throwable t) { error("release", t); }
    }

    // ---- the app's own native handle and lock (Iap2Service.mHandleIap2 / mLock) --------

    private static Field serviceField(String name) throws Exception {
        Field f = Class.forName("com.tcc.iap2server.Iap2Service").getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
    static Object nativeLock() throws Exception { return serviceField("mLock").get(null); }
    static long nativeHandle() throws Exception { return serviceField("mHandleIap2").getLong(null); }

    // ---- the car: profile and battery ---------------------------------------------

    /** The bundled profile for this product id (Generated.PROFILES: {prefix, json}), or null. */
    static String profileFor(String product) {
        if (product == null) return null;
        for (String[] p : Generated.PROFILES) if (product.startsWith(p[0])) return p[1];
        return null;
    }

    /** Nominal pack energy in Wh (HEV getHEVStatus 0x35 BMS_EVENERGYCAPA, 2 Wh steps), or -1. */
    static int nominalWh() {
        try {
            Class<?> pc = Class.forName("android.os.Parcel"), ib = Class.forName("android.os.IBinder");
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                    .invoke(null, "com.hkmc.micom.hev");
            if (binder == null) return -1;
            Object data = pc.getMethod("obtain").invoke(null), reply = pc.getMethod("obtain").invoke(null);
            try {
                pc.getMethod("writeInterfaceToken", String.class).invoke(data, "com.hkmc.hev.IHEVService");
                pc.getMethod("writeInt", int.class).invoke(data, 0x35);
                if (!Boolean.TRUE.equals(ib.getMethod("transact", int.class, pc, pc, int.class).invoke(binder, 3, data, reply, 0)))
                    return -1;
                pc.getMethod("readException").invoke(reply);
                int raw = ((Integer) pc.getMethod("readInt").invoke(reply)).intValue();
                return raw > 0 && raw < 0xFFFF ? raw * 2 : -1;
            } finally { pc.getMethod("recycle").invoke(data); pc.getMethod("recycle").invoke(reply); }
        } catch (Throwable unavailable) { return -1; }
    }

    static String prop(String name) {
        try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name); }
        catch (Throwable unavailable) { return null; }
    }

    // ---- helpers --------------------------------------------------------------------

    static String md5(String path) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            FileInputStream in = new FileInputStream(path);
            try { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) d.update(b, 0, n); }
            finally { in.close(); }
            StringBuilder s = new StringBuilder();
            for (byte x : d.digest()) s.append(Integer.toHexString((x & 0xff) | 0x100).substring(1));
            return s.toString();
        } catch (Exception e) { return "unavailable"; }
    }

    static void log(String m) { Log.i(TAG, m); Log.w(REC, "cpev " + m); }

    static void error(String m, Throwable t) { Log.e(TAG, m, t); Log.w(REC, "cpev error " + m + ": " + t); }
}
