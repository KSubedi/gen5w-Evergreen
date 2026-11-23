// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

import android.os.SystemClock;
import com.hkmc.cluster.NavigationCluster;

/** Android Auto turns as the car's own AR types, in GPM (the head unit's Android Auto app).
 *
 * GPM's turn manager draws every Android Auto turn as a projection icon, which the cluster
 * and the HUD's list show but the AR HUD does not animate. Near a maneuver this sends the
 * native type instead (TURN with its angle, U_TURN, FORK, the exit signs, roundabouts),
 * side streets 0 and the bar, at Android Auto's exact distance, gliding between its updates
 * as the CarPlay path does. Farther away, and for anything without a native type (on-ramps,
 * merges, ferries, destinations), GPM's own frame goes through unchanged.
 *
 * Inputs, from entry hooks on GPM's GpmNaviTBTManager: onNextTurn (side, event, angle,
 * exit number; GPM's own extra events 0x20 and up are ignored), onNextTurnDistance (exact
 * meters, display distance x1000, display unit) and onStatus (1 active, 2 inactive, 3
 * rerouting); GPM's updateTBTInfo calls come here instead of the cluster. Numbers only in
 * the trace: never road names.
 */
public final class ArHudAuto implements Runnable {
    /** Android Auto's NextTurnEnum and TurnSide, as the protocol numbers them. */
    public static final int DEPART = 1, NAME_CHANGE = 2, SLIGHT = 3, TURN = 4, SHARP = 5, U_TURN = 6, ON_RAMP = 7,
            OFF_RAMP = 8, FORK = 9, MERGE = 10, ROUNDABOUT_ENTER = 11, ROUNDABOUT_EXIT = 12, ROUNDABOUT_ENTER_AND_EXIT = 13,
            STRAIGHT = 14, DESTINATION = 19;
    public static final int LEFT = 1, RIGHT = 2, UNSPECIFIED = 3;
    /** GPM's own multiplexed events (destination, lanes, the ahead list) start here. */
    static final int GPM_EXTRA = 0x20;
    static final long FRESH_MS = 5000, GLIDE_MS = 250;
    /** A distance this much larger than the last one is a new maneuver or a reroute, not noise. */
    static final float NEW_MANEUVER_METERS = 50f;
    /** A maneuver gets AR only once the phone has put it at least this far ahead. Google Maps reports
     * 0 m for a new maneuver until its distance arrives, and 0 m for minutes for a "continue" with no
     * distance; drawn as AR, both looked like a turn right now. */
    static final float APPROACH_METERS = 10f;
    private static final ArHudMotion motion = new ArHudMotion();
    private static int event = -1, side, angle, number, exactMeters = -1;
    private static long distanceAt = -1, receivedAt = -1, heartbeatAt = -1, traceAt = -1;
    private static NavigationCluster cluster;
    private static int icon, originalSide, originalError, originalCombi, unit, originalBar, originalBarError;
    private static float originalDirection, originalDistance, sentMeters = -1f;
    private static boolean active, running;
    /** This maneuver: a real distance seen (APPROACH_METERS); its street AR already started. */
    private static boolean approached, streetStarted;
    private static int shownType;
    private static String traceKey;
    static boolean background = true; // Host tests drive tick() deterministically.
    /** Host tests: -1 reads the opt-in files; 0 off, 1 basic, 2 advanced. */
    static int testFlags = -1;

    private ArHudAuto() {}

    /** GPM's onNextTurn entry: the next maneuver (the road name stays in GPM). */
    public static void turn(int turnSide, int turnEvent, int turnAngle, int turnNumber) {
        if (turnEvent >= GPM_EXTRA || turnEvent < 0) return;
        synchronized (ArHudAuto.class) {
            if (turnEvent == event && turnSide == side && turnAngle == angle && turnNumber == number) return;
            event = turnEvent; side = turnSide; angle = turnAngle; number = turnNumber;
            motion.reset(); sentMeters = -1f;
            // The distance so far belongs to the previous maneuver.
            exactMeters = -1; distanceAt = -1; approached = false; streetStarted = false;
            ArHudDebug.event("auto_turn event=" + turnEvent + " side=" + turnSide + " angle=" + turnAngle + " number=" + turnNumber);
        }
    }

