// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

/** Closing-rate estimate from the phone's exact meters, never vehicle speed.
 * A new maneuver, increasing distance, stale sample or implausible jump removes
 * velocity. The phone's newest distance is always authoritative.
 */
public final class ArHudMotion {
    private final ArHudDistance distance = new ArHudDistance();
    private float previous = -1f, speed;
    private long at = -1;

    public void reset() { previous = -1f; speed = 0f; at = -1; distance.reset(); }

    public void sample(float meters, long now) {
        if (ArHudTurns.meters(meters, 0) < 0f || now < 0) { reset(); return; }
        if (at >= 0 && now <= at) return;
        long elapsed = now - at;
        float delta = previous - meters;
        float rate = elapsed > 0 ? delta * 1000f / elapsed : 0f;
        if (at >= 0 && elapsed >= 200 && elapsed <= 3000 && delta > 0f && rate <= 90f) {
            speed = speed > 0f ? (speed + rate) / 2f : rate;
        } else {
            speed = 0f;
        }
        previous = meters;
        at = now;
        distance.sample(meters, speed > 0f ? meters / speed : 0f, now);
    }

    public float meters(long now) { return distance.meters(now); }
}
