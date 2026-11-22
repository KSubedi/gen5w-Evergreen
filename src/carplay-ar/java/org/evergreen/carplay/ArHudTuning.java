// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/** Road-test tuning from the package's own ar-tuning file, written by the USB
 * control kit. Each value has a fixed allowed range; a missing, unreadable or
 * out-of-range value keeps the built-in default, so the file can only move a
 * threshold inside its range. Re-read at most every two seconds, and only when
 * the file's size or modification time changed. No route data is involved.
 */
public final class ArHudTuning {
    public static final float DEFAULT_STREET_AR_METERS = 200f, DEFAULT_GAIN = 0.8f, DEFAULT_BACKSTEP_METERS = 15f;
    public static final float MIN_STREET_AR_METERS = 30f, MIN_GAIN = 0.5f, MAX_GAIN = 1f, MAX_BACKSTEP_METERS = 50f;
    /** The bar's window: the stock navigation fills it over about 457 m regardless of where AR starts. */
    public static final float DEFAULT_BAR_METERS = ArHudTurns.GUIDE_METERS, MIN_BAR_METERS = 30f;
    /** Added to the distance of large AR arrows only; the HUD's number stays the phone's. */
    public static final float DEFAULT_TURN_OFFSET_METERS = 0f, MAX_TURN_OFFSET_METERS = 60f;
    /** Side for the phone's direction-unspecified exit (raw 8): auto picks the exit angle's sign, else the traffic side. */
    public static final int EXIT_AUTO = 0, EXIT_RIGHT = 1, EXIT_LEFT = 2, EXIT_GENERIC = 3;
    /** How a sided exit is drawn: slight-turn arrow (stock-sendable icons, native TURN), or the native exit sign near. */
    public static final int EXIT_STYLE_SLIGHT = 0, EXIT_STYLE_SIGN = 1;
    /** Recovery of a stalled picture (ArHudRecovery): off, key frame only, + screen return (default), + reconnect. */
    public static final int RECOVER_OFF = 0, RECOVER_KEYFRAME = 1, RECOVER_SCREEN = 2, RECOVER_RECONNECT = 3;
    public static final int DEFAULT_RECOVER = RECOVER_SCREEN;
    /** Silence of the phone's active route guidance before it is restarted (s); 0 turns it off. */
    public static final float DEFAULT_GUIDANCE_RESTART_S = 90f, MIN_GUIDANCE_RESTART_S = 60f, MAX_GUIDANCE_RESTART_S = 600f;
    public static final int MAX_BYTES = 4096;
    static String path = ArHudIpc.ROOT + "/ar-tuning";
    private static float streetArMeters = DEFAULT_STREET_AR_METERS, gain = DEFAULT_GAIN, backstepMeters = DEFAULT_BACKSTEP_METERS;
    private static float barMeters = DEFAULT_BAR_METERS, turnOffsetMeters = DEFAULT_TURN_OFFSET_METERS;
    // The sign is what the 108 drive showed correctly; its freeze was missing phone input.
    public static final int DEFAULT_EXIT_STYLE = EXIT_STYLE_SIGN;
    private static int exitSide = EXIT_AUTO, exitStyle = DEFAULT_EXIT_STYLE, recover = DEFAULT_RECOVER;
    private static float guidanceRestartS = DEFAULT_GUIDANCE_RESTART_S;
    private static boolean androidAuto = true;
    private static long checkedAt = -1, seenModified = -2, seenLength = -2;

    private ArHudTuning() {}