    /** GPM's onNextTurnDistance entry: Android Auto's exact meters to the maneuver. */
    public static void distance(int meters, int displayE3, int displayUnit) {
        long now = SystemClock.elapsedRealtime();
        synchronized (ArHudAuto.class) {
            if (ArHudDebug.on()) ArHudDebug.event("auto_distance meters=" + meters + " e3=" + displayE3 + " unit=" + displayUnit);
            if (meters < 0) { exactMeters = -1; distanceAt = -1; motion.reset(); return; }
            if (exactMeters >= 0 && meters > exactMeters + NEW_MANEUVER_METERS) { motion.reset(); sentMeters = -1f; streetStarted = false; }
            if (meters >= APPROACH_METERS) approached = true;
            motion.gain(ArHudTuning.gain(now));
            motion.sample(meters, now);
            exactMeters = meters; distanceAt = now;
        }
    }

    /** GPM's onStatus entry: 1 active, 2 inactive, 3 rerouting. */
    public static void status(int status) {
        long now = SystemClock.elapsedRealtime();
        synchronized (ArHudAuto.class) {
            ArHudDebug.event("auto_status status=" + status);
            if (status == 2) stop(now);
            else if (status == 3) { motion.reset(); sentMeters = -1f; streetStarted = false; }
        }
    }

    /** Every GPM call to updateTBTInfo lands here. */
    public static void update(NavigationCluster target, int type, int sideStreet, float direction, float distance,
            int distanceError, int combiSideStreet, int frameUnit, int bar, int barError) {
        long now = SystemClock.elapsedRealtime();
        boolean enabled = false, advanced = false;
        try {
            enabled = (testFlags < 0 ? ArHudIpc.enabled() : testFlags > 0) && ArHudTuning.androidAuto(now);
            advanced = testFlags < 0 ? ArHudIpc.advanced() : testFlags == 2;
        } catch (RuntimeException unavailable) { /* a missing opt-in keeps GPM's frame */ }
        ArHudDebug.watch();
        synchronized (ArHudAuto.class) {
            cluster = target; icon = type; originalSide = sideStreet; originalDirection = direction;
            originalDistance = distance; originalError = distanceError; originalCombi = combiSideStreet;
            unit = frameUnit; originalBar = bar; originalBarError = barError; receivedAt = now;
            if (ArHudDebug.on()) ArHudDebug.event("auto_input icon=" + type + " distance=" + distance + " unit=" + frameUnit
                    + " bar=" + bar + " enabled=" + enabled + " advanced=" + advanced);
            if (enabled && type != 0) {
                float meters = meters(now, advanced);
                int[] turn = convert(meters, now);
                trace(now, meters, turn);
                if (turn != null && send(now, turn, meters, true)) { start(); return; }
            }
            if (active) { active = false; shownType = 0; ArHudIpc.publish(false, 0, now, now); }
        }
        ArHudForwarder.send(target, type, sideStreet, direction, distance, distanceError, combiSideStreet, frameUnit, bar, barError);
        if (ArHudDebug.on()) ArHudDebug.event("auto_passed icon=" + type + " distance=" + distance + " unit=" + frameUnit);
    }

    /** Exact (gliding when advanced) while fresh, else GPM's display distance. */
    private static float meters(long now, boolean glide) {
        float m = -1f;
        if (distanceAt >= 0 && now >= distanceAt && now - distanceAt <= FRESH_MS && exactMeters >= 0) {
            m = glide ? motion.meters(now) : exactMeters;
            // A phone update slightly above the glided distance would step the arrow back: hold.
            if (m >= 0f && sentMeters >= 0f && m > sentMeters && m - sentMeters <= ArHudTuning.backstepMeters(now)) m = sentMeters;
        }
        return m >= 0f ? m : ArHudTurns.meters(originalDistance, unit);
    }

