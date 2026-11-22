// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Brings a stalled CarPlay picture or silent phone guidance back without the driver.
 *
 * Owner logs (EV6 US, 1.2.0.103 and 1.2.0.111) show the picture stop while the session
 * and often the guidance continue; DiPO's own screen timeout (no video for 10 s) only
 * sets a flag, and its decode-failure callback only logs. Leaving CarPlay and coming back
 * fixed it. Steps, each at most once per stall and each logged:
 *   1. ask the phone for a key frame (CarPlayService.forceKeyFrame);
 *   2. if the picture has not started again RETAKE_MS later, borrow the screen and give it
 *      back RETURN_MS later, which makes the phone restart its video stream;
 *   3. only with recover=reconnect: if still nothing RECONNECT_MS after step 2, end the
 *      session and advertise CarPlay again, so the phone reconnects (drops calls and audio).
 * Separately, when the phone's route guidance was active but nothing has arrived for
 * guidance_restart_s (90 s; stops at lights were at most 60 s in the recorded drives),
 * route guidance updates are stopped and started again, once per silence, as DiPO does
 * when the guidance source changes.
 * Dead link (owner logs 2026-11-21/22): the phone left the head unit's Wi-Fi and never came
 * back, but DiPO never saw the session end; turn-by-turn mode stayed on and the HUD kept the last
 * arrow until the head unit restarted. During a wireless session the phone's iAP2 messages
 * arrive over the CarPlay channel about once a second (CarPlayService.oniAPSendMessage, also
 * without navigation); after LINK_DEAD_MS without one the main frame and the small HUD are
 * cleared at once, and (recover=screen or reconnect, the default) the session is ended and
 * CarPlay advertised again so the phone can reconnect. Not armed until the first iAP2 message
 * of a session, so wired CarPlay (iAP2 over USB) never triggers it.
 * Calls go through reflection to DiPO's own classes; any failure is logged and the step is
 * skipped. Numbers and class names only in the trace.
 */
public final class ArHudRecovery implements Runnable {
    public static final int TIMEOUT = 1, DECODE = 2, SCREEN_STARTED = 3, SCREEN_STOPPED = 4,
            SESSION_STARTED = 5, SESSION_ENDED = 6;
    static final long RETAKE_MS = 4000, RETURN_MS = 1500, RECONNECT_MS = 10000, BONJOUR_MS = 3000,
            DECODE_GAP_MS = 5000, LINK_DEAD_MS = 30000;
    static final String BORROW_ID = "evergreen-recover";
    private static Object service;
    private static boolean session, screen, running;
    /** stall start, and when each step ran (-1: not yet) */
    private static long stallAt = -1, keyAt = -1, borrowAt = -1, returnAt = -1, reconnectAt = -1, bonjourAt = -1, decodeAt = -1;
    private static int guidanceState;
    private static long guidanceAt = -1, restartedAt = -1, iapAt = -1, linkDeadAt = -1;
    static boolean background = true; // Host tests drive tick() deterministically.
    /** Host tests: what the steps would have called, instead of DiPO. */
    static StringBuilder calls;

    private ArHudRecovery() {}

    /** From CarPlayService's own callbacks (patched entry): event, and the decode failure type. */
    public static void screen(Object carPlayService, int event, int argument) {
        long now = SystemClock.elapsedRealtime();
        synchronized (ArHudRecovery.class) {
            if (carPlayService != null) service = carPlayService;
            event(now, event, argument);
        }
        start();
    }

    static void event(long now, int event, int argument) {
        switch (event) {
            case SESSION_STARTED:
                ArHudDebug.event("screen session_started" + recovering(now));
                session = true; screen = false; clearStall(); iapAt = -1; linkDeadAt = -1; reconnectAt = bonjourAt = -1;
                break;
            case SESSION_ENDED:
                ArHudDebug.event("screen session_ended" + recovering(now));
                session = false; screen = false; clearStall(); guidanceState = 0; guidanceAt = -1; iapAt = -1; linkDeadAt = -1;
                break;
            case SCREEN_STARTED:
                if (stallAt >= 0) ArHudDebug.event("recover_ok after_ms=" + (now - stallAt) + " step=" + step());
                else ArHudDebug.event("screen started");
                screen = true; clearStall();
                break;
            case SCREEN_STOPPED:
                ArHudDebug.event("screen stopped" + recovering(now));
                screen = false;
                break;
            case TIMEOUT:
                ArHudDebug.event("screen timeout" + recovering(now));
                if (stallAt < 0 && mode() >= ArHudTuning.RECOVER_KEYFRAME) { stallAt = now; keyFrame(now); }
                break;
            case DECODE:
                ArHudDebug.event("screen decode_failure type=" + argument + recovering(now));
                // A failure while the picture runs: a fresh key frame, rate-limited; no escalation.
                if (screen && stallAt < 0 && mode() >= ArHudTuning.RECOVER_KEYFRAME
                        && (decodeAt < 0 || now < decodeAt || now - decodeAt >= DECODE_GAP_MS)) { decodeAt = now; keyFrame(now); }
                break;
            default:
                break;
        }
    }

