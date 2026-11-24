/* SPDX-License-Identifier: GPL-3.0-or-later
 * Ultimate Wireless Projection: the part of HMG-API's ModContext that CarPlay Ultra-Lite calls.
 *
 * - hook(): no runtime hooking; the hook is kept in Hooks and our DiPO build calls it from the
 *   method the point names (only the points in Hooks.SUPPORTED are wired; others are logged).
 * - nativePatch(): nothing to do, libCarPlayCore.so is patched on disk by part 3_bridge.
 * - settings: the defaults CarPlay Ultra-Lite passes, unless DiPO's files/uwp-carplay-ultra.conf
 *   holds "key=true" or "key=false" lines (no HMG-API settings screen here).
 * - log()/error(): logcat tag CarPlayUltra, and files/uwp-carplay-ultra.log (rotated at 256 KB,
 *   one .1 kept), which the root service copies where the USB kit collects logs.
 */
package hmgapi.hook;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class ModContext {
    public static final String TAG = "CarPlayUltra";
    static final long LOG_MAX = 256 * 1024;

    public final String id;
    public final String version;
    public final File dir;
    private final Map<String, String> settings = new HashMap<String, String>();
    private final File logFile;

    public ModContext(String id, String version, File dir) {
        this.id = id;
        this.version = version;
        this.dir = dir;
        this.logFile = new File(dir, "uwp-carplay-ultra.log");
        readSettings(new File(dir, "uwp-carplay-ultra.conf"));
    }

    private void readSettings(File f) {
        if (!f.isFile()) return;
        try {
            BufferedReader in = new BufferedReader(new FileReader(f));
            try {
                String l;
                while ((l = in.readLine()) != null) {
                    l = l.trim();
                    int eq = l.indexOf('=');
                    if (l.startsWith("#") || eq <= 0) continue;
                    settings.put(l.substring(0, eq).trim(), l.substring(eq + 1).trim());
                }
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            log("settings file not read: " + t);
        }
    }

    public java.lang.reflect.Member hook(ClassLoader cl, String point, MethodHook hook) {
        if (!Hooks.add(point, hook)) log("hook point " + point + " isn't wired in this build; left out");
        return null;
    }

    public void nativePatch(LoadedApp app, String lib) {
        log(lib + ": patched on disk by Ultimate Wireless Projection (part 3_bridge)");
    }

    public boolean enabled() {
        return true;
    }

    public Object setting(String key) {
        return settings.get(key);
    }

    public String settingString(String key, String fallback) {
        String v = settings.get(key);
        return v != null ? v : fallback;
    }

    public boolean settingBoolean(String key, boolean fallback) {
        String v = settings.get(key);
        if ("true".equalsIgnoreCase(v) || "1".equals(v) || "on".equalsIgnoreCase(v)) return true;
        if ("false".equalsIgnoreCase(v) || "0".equals(v) || "off".equalsIgnoreCase(v)) return false;
        return fallback;
    }

    public int settingInt(String key, int fallback) {
        try {
            String v = settings.get(key);
            return v != null ? Integer.parseInt(v) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void log(String message) {
        android.util.Log.i(TAG, message);
        write(message);
    }

    public void error(String message, Throwable t) {
        android.util.Log.e(TAG, message, t);
        if (t == null) {
            write("ERROR " + message);
            return;
        }
        StringWriter s = new StringWriter();
        t.printStackTrace(new PrintWriter(s));
        write("ERROR " + message + ": " + s);
    }

    private synchronized void write(String message) {
        try {
            if (logFile.length() > LOG_MAX) {
                File old = new File(dir, logFile.getName() + ".1");
                old.delete();
                logFile.renameTo(old);
            }
            FileWriter w = new FileWriter(logFile, true);
            try {
                w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + message + "\n");
            } finally {
                w.close();
            }
            logFile.setReadable(true, false);
        } catch (Throwable ignored) {
            // logcat has it
        }
    }
}
