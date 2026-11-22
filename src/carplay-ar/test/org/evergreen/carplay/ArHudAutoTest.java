package org.evergreen.carplay;

import com.hkmc.cluster.NavigationCluster;
import java.io.File;
import java.io.FileWriter;

/** Android Auto (GPM) turns as native AR types: mapping, windows, glide, expiry, pass-through. */
public final class ArHudAutoTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    static final class Cluster extends NavigationCluster {
        int calls, type, side, unit, bar; float direction, distance;
        public void updateTBTInfo(int t, int s, float d, float dist, int e, int c, int u, int b, int be) {
            calls++; type = t; side = s; direction = d; distance = dist; unit = u; bar = b;
        }
    }
    static void write(File f, String text) throws Exception { FileWriter w = new FileWriter(f); try { w.write(text); } finally { w.close(); } }
    static int[] map(int event, int side, int icon, float meters) {
        return ArHudAuto.map(event, side, icon, meters, 1, true);
    }

    public static void main(String[] args) throws Exception {
        // Mapping through the CarPlay table, US right-hand traffic.
        check(map(ArHudAuto.TURN, ArHudAuto.RIGHT, 67, 100)[0] == 13 && map(ArHudAuto.TURN, ArHudAuto.RIGHT, 67, 100)[1] == 90, "right turn");
        check(map(ArHudAuto.TURN, ArHudAuto.LEFT, 70, 100)[1] == 270, "left turn");
        check(map(ArHudAuto.SLIGHT, ArHudAuto.RIGHT, 66, 100)[1] == 45 && map(ArHudAuto.SLIGHT, ArHudAuto.LEFT, 71, 100)[1] == 315, "slight turns");
        check(map(ArHudAuto.SHARP, ArHudAuto.RIGHT, 68, 100)[1] == 135 && map(ArHudAuto.SHARP, ArHudAuto.LEFT, 69, 100)[1] == 225, "sharp turns");
        check(map(ArHudAuto.STRAIGHT, ArHudAuto.UNSPECIFIED, 65, 100)[0] == 13 && map(ArHudAuto.STRAIGHT, ArHudAuto.UNSPECIFIED, 65, 100)[1] == 0, "straight");
        check(map(ArHudAuto.U_TURN, ArHudAuto.LEFT, 72, 100)[0] == 31 && map(ArHudAuto.U_TURN, ArHudAuto.LEFT, 72, 100)[1] == 270, "U-turn to the left");
        check(map(ArHudAuto.U_TURN, ArHudAuto.RIGHT, 73, 100)[1] == 90, "U-turn to the right");
        check(map(ArHudAuto.FORK, ArHudAuto.LEFT, 174, 300)[0] == 25 && map(ArHudAuto.FORK, ArHudAuto.LEFT, 174, 300)[1] == 270, "fork left");
        check(map(ArHudAuto.FORK, ArHudAuto.RIGHT, 173, 300)[1] == 90, "fork right");
        check(map(ArHudAuto.OFF_RAMP, ArHudAuto.RIGHT, 66, 400)[0] == 17 && map(ArHudAuto.OFF_RAMP, ArHudAuto.LEFT, 71, 400)[0] == 18, "sided exits: native exit signs");
        check(ArHudAuto.map(ArHudAuto.OFF_RAMP, ArHudAuto.RIGHT, 66, 400, 1, false)[0] == 13
                && ArHudAuto.map(ArHudAuto.OFF_RAMP, ArHudAuto.RIGHT, 66, 400, 1, false)[1] == 45, "exit_style=slight: a slight-turn arrow");
        check(map(ArHudAuto.OFF_RAMP, ArHudAuto.UNSPECIFIED, 66, 400)[0] == 17, "an exit without a side uses the chosen side");
        check(map(ArHudAuto.ROUNDABOUT_ENTER_AND_EXIT, ArHudAuto.RIGHT, 99, 100)[0] == 32 && map(ArHudAuto.ROUNDABOUT_ENTER_AND_EXIT, ArHudAuto.RIGHT, 99, 100)[1] == 90,
                "roundabout from GPM's exit icon");
        check(map(ArHudAuto.ROUNDABOUT_ENTER, ArHudAuto.RIGHT, 0x40, 100) == null, "roundabout without a documented exit icon keeps GPM's frame");
        for (int kept : new int[] {ArHudAuto.DEPART, ArHudAuto.NAME_CHANGE, ArHudAuto.ON_RAMP, ArHudAuto.MERGE, ArHudAuto.DESTINATION, 0, 16, 17})
            check(map(kept, ArHudAuto.RIGHT, 67, 100) == null, "no native type for event " + kept);
        check(map(ArHudAuto.TURN, ArHudAuto.UNSPECIFIED, 67, 100) == null, "a turn without a side keeps the icon");
        check(map(ArHudAuto.FORK, ArHudAuto.RIGHT, 173, 500) == null, "nothing beyond 457 m");

        File tuning = File.createTempFile("evergreen-ar-", "-tuning");
        try {
            ArHudTuning.path = tuning.getPath(); write(tuning, ""); ArHudTuning.reload();
            ArHudAuto.background = false; ArHudAuto.testFlags = 2; ArHudAuto.reset();
            Cluster c = new Cluster();
            // Far: GPM's icon frame goes through unchanged (unit 3 = feet).
            android.os.SystemClock.now = 1000;
            ArHudAuto.turn(ArHudAuto.RIGHT, ArHudAuto.TURN, 0, 0);
            ArHudAuto.distance(600, 1970000, 6);
            ArHudAuto.update(c, 67, 65535, 0f, 1970f, 0, 0, 3, 0, 0);
            check(c.calls == 1 && c.type == 67 && c.side == 65535 && c.distance == 1970f, "far turn: GPM's frame unchanged");
            check(ArHudAuto.shown() == 0, "nothing of ours shown far away");
            // Inside the 200 m street window: native TURN, side streets 0, exact distance in GPM's unit, bar.
            android.os.SystemClock.now = 20000; ArHudAuto.distance(180, 590000, 6);
            ArHudAuto.update(c, 67, 65535, 0f, 590f, 0, 0, 3, 0, 0);
            check(c.calls == 2 && c.type == 13 && c.direction == 90f && c.side == 0, "street turn near: native TURN, side streets 0");
            check(Math.abs(c.distance - 180f / 0.3048f) < 0.5f && c.unit == 3, "exact meters in the frame's own unit");
            check(c.bar == 30, "bar on the 457 m scale (180 m: 30)");
            // Glide between Android Auto's updates.
            android.os.SystemClock.now = 21000; ArHudAuto.distance(165, 540000, 6);
            ArHudAuto.update(c, 67, 65535, 0f, 540f, 0, 0, 3, 0, 0);
            int sent = c.calls;
            ArHudAuto.tick(21250, true);
            check(c.calls == sent + 1 && c.distance < 165f / 0.3048f && c.distance > 150f / 0.3048f, "the 4 Hz worker glides toward the turn");
            ArHudAuto.tick(21250, false);
            check(c.calls == sent + 1, "no glide without advanced mode");
            // GPM's own extra events never change the maneuver.
            ArHudAuto.turn(3, 0x40, 0, 0); ArHudAuto.turn(0, 0x22, 0, 0);
            android.os.SystemClock.now = 21500; ArHudAuto.update(c, 67, 65535, 0f, 520f, 0, 0, 3, 0, 0);
            check(c.type == 13, "lanes and the ahead list are ignored");
            // A clear from GPM (releaseGuidance) goes through and ends our frame.
            ArHudAuto.update(c, 0, 0, 0f, 0f, -1, 0, 0, 0, -1);
            check(c.type == 0 && ArHudAuto.shown() == 0, "GPM's clear passes and stops ours");
            // Exits use the full 457 m window and the native sign.
            ArHudAuto.turn(ArHudAuto.RIGHT, ArHudAuto.OFF_RAMP, 0, 0);
            android.os.SystemClock.now = 40000; ArHudAuto.distance(420, 260000, 4);
            ArHudAuto.update(c, 66, 65535, 0f, 0.3f, 0, 0, 2, 0, 0);
            check(c.type == 17 && c.direction == 90f && c.bar == 90, "exit at 420 m: native right exit sign, bar 90");
            check(Math.abs(c.distance - 420f / 1609.344f) < 0.001f && c.unit == 2, "miles in the frame's unit");
            // A new maneuver resets the glide; a distance jump (reroute) too.
            ArHudAuto.turn(ArHudAuto.LEFT, ArHudAuto.TURN, 0, 0);
            android.os.SystemClock.now = 41000; ArHudAuto.distance(900, 900000, 2);
            ArHudAuto.update(c, 70, 65535, 0f, 0.6f, 0, 0, 2, 0, 0);
            check(c.type == 70, "new far maneuver: GPM's icon again");
            // Expiry: moving fast, then silence: cleared once the maneuver is behind the car.
            ArHudAuto.turn(ArHudAuto.RIGHT, ArHudAuto.FORK, 0, 0);
            android.os.SystemClock.now = 50000; ArHudAuto.distance(400, 0, 2);
            android.os.SystemClock.now = 51000; ArHudAuto.distance(380, 0, 2);
            android.os.SystemClock.now = 52000; ArHudAuto.distance(360, 0, 2);
            ArHudAuto.update(c, 173, 65535, 0f, 0.2f, 0, 0, 2, 0, 0);
            check(c.type == 25 && ArHudAuto.shown() == 25, "fork shown");
            sent = c.calls; ArHudAuto.tick(60000, true);
            check(ArHudAuto.shown() == 25, "a short silence keeps it");
            ArHudAuto.tick(200000, true);
            check(ArHudAuto.shown() == 0 && c.type == 0 && c.calls > sent, "silence after the car was moving: cleared");
            // Status 2 (guidance ended) stops ours; GPM clears the cluster itself.
            ArHudAuto.turn(ArHudAuto.LEFT, ArHudAuto.TURN, 0, 0);
            android.os.SystemClock.now = 300000; ArHudAuto.distance(100, 0, 2);
            ArHudAuto.update(c, 70, 65535, 0f, 0.1f, 0, 0, 2, 0, 0);
            check(ArHudAuto.shown() == 13, "left turn shown");
            ArHudAuto.status(2);
            check(ArHudAuto.shown() == 0, "status inactive ends ours");
            // Off: the opt-in, or android_auto=off.
            ArHudAuto.testFlags = 0; ArHudAuto.reset(); ArHudAuto.turn(ArHudAuto.RIGHT, ArHudAuto.TURN, 0, 0);
            android.os.SystemClock.now = 400000; ArHudAuto.distance(100, 0, 2);
            ArHudAuto.update(c, 67, 65535, 0f, 0.1f, 0, 0, 2, 0, 0);
            check(c.type == 67 && c.side == 65535, "AR disabled: GPM's frame");
            ArHudAuto.testFlags = 2; write(tuning, "android_auto=off\n"); ArHudTuning.reload();
            ArHudAuto.update(c, 67, 65535, 0f, 0.1f, 0, 0, 2, 0, 0);
            check(c.type == 67, "android_auto=off: GPM's frame");
            check(ArHudTuning.parse("android_auto=off\n")[9] == 0f && ArHudTuning.parse("android_auto=on\n")[9] == 1f
                    && ArHudTuning.parse("android_auto=maybe\n")[9] == 1f, "android_auto tuning");
            // Without an exact distance yet: GPM's display distance (basic icon conversion before any event).
            write(tuning, ""); ArHudTuning.reload(); ArHudAuto.reset(); ArHudAuto.testFlags = 1;
            android.os.SystemClock.now = 500000;
            ArHudAuto.update(c, 173, 65535, 0f, 0.2f, 0, 0, 2, 0, 0);
            check(c.type == 25, "no Android Auto event seen yet: GPM's icon converted near the maneuver");
            ArHudAuto.update(c, 67, 65535, 0f, 999f, 0, 0, 7, 0, 0);
            check(c.type == 67, "unknown unit: GPM's frame");
        } finally {
            tuning.delete(); ArHudTuning.path = null; ArHudTuning.reload(); ArHudAuto.testFlags = -1; ArHudAuto.reset();
            android.os.SystemClock.now = -1;
        }
        System.out.println("AR Android Auto: " + checks + " checks passed");
    }
}
