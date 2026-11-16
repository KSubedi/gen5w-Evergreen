// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

/** Regression scenarios; known bugs are characterized, not mislabeled as fixes. */
public final class ArHudReplayTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public static void main(String[] args) {
        for(boolean candidate:new boolean[]{false,true}) {
            ArHudReplay r=new ArHudReplay(candidate);
            r.event("1000,route,7");r.event("1010,distance,300");
            check(r.cluster.calls==0,"incomplete state must not invent a turn");
            r.event("1020,details,7,2,90,67");
            check(r.cluster.calls==(candidate?1:0),"late details expose current skipped refresh");
            r.event("2000,distance,280");
            check(r.cluster.type==13&&r.hudIcon==67,"next route update restores both paths");
            int hud=r.hudCalls;r.event("2250,tick");
            check(r.hudCalls==hud,"smooth ticks do not refresh turn list");
            check(Math.abs(r.cluster.distance-(candidate?274.95f:280f))<0.1,"late refresh also controls when motion receives its first sample");
            r.event("2300,source");int calls=r.cluster.calls;
            r.event("2400,details,8,1,270,70");r.event("2500,tick");
            check(r.cluster.calls==calls,"source change prevents old route replay");
            r.event("2600,route,8");check(r.cluster.calls==calls,"new route cannot inherit old display distance");
            r.event("2700,distance,100");
            check(r.cluster.angle==270&&r.hudIcon==70,"new route uses fresh left maneuver");
            r.event("2800,stop");calls=r.cluster.calls;r.event("3000,tick");
            check(r.cluster.calls==calls,"stop cancels smoothing");
        }
        ArHudReplay r=new ArHudReplay(false);
        r.event("1000,details,7,2,90,67");r.event("1010,route,7");r.event("1020,distance,300");
        check(r.cluster.type==13,"details-first ordering works today");
        check(ArHudTurns.convert(81,200,0,0,0)[0]==17,"known right exit maps");
        check(ArHudTurns.convert(86,200,0,0,0)==null,"unmapped exit remains an explicit coverage gap");
        check(ArHudTurns.convert(81,600,0,0,0)==null,"far exit remains outside activation window");
        // Exercise the real refresh adapter against malformed/oversized state.
        r=new ArHudReplay(true);r.event("1000,route,7");r.event("1010,distance,300");
        r.event("1020,details,8,1,270,70");
        check(r.cluster.calls==0,"unrelated details cannot invent the missing current maneuver");
        r.event("1030,details,7,2,90,67");
        check(r.cluster.calls==1&&r.cluster.angle==90,"real details adapter refreshes current turn");
        r.event("1100,stop");int sent=r.cluster.calls;r.event("1200,details,7,1,270,70");
        check(r.cluster.calls==sent,"details after stop cannot resurrect guidance");
        ArHudRoute.refreshDetails(new Object());
        check(r.cluster.calls==sent,"unknown layouts fail back without output");
        ArHudRuntime.reset();System.out.println("ArHudReplayTest: "+checks+" assertions passed");
    }
}
