// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;

/** Tiny, atomic, one-way bar lease from system-uid DiPO to the navigation app.
 * Only the dedicated directory is owned by system; readers cannot change it.
 * No route/phone data, arbitrary commands or raw vehicle messages are shared.
 */
public final class ArHudIpc {
    public static final String ROOT = "/data/nemesis/apps/wireless.carplay.aa";
    private static final int MAGIC = 0x45564152;
    private static final String boot = bootId();
    private static int publishedBar = -1;
    private static long publishedAt = -1;
    private static boolean wasActive;
    private static boolean warned;
    private static int readBar=-2;

    private ArHudIpc() {}

    public static boolean enabled() { return new File(ROOT, "ar-hud.enabled").isFile(); }
    public static boolean advanced() { return enabled() && !new File(ROOT, "ar-advanced.disabled").isFile(); }

    private static String bootId() {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"));
            try { String b = r.readLine(); return b != null && b.matches("[0-9a-f-]{36}") ? b : ""; }
            finally { r.close(); }
        } catch (Exception unavailable) { return ""; }
    }

    public static synchronized void publish(boolean active, int bar, long sampleAt, long now) {
        if (boot.length() == 0) return;
        if (!active && !wasActive) return;
        if (active && wasActive && bar == publishedBar && now - publishedAt < 2000) return;
        if (writeLease(new File(ROOT+"/ar-state"),boot,active,bar,sampleAt,now)) {
            ArHudDebug.event("ipc_written active="+active+" bar="+bar+" sample="+sampleAt+" published="+now);
            wasActive = active; publishedBar = bar; publishedAt = now;
        }
    }

    static boolean writeLease(File directory, String boot, boolean active, int bar, long sampleAt, long now) {
        File temporary = null;
        File target = new File(directory,"progress");
        try {
            temporary=File.createTempFile("progress-",".tmp",directory);
            FileOutputStream file = new FileOutputStream(temporary);
            try {
                DataOutputStream out = new DataOutputStream(file);
                out.writeInt(MAGIC); out.writeInt(1); out.writeUTF(boot);
                out.writeBoolean(active); out.writeInt(bar); out.writeLong(sampleAt); out.writeLong(now);
                out.flush();
                // Do not fsync at navigation cadence: this is an expiring runtime lease.
            } finally { file.close(); }
            if (!temporary.setWritable(false, false) || !temporary.setWritable(true, true)
                    || !temporary.setReadable(true, false) || !temporary.renameTo(target)) return false;
            return true;
        } catch (Exception unavailable) {
            if (!warned) { warned=true; android.util.Log.w("EvergreenAR","Progress state unavailable; retaining OEM bar",unavailable); }
            return false;
        } finally { if(temporary!=null)temporary.delete(); }
    }

    public static int read(long now) {
        if (!advanced() || boot.length() == 0) return -1;
        int value=readLease(new File(ROOT+"/ar-state/progress"),boot,now);
        if(value!=readBar){ArHudDebug.event("ipc_read bar="+value);readBar=value;}
        return value;
    }

    static int readLease(File target, String boot, long now) {
        if (!target.isFile() || target.length() > 128) return -1;
        try {
            DataInputStream in = new DataInputStream(new FileInputStream(target));
            try {
                if (in.readInt() != MAGIC || in.readInt() != 1 || !boot.equals(in.readUTF())) return -1;
                boolean active = in.readBoolean(); int bar = in.readInt();
                long sampleAt = in.readLong(), published = in.readLong();
                if (in.read() != -1 || !active || sampleAt < 0 || published < sampleAt || now < published
                        || now - sampleAt > 5000 || now - published > 3000 || bar < 0 || bar > 90 || bar % 10 != 0) return -1;
                return bar;
            } finally { in.close(); }
        } catch (Exception unavailable) { return -1; }
    }
}
