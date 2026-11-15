// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

/** Immutable metadata snapshot; contains no road names or route coordinates. */
public final class ArHudSample {
    public final Object owner;
    public final int component, maneuver, type, drivingSide, junction, angle;
    public final float exactMeters;
    public final long exactAt;
    public final boolean guiding;

    public ArHudSample(Object owner, int component, int maneuver, int type,
            int drivingSide, int junction, int angle, float exactMeters,
            long exactAt, boolean guiding) {
        this.owner = owner; this.component = component; this.maneuver = maneuver;
        this.type = type; this.drivingSide = drivingSide; this.junction = junction;
        this.angle = angle; this.exactMeters = exactMeters; this.exactAt = exactAt;
        this.guiding = guiding;
    }
}
