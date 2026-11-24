/* SPDX-License-Identifier: GPL-3.0-or-later
 * Ultimate Wireless Projection: the hooks CarPlay Ultra-Lite registered, called from our DiPO build.
 *
 * Wired points (tools/build-carplay-ultra.py adds the calls to DiPO's telechips.CarPlayService):
 *   dipo.carplay.session_started    onSessionStarted(int): before at the start, after at each return
 *   dipo.carplay.session_finalized  onSessionFinalized(int): the same
 * CarPlay Ultra-Lite's other points (iAP messages, the volume-key and system_server ones) are only
 * used by experiments that are off in its 1.3.3 release; they are accepted and never called.
 * A hook that throws is logged and the original method goes on (a call site can't skip it).
 */
package hmgapi.hook;

import java.util.ArrayList;
import java.util.List;

public final class Hooks {
    static final String STARTED = "dipo.carplay.session_started", FINALIZED = "dipo.carplay.session_finalized";
    private static final List<MethodHook> started = new ArrayList<MethodHook>(), finalized = new ArrayList<MethodHook>();
    private static final ThreadLocal<MethodHook.Param> current = new ThreadLocal<MethodHook.Param>();
    private static ModContext mod;

    private Hooks() {
    }

    /** where a hook that throws is logged */
    public static void setMod(ModContext m) {
        mod = m;
    }

    static synchronized boolean add(String point, MethodHook hook) {
        if (STARTED.equals(point)) return started.add(hook);
        if (FINALIZED.equals(point)) return finalized.add(hook);
        return false;
    }

    private static synchronized MethodHook[] of(String point) {
        List<MethodHook> l = STARTED.equals(point) ? started : finalized;
        return l.toArray(new MethodHook[l.size()]);
    }

    /** at the start of the hooked method (the thread's Param is kept for after()) */
    public static void before(String point, Object thisObject, int arg) {
        MethodHook.Param p = new MethodHook.Param(null, thisObject, new Object[] { Integer.valueOf(arg) });
        current.set(p);
        for (MethodHook h : of(point)) {
            try {
                h.beforeHookedMethod(p);
            } catch (Throwable t) {
                fail(point, t);
            }
        }
    }

    /** before each return of the hooked method */
    public static void after(String point) {
        MethodHook.Param p = current.get();
        current.remove();
        if (p == null) return;
        for (MethodHook h : of(point)) {
            try {
                h.afterHookedMethod(p);
            } catch (Throwable t) {
                fail(point, t);
            }
        }
    }

    private static void fail(String point, Throwable t) {
        if (mod != null) mod.error("hook " + point + " threw", t);
    }
}