    /** CarPlayService.oniAPSendMessage entry: an iAP2 message from the phone over the CarPlay channel. */
    public static void iap() {
        long now = SystemClock.elapsedRealtime();
        synchronized (ArHudRecovery.class) {
            if (linkDeadAt >= 0) ArHudDebug.event("link_back after_ms=" + (now - linkDeadAt));
            iapAt = now; linkDeadAt = -1;
        }
    }

    /** Every phone route update (DiPO's sender, through ArHudRuntime.prepare). */
    public static synchronized void guidance(int state, long now) {
        // Any update re-arms the restart for the next silence.
        guidanceState = state; guidanceAt = now; restartedAt = -1;
    }

    /** DiPO cleared turn-by-turn (stop, source change, disconnect). */
    public static synchronized void guidanceStopped() { guidanceState = 0; }

    static void tick(long now) {
        boolean dead = false;
        synchronized (ArHudRecovery.class) {
            if (session && iapAt >= 0 && linkDeadAt < 0 && now >= iapAt && now - iapAt >= LINK_DEAD_MS) {
                linkDeadAt = now; dead = true;
                ArHudDebug.event("link_dead silent_ms=" + (now - iapAt) + " guidance_state=" + guidanceState);
                if (mode() >= ArHudTuning.RECOVER_SCREEN && reconnectAt < 0) {
                    // Nothing flows any more, so nothing is lost: end the session, advertise again (in steps()).
                    reconnectAt = now; bonjourAt = -1;
                    ArHudDebug.event("recover step=link end_session");
                    call("disconnectCarPlay");
                }
                guidanceState = 0;
            }
        }
        // Outside our lock: the runtime takes its own, and the OEM calls may block.
        if (dead) ArHudRuntime.clearDisplay("link_dead");
        steps(now);
    }

    private static synchronized void steps(long now) {
        int mode = mode();
        if (stallAt >= 0 && session) {
            if (borrowAt < 0 && mode >= ArHudTuning.RECOVER_SCREEN && now - keyAt >= RETAKE_MS) {
                borrowAt = now;
                ArHudDebug.event("recover step=2 borrow_screen after_ms=" + (now - stallAt));
                call("changeModeBorrowScreen");
            } else if (borrowAt >= 0 && returnAt < 0 && now - borrowAt >= RETURN_MS) {
                returnAt = now;
                ArHudDebug.event("recover step=2 return_screen after_ms=" + (now - stallAt));
                call("changeModeUnborrowScreen");
            } else if (returnAt >= 0 && reconnectAt < 0 && mode >= ArHudTuning.RECOVER_RECONNECT && now - returnAt >= RECONNECT_MS) {
                reconnectAt = now;
                ArHudDebug.event("recover step=3 reconnect after_ms=" + (now - stallAt));
                call("disconnectCarPlay");
            }
        }
        if (reconnectAt >= 0 && bonjourAt < 0 && now - reconnectAt >= BONJOUR_MS) {
            bonjourAt = now;
            ArHudDebug.event("recover step=3 advertise");
            call("restartBonjour");
        }
        long quiet = ArHudTuning.guidanceRestartMs(now);
        if (quiet > 0 && session && linkDeadAt < 0 && guidanceState != 0 && guidanceAt >= 0 && now >= guidanceAt
                && now - guidanceAt >= quiet && restartedAt < 0) {
            restartedAt = now;
            ArHudDebug.event("guidance_restart silent_ms=" + (now - guidanceAt) + " state=" + guidanceState);
            restartGuidance();
        }
    }

