// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

import android.os.SystemClock;
import com.hkmc.cluster.NavigationCluster;

/** DiPO-side bounded 4 Hz sender. It reuses only the existing OEM turn API.
 * All sends and input changes serialize on one lock. Raw receipt times are not
 * refreshed by unrelated partial updates or by the interpolation thread.
 */
public final class ArHudRuntime implements Runnable {
    private static final ArHudMotion motion = new ArHudMotion();
    private static ArHudSample sample;
    private static NavigationCluster cluster;
    private static int icon, side, combi, unit, distanceError, barError, originalBar;
    private static float direction, rounded;
    private static long receivedAt, acceptedExactAt = -1;
    private static float sentMeters = -1f;
    private static boolean running, active;
    public static final float STREET_AR_METERS = 75f;
    private static Object seenOwner;
    private static int seenComponent=-1, seenManeuver=-1;
    private static String traceKey;
    private static long traceAt = -1, heartbeatAt = -1;
    static boolean background = true; // Host tests drive tick() deterministically.

    private ArHudRuntime() {}

    public static void exact(Object route, int meters) {
        ArHudRoute.exact(route, meters, SystemClock.elapsedRealtime());
    }

    /** Called only after the stock maneuver-details parser has returned normally. */
    public static void details(Object owner) {
        ArHudDebug.event("details_message");
        try { if (ArHudIpc.advanced()) ArHudRoute.refreshDetails(owner); }
        catch (RuntimeException unavailable) { /* Original callback must still complete. */ }
    }

    public static void display(Object route) { ArHudRoute.display(route); }

    public static void forget(Object route) { ArHudRoute.forget(route); }
    public static void begin(Object route) { ArHudRoute.begin(route); }
    public static void finish(Object route) { ArHudRoute.finish(route); }

    public static void prepare(Object owner, Integer component, Object route) {
        prepare(owner,component,route,ArHudIpc.advanced());
    }
    static void prepare(Object owner,Integer component,Object route,boolean advanced) {
        ArHudRoute.prepare(owner, component, route);
        int current=ArHudRoute.identity(route), state=ArHudRoute.state(route);
        ArHudDebug.event("sender_prepare component="+component+" current="+current+" state="+state);
        boolean blank=false;
        synchronized(ArHudRuntime.class) {
            if(advanced && (seenOwner!=owner || component==null || seenComponent!=component.intValue() || seenManeuver!=current)) {
                if(cluster!=null) {
                    ArHudDebug.event("maneuver_replaced clear_previous");
                    ArHudForwarder.forward(false,cluster,0,0,0,0,0,0,0,0,0);
                    blank=true;
                }
                active=false;sample=null;cluster=null;motion.reset();acceptedExactAt=-1;sentMeters=-1;
                ArHudIpc.publish(false,0,SystemClock.elapsedRealtime(),SystemClock.elapsedRealtime());
                seenOwner=owner;seenComponent=component==null?-1:component.intValue();seenManeuver=current;
            }
        }
        if(blank)ArHudRoute.blank(owner);
        if (ArHudRoute.stopped()) reset();
    }

    public static void event(String name) { ArHudDebug.event(name); }

    public static void distanceEvent(String name,float distance,int unit) { ArHudDebug.event(name+" distance="+distance+" unit="+unit); }

    public static void end() {
        ArHudSample remaining=ArHudRoute.take();
        ArHudDebug.event("sender_return unconsumed_metadata="+(remaining!=null));
    }

    /** Runs before DiPO's zero-icon early return, preserving native and known icons. */
    public static int resolveIcon(int original) {
        try {
            boolean advanced=ArHudIpc.advanced();
            if(advanced && (original==0||original>=64) && !ArHudRoute.distanceReady()) {
                ArHudDebug.event("icon_gate waiting_for_current_distance");return 0;
            }
            return resolveIcon(original,ArHudRoute.peek(),advanced);
        } catch(RuntimeException unavailable){ArHudDebug.event("icon_gate_failed");return original;}
    }
    static int resolveIcon(int original,ArHudSample s,boolean advanced) {
            if(!advanced || original!=0)return original;
            int recovered=s==null?0:ArHudTurns.recoverIcon(s.type,s.drivingSide,s.junction);
            ArHudDebug.event("icon_gate original="+original+" recovered="+recovered+" raw="+(s==null?-1:s.type)+
                    " angle="+(s==null?0:s.angle));
            return recovered;
    }

    public static synchronized void reset() {
        ArHudDebug.event("reset");
        active = false; sample = null; cluster = null; motion.reset();
        seenOwner=null;seenComponent=-1;seenManeuver=-1;
        acceptedExactAt = -1; sentMeters = -1f; traceKey = null; traceAt = -1;
        ArHudRoute.clear();
        long now = SystemClock.elapsedRealtime();
        ArHudIpc.publish(false, 0, now, now);
    }

    private static void start() {
        if (running || !background) return;
        Thread t = new Thread(new ArHudRuntime(), "Evergreen-AR-turns");
        t.setDaemon(true); t.start(); running = true;
    }

