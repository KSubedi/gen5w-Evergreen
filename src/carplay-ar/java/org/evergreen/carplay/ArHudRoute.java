// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Read-only adapter for the inspected US EV6 260507 DiPO field layout.
 * The patch builder verifies class/method fingerprints and all field types.
 * Reflection crosses the app's private fields without changing their visibility.
 * Missing fields fail back to the proven icon adapter, never crash the bridge.
 */
public final class ArHudRoute {
    private static final Map<Object, long[]> exact = new WeakHashMap<Object, long[]>();
    private static final Map<Object, Boolean> packets = new WeakHashMap<Object, Boolean>();
    private static final Map<String, Field> fields = new HashMap<String, Field>();
    private static final ThreadLocal<Object[]> prepared = new ThreadLocal<Object[]>();
    private static long generation;
    private static boolean warned;

    private ArHudRoute() {}

    private static synchronized Object field(Object object, String name) throws Exception {
        String key = object.getClass().getName() + ":" + name;
        Field f = fields.get(key);
        if (f == null) { f = object.getClass().getDeclaredField(name); f.setAccessible(true); fields.put(key, f); }
        return f.get(object);
    }

    public static synchronized void exact(Object route, int meters, long now) {
        if (route == null) return;
        if (meters < 0 || now < 0) exact.remove(route);
        else {
            int maneuver=-1;
            if (packets.containsKey(route)) packets.put(route,Boolean.TRUE);
            else {
                try { int[] ids=(int[])field(route,"bd");if(ids!=null&&ids.length>0)maneuver=ids[0]; }
                catch(Exception unavailable) { /* Unbound exact samples are never consumed. */ }
            }
            exact.put(route, new long[] {meters, now, maneuver});
        }
    }

    public static synchronized void begin(Object route) { packets.put(route,Boolean.FALSE); }

    public static synchronized void finish(Object route) {
        Boolean touched=packets.remove(route);long[] receipt=exact.get(route);
        if(receipt==null)return;
        try {
            int[] ids=(int[])field(route,"bd");int maneuver=ids!=null&&ids.length>0?ids[0]:-1;
            if(maneuver<0 || (!Boolean.TRUE.equals(touched) && receipt[2]!=maneuver))exact.remove(route);
            else receipt[2]=maneuver;
        } catch(Exception unavailable){exact.remove(route);}
    }

    public static void prepare(Object owner, Integer component, Object route) {
        long epoch;
        synchronized (ArHudRoute.class) { epoch = generation; }
        prepared.set(new Object[] {owner, component, route, Long.valueOf(epoch)});
    }

    public static boolean stopped() {
        Object[] p = prepared.get();
        if (p == null || p[2] == null) return false;
        try { return ((Integer) field(p[2], "aR")).intValue() == 0; }
        catch (Exception unavailable) { return false; }
    }

    public static ArHudSample take() {
        Object[] p = prepared.get(); prepared.remove();
        if (p == null || p[0] == null || p[1] == null || p[2] == null) return null;
        synchronized (ArHudRoute.class) { if (((Long)p[3]).longValue() != generation) return null; }
        try {
            Object route = p[2];
            int component = ((Integer)p[1]).intValue();
            int state = ((Integer)field(route, "aR")).intValue();
            int[] current = (int[])field(route, "bd");
            if (state == 0 || current == null || current.length == 0) return null;
            Object maneuvers = ((Map<?, ?>)field(p[0], "Am")).get(Integer.valueOf(component));
            if (!(maneuvers instanceof Map)) return null;
            Object maneuver = ((Map<?, ?>)maneuvers).get(Integer.valueOf(current[0]));
            if (maneuver == null) return null;
            long[] packet;
            synchronized (ArHudRoute.class) { packet = exact.get(route); if(packet!=null)packet=packet.clone(); }
            if(packet!=null && packet[2]!=current[0])packet=null;
            return new ArHudSample(p[0], component, current[0], ((Integer)field(maneuver,"DM")).intValue(),
                    ((Integer)field(maneuver,"DR")).intValue(), ((Integer)field(maneuver,"DS")).intValue(),
                    ((Integer)field(maneuver,"DU")).intValue(), packet == null ? -1f : packet[0],
                    packet == null ? -1 : packet[1], true);
        } catch (Exception unavailable) {
            if (!warned) { warned=true; android.util.Log.w("EvergreenAR","Metadata unavailable; retaining basic AR",unavailable); }
            return null;
        }
    }

    /** Re-enter the verified OEM sender after a maneuver-details packet completes.
     * No route fields or receipt times are changed here. Calls happen without our
     * metadata lock held; OEM a(Integer,Object) retains its own synchronization.
     * Al is bounded to 16 components before copying to avoid unbounded work.
     */
    public static void refreshDetails(Object owner) {
        if (owner == null) return;
        try {
            Object routes = field(owner, "Al");
            if (!(routes instanceof Map)) return;
            Map<?, ?> map = (Map<?, ?>) routes;
            if (map.size() > 16) return;
            Object[] entries = map.entrySet().toArray();
            Method sender = owner.getClass().getDeclaredMethod("a", Integer.class, Object.class);
            sender.setAccessible(true);
            for (Object value : entries) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) value;
                if (!(entry.getKey() instanceof Integer) || ((Integer)entry.getKey()).intValue() < 0) continue;
                Object route = entry.getValue();
                if (route == null || ((Integer)field(route,"aR")).intValue() == 0) continue;
                int[] current = (int[])field(route,"bd");
                if (current == null || current.length == 0) continue;
                Object details = ((Map<?, ?>)field(owner,"Am")).get(entry.getKey());
                if (!(details instanceof Map) || !((Map<?, ?>)details).containsKey(Integer.valueOf(current[0]))) continue;
                sender.invoke(owner, entry.getKey(), route);
            }
        } catch (Exception unavailable) {
            if (!warned) { warned=true; android.util.Log.w("EvergreenAR","Details refresh unavailable; retaining route-update path",unavailable); }
        }
    }

    public static synchronized void clear() { generation++; exact.clear(); packets.clear(); prepared.remove(); }
    public static synchronized void forget(Object route) { exact.remove(route); packets.remove(route); }
}