    private static int mode() { return ArHudTuning.recoverMode(SystemClock.elapsedRealtime()); }
    /** The picture's steps; a session restart in progress (reconnectAt) survives the session's end, which it causes. */
    private static void clearStall() { stallAt = keyAt = borrowAt = returnAt = -1; }
    private static int step() { return reconnectAt >= 0 ? 3 : borrowAt >= 0 ? 2 : keyAt >= 0 ? 1 : 0; }
    private static String recovering(long now) { return stallAt < 0 ? "" : " recovering_ms=" + (now - stallAt) + " step=" + step(); }

    private static void keyFrame(long now) {
        keyAt = now;
        ArHudDebug.event("recover step=1 key_frame");
        call("forceKeyFrame");
    }

    /** One CarPlayService call by name; never throws into DiPO. */
    private static void call(String name) {
        if (calls != null) { calls.append(name).append(';'); return; }
        Object target = service;
        if (target == null) { ArHudDebug.event("recover_failed " + name + " no_service"); return; }
        try {
            Class<?> type = target.getClass();
            Object result;
            if (name.equals("changeModeBorrowScreen")) {
                ClassLoader loader = type.getClassLoader();
                Object priority = constant(loader, "com.daudio.app.dipo.telechips.CarPlayTransferPriority", "UserInitiated");
                Object constraint = constant(loader, "com.daudio.app.dipo.telechips.CarPlayConstraint", "UserInitiated");
                Method m = type.getMethod(name, priority.getClass(), constraint.getClass(), String.class);
                result = m.invoke(target, priority, constraint, BORROW_ID);
            } else if (name.equals("changeModeUnborrowScreen")) {
                result = type.getMethod(name, String.class).invoke(target, BORROW_ID);
            } else {
                Method m = type.getDeclaredMethod(name);
                m.setAccessible(true); // restartBonjour is private in DiPO
                result = m.invoke(target);
            }
            ArHudDebug.event("recover_called " + name + " result=" + (result == null ? "void" : String.valueOf(result)));
        } catch (Throwable failure) {
            ArHudDebug.event("recover_failed " + name + " " + failure.getClass().getName());
        }
    }

    private static Object constant(ClassLoader loader, String type, String name) throws Exception {
        Field f = Class.forName(type, true, loader).getField(name);
        return f.get(null);
    }

    /** DiPO's own stop and start of the phone's route guidance updates (e/b N() and M()). */
    private static void restartGuidance() {
        if (calls != null) { calls.append("restartGuidance;"); return; }
        try {
            // The turn-by-turn build keeps DiPO's iAP2 manager (e/b) in e/u's static leafiaOwner.
            ClassLoader loader = ArHudRecovery.class.getClassLoader();
            Object owner = Class.forName("com.daudio.app.dipo.e.u", true, loader).getField("leafiaOwner").get(null);
            Class<?> manager = Class.forName("com.daudio.app.dipo.e.b", true, loader);
            if (owner == null) { ArHudDebug.event("guidance_restart_failed no_owner"); return; }
            manager.getMethod("N").invoke(owner);
            manager.getMethod("M").invoke(owner);
            ArHudDebug.event("guidance_restart_called");
        } catch (Throwable failure) {
            ArHudDebug.event("guidance_restart_failed " + failure.getClass().getName());
        }
    }

    private static void start() {
        synchronized (ArHudRecovery.class) {
            if (running || !background) return;
            running = true;
        }
        Thread t = new Thread(new ArHudRecovery(), "Evergreen-AR-recover");
        t.setDaemon(true);
        t.start();
    }

    public void run() {
        while (true) {
            // Calls DiPO from this thread, never from CarPlay's callback thread.
            try { Thread.sleep(250); } catch (InterruptedException stopped) { synchronized (ArHudRecovery.class) { running = false; } return; }
            try { tick(SystemClock.elapsedRealtime()); } catch (RuntimeException unavailable) { /* next tick */ }
        }
    }

    /** Host tests. */
    static synchronized void reset() {
        service = null; session = false; screen = false; clearStall(); decodeAt = -1; iapAt = -1; linkDeadAt = -1;
        reconnectAt = bonjourAt = -1;
        guidanceState = 0; guidanceAt = -1; restartedAt = -1;
    }
}