    public static synchronized boolean update(NavigationCluster receiver, int type, int street,
            float angle, float display, int distError, int combined, int scale, int bar, int barErr,
            ArHudSample incoming, long now, boolean advanced) {
        ArHudDebug.event("main_input icon="+type+" distance="+display+" unit="+scale+" dist_error="+distError+
                " bar_error="+barErr+" advanced="+advanced+" metadata="+(incoming!=null));
        if (!advanced || incoming == null || !incoming.guiding || type < 64 || distError != 0 || barErr != 0
                || ArHudTurns.meters(display, scale) < 0f) {
            active = false; motion.reset(); acceptedExactAt = -1;
            ArHudIpc.publish(false, 0, now, now);
            return false;
        }
        boolean changed = sample == null || sample.owner != incoming.owner
                || sample.component != incoming.component || sample.maneuver != incoming.maneuver
                || icon != type;
        if (changed) { motion.reset(); acceptedExactAt = -1; sentMeters = -1f; }
        sample = incoming; cluster = receiver; icon = type; side = street; direction = angle;
        rounded = display; distanceError = distError; combi = combined; unit = scale; barError = barErr;
        originalBar = bar;
        receivedAt = now; active = true;
        if (incoming.exactAt < 0 || now < incoming.exactAt || now - incoming.exactAt > 5000) {
            motion.reset(); acceptedExactAt = -1;
        }
        if (incoming.exactAt >= 0 && incoming.exactAt <= now && now - incoming.exactAt <= 5000
                && incoming.exactAt != acceptedExactAt && incoming.exactMeters >= 0f) {
            motion.sample(incoming.exactMeters, incoming.exactAt); acceptedExactAt = incoming.exactAt;
        }
        send(now, true);
        start();
        return true;
    }

    private static void send(long now, boolean force) {
        float exact = sample.exactAt >= 0 && now >= sample.exactAt && now - sample.exactAt <= 5000
                ? motion.meters(now) : -1f;
        float meters = exact >= 0f ? exact : ArHudTurns.meters(rounded, unit);
        int[] turn = exact >= 0f ? ArHudTurns.maneuver(sample.type, sample.drivingSide, sample.junction,
                sample.angle, sample.angle != 0, icon, exact, 1) : null;
        if (turn == null) turn = ArHudTurns.convert(icon, exact >= 0f ? exact : rounded, 0, exact >= 0f ? 0 : unit, 0);
        if(turn!=null && turn[0]==13 && meters>STREET_AR_METERS)turn=null;
        if (force) trace(now, exact, meters, turn);
        int bar = turn == null ? 0 : turn[2];
        ArHudIpc.publish(true, bar, receivedAt, now);
        if (!force && (turn == null || exact < 0f || Math.abs(meters - sentMeters) < 0.05f)) return;
        ArHudDebug.event("main_send type="+(turn==null?icon:turn[0])+" meters="+meters);
        if (turn == null) {
            // Far/unknown/native values retain every original display field.
            cluster.updateTBTInfo(icon, side, direction, rounded, distanceError, combi, unit, originalBar, barError);
        } else {
            float distance = exact >= 0f ? ArHudTurns.fromMeters(exact, unit) : rounded;
            cluster.updateTBTInfo(turn[0], 0, turn[1], distance, 0, combi, unit, bar, 0);
        }
        ArHudDebug.event("main_sent type="+(turn==null?icon:turn[0])+" meters="+meters+" exact="+(exact>=0)+
                " age="+(sample.exactAt<0?-1:now-sample.exactAt)+" force="+force+" bar="+bar);
        sentMeters = meters;
    }

    /** Small diagnostic record on changed conversion state, at most once/second.
     * No road names, route coordinates, phone IDs or maneuver identifiers.
     */
    private static void trace(long now, float exact, float meters, int[] turn) {
        String state = turn != null ? "native" : meters > ArHudTurns.GUIDE_METERS ? "far" : "unmapped";
        String key = icon+":"+sample.type+":"+sample.drivingSide+":"+sample.junction+":"+sample.angle+":"+state+":"+(exact>=0);
        if (key.equals(traceKey) || (traceAt >= 0 && now >= traceAt && now-traceAt < 1000)) return;
        traceKey=key;traceAt=now;
        android.util.Log.w("EvergreenAR", "turn icon="+icon+" raw="+sample.type+" traffic="+sample.drivingSide+
                " junction="+sample.junction+" angle="+sample.angle+" exact="+(exact>=0)+" meters="+(int)meters+
                " result="+state+" output="+(turn==null?icon:turn[0]), null);
    }

    static synchronized void tick(long now, boolean advanced) {
        if(heartbeatAt<0 || now<heartbeatAt || now-heartbeatAt>=5000) {
            ArHudDebug.event("worker_alive active="+active+" advanced="+advanced+" input_age="+(now-receivedAt));heartbeatAt=now;
        }
        if (!active) return;
        if (!advanced || now < receivedAt || now - receivedAt > 5000) {
            ArHudDebug.event("timer_expired advanced="+advanced+" input_age="+(now-receivedAt));
            active = false; motion.reset(); acceptedExactAt = -1;
            ArHudIpc.publish(false, 0, now, now); return;
        }
        send(now, false);
    }

    public void run() {
        while (true) {
            try { Thread.sleep(250); }
            catch (InterruptedException stopped) { synchronized (ArHudRuntime.class) { running = false; reset(); } return; }
            try { tick(SystemClock.elapsedRealtime(), ArHudIpc.advanced()); }
            catch (RuntimeException unavailable) { reset(); }
        }
    }
}
