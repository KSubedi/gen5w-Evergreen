// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

/** Stateless conversion of the existing CarPlay projection icons to OEM AR turns.
 * Protocol values: Open-HMG's docs/cluster-turns.md and hwjang1's analysis section 47.
 * No phone parser, Android service, raw CAN, or HMG-API runtime is used here.
 */
public final class ArHudTurns {
    public static final float GUIDE_METERS = 457f;

    private ArHudTurns() {}

    /** Returns {native type, angle, bar}, or null to retain every original field.
     * Units are NavigationCluster units, not iAP2 units. Distances here are the
     * projection app's display distances: rounded, not exact route geometry.
     */
    public static int[] convert(int icon, float distance, int distanceError,
            int unit, int barError) {
        if (distanceError != 0 || barError != 0 || Float.isNaN(distance)
                || Float.isInfinite(distance) || distance < 0f) return null;
        float meters = meters(distance, unit);
        if (meters < 0f || meters > GUIDE_METERS) return null;
        return fromIcon(icon, meters);
    }

    /** Cluster units, deliberately separate from the different iAP2 unit enum. */
    public static float meters(float distance, int unit) {
        if (Float.isNaN(distance) || Float.isInfinite(distance) || distance < 0f) return -1f;
        float meters;
        switch (unit) {
            case 0: meters = distance; break;
            case 1: meters = distance * 1000f; break;
            case 2: meters = distance * 1609.344f; break;
            case 3: meters = distance * 0.3048f; break;
            case 4: meters = distance * 0.9144f; break;
            default: return -1f;
        }
        return Float.isInfinite(meters) ? -1f : meters;
    }

    public static float fromMeters(float meters, int unit) {
        if (meters(meters, 0) < 0f) return -1f;
        switch (unit) {
            case 0: return meters;
            case 1: return meters / 1000f;
            case 2: return meters / 1609.344f;
            case 3: return meters / 0.3048f;
            case 4: return meters / 0.9144f;
            default: return -1f;
        }
    }

    /** Raw iAP2 metadata entry point, ready for the firmware-specific parser adapter.
     * This is not called by the icon-only DiPO forwarder. Exact meters are item 10,
     * drivingSide is 0 (right-hand traffic) or 1 (left-hand traffic), and junction
     * is 0 (intersection) or 1 (roundabout). hasAngle distinguishes missing from 0.
     * The current projection icon remains the authoritative roundabout exit geometry.
     */
    public static int[] maneuver(int maneuver, int drivingSide, int junction,
            int exitAngle, boolean hasAngle, int icon, float exactMeters, int state) {
        if (state < 1 || drivingSide < 0 || drivingSide > 1 || junction < 0 || junction > 1) return null;
        // An explicit phone arrival state is reliable; rounded zero meters isn't.
        if (state == 2) return new int[] {3, 0, 0};
        if (meters(exactMeters, 0) < 0f || exactMeters > GUIDE_METERS) return null;
        if (junction == 1) {
            int[] round = fromIcon(icon, exactMeters);
            return round != null && (round[0] == 32 || round[0] == 33) ? round : null;
        }
        int type, angle;
        switch (maneuver) {
            case 3: type = 13; angle = 0; break;
            case 1: case 20: type = 13; angle = turnAngle(exitAngle, hasAngle, false); break;
            case 2: case 21: type = 13; angle = turnAngle(exitAngle, hasAngle, true); break;
            case 49: type = 13; angle = 315; break;
            case 50: type = 13; angle = 45; break;
            case 47: type = 13; angle = 225; break;
            case 48: type = 13; angle = 135; break;
            case 4: case 26: type = 31; angle = drivingSide == 1 ? 90 : 270; break;
            case 13: case 52: type = 25; angle = 270; break;
            case 14: case 53: type = 25; angle = 90; break;
            case 22: type = 18; angle = 270; break;
            case 23: type = 17; angle = 90; break;
            default: return null;
        }
        return new int[] {type, angle, bar(exactMeters)};
    }

    /** Recover only supported unambiguous raw maneuvers rejected by the stock icon table. */
    public static int recoverIcon(int raw,int traffic,int junction) {
        if(traffic<0||traffic>1||junction!=0)return 0;
        switch(raw) {
            // Direction-unspecified off-ramp: projection's generic exit, no invented side.
            case 8:return 86;
            case 1: case 20:return 70;
            case 2: case 21:return 67;
            case 3:return 65;
            case 4: case 26:return traffic==1?73:72;
            case 13: case 52:return 174;
            case 14: case 53:return 173;
            case 22:return 82;
            case 23:return 81;
            case 47:return 69;
            case 48:return 68;
            case 49:return 71;
            case 50:return 66;
            default:return 0;
        }
    }

    private static int turnAngle(int exit, boolean present, boolean right) {
        int plain = right ? 90 : 270;
        if (!present) return plain;
        int normalized = (exit % 360 + 360) % 360;
        int quantized = (normalized + 22) / 45 * 45 % 360;
        if (right) return quantized >= 45 && quantized <= 135 ? quantized : plain;
        return quantized >= 225 && quantized <= 315 ? quantized : plain;
    }

    private static int[] fromIcon(int icon, float meters) {
        int type, angle;
        switch (icon) {
            case 65: type = 13; angle = 0; break;
            case 66: case 144: type = 13; angle = 45; break;
            case 67: type = 13; angle = 90; break;
            case 68: type = 13; angle = 135; break;
            case 69: type = 13; angle = 225; break;
            case 70: type = 13; angle = 270; break;
            case 71: case 145: type = 13; angle = 315; break;
            case 72: type = 31; angle = 270; break;
            case 73: type = 31; angle = 90; break;
            case 81: type = 17; angle = 90; break;
            case 82: type = 18; angle = 270; break;
            case 173: type = 25; angle = 90; break;
            case 174: type = 25; angle = 270; break;
            default:
                // Only the documented, unambiguous roundabout exit icons.
                int base = icon >= 192 && icon <= 203 ? icon - 96 : icon;
                switch (base) {
                    case 96: angle = 0; break;
                    case 97: angle = 45; break;
                    case 99: angle = 90; break;
                    case 100: angle = 135; break;
                    case 102: angle = 180; break;
                    case 104: angle = 225; break;
                    case 105: angle = 270; break;
                    case 107: angle = 315; break;
                    default: return null;
                }
                type = icon >= 192 ? 33 : 32;
        }
        return new int[] {type, angle, bar(meters)};
    }

    public static int bar(float meters) {
        if (meters(meters, 0) < 0f || meters > GUIDE_METERS) return 0;
        return Math.max(1, Math.min(9, (int) (meters * 10f / GUIDE_METERS))) * 10;
    }
}
