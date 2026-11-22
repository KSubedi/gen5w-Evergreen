// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Adapter for the inspected US EV6 260507 DiPO field layout. It reads, except for one write:
 * the display distance text is made parseable (distanceText) before DiPO's sender reads it.
 * The patch builder verifies class/method fingerprints and all field types.
 * Reflection crosses the app's private fields without changing their visibility.
 * Missing fields fail back to the proven icon adapter, never crash the bridge.
 */
public final class ArHudRoute {
    private static final Map<Object, long[]> exact = new WeakHashMap<Object, long[]>();
    private static final Map<Object,long[]> displays=new WeakHashMap<Object,long[]>();
    private static final Map<Object,Boolean> displayPackets=new WeakHashMap<Object,Boolean>();
    private static final Map<Object, Boolean> packets = new WeakHashMap<Object, Boolean>();
    private static final Map<String, Field> fields = new HashMap<String, Field>();
    private static final ThreadLocal<Object[]> prepared = new ThreadLocal<Object[]>();
    private static long generation;
    private static boolean warned;

    private ArHudRoute() {}

    private static synchronized Object field(Object object, String name) throws Exception {
        return fieldOf(object, name).get(object);
    }
    private static synchronized Field fieldOf(Object object, String name) throws Exception {
        String key = object.getClass().getName() + ":" + name;
        Field f = fields.get(key);
        if (f == null) { f = object.getClass().getDeclaredField(name); f.setAccessible(true); fields.put(key, f); }
        return f;
    }

    /** The phone's display distance as stock DiPO can parse it (Float.parseFloat). Apple Maps
     * sends 1,000 ft as "1,000": the parse failed and DiPO sent the cluster and HUD distance 0
     * (a US owner's log, 2026-11-18). Grouping commas and spaces go, and a decimal comma
     * ("0,8") becomes a point; anything else is returned unchanged for DiPO to handle as before.
     */
    static String distanceText(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.matches("[0-9]{1,3}([,\u00a0\u202f ][0-9]{3})+(\\.[0-9]+)?"))
            return t.replace(",", "").replace("\u00a0", "").replace("\u202f", "").replace(" ", "");
        if (t.matches("[0-9]+,[0-9]{1,2}")) return t.replace(',', '.');
        return text;
    }

    /** Rewrites the route update's display distance (field bb) before DiPO's sender parses it. */
    private static void fixDistanceText(Object route) {
        try {
            Field f = fieldOf(route, "bb");
            Object value = f.get(route);
            if (!(value instanceof String)) return;
            String fixed = distanceText((String) value);
            if (fixed.equals(value)) return;
            f.set(route, fixed);
            if (ArHudDebug.on()) ArHudDebug.event("distance_text fixed length="+((String)value).length()+" value="+fixed);
        } catch (Exception unavailable) { /* DiPO parses its own text as before */ }
    }

    public static synchronized void exact(Object route, int meters, long now) {
        if (route == null) return;
        if (ArHudDebug.on()) ArHudDebug.event("exact meters="+meters+" at="+now);
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

    public static synchronized void begin(Object route) { packets.put(route,Boolean.FALSE); displayPackets.put(route,Boolean.FALSE); ArHudDebug.event("parser_begin"); }

    public static synchronized void finish(Object route) {
        Boolean touched=packets.remove(route);long[] receipt=exact.get(route);
        if(ArHudDebug.on())ArHudDebug.event("parser_finish state="+state(route)+" current="+identity(route)+" exact_touched="+touched+" receipt="+(receipt!=null));
        Boolean displayTouched=displayPackets.remove(route);long[] display=displays.get(route);
        int id=identity(route);
        if(display!=null) {
            if(id<0 || (!Boolean.TRUE.equals(displayTouched)&&display[1]!=id))displays.remove(route);
            else display[1]=id;
        }
        if(receipt==null)return;
        try {
            int[] ids=(int[])field(route,"bd");int maneuver=ids!=null&&ids.length>0?ids[0]:-1;
            if(maneuver<0 || (!Boolean.TRUE.equals(touched) && receipt[2]!=maneuver))exact.remove(route);
            else receipt[2]=maneuver;
        } catch(Exception unavailable){exact.remove(route);}
    }

    public static synchronized void display(Object route) {
        if(route==null)return;
        displayPackets.put(route,Boolean.TRUE);
        displays.put(route,new long[]{android.os.SystemClock.elapsedRealtime(),identity(route)});
        ArHudDebug.event("display_distance_received");
    }
    public static boolean distanceReady() {
        Object[] p=prepared.get();if(p==null||p[2]==null)return false;
        ArHudSample sample=peek();if(sample!=null&&sample.exactAt>=0)return true;
        synchronized(ArHudRoute.class){long[] d=displays.get(p[2]);return d!=null&&d[1]==identity(p[2]);}
    }

    public static void prepare(Object owner, Integer component, Object route) {
        if (route != null) fixDistanceText(route);
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
        return snapshot(p);
    }
    public static ArHudSample peek() { return snapshot(prepared.get()); }
    private static ArHudSample snapshot(Object[] p) {
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
                    packet == null ? -1 : packet[1], true, state, optionalInt(route, "aS"), optionalLength(maneuver, "DT"),
                    optionalInts(maneuver, "DT"), optionalInt(maneuver, "DV"));
        } catch (Exception unavailable) {
            if (!warned) { warned=true; android.util.Log.w("EvergreenAR","Metadata unavailable; retaining basic AR",unavailable); }
            return null;
        }
    }

    /** Diagnostic-only fields; a layout without them still yields a usable sample. */
    private static int optionalInt(Object object, String name) {
        try { return ((Integer)field(object, name)).intValue(); } catch (Exception unavailable) { return -1; }
    }
    /** At most eight values: enough for any junction, bounded for the trace. */
    private static int[] optionalInts(Object object, String name) {
        try {
            int[] values = (int[])field(object, name);
            if (values == null) return null;
            int[] copy = new int[Math.min(values.length, 8)];
            System.arraycopy(values, 0, copy, 0, copy.length);
            return copy;
        } catch (Exception unavailable) { return null; }
    }
    private static int optionalLength(Object object, String name) {
        try { int[] values = (int[])field(object, name); return values == null ? -1 : values.length; }
        catch (Exception unavailable) { return -1; }
    }

    public static int identity(Object route) {
        try { int[] ids=(int[])field(route,"bd");return ids!=null&&ids.length>0?ids[0]:-1; }
        catch(Exception unavailable){return -1;}
    }
    public static int state(Object route) {
        try {return ((Integer)field(route,"aR")).intValue();}
        catch(Exception unavailable){return -1;}
    }
    public static void blank(Object owner) {
        try {
            Method method=owner.getClass().getDeclaredMethod("sendHudBlank");method.setAccessible(true);method.invoke(owner);
            ArHudDebug.event("hud_blank_sent");
        } catch(Exception unavailable){ArHudDebug.event("hud_blank_failed");}
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
                if (ArHudDebug.on()) ArHudDebug.event("details_refresh component="+entry.getKey()+" current="+current[0]);
                sender.invoke(owner, entry.getKey(), route);
            }
        } catch (Exception unavailable) {
            if (!warned) { warned=true; android.util.Log.w("EvergreenAR","Details refresh unavailable; retaining route-update path",unavailable); }
        }
    }

    public static synchronized void clear() { generation++; exact.clear(); displays.clear();displayPackets.clear();packets.clear(); prepared.remove(); }
    public static synchronized void forget(Object route) { exact.remove(route);displays.remove(route);displayPackets.remove(route);packets.remove(route); }
}