    /** Large street-turn AR starts inside this distance; smaller cues stay earlier. */
    public static synchronized float streetArMeters(long now) { refresh(now); return streetArMeters; }
    /** Fraction of the measured closing rate used between phone samples. */
    public static synchronized float gain(long now) { refresh(now); return gain; }
    /** A phone sample this far above the last sent distance holds instead of stepping back. */
    public static synchronized float backstepMeters(long now) { refresh(now); return backstepMeters; }
    /** Window over which the street-turn bar runs 90 down to 10. */
    public static synchronized float barMeters(long now) { refresh(now); return barMeters; }
    /** Meters added to the large AR arrow's distance. */
    public static synchronized float turnOffsetMeters(long now) { refresh(now); return turnOffsetMeters; }
    /** EXIT_AUTO, EXIT_RIGHT, EXIT_LEFT or EXIT_GENERIC for raw exit 8. */
    public static synchronized int exitSide(long now) { refresh(now); return exitSide; }
    /** EXIT_STYLE_SLIGHT or EXIT_STYLE_SIGN for a sided raw exit 8. */
    public static synchronized int exitStyle(long now) { refresh(now); return exitStyle; }

    /** RECOVER_OFF, RECOVER_KEYFRAME, RECOVER_SCREEN or RECOVER_RECONNECT. */
    public static synchronized int recoverMode(long now) { refresh(now); return recover; }
    /** Android Auto turns as AR (android_auto=on, the default) or GPM's icons only (off). */
    public static synchronized boolean androidAuto(long now) { refresh(now); return androidAuto; }
    /** Milliseconds of silent active guidance before a restart; 0: never. */
    public static synchronized long guidanceRestartMs(long now) { refresh(now); return (long) (guidanceRestartS * 1000f); }

    /** Forget the cached file state so the next read looks at the file again. */
    static synchronized void reload() { checkedAt = -1; seenModified = -2; seenLength = -2; }

    private static void refresh(long now) {
        if (checkedAt >= 0 && now >= checkedAt && now - checkedAt < 2000) return;
        checkedAt = now;
        try {
            File file = path == null ? null : new File(path);
            boolean present = file != null && file.isFile();
            long modified = present ? file.lastModified() : -1, length = present ? file.length() : -1;
            if (modified == seenModified && length == seenLength) return;
            seenModified = modified; seenLength = length;
            float[] values = parse(present && length <= MAX_BYTES ? read(file) : null);
            streetArMeters = values[0]; gain = values[1]; backstepMeters = values[2];
            barMeters = values[3]; turnOffsetMeters = values[4]; exitSide = (int) values[5];
            exitStyle = (int) values[6]; recover = (int) values[7]; guidanceRestartS = values[8]; androidAuto = values[9] != 0f;
            ArHudDebug.event("tuning present=" + present + " street_ar_meters=" + streetArMeters
                    + " gain=" + gain + " backstep_meters=" + backstepMeters + " bar_meters=" + barMeters
                    + " turn_offset_meters=" + turnOffsetMeters + " exit_side=" + exitSideName(exitSide)
                    + " exit_style=" + exitStyleName(exitStyle) + " recover=" + recoverName(recover)
                    + " guidance_restart_s=" + (int) guidanceRestartS + " android_auto=" + (androidAuto ? "on" : "off"));
        } catch (RuntimeException unavailable) {
            streetArMeters = DEFAULT_STREET_AR_METERS; gain = DEFAULT_GAIN; backstepMeters = DEFAULT_BACKSTEP_METERS;
            barMeters = DEFAULT_BAR_METERS; turnOffsetMeters = DEFAULT_TURN_OFFSET_METERS; exitSide = EXIT_AUTO;
            exitStyle = DEFAULT_EXIT_STYLE; recover = DEFAULT_RECOVER; guidanceRestartS = DEFAULT_GUIDANCE_RESTART_S; androidAuto = true;
        }
    }