    /** {native type, direction, bar} for the current maneuver at these meters, or null for GPM's frame. */
    static int[] convert(float meters, long now) {
        if (meters < 0f || ArHudTurns.fromMeters(meters, unit) < 0f) return null;
        if (event >= 0 && !approached) return null;
        int exitSide = ArHudTurns.exitSide(8, 0, 0, ArHudTuning.exitSide(now));
        int[] turn = event < 0 ? ArHudTurns.convert(icon, ArHudTurns.fromMeters(meters, unit), 0, unit, 0)
                : map(event, side, icon, meters, exitSide, ArHudTuning.exitStyle(now) == ArHudTuning.EXIT_STYLE_SIGN);
        if (turn == null) return null;
        if (turn[0] == 13 && event != OFF_RAMP) {
            // Street turns start inside the street window; the bar keeps its own scale (as for CarPlay).
            // At speed the window grows to street_ar_seconds ahead (up to 457 m); once started it stays.
            if (!streetStarted && meters > streetWindow(now)) return null;
            streetStarted = true;
            turn[2] = ArHudTurns.bar(meters, ArHudTuning.barMeters(now));
        }
        return turn;
    }

    /** Where street AR starts: street_ar_meters, or street_ar_seconds at the closing speed if farther. */
    static float streetWindow(long now) {
        float window = ArHudTuning.streetArMeters(now), lead = motion.speed() * ArHudTuning.streetArSeconds(now);
        return Math.max(window, Math.min(lead, ArHudTurns.GUIDE_METERS));
    }

    /** Android Auto's maneuver as the cluster's native type, through the CarPlay table (US right-hand traffic). */
    static int[] map(int turnEvent, int turnSide, int turnIcon, float meters, int exitSide, boolean exitSign) {
        int raw;
        switch (turnEvent) {
            case TURN: raw = turnSide == LEFT ? 1 : turnSide == RIGHT ? 2 : 0; break;
            case SLIGHT: raw = turnSide == LEFT ? 49 : turnSide == RIGHT ? 50 : 0; break;
            case SHARP: raw = turnSide == LEFT ? 47 : turnSide == RIGHT ? 48 : 0; break;
            case STRAIGHT: raw = 3; break;
            case FORK: raw = turnSide == LEFT ? 13 : turnSide == RIGHT ? 14 : 0; break;
            case OFF_RAMP: raw = turnSide == LEFT ? 22 : turnSide == RIGHT ? 23 : 8; break;
            case U_TURN:
                // A U-turn to the left is right-hand traffic's; to the right, left-hand traffic's.
                return turnSide == LEFT || turnSide == RIGHT
                        ? ArHudTurns.maneuver(4, turnSide == RIGHT ? 1 : 0, 0, 0, false, turnIcon, meters, 1) : null;
            case ROUNDABOUT_ENTER: case ROUNDABOUT_EXIT: case ROUNDABOUT_ENTER_AND_EXIT: {
                // GPM already picked the roundabout icon from the exit angle; only its documented exits.
                int[] round = ArHudTurns.convert(turnIcon, meters, 0, 0, 0);
                return round != null && (round[0] == 32 || round[0] == 33) ? round : null;
            }
            default: return null;
        }
        if (raw == 0) return null;
        return ArHudTurns.maneuver(raw, 0, 0, 0, false, turnIcon, meters, 1, exitSide, exitSign);
    }

    private static boolean send(long now, int[] turn, float meters, boolean force) {
        float distance = ArHudTurns.fromMeters(meters + ArHudTuning.turnOffsetMeters(now), unit);
        if (distance < 0f || cluster == null) return false;
        ArHudForwarder.send(cluster, turn[0], 0, turn[1], distance, 0, originalCombi, unit, turn[2], 0);
        boolean exact = distanceAt >= 0 && now - distanceAt <= FRESH_MS;
        if (ArHudDebug.on()) ArHudDebug.event("auto_sent type=" + turn[0] + " dir=" + turn[1] + " meters=" + meters
                + " exact=" + exact + " bar=" + turn[2] + " force=" + force);
        active = true; sentMeters = meters; shownType = turn[0];
        ArHudIpc.publish(true, turn[2], exact ? distanceAt : now, now);
        return true;
    }

    /** Guidance ended or lost: drop our frame; GPM sends its own clear. */
    private static void stop(long now) {
        if (active) ArHudDebug.event("auto_stopped type=" + shownType);
        active = false; shownType = 0; sentMeters = -1f; event = -1; exactMeters = -1; distanceAt = -1;
        approached = false; streetStarted = false; motion.reset();
        ArHudIpc.publish(false, 0, now, now);
    }

