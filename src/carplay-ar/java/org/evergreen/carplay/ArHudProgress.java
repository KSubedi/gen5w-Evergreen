// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

/** Progress lease and non-mutating merge into OEM service arguments.
 * NOT a CAN-frame builder. The 17-int OEM argument's bar index must be verified
 * from the target framework before calling merge(); no firmware index is guessed.
 * The actual cross-process adapter is pending the car's firmware files.
 */
public final class ArHudProgress {
    public static final long MAX_AGE_MS = 5000;
    private int bar = -1;
    private long updatedAt;

    public void clear() { bar = -1; updatedAt = 0; }

    public void update(int value, long now) {
        if (now < 0 || value < 0 || value > 90 || value % 10 != 0) {
            clear();
            return;
        }
        if (bar >= 0 && now < updatedAt) return;
        bar = value;
        updatedAt = now;
    }

    /** -1 means retain the navigation app's original value. */
    public int value(boolean phoneGuiding, long now) {
        if (!phoneGuiding || bar < 0 || now < updatedAt || now - updatedAt > MAX_AGE_MS) return -1;
        return bar;
    }

    public int[] merge(int[] original, int verifiedBarIndex, boolean phoneGuiding, long now) {
        int replacement = value(phoneGuiding, now);
        if (original == null || original.length != 17 || verifiedBarIndex < 0
                || verifiedBarIndex >= original.length || replacement < 0) return original;
        int[] copy = original.clone();
        copy[verifiedBarIndex] = replacement;
        return copy;
    }
}
