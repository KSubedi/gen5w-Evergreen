// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

/** Bounded distance interpolation for the future exact-metadata adapter.
 * No timers, Android dependencies, speed sensors or display writes. The caller
 * supplies monotonic milliseconds and must reset on route/source/maneuver change.
 */
public final class ArHudDistance {
    public static final long MAX_EXTRAPOLATION_MS = 1500;
    private float distance = -1f, metersPerSecond;
    private long sampledAt;

    public void reset() {
        distance = -1f;
        metersPerSecond = 0f;
        sampledAt = 0;
    }

    public void sample(float meters, float secondsToTurn, long now) {
        if (now < 0 || ArHudTurns.meters(meters, 0) < 0f) {
            reset();
            return;
        }
        // Out-of-order callbacks must not rewind an already accepted sample.
        if (distance >= 0f && now < sampledAt) return;
        distance = meters;
        sampledAt = now;
        metersPerSecond = 0f;
        if (!Float.isNaN(secondsToTurn) && !Float.isInfinite(secondsToTurn) && secondsToTurn > 0f) {
            float speed = meters / secondsToTurn;
            if (!Float.isInfinite(speed)) metersPerSecond = speed;
        }
    }

    public float meters(long now) {
        if (distance < 0f || now < sampledAt) return -1f;
        long elapsed = Math.min(now - sampledAt, MAX_EXTRAPOLATION_MS);
        return Math.max(0f, distance - metersPerSecond * (elapsed / 1000f));
    }
}
