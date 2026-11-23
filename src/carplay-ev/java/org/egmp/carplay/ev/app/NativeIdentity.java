package org.egmp.carplay.ev.app;
// SPDX-License-Identifier: GPL-3.0-or-later
/**
 * JNI boundary for the identification serializer gate patched into libiap2session.so
 * (native/identity.c in libcarplayev.so; after Open-HMG/hmgapi-wireless-cp-aa's
 * NativeIdentity). Returns 0, or a negative code (identity.c): -10 libraries, -12 bad
 * values, -18/-21 gate missing, -20 to -26 an unexpected library.
 */
final class NativeIdentity {
    private NativeIdentity() {}
    static native int configure(byte[] displayName, int engineType, int dcConnector,
            int dcPowerW, int acConnector, int acPowerW, byte[] themeIdentity);
    static native void shutdown();
}
