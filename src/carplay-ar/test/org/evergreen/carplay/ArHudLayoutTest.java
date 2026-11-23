package org.evergreen.carplay;

import java.util.LinkedHashMap;

/** ArHudRoute.layoutName: the EV6 260507 DiPO names, or the Ioniq 5 260903 ones (one letter on). */
public final class ArHudLayoutTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    // e/u and e/v as in the EV6 build, and as in the 260903 build (one more field before the run)
    static final class U6 { LinkedHashMap<?, ?> Al, Am; }
    static final class U5 { LinkedHashMap<?, ?> Am, An; }
    static final class V6 { int DM, DR, DS, DU, DV; int[] DT; }
    static final class V5 { int DN, DS, DT, DV, DW; int[] DU; }
    /** An unrelated class with a field named like the marker but of another type: not shifted. */
    static final class Odd { String An; int DW; LinkedHashMap<?, ?> Al; }
    static final class OddV { String DW; int DM; }

    public static void main(String[] args) {
        check(ArHudRoute.layoutName(U6.class, "Al").equals("Al") && ArHudRoute.layoutName(U6.class, "Am").equals("Am"), "EV6 e/u");
        check(ArHudRoute.layoutName(U5.class, "Al").equals("Am") && ArHudRoute.layoutName(U5.class, "Am").equals("An"), "260903 e/u");
        for (String n : new String[] {"DM", "DR", "DS", "DT", "DU", "DV"}) {
            check(ArHudRoute.layoutName(V6.class, n).equals(n), "EV6 e/v " + n);
            String shifted = ArHudRoute.layoutName(V5.class, n);
            check(shifted.equals("D" + (char) (n.charAt(1) + 1)), "260903 e/v " + n);
            try { V5.class.getDeclaredField(shifted); } catch (NoSuchFieldException e) { throw new AssertionError("no field " + shifted); }
        }
        check(ArHudRoute.layoutName(U5.class, "aS").equals("aS") && ArHudRoute.layoutName(V5.class, "bd").equals("bd"), "other names stay");
        check(ArHudRoute.layoutName(Odd.class, "Al").equals("Al"), "marker of the wrong type: not shifted");
        check(ArHudRoute.layoutName(OddV.class, "DM").equals("DM"), "e/v marker of the wrong type: not shifted");
        System.out.println("AR DiPO layouts: " + checks + " checks passed");
    }
}
