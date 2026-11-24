/* SPDX-License-Identifier: GPL-3.0-or-later
 * JVM test of the HMG-API stand-in (src/carplay-ultra/shim): settings file, hook dispatch,
 * a throwing hook, unwired points. Run by tools/test-carplay-ultra.sh.
 */
package hmgapi.hook;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

public final class ShimTest {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("shim").toFile();
        FileWriter w = new FileWriter(new File(dir, "uwp-carplay-ultra.conf"));
        w.write("# comment\ncarplay_ultra=false\nrear_defrost = off\nvolume_link=1\nsize=80\nbroken\n");
        w.close();
        ModContext m = new ModContext("cp-climate", "1.3.3", dir);
        check(!m.settingBoolean("carplay_ultra", true), "settings file: carplay_ultra=false");
        check(!m.settingBoolean("rear_defrost", true), "settings file: spaces and 'off'");
        check(m.settingBoolean("volume_link", false), "settings file: 1 is true");
        check(m.settingBoolean("vehicle_page", true) && !m.settingBoolean("drive_log", false), "missing keys: the caller's default");
        check(m.settingInt("size", 100) == 80 && m.settingInt("nope", 7) == 7, "settingInt");
        Hooks.setMod(m);
        final int[] seen = new int[4];
        final Object me = new Object();
        m.hook(null, "dipo.carplay.session_started", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (p.thisObject == me && ((Integer) p.args[0]) == 1) seen[0]++;
            }

            @Override
            protected void afterHookedMethod(Param p) {
                if (p.thisObject == me) seen[1]++;
            }
        });
        m.hook(null, "dipo.carplay.session_started", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                throw new IllegalStateException("boom");
            }
        });
        m.hook(null, "dipo.carplay.session_finalized", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                seen[2]++;
            }
        });
        m.hook(null, "dipo.carplay.native_iap_send", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                seen[3]++;
            }
        });
        Hooks.before("dipo.carplay.session_started", me, 1);
        Hooks.after("dipo.carplay.session_started");
        check(seen[0] == 1 && seen[1] == 1, "session_started: before with this and the transport, after with the same Param");
        Hooks.after("dipo.carplay.session_started");
        check(seen[1] == 1, "after() without before() does nothing");
        Hooks.before("dipo.carplay.session_finalized", me, 1);
        Hooks.after("dipo.carplay.session_finalized");
        check(seen[2] == 1, "session_finalized");
        check(seen[3] == 0, "unwired point never called");
        String log = new String(Files.readAllBytes(new File(dir, "uwp-carplay-ultra.log").toPath()), "UTF-8");
        check(log.contains("ERROR hook dipo.carplay.session_started threw") && log.contains("boom"), "a throwing hook is logged to the file");
        check(log.contains("native_iap_send isn't wired"), "unwired point logged");
        System.out.println(failures == 0 ? "all shim checks passed" : failures + " shim check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