    /** Once a second when the conversion changes: what came in and what went out. */
    private static void trace(long now, float meters, int[] turn) {
        String result = turn != null ? "native" : event >= 0 && !approached ? "no_distance" : event >= 0 && map(event, side, icon, ArHudTurns.GUIDE_METERS / 2f, 0, true) == null
                ? "kept" : meters > ArHudTurns.GUIDE_METERS ? "far" : "street_ar_deferred";
        String key = event + ":" + side + ":" + angle + ":" + icon + ":" + result;
        // A change at most once a second; an unchanged conversion every ten seconds.
        if (traceAt >= 0 && now >= traceAt && now - traceAt < 1000) return;
        if (key.equals(traceKey) && traceAt >= 0 && now - traceAt < 10000) return;
        traceKey = key; traceAt = now;
        android.util.Log.w("EvergreenAR", "aaturn event=" + event + " side=" + side + " angle=" + angle + " number=" + number
                + " icon=" + icon + " exact=" + (distanceAt >= 0 && now - distanceAt <= FRESH_MS) + " meters=" + (int) meters
                + " result=" + result + " output=" + (turn == null ? icon : turn[0]) + " dir=" + (turn == null ? 0 : turn[1])
                + " bar=" + (turn == null ? 0 : turn[2]) + " unit=" + unit, null);
    }

    /** The worker: glide between Android Auto's updates, expire lost guidance; true on the 5 s heartbeat. */
    static boolean tick(long now, boolean advanced) {
        NavigationCluster stale = null;
        boolean beat;
        synchronized (ArHudAuto.class) {
            beat = heartbeatAt < 0 || now < heartbeatAt || now - heartbeatAt >= 5000;
            if (beat) {
                heartbeatAt = now;
                if (ArHudDebug.on()) ArHudDebug.event("auto_alive active=" + active + " input_age=" + (receivedAt < 0 ? -1 : now - receivedAt)
                        + " distance_age=" + (distanceAt < 0 ? -1 : now - distanceAt));
            }
            if (!active) return beat;
            long passedAt = motion.passedAt();
            boolean passed = passedAt >= 0 && now > passedAt && distanceAt >= 0 && now - distanceAt >= ArHudMotion.MIN_PASS_MS;
            if (now < receivedAt) now = receivedAt;
            if (now - receivedAt > ArHudIpc.HOLD_MS || passed) {
                ArHudDebug.event("auto_expired input_age=" + (now - receivedAt) + " passed=" + passed);
                stale = cluster; active = false; shownType = 0; sentMeters = -1f; motion.reset();
                ArHudIpc.publish(false, 0, now, now);
            } else if (advanced && distanceAt >= 0 && now - distanceAt <= FRESH_MS) {
                float m = meters(now, true);
                if (Math.abs(m - sentMeters) >= 0.05f) {
                    int[] turn = convert(m, now);
                    if (turn != null) send(now, turn, m, false);
                }
            }
        }
        if (stale != null) {
            ArHudDebug.event("auto_clear_expired");
            try { ArHudForwarder.send(stale, 0, 0, 0f, 0f, 0, 0, 0, 0, 0); } catch (RuntimeException unavailable) { /* stale display only */ }
        }
        return beat;
    }

    private static void start() {
        if (running || !background) return;
        running = true;
        Thread t = new Thread(new ArHudAuto(), "Evergreen-AR-auto");
        t.setDaemon(true);
        t.start();
    }

    public void run() {
        while (true) {
            try { Thread.sleep(GLIDE_MS); }
            catch (InterruptedException stopped) { synchronized (ArHudAuto.class) { running = false; } return; }
            try {
                boolean advanced = false;
                try { advanced = ArHudIpc.advanced(); } catch (RuntimeException unavailable) { /* no glide */ }
                if (tick(SystemClock.elapsedRealtime(), advanced)) ArHudDebug.health(SystemClock.elapsedRealtime());
            } catch (RuntimeException unavailable) { /* next tick */ }
        }
    }

    /** Host tests. */
    static synchronized void reset() {
        event = -1; side = 0; angle = 0; number = 0; exactMeters = -1; distanceAt = -1; receivedAt = -1; heartbeatAt = -1;
        traceAt = -1; traceKey = null; cluster = null; active = false; shownType = 0; sentMeters = -1f; motion.reset();
        approached = false; streetStarted = false;
    }
    static synchronized int shown() { return active ? shownType : 0; }
}
