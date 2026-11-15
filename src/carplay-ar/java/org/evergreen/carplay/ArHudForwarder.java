// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

import java.io.File;
import com.hkmc.cluster.NavigationCluster;

/** Replaces only DiPO's updateTBTInfo call; the OEM service remains the sender. */
public final class ArHudForwarder {
    private ArHudForwarder() {}

    public static void update(NavigationCluster cluster, int type, int sideStreet,
            float direction, float distance, int distanceError, int combiSideStreet,
            int unit, int bar, int barError) {
        boolean enabled = false;
        try {
            enabled = new File("/data/nemesis/apps/wireless.carplay.aa/ar-hud.enabled").isFile();
        } catch (RuntimeException unavailable) {
            // A missing/unreadable opt-in must retain the original turn.
        }
        try {
            ArHudSample sample = ArHudRoute.take();
            if (enabled && ArHudRuntime.update(cluster, type, sideStreet, direction, distance,
                    distanceError, combiSideStreet, unit, bar, barError, sample,
                    android.os.SystemClock.elapsedRealtime(), ArHudIpc.advanced())) return;
        } catch (RuntimeException unavailable) {
            ArHudRuntime.reset();
        }
        forward(enabled, cluster, type, sideStreet, direction, distance, distanceError,
                combiSideStreet, unit, bar, barError);
    }

    static void forward(boolean enabled, NavigationCluster cluster, int type, int sideStreet,
            float direction, float distance, int distanceError, int combiSideStreet,
            int unit, int bar, int barError) {
        int[] turn = enabled ? ArHudTurns.convert(type, distance, distanceError, unit, barError) : null;
        if (turn != null) {
            type = turn[0];
            sideStreet = 0;
            direction = turn[1];
            bar = turn[2];
        }
        cluster.updateTBTInfo(type, sideStreet, direction, distance, distanceError,
                combiSideStreet, unit, bar, barError);
    }
}
