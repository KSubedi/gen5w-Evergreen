// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

/** Regression scenarios; known bugs are characterized, not mislabeled as fixes. */
public final class ArHudReplayTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static int logged(String prefix){int n=0;for(String m:android.util.Log.messages)if(m.contains(" "+prefix))n++;return n;}
    public static void main(String[] args) {
        ArHudDebug.flagPath=null;
        for(boolean candidate:new boolean[]{false,true}) {
            ArHudReplay r=new ArHudReplay(candidate);
            r.event("1000,route,7");r.event("1010,distance,300");
            check(r.cluster.calls==0,"incomplete state must not invent a turn");
            r.event("1020,details,7,2,90,67");
            check(r.cluster.calls==(candidate?1:0),"late details expose current skipped refresh");
            r.event("2000,distance,280");
            check(r.cluster.type==67&&r.hudIcon==67,"far street turn keeps small cue without early AR");
            int hud=r.hudCalls;r.event("2250,tick");
            check(r.hudCalls==hud,"smooth ticks do not refresh turn list");
            check(r.cluster.type==67,"far street turn does not become large AR during timer tick");
            r.event("2300,source");int calls=r.cluster.calls;
            r.event("2400,details,8,1,270,70");r.event("2500,tick");
            check(r.cluster.calls==calls,"source change prevents old route replay");
            r.event("2600,route,8");check(r.cluster.calls==calls,"new route cannot inherit old display distance");
            r.event("2700,distance,200");
            check(r.cluster.type==70&&r.hudIcon==70,"far left turn keeps small cue");
            r.event("2800,stop");calls=r.cluster.calls;r.event("3000,tick");
            check(r.cluster.calls==calls,"stop cancels smoothing");
        }
        ArHudReplay r=new ArHudReplay(false);
        r.event("1000,details,7,2,90,67");r.event("1010,route,7");r.event("1020,distance,300");
        check(r.cluster.type==67,"details-first ordering keeps far street cue");
        check(ArHudTurns.convert(81,200,0,0,0)[0]==17,"known right exit maps");
        check(ArHudTurns.convert(86,200,0,0,0)==null,"unmapped exit remains an explicit coverage gap");
        check(ArHudTurns.convert(81,600,0,0,0)==null,"far exit remains outside activation window");
        // Exercise the real refresh adapter against malformed/oversized state.
        r=new ArHudReplay(true);r.event("1000,route,7");r.event("1010,distance,300");
        r.event("1020,details,8,1,270,70");
        check(r.cluster.calls==0,"unrelated details cannot invent the missing current maneuver");
        r.event("1030,details,7,2,90,67");
        check(r.cluster.calls==1&&r.cluster.type==67,"real details adapter refreshes far current turn");
        r.event("1100,stop");int sent=r.cluster.calls;r.event("1200,details,7,1,270,70");
        check(r.cluster.calls==sent,"details after stop cannot resurrect guidance");
        ArHudRoute.refreshDetails(new Object());
        check(r.cluster.calls==sent,"unknown layouts fail back without output");
        // Road report: straight reaches 10 feet, then an exit is 1 mile away.
        // Explicit icon zero models the inspected stock rejection of, e.g.,
        // raw exit 23 with a non-zero optional angle. It is not a captured packet.
        r=new ArHudReplay(true);
        r.event("1000,details,7,3,0,65");r.event("1010,route,7");r.event("1020,distance,61");
        r.event("2000,distance,3");
        check(r.cluster.type==13&&r.cluster.distance==3,"previous straight reaches ten-foot equivalent");
        r.event("2100,route,8");r.event("2200,distance,1609");
        r.event("2300,details,8,23,30,0");r.event("3000,distance,1500");
        check(r.cluster.type==13&&r.cluster.distance==3&&r.hudIcon==65,
                "zero-icon early return strands old main and small HUD instructions");
        int stuckCalls=r.cluster.calls;r.event("8000,tick");
        check(r.cluster.calls==stuckCalls&&r.hudIcon==65,
                "runtime expiry stops work but cannot clear a skipped OEM HUD path");
        r.event("8100,details,8,23,0,81");
        check(r.cluster.type==81&&r.hudIcon==81&&r.cluster.distance==1500,
                "recognized far exit does replace the old direction in the replay");
        r.event("9000,distance,300");
        check(r.cluster.type==17&&r.cluster.angle==90&&r.hudIcon==81,
                "recognized near exit sends the native right exit; physical rendering remains outside replay");
        check(ArHudTurns.convert(67,300,0,0,0)[0]==13,
                "current 457-meter policy activates ordinary-turn AR at 300 meters");
        r=new ArHudReplay(true);r.livePipeline=true;
        r.event("1000,details,7,3,0,65");r.event("1010,route,7");r.event("1020,distance,61");
        r.event("2000,distance,3");r.event("2100,route,8");
        check(r.cluster.type==0&&r.hudIcon==-1,"replacement with missing details clears previous guidance");
        r.event("2200,distance,1609");r.event("2300,details,8,23,30,0");
        check(r.cluster.type==66&&r.cluster.distance==1609&&r.hudIcon==66,"angled exit recovered before zero-icon gate as a slight right");
        r.event("3000,distance,300");check(r.cluster.type==17&&r.cluster.angle==90,"near angled exit uses the native right exit");
        r.event("4000,details,9,2,90,67");r.event("4010,route,9");
        check(r.cluster.type==0&&r.hudIcon==-1,"new maneuver cannot borrow previous rounded distance");
        r.event("4020,distance,300");
        check(r.cluster.type==67&&r.hudIcon==67,"street-turn small cue remains at 300 meters");
        r.event("5000,distance,126");check(r.cluster.type==67,"street AR remains deferred just outside 125 meters");
        r.event("6000,distance,125");check(r.cluster.type==13,"street AR begins at 125 meters");
        check(ArHudTurns.recoverIcon(13,0,0)==174&&ArHudTurns.recoverIcon(14,0,0)==173,"keep left/right recovery");
        check(ArHudTurns.recoverIcon(999,0,0)==0&&ArHudTurns.recoverIcon(23,0,1)==0,"unknown/roundabout do not invent exits");
        // Regression from the 104 drive: raw 8 previously never reached HUD.
        r=new ArHudReplay(true);r.livePipeline=true;
        r.event("1000,details,7,13,0,0");r.event("1010,route,7");r.event("1020,distance,441");
        check(r.cluster.type==25&&r.hudIcon==174,"observed raw13 recovery reaches main and HUD");
        r.event("2000,details,8,8,0,0");r.event("2010,route,8");r.event("2020,distance,1100");
        check(r.cluster.type==66&&r.hudIcon==66&&r.cluster.distance==1100,
                "observed raw8 reaches main and small HUD as a slight right (the US car's default side)");
        r.event("3000,distance,716");check(r.cluster.type==66,"far exit keeps the icon outside the 457 m window");
        r.event("4000,distance,300");
        check(r.cluster.type==17&&r.cluster.angle==90&&r.cluster.bar==60&&r.cluster.distance==300,
                "near exit sends the native right exit over the full 457 m window with the stock bar scale");
        r.event("5000,distance,2");check(r.cluster.type==17&&r.cluster.angle==90&&r.cluster.distance==2,"the exit sign stays through the final distance");
        check(ArHudTurns.recoverIcon(8,-1,-1)==86,"generic exit does not require optional geometry");
        r=new ArHudReplay(true);r.livePipeline=true;
        r.event("1000,details,8,8,-25,0");r.event("1010,route,8");r.event("1020,distance,900");
        check(r.cluster.type==71&&r.hudIcon==71,"a negative exit angle picks the left exit");
        r.event("2000,distance,400");check(r.cluster.type==18&&r.cluster.angle==270,"near left exit is the native left exit");
        // Route start from the 104 drive: raw 11 (start route) in state 6 stayed blank for 16-26 s.
        r=new ArHudReplay(true);r.livePipeline=true;
        r.event("1000,route,0,6");r.event("1010,details,0,11,0,0");r.event("1020,distance,61");
        check(r.cluster.type==65&&r.hudIcon==65&&r.cluster.distance==61,"start of route shows the straight cue instead of nothing");
        r.event("2000,distance,58");check(r.cluster.type==65,"start cue never becomes a large AR arrow");
        r.event("3000,details,2,2,0,67");r.event("3010,route,2,1");r.event("3020,distance,44");
        check(r.cluster.type==13&&r.cluster.distance==44,"first real turn converts as before");
        check(r.cluster.bar==10,"street-turn bar is on the stock 457 m scale unless bar_meters says otherwise");
        // Waiting at a light: the phone sends nothing, the lease and the arrow stay.
        r.event("3600,distance,13");r.event("4000,distance,12");int calls=r.cluster.calls;int expired=logged("timer_expired");
        r.event("44000,tick");check(r.cluster.calls==calls&&logged("timer_expired")==expired,"forty silent seconds keep the lease");
        // The driver turns elsewhere: rerouting (state 5) passes the OEM frame through.
        r.event("45000,route,2,5");check(r.cluster.type==67&&r.cluster.distance==12,"rerouting shows the plain icon, no stale AR arrow");
        r.event("46000,stop");
        // Consecutive maneuvers: the new frame replaces the old one without a blank in between.
        r=new ArHudReplay(true);r.livePipeline=true;
        r.event("1000,details,7,2,0,67");r.event("1010,route,7");r.event("1020,distance,60");
        r.event("2000,details,8,1,0,70");calls=r.cluster.calls;int hud=r.hudCalls;
        r.event("2010,packet,8,300");
        check(r.cluster.calls==calls+1&&r.cluster.type==70&&r.cluster.distance==300,"replacement sends exactly the new frame, no zero frame");
        check(r.hudIcon==70&&r.hudCalls==hud+1,"small HUD goes straight to the new icon, no blank");
        r.event("3000,route,9");
        check(r.cluster.type==0&&r.hudIcon==-1,"replacement without details still clears at the end of the callback");
        // HUD distance glides with the arrow between phone samples.
        r=new ArHudReplay(true);r.livePipeline=true;
        r.event("1000,details,7,2,0,67");r.event("1010,route,7");r.event("1020,distance,100");
        check(r.hudDistance==100,"first HUD distance is the phone's");
        r.event("2000,distance,80");check(r.hudDistance==80,"phone update refreshes the HUD distance");
        int glides=r.hudDistanceCalls;r.event("2250,tick");
        check(r.hudDistanceCalls==glides+1&&r.hudDistance<80&&r.hudDistance>75,"worker tick moves the HUD distance with the arrow");
        check(Math.abs(r.hudDistance-r.cluster.distance)<0.01f,"HUD distance equals the main arrow distance");
        glides=r.hudDistanceCalls;r.event("2300,tick");
        check(r.hudDistanceCalls==glides,"unchanged whole-number HUD value is not re-sent");
        ArHudRuntime.reset();System.out.println("ArHudReplayTest: "+checks+" assertions passed");
    }
}