    private static String read(File file) {
        try {
            BufferedReader in = new BufferedReader(new FileReader(file));
            try {
                StringBuilder text = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    text.append(line).append('\n');
                    if (text.length() > MAX_BYTES) return null;
                }
                return text.toString();
            } finally { in.close(); }
        } catch (Exception unavailable) { return null; }
    }

    public static String exitSideName(int side) {
        switch (side) {
            case EXIT_RIGHT: return "right";
            case EXIT_LEFT: return "left";
            case EXIT_GENERIC: return "generic";
            default: return "auto";
        }
    }

    public static String exitStyleName(int style) { return style == EXIT_STYLE_SIGN ? "sign" : "slight"; }

    public static String recoverName(int mode) {
        switch (mode) {
            case RECOVER_OFF: return "off";
            case RECOVER_KEYFRAME: return "keyframe";
            case RECOVER_RECONNECT: return "reconnect";
            default: return "screen";
        }
    }

    /** {street AR meters, gain, backstep meters, bar meters, turn offset meters, exit side, exit style,
     * recover mode, guidance restart seconds, Android Auto AR (1 on, 0 off)}.
     * Unknown keys and bad values are ignored. */
    static float[] parse(String text) {
        float[] values = {DEFAULT_STREET_AR_METERS, DEFAULT_GAIN, DEFAULT_BACKSTEP_METERS,
                DEFAULT_BAR_METERS, DEFAULT_TURN_OFFSET_METERS, EXIT_AUTO, DEFAULT_EXIT_STYLE,
                DEFAULT_RECOVER, DEFAULT_GUIDANCE_RESTART_S, 1f};
        if (text == null) return values;
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int separator = line.indexOf('=');
            if (line.trim().startsWith("#") || separator < 0) continue;
            String key = line.substring(0, separator).trim();
            String text1 = line.substring(separator + 1).trim();
            if (key.equals("exit_side")) {
                if (text1.equals("right")) values[5] = EXIT_RIGHT;
                else if (text1.equals("left")) values[5] = EXIT_LEFT;
                else if (text1.equals("generic")) values[5] = EXIT_GENERIC;
                else if (text1.equals("auto")) values[5] = EXIT_AUTO;
                continue;
            }
            if (key.equals("android_auto")) {
                if (text1.equals("on")) values[9] = 1f;
                else if (text1.equals("off")) values[9] = 0f;
                continue;
            }
            if (key.equals("recover")) {
                if (text1.equals("off")) values[7] = RECOVER_OFF;
                else if (text1.equals("keyframe")) values[7] = RECOVER_KEYFRAME;
                else if (text1.equals("screen")) values[7] = RECOVER_SCREEN;
                else if (text1.equals("reconnect")) values[7] = RECOVER_RECONNECT;
                continue;
            }
            if (key.equals("exit_style")) {
                if (text1.equals("sign")) values[6] = EXIT_STYLE_SIGN;
                else if (text1.equals("slight")) values[6] = EXIT_STYLE_SLIGHT;
                continue;
            }
            float value;
            try { value = Float.parseFloat(text1); }
            catch (NumberFormatException bad) { continue; }
            if (Float.isNaN(value) || Float.isInfinite(value)) continue;
            if (key.equals("street_ar_meters")) values[0] = accept(value, MIN_STREET_AR_METERS, ArHudTurns.GUIDE_METERS, values[0]);
            else if (key.equals("gain")) values[1] = accept(value, MIN_GAIN, MAX_GAIN, values[1]);
            else if (key.equals("backstep_meters")) values[2] = accept(value, 0f, MAX_BACKSTEP_METERS, values[2]);
            else if (key.equals("bar_meters")) values[3] = accept(value, MIN_BAR_METERS, ArHudTurns.GUIDE_METERS, values[3]);
            else if (key.equals("turn_offset_meters")) values[4] = accept(value, 0f, MAX_TURN_OFFSET_METERS, values[4]);
            else if (key.equals("guidance_restart_s")) values[8] = value == 0f ? 0f : accept(value, MIN_GUIDANCE_RESTART_S, MAX_GUIDANCE_RESTART_S, values[8]);
        }
        return values;
    }

    private static float accept(float value, float low, float high, float fallback) {
        return value < low || value > high ? fallback : value;
    }
}
