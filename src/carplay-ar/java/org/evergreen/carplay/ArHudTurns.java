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
        float meters;
        switch (unit) {
            case 0: meters = distance; break;
            case 1: meters = distance * 1000f; break;
            case 2: meters = distance * 1609.344f; break;
            case 3: meters = distance * 0.3048f; break;
            case 4: meters = distance * 0.9144f; break;
            default: return null;
        }
        if (meters > GUIDE_METERS) return null;
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
        int bar = Math.max(1, Math.min(9, (int) (meters * 10f / GUIDE_METERS))) * 10;
        return new int[] {type, angle, bar};
    }
}
