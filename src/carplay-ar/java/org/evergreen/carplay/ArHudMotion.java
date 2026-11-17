// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

/** Closing-rate estimate from the phone's exact meters, never vehicle speed.
 * A new maneuver, increasing distance, stale sample or implausible jump removes
 * velocity. The phone's newest distance is always authoritative. The gain
 * scales the rate used between samples: a car approaching a turn is usually
 * slowing, so a full-rate projection overshoots and the next phone sample
 * steps the arrow back.
 */
public final class ArHudMotion {
    private final ArHudDistance distance = new ArHudDistance();
    private float previous = -1f, speed, last, gain = ArHudTuning.DEFAULT_GAIN;
    private long at = -1;
    // A car closing this fast cannot stop without the phone reporting it slowing down: in the
    // recorded drives every stop was preceded by samples below 2 m/s.
    static final float MOVING = 12f;
    static final long MIN_PASS_MS = 20000;

    public void reset() { previous = -1f; speed = 0f; last = 0f; at = -1; distance.reset(); }

    /** Fraction of the measured closing rate to project with, within the tuning range. */
    public void gain(float value) {
        if (!Float.isNaN(value) && value >= ArHudTuning.MIN_GAIN && value <= ArHudTuning.MAX_GAIN) gain = value;
    }

    public void sample(float meters, long now) {
        if (ArHudTurns.meters(meters, 0) < 0f || now < 0) { reset(); return; }
        if (at >= 0 && now <= at) return;
        long elapsed = now - at;
        float delta = previous - meters;
        float rate = elapsed > 0 ? delta * 1000f / elapsed : 0f;
        if (at >= 0 && elapsed >= 200 && elapsed <= 3000 && delta > 0f && rate <= 90f) {
            speed = speed > 0f ? (speed + rate) / 2f : rate;
            last = rate;
        } else {
            speed = 0f; last = 0f;
        }
        previous = meters;
        at = now;
        float projected = speed * gain;
        distance.sample(meters, projected > 0f ? meters / projected : 0f, now);
    }

    public float meters(long now) { return distance.meters(now); }

    /** Time after which a car that was moving at the last sample has passed the maneuver
     * (twice the time it needed, plus ten seconds); -1 when it may simply have stopped. */
    public long passedAt() {
        if (at < 0 || previous < 0f || speed < MOVING || last < MOVING) return -1;
        return at + Math.max(MIN_PASS_MS, (long) (2000f * previous / speed) + 10000);
    }
}
