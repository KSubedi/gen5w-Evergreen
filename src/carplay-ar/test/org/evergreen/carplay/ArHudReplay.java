// SPDX-License-Identifier: GPL-3.0-only
package org.evergreen.carplay;

import com.hkmc.cluster.NavigationCluster;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

/** Host-only behavioral model of DiPO's split callbacks, not its OEM implementation.
 * Executes the real route adapter/runtime/converter against a recording cluster.
 * No wall-clock sleeps, background workers, car connection or OEM files required.
 */
public final class ArHudReplay {
    static final class Route { private int aR; private int aS=-1; private int[] bd; }
    static final class Maneuver {
        private int DM, DR=0, DS=0, DU;
        int icon;
        Maneuver(int type,int angle,int icon) {DM=type;DU=angle;this.icon=icon;}
    }
    static final class Owner {
        private final Map<Integer,Object> Am=new HashMap<Integer,Object>();
        private final Map<Integer,Object> Al=new HashMap<Integer,Object>();
        ArHudReplay replay;
        private void a(Integer component,Object route) { replay.refresh(); }
        private void sendHudBlank() { replay.hudIcon=-1;replay.hudCalls++; }
        private void sendHudDist(float distance,int unit) { replay.hudDistance=distance;replay.hudUnit=unit;replay.hudDistanceCalls++; }
    }
    static final class Cluster extends NavigationCluster {
        int calls,type,bar; float angle,distance;
        public void updateTBTInfo(int t,int side,float a,float d,int error,int combined,int unit,int b,int barError) {
            calls++;type=t;angle=a;distance=d;bar=b;
        }
    }
    final Owner owner=new Owner();
    final Route route=new Route();
    final Map<Integer,Maneuver> maneuvers=new HashMap<Integer,Maneuver>();
    final Cluster cluster=new Cluster();
    final boolean refreshDetails;
    boolean livePipeline;
    long now;
    int hudIcon=-1,hudCalls,hudUnit=-1,hudDistanceCalls;
    float display=-1,hudDistance=-1;

    ArHudReplay(boolean refreshDetails) {
        this.refreshDetails=refreshDetails;owner.Am.put(1,maneuvers);owner.Al.put(1,route);owner.replay=this;
        ArHudRuntime.background=false;ArHudRuntime.reset();
    }
    void refresh() {
        // Deliberately model readiness gates, not the entire stock icon selection.
        if(route.aR==0||route.bd==null||display<0)return;
        if(livePipeline)ArHudRuntime.prepare(owner,1,route,true);
        try {
            Maneuver m=maneuvers.get(route.bd[0]);if(m==null)return;
            int selected=livePipeline?(!ArHudRoute.distanceReady()?0:ArHudRuntime.resolveIcon(m.icon,ArHudRoute.peek(),true)):m.icon;
            if(selected==0)return;
            // DiPO skips the final sender for icon zero, before our adapter can run.
            ArHudRoute.prepare(owner,1,route);
            ArHudSample s=ArHudRoute.take();
            boolean handled=ArHudRuntime.update(cluster,selected,0,0,display,0,0,0,0,0,s,now,true);
            if(!handled)ArHudForwarder.forward(true,cluster,selected,0,0,display,0,0,0,0,0);
            ArHudRuntime.mainSent();
            // Host model of the separate turn-list output. Runtime ticks do not call it.
            if(hudIcon!=selected){hudIcon=selected;hudCalls++;}
            ArHudRuntime.hudSent();
            // The OEM then sends its rounded HUD distance through the hooked entry.
            owner.sendHudDist(ArHudRuntime.hudDistance(display,0),0);
        } finally { if(livePipeline)ArHudRuntime.end(); }
    }
    void event(String line) {
        String[] p=line.split(",");now=Long.parseLong(p[0].trim());String op=p[1].trim();
        if(op.equals("route")) {
            // route,<maneuver>[,<route state>[,<maneuver state>]]: state 1 unless given.
            ArHudRoute.begin(route);route.aR=p.length>3?Integer.parseInt(p[3].trim()):1;
            route.aS=p.length>4?Integer.parseInt(p[4].trim()):-1;
            route.bd=new int[]{Integer.parseInt(p[2].trim())};
            ArHudRoute.finish(route);refresh();
        } else if(op.equals("packet")) {
            // packet,<maneuver>,<distance>[,<route state>]: one RouteGuidanceUpdate carrying both,
            // as DiPO receives it when a maneuver changes on the road.
            ArHudRoute.begin(route);route.aR=p.length>4?Integer.parseInt(p[4].trim()):1;
            route.bd=new int[]{Integer.parseInt(p[2].trim())};
            display=Float.parseFloat(p[3].trim());ArHudRoute.exact(route,Integer.parseInt(p[3].trim()),now);
            ArHudRoute.finish(route);refresh();
        } else if(op.equals("details")) {
            maneuvers.put(Integer.parseInt(p[2].trim()),new Maneuver(Integer.parseInt(p[3].trim()),
                    Integer.parseInt(p[4].trim()),Integer.parseInt(p[5].trim())));
            if(refreshDetails)ArHudRoute.refreshDetails(owner);
        } else if(op.equals("distance")) {
            display=Float.parseFloat(p[2].trim());ArHudRoute.begin(route);
            ArHudRoute.exact(route,Integer.parseInt(p[2].trim()),now);ArHudRoute.finish(route);refresh();
        } else if(op.equals("tick"))ArHudRuntime.tick(now,true);
        else if(op.equals("stop")) {route.aR=0;ArHudRuntime.reset();hudIcon=-1;}
        else if(op.equals("source")) {
            ArHudRuntime.reset();maneuvers.clear();route.aR=0;route.bd=null;display=-1;hudIcon=-1;
        } else throw new IllegalArgumentException("Unknown event: "+op);
    }
    static void replay(String path,boolean candidate,boolean live,PrintStream out) throws Exception {
        ArHudReplay r=new ArHudReplay(candidate);r.livePipeline=live;BufferedReader input=new BufferedReader(new FileReader(path));
        long previous=-1;
        try {
            out.println("time,event,main_calls,main_type,angle,meters,bar,hud_icon,hud_calls,hud_distance");
            String line;int number=0;
            while((line=input.readLine())!=null) {
                number++;line=line.trim();if(line.length()==0||line.startsWith("#"))continue;
                try {
                    long time=Long.parseLong(line.split(",")[0].trim());
                    if(time<0||time<previous)throw new IllegalArgumentException("timestamps must be ordered");
                    r.event(line);previous=time;
                    out.println(r.now+","+line.split(",")[1]+","+r.cluster.calls+","+r.cluster.type+","+
                            r.cluster.angle+","+r.cluster.distance+","+r.cluster.bar+","+r.hudIcon+","+r.hudCalls+","+r.hudDistance);
                } catch(RuntimeException bad){throw new IllegalArgumentException(path+":"+number+": "+bad.getMessage(),bad);}
            }
        } finally {input.close();ArHudRuntime.reset();}
    }
    public static void main(String[] args) throws Exception {
        if(args.length<1)throw new IllegalArgumentException("Usage: ArHudReplay scenario.csv [--refresh-details] [--live]");
        boolean refresh=false,live=false;
        for(int i=1;i<args.length;i++) {
            if(args[i].equals("--refresh-details"))refresh=true;
            else if(args[i].equals("--live")){live=true;refresh=true;}
            else throw new IllegalArgumentException("Unknown replay option: "+args[i]);
        }
        replay(args[0],refresh,live,System.out);
    }
}
