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
    private static Object seenOwner;
    /** DiPO's turn-by-turn manager from the latest route update, in any mode: its sendHudBlank. */
    private static volatile Object lastOwner;
    private static int seenComponent=-1, seenManeuver=-1;
    private static String traceKey;
    private static long traceAt = -1, heartbeatAt = -1;
    // A replaced maneuver is cleared only if the new one is not sent in the same callback.
    private static boolean clearPending, mainSentSincePrepare, hudSentSincePrepare;
    private static NavigationCluster clearCluster;
    private static Object clearOwner;
    private static java.lang.reflect.Method hudDistanceMethod;
    private static Class<?> hudDistanceClass, hudDistanceFailedClass;
    private static int hudKey = Integer.MIN_VALUE;
    // Native type of the last main frame for the current maneuver; 0 while a plain icon is shown.
    private static int shownType;
    // The stock sender's thread, for the one-line record when its input goes quiet.
    private static Thread inputThread;
    private static boolean quietLogged;
    static final long QUIET_MS = 15000;
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
        if (owner != null) lastOwner = owner;
        int current=ArHudRoute.identity(route), state=ArHudRoute.state(route);
        ArHudRecovery.guidance(state, SystemClock.elapsedRealtime());
        if(ArHudDebug.on())ArHudDebug.event("sender_prepare component="+component+" current="+current+" state="+state);
        ArHudDebug.watch();
        synchronized(ArHudRuntime.class) {
            inputThread=Thread.currentThread();
            mainSentSincePrepare=false;hudSentSincePrepare=false;
            if(advanced && (seenOwner!=owner || component==null || seenComponent!=component.intValue() || seenManeuver!=current)) {
                if(cluster!=null && (shownType==17 || shownType==18)) {
                    // The native exit sign is not proven to give way to the next maneuver's
                    // frame on its own, so it is cleared before that frame rather than after.
                    ArHudDebug.event("maneuver_replaced clear_exit_sign");
                    try { ArHudForwarder.forward(false,cluster,0,0,0,0,0,0,0,0,0); } catch(RuntimeException unavailable) { /* stale display only */ }
                    // The small HUD's entry is still blanked in end() if no new list follows.
                    clearPending=true;clearCluster=null;clearOwner=owner;
                } else if(cluster!=null) {
                    // Deferred to end(): in the recorded drive the new maneuver followed within
                    // 5-10 ms in 13 of 14 replacements, so an immediate clear was a one-frame blink.
                    ArHudDebug.event("maneuver_replaced clear_deferred");
                    clearPending=true;clearCluster=cluster;clearOwner=owner;
                }
                active=false;sample=null;cluster=null;motion.reset();acceptedExactAt=-1;sentMeters=-1;hudKey=Integer.MIN_VALUE;shownType=0;
                ArHudIpc.publish(false,0,SystemClock.elapsedRealtime(),SystemClock.elapsedRealtime());
                seenOwner=owner;seenComponent=component==null?-1:component.intValue();seenManeuver=current;
            }
        }
        if (ArHudRoute.stopped()) reset();
    }

    /** The OEM (or our fallback) sent a main frame for the current maneuver. */
    public static synchronized void mainSent() { mainSentSincePrepare=true; }
    /** The OEM sent its small-HUD turn list for the current maneuver. */
    public static synchronized void hudSent() { hudSentSincePrepare=true; }

    public static void event(String name) { ArHudDebug.event(name); }

    public static void distanceEvent(String name,float distance,int unit) { ArHudDebug.event(name+" distance="+distance+" unit="+unit); }

    public static void end() {
        ArHudSample remaining=ArHudRoute.take();
        if(ArHudDebug.on())ArHudDebug.event("sender_return unconsumed_metadata="+(remaining!=null));
        NavigationCluster staleCluster=null;Object staleOwner=null;boolean clearMain=false,clearHud=false;
        synchronized(ArHudRuntime.class) {
            if(clearPending) {
                clearMain=!mainSentSincePrepare;clearHud=!hudSentSincePrepare;
                staleCluster=clearCluster;staleOwner=clearOwner;
                clearPending=false;clearCluster=null;clearOwner=null;
            }
        }
        if(clearMain && staleCluster!=null) {
            ArHudDebug.event("clear_previous_main");
            try { ArHudForwarder.forward(false,staleCluster,0,0,0,0,0,0,0,0,0); } catch(RuntimeException unavailable) { /* stale display only */ }
        }
        if(clearHud && staleOwner!=null) { ArHudDebug.event("clear_previous_hud"); ArHudRoute.blank(staleOwner); }
        if(!clearMain && !clearHud && (staleCluster!=null || staleOwner!=null)) ArHudDebug.event("clear_skipped new_maneuver_sent");
    }

    /** Hooked at the entry of DiPO's sendHudDist: the small HUD shows the same distance as the
     * gliding arrow instead of the phone's rounded display value. Falls back to the OEM value. */
    public static synchronized float hudDistance(float display, int unit) {
        try {
            if(!active || sentMeters<0f || sample==null || sample.exactAt<0 || unit<0 || unit>4 || ArHudTurns.cueOnly(sample.type)) return display;
            float value=ArHudTurns.fromMeters(sentMeters,unit);
            return value<0f ? display : value;
        } catch(RuntimeException unavailable) { return display; }
    }

    /** Re-send the HUD distance from the worker when its displayed value would change. */
    private static void hudGlide(float meters, long now) {
        if(sample==null || sample.owner==null) return;
        Class<?> type=sample.owner.getClass();
        if(type==hudDistanceFailedClass) return;
        float value=ArHudTurns.fromMeters(meters,unit);
        if(value<0f) return;
        // The OEM shows tenths for km/mi and whole numbers otherwise.
        int key=unit==1||unit==2 ? Math.round(value*10f) : (int)value;
        if(key==hudKey) return;
        try {
            if(hudDistanceMethod==null || hudDistanceClass!=type) {
                hudDistanceMethod=type.getDeclaredMethod("sendHudDist",float.class,int.class);
                hudDistanceMethod.setAccessible(true);hudDistanceClass=type;
            }
            hudDistanceMethod.invoke(sample.owner,Float.valueOf(value),Integer.valueOf(unit));
            hudKey=key;
            if(ArHudDebug.on())ArHudDebug.event("hud_distance_glide value="+value+" unit="+unit);
        } catch(Exception unavailable) {
            hudDistanceFailedClass=type;
            android.util.Log.w("EvergreenAR","HUD distance glide unavailable; retaining OEM cadence",unavailable);
        }
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
            int exitSide=s==null?0:exitSide(s,SystemClock.elapsedRealtime());
            int recovered=s==null?0:ArHudTurns.recoverIcon(s.type,s.drivingSide,s.junction,exitSide);
            if(ArHudDebug.on())ArHudDebug.event("icon_gate original="+original+" recovered="+recovered+" raw="+(s==null?-1:s.type)+
                    " angle="+(s==null?0:s.angle)+" traffic="+(s==null?-1:s.drivingSide)+" junction="+(s==null?-1:s.junction)+
                    " state="+(s==null?-1:s.state)+" mstate="+(s==null?-1:s.maneuverState)+" angles="+(s==null?-1:s.junctionAngles)+
                    " exit_side="+exitSide);
            return recovered;
    }

    /** Side chosen for the phone's direction-unspecified exit, from the tuning mode and the sample. */
    static int exitSide(ArHudSample s,long now) {
        return ArHudTurns.exitSide(s.type,s.drivingSide,s.angle,ArHudTuning.exitSide(now));
    }

    static String angles(int[] values) {
        if(values==null)return "-";
        StringBuilder text=new StringBuilder();
        for(int i=0;i<values.length;i++){if(i>0)text.append('/');text.append(values[i]);}
        return text.length()==0?"none":text.toString();
    }

    /** DiPO stopped or restarted turn-by-turn (its stopTurnByTurn and clear hooks), or a failure.
     * DiPO's stopTurnByTurn blanks only the small HUD list; a main frame still showing is cleared
     * here, so a stop never leaves the arrow behind (the 113 guidance restart could). */
    public static void reset() {
        NavigationCluster shown;
        synchronized (ArHudRuntime.class) {
            shown = shownType != 0 || ArHudForwarder.lastType != 0 ? (cluster != null ? cluster : ArHudForwarder.lastCluster) : null;
            resetState();
        }
        if (shown != null) {
            ArHudDebug.event("clear_on_reset");
            try { ArHudForwarder.forward(false, shown, 0, 0, 0, 0, 0, 0, 0, 0, 0); ArHudForwarder.lastType = 0; }
            catch (RuntimeException unavailable) { /* stale display only */ }
        }
    }

    /** Clear the main frame and the small HUD now (a dead CarPlay link: nothing will refresh them). */
    public static void clearDisplay(String why) {
        NavigationCluster target; Object owner;
        synchronized (ArHudRuntime.class) {
            target = cluster != null ? cluster : ArHudForwarder.lastCluster;
            owner = lastOwner;
            resetState();
        }
        ArHudDebug.event("clear_" + why);
        if (target != null) {
            try { ArHudForwarder.forward(false, target, 0, 0, 0, 0, 0, 0, 0, 0, 0); ArHudForwarder.lastType = 0; }
            catch (RuntimeException unavailable) { /* stale display only */ }
        }
        if (owner != null) ArHudRoute.blank(owner);
    }

    private static synchronized void resetState() {
        ArHudDebug.event("reset");
        ArHudRecovery.guidanceStopped();
        active = false; sample = null; cluster = null; motion.reset();
        seenOwner=null;seenComponent=-1;seenManeuver=-1;
        acceptedExactAt = -1; sentMeters = -1f; traceKey = null; traceAt = -1; hudKey = Integer.MIN_VALUE;
        shownType = 0; quietLogged = false;
        clearPending = false; clearCluster = null; clearOwner = null;
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
        if (ArHudDebug.on()) ArHudDebug.event("main_input icon="+type+" distance="+display+" unit="+scale+" dist_error="+distError+
                " bar_error="+barErr+" advanced="+advanced+" metadata="+(incoming!=null)+
                " state="+(incoming==null?-1:incoming.state)+" mstate="+(incoming==null?-1:incoming.maneuverState));
        if (advanced && incoming != null && incoming.state == ArHudSample.STATE_REROUTING) {
            // The phone is recalculating: keep the OEM frame as it is, with no AR arrow or
            // smoothing for a maneuver that may no longer be on the route.
            ArHudDebug.event("rerouting_passthrough icon="+type+" distance="+display);
            active = false; motion.reset(); acceptedExactAt = -1; sentMeters = -1f;
            ArHudIpc.publish(false, 0, now, now);
            ArHudForwarder.forward(false, receiver, type, street, angle, display, distError, combined, scale, bar, barErr);
            mainSentSincePrepare = true;
            return true;
        }
        if (!advanced || incoming == null || !incoming.guiding || type < 64 || distError != 0 || barErr != 0
                || ArHudTurns.meters(display, scale) < 0f) {
            active = false; motion.reset(); acceptedExactAt = -1;
            ArHudIpc.publish(false, 0, now, now);
            return false;
        }
        boolean changed = sample == null || sample.owner != incoming.owner
                || sample.component != incoming.component || sample.maneuver != incoming.maneuver
                || icon != type;
        if (changed) { motion.reset(); acceptedExactAt = -1; sentMeters = -1f; hudKey = Integer.MIN_VALUE; }
        quietLogged = false;
        sample = incoming; cluster = receiver; icon = type; side = street; direction = angle;
        rounded = display; distanceError = distError; combi = combined; unit = scale; barError = barErr;
        originalBar = bar;
        receivedAt = now; active = true;
        if (incoming.exactAt < 0 || now < incoming.exactAt || now - incoming.exactAt > 5000) {
            motion.reset(); acceptedExactAt = -1;
        }
        if (incoming.exactAt >= 0 && incoming.exactAt <= now && now - incoming.exactAt <= 5000
                && incoming.exactAt != acceptedExactAt && incoming.exactMeters >= 0f) {
            motion.gain(ArHudTuning.gain(now));
            motion.sample(incoming.exactMeters, incoming.exactAt); acceptedExactAt = incoming.exactAt;
        }
        send(now, true);
        mainSentSincePrepare = true;
        start();
        return true;
    }

    private static void send(long now, boolean force) {
        float street = ArHudTuning.streetArMeters(now);
        float exact = sample.exactAt >= 0 && now >= sample.exactAt && now - sample.exactAt <= 5000
                ? motion.meters(now) : -1f;
        // A phone sample slightly above the already extrapolated distance would step the
        // arrow backward; hold until the phone's distance passes it. Larger increases
        // (reroute, backing up) go through.
        if (exact >= 0f && sentMeters >= 0f && exact > sentMeters && exact - sentMeters <= ArHudTuning.backstepMeters(now)) {
            exact = sentMeters;
        }
        float meters = exact >= 0f ? exact : ArHudTurns.meters(rounded, unit);
        int exitSide = exitSide(sample, now);
        int[] turn = exact >= 0f ? ArHudTurns.maneuver(sample.type, sample.drivingSide, sample.junction,
                sample.angle, sample.angle != 0, icon, exact, 1, exitSide,
                ArHudTuning.exitStyle(now) == ArHudTuning.EXIT_STYLE_SIGN) : null;
        if (turn == null) turn = ArHudTurns.convert(icon, exact >= 0f ? exact : rounded, 0, exact >= 0f ? 0 : unit, 0);
        if (turn != null && ArHudTurns.cueOnly(sample.type)) turn = null;
        float offset = ArHudTuning.turnOffsetMeters(now);
        if (turn != null && turn[0] == 13) {
            // Street turns start later than the 457 m window; the bar keeps the stock
            // navigation's scale (bar_meters, 457 m by default) rather than the AR window's.
            // An exit drawn as a slight turn is a highway maneuver and keeps the full window.
            if (!ArHudTurns.exit(sample.type) && meters > street) turn = null;
            else turn[2] = ArHudTurns.bar(meters, ArHudTuning.barMeters(now));
        }
        if (force) trace(now, exact, meters, turn, street, exitSide, offset);
        int bar = turn == null ? 0 : turn[2];
        ArHudIpc.publish(true, bar, receivedAt, now);
        if (!force && (turn == null || exact < 0f || Math.abs(meters - sentMeters) < 0.05f)) return;
        boolean tracing = ArHudDebug.on();
        if (tracing) ArHudDebug.event("main_send type="+(turn==null?icon:turn[0])+" meters="+meters);
        if (turn == null) {
            // Far/unknown/native/cue-only values retain every original display field.
            cluster.updateTBTInfo(icon, side, direction, rounded, distanceError, combi, unit, originalBar, barError);
        } else {
            // The offset moves only the large arrow's distance; sentMeters and the HUD number stay the phone's.
            float distance = exact >= 0f ? ArHudTurns.fromMeters(exact + offset, unit) : rounded;
            cluster.updateTBTInfo(turn[0], 0, turn[1], distance, 0, combi, unit, bar, 0);
        }
        if (tracing) ArHudDebug.event("main_sent type="+(turn==null?icon:turn[0])+" meters="+meters+" exact="+(exact>=0)+
                " age="+(sample.exactAt<0?-1:now-sample.exactAt)+" force="+force+" bar="+bar);
        sentMeters = meters;
        shownType = turn == null ? 0 : turn[0];
        // The OEM sends its own HUD distance after a phone update (through the hooked entry);
        // worker ticks keep the small HUD's number moving with the arrow.
        if (!force && exact >= 0f) hudGlide(meters, now);
    }

    /** Small diagnostic record on changed conversion state, at most once/second.
     * No road names, route coordinates, phone IDs or maneuver identifiers.
     */
    private static void trace(long now, float exact, float meters, int[] turn, float street, int exitSide, float offset) {
        int[] candidate=ArHudTurns.convert(icon,meters,0,0,0);
        boolean deferred=candidate!=null && candidate[0]==13 && !ArHudTurns.exit(sample.type) && meters>street;
        String state = turn != null ? "native" : ArHudTurns.cueOnly(sample.type) ? "cue_only" : icon==86 ? "generic_exit" :
                deferred ? "street_ar_deferred" : meters > ArHudTurns.GUIDE_METERS ? "far" : "unmapped";
        String key = icon+":"+sample.type+":"+sample.drivingSide+":"+sample.junction+":"+sample.angle+":"+state+":"+(exact>=0)+":"+sample.maneuverState+":"+exitSide;
        if (key.equals(traceKey) || (traceAt >= 0 && now >= traceAt && now-traceAt < 1000)) return;
        traceKey=key;traceAt=now;
        android.util.Log.w("EvergreenAR", "turn icon="+icon+" raw="+sample.type+" traffic="+sample.drivingSide+
                " junction="+sample.junction+" angle="+sample.angle+" angles="+sample.junctionAngles+
                " angle_values="+angles(sample.angles)+" extra="+sample.extra+" state="+sample.state+
                " mstate="+sample.maneuverState+" exact="+(exact>=0)+" meters="+(int)meters+
                " result="+state+" output="+(turn==null?icon:turn[0])+" bar="+(turn==null?0:turn[2])+
                " exit_side="+exitSide+" exit_style="+ArHudTuning.exitStyleName(ArHudTuning.exitStyle(now))+
                " offset="+(int)offset, null);
    }

    /** One worker step; true on the five-second heartbeat, after which run() takes the health sample. */
    static boolean tick(long now, boolean advanced) {
        NavigationCluster staleCluster=null;Object staleOwner=null;Thread quiet=null;
        boolean beat;
        synchronized(ArHudRuntime.class) {
            beat=heartbeatAt<0 || now<heartbeatAt || now-heartbeatAt>=5000;
            if(beat) {
                if(ArHudDebug.on())ArHudDebug.event("worker_alive active="+active+" advanced="+advanced+" input_age="+(now-receivedAt));heartbeatAt=now;
            }
            if (!active) return beat;
            // The phone's callback may land between this tick's clock reading and the lock (the
            // 111 drive: 25 expiries with a negative input age, each blanking the arrow for a
            // second). A sample from the near future is simply fresh.
            if (now < receivedAt) now = receivedAt;
            // A silent phone usually means a stationary car; the route end, a maneuver change,
            // a source change or a disconnect end the lease. HOLD_MS catches a stuck sender, and
            // input that stops while the car is moving ends it once the maneuver is behind the car
            // (the 108 drive: nothing from the phone for nine minutes, 410 m before an exit).
            long passedAt = motion.passedAt();
            boolean passed = passedAt >= 0 && now > passedAt && now - receivedAt >= ArHudMotion.MIN_PASS_MS;
            if (!advanced || now - receivedAt > ArHudIpc.HOLD_MS || passed) {
                if(ArHudDebug.on())ArHudDebug.event("timer_expired advanced="+advanced+" input_age="+(now-receivedAt)+" passed="+passed);
                active = false; motion.reset(); acceptedExactAt = -1;
                ArHudIpc.publish(false, 0, now, now);
                // Nothing will refresh this maneuver any more: do not leave its last frame on the HUD.
                staleCluster=cluster;staleOwner=sample==null?null:sample.owner;
                cluster=null;sentMeters=-1f;shownType=0;hudKey=Integer.MIN_VALUE;
            } else {
                send(now, false);
                if(!quietLogged && now-receivedAt>=QUIET_MS) { quietLogged=true;quiet=inputThread; }
            }
        }
        // Outside the lock: neither the OEM calls nor the thread inspection may hold up the sender.
        if(quiet!=null) ArHudDebug.thread("input_quiet",quiet);
        if(staleCluster!=null) {
            ArHudDebug.event("clear_expired_main");
            try { ArHudForwarder.forward(false,staleCluster,0,0,0,0,0,0,0,0,0); } catch(RuntimeException unavailable) { /* stale display only */ }
        }
        if(staleOwner!=null) { ArHudDebug.event("clear_expired_hud"); ArHudRoute.blank(staleOwner); }
        return beat;
    }

    public void run() {
        while (true) {
            try { Thread.sleep(250); }
            catch (InterruptedException stopped) { synchronized (ArHudRuntime.class) { running = false; reset(); } return; }
            try {
                // The health sample reads /proc after the tick, outside the lock and off the sender's clock.
                if (tick(SystemClock.elapsedRealtime(), ArHudIpc.advanced())) ArHudDebug.health(SystemClock.elapsedRealtime());
            } catch (RuntimeException unavailable) { reset(); }
        }
    }
}
