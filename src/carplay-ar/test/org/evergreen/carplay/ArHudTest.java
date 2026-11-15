package org.evergreen.carplay;

import com.hkmc.cluster.NavigationCluster;
import java.util.Arrays;

public final class ArHudTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    private static void turn(int icon, int type, int angle) {
        check(Arrays.equals(new int[] {type, angle, 40},
                ArHudTurns.convert(icon, 200, 0, 0, 0)), "turn " + icon);
    }

    private static final class RecordingCluster extends NavigationCluster {
        float[] values;
        int calls;
        public void updateTBTInfo(int type, int side, float direction, float distance,
                int error, int combi, int unit, int bar, int barError) {
            values = new float[] {type, side, direction, distance, error, combi, unit, bar, barError};
            calls++;
        }
    }

    public static void main(String[] args) {
        int[] icons = {65,66,67,68,69,70,71,72,73,81,82,144,145,173,174};
        int[] types = {13,13,13,13,13,13,13,31,31,17,18,13,13,25,25};
        int[] angles = {0,45,90,135,225,270,315,270,90,90,270,45,315,90,270};
        for (int i = 0; i < icons.length; i++) turn(icons[i], types[i], angles[i]);
        int[] round = {96,97,99,100,102,104,105,107};
        for (int i = 0; i < round.length; i++) {
            turn(round[i], 32, i * 45);
            turn(round[i] + 96, 33, i * 45);
        }
        int[] untouched = {0,1,2,3,10,13,17,31,32,33,64,74,86,98,101,103,106,112,113,114,172,176,194,2030};
        for (int icon : untouched) check(ArHudTurns.convert(icon, 100, 0, 0, 0) == null, "retain " + icon);
        check(ArHudTurns.convert(67, 457, 0, 0, 0)[2] == 90, "window edge");
        check(ArHudTurns.convert(67, 458, 0, 0, 0) == null, "past window");
        check(ArHudTurns.convert(67, 0, 0, 0, 0)[2] == 10, "zero distance does not invent arrival");
        check(ArHudTurns.convert(67, 229, 0, 0, 0)[2] == 50, "middle bar");
        float[] scales = {1f,1000f,1609.344f,0.3048f,0.9144f};
        for (int unit = 0; unit < scales.length; unit++) {
            check(ArHudTurns.convert(67, 200f / scales[unit], 0, unit, 0)[0] == 13, "unit near " + unit);
            check(ArHudTurns.convert(67, 900f / scales[unit], 0, unit, 0) == null, "unit far " + unit);
        }
        for (float bad : new float[] {-1,Float.NaN,Float.NEGATIVE_INFINITY,Float.POSITIVE_INFINITY,Float.MAX_VALUE})
            check(ArHudTurns.convert(67, bad, 0, 0, 0) == null, "invalid distance");
        check(ArHudTurns.convert(67, 100, 1, 0, 0) == null, "distance error");
        check(ArHudTurns.convert(67, 100, 0, 0, -1) == null, "bar error");
        check(ArHudTurns.convert(67, 100, 0, 5, 0) == null, "unknown unit");

        RecordingCluster cluster = new RecordingCluster();
        float[] original = {67,65535,12,0.1f,0,8,2,7,0};
        ArHudForwarder.forward(false, cluster, 67,65535,12,0.1f,0,8,2,7,0);
        check(Arrays.equals(original, cluster.values), "disabled: every original field preserved");
        ArHudForwarder.forward(true, cluster, 67,65535,12,0.1f,0,8,2,7,0);
        check(Arrays.equals(new float[] {13,0,90,0.1f,0,8,2,30,0}, cluster.values), "enabled: rewrite only AR fields");
        check(cluster.calls == 2, "one OEM call per update");
        ArHudForwarder.forward(true, cluster, 67,65535,12,0.1f,1,8,2,7,0);
        check(Arrays.equals(new float[] {67,65535,12,0.1f,1,8,2,7,0}, cluster.values), "invalid enabled update passes through");
        System.out.println("AR conversion/forwarding: " + checks + " checks passed");
    }
}
