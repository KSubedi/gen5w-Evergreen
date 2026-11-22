// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

import com.hkmc.cluster.NavigationCluster;

/** Replaces only DiPO's updateTBTInfo call; the OEM service remains the sender. */
public final class ArHudForwarder {
    private ArHudForwarder() {}
    /** The cluster and type of DiPO's latest main frame: what a clear has to undo. */
    static volatile NavigationCluster lastCluster;
    static volatile int lastType;

    public static void update(NavigationCluster cluster, int type, int sideStreet,
            float direction, float distance, int distanceError, int combiSideStreet,
            int unit, int bar, int barError) {
        lastCluster = cluster; lastType = type;
        boolean enabled = false;
        try {
            enabled = ArHudIpc.enabled();
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
        ArHudRuntime.mainSent();
    }

    /** The Android Auto adapter's frames, exactly as given. */
    static void send(NavigationCluster cluster, int type, int sideStreet, float direction, float distance,
            int distanceError, int combiSideStreet, int unit, int bar, int barError) {
        cluster.updateTBTInfo(type, sideStreet, direction, distance, distanceError, combiSideStreet, unit, bar, barError);
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
        if (ArHudDebug.on()) ArHudDebug.event("fallback_send enabled="+enabled+" type="+type+" distance="+distance+" unit="+unit);
        cluster.updateTBTInfo(type, sideStreet, direction, distance, distanceError,
                combiSideStreet, unit, bar, barError);
        ArHudDebug.event("fallback_sent");
    }
}
