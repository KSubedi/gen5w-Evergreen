// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

/** Immutable metadata snapshot; contains no road names or route coordinates. */
public final class ArHudSample {
    /** Phone route-guidance states seen on the US EV6: 1 guiding, 2 arrived, 3 loading, 5 rerouting, 6 proceed to route. */
    public static final int STATE_REROUTING = 5;
    public final Object owner;
    public final int component, maneuver, type, drivingSide, junction, angle;
    public final float exactMeters;
    public final long exactAt;
    public final boolean guiding;
    /** Route guidance state, the phone's maneuver state (-1 unknown) and the junction angle count (-1 unknown). */
    public final int state, maneuverState, junctionAngles;
    /** Diagnostic copies of the phone's junction element angles (null unknown) and its item 12 (-1 unknown). */
    public final int[] angles;
    public final int extra;

    public ArHudSample(Object owner, int component, int maneuver, int type,
            int drivingSide, int junction, int angle, float exactMeters,
            long exactAt, boolean guiding) {
        this(owner, component, maneuver, type, drivingSide, junction, angle, exactMeters, exactAt, guiding, 1, -1, -1);
    }

    public ArHudSample(Object owner, int component, int maneuver, int type,
            int drivingSide, int junction, int angle, float exactMeters,
            long exactAt, boolean guiding, int state, int maneuverState, int junctionAngles) {
        this(owner, component, maneuver, type, drivingSide, junction, angle, exactMeters, exactAt, guiding,
                state, maneuverState, junctionAngles, null, -1);
    }

    public ArHudSample(Object owner, int component, int maneuver, int type,
            int drivingSide, int junction, int angle, float exactMeters,
            long exactAt, boolean guiding, int state, int maneuverState, int junctionAngles,
            int[] angles, int extra) {
        this.owner = owner; this.component = component; this.maneuver = maneuver;
        this.type = type; this.drivingSide = drivingSide; this.junction = junction;
        this.angle = angle; this.exactMeters = exactMeters; this.exactAt = exactAt;
        this.guiding = guiding; this.state = state; this.maneuverState = maneuverState;
        this.junctionAngles = junctionAngles;
        this.angles = angles == null ? null : (int[]) angles.clone(); this.extra = extra;
    }
}
