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
    static final class Route { private int aR; private int[] bd; }
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
    }
    static final class Cluster extends NavigationCluster {
        int calls,type; float angle,distance;
        public void updateTBTInfo(int t,int side,float a,float d,int error,int combined,int unit,int bar,int barError) {
            calls++;type=t;angle=a;distance=d;
        }
    }
    final Owner owner=new Owner();
    final Route route=new Route();
    final Map<Integer,Maneuver> maneuvers=new HashMap<Integer,Maneuver>();
    final Cluster cluster=new Cluster();
    final boolean refreshDetails;
    long now;
    int hudIcon=-1,hudCalls;
    float display=-1;

    ArHudReplay(boolean refreshDetails) {
        this.refreshDetails=refreshDetails;owner.Am.put(1,maneuvers);owner.Al.put(1,route);owner.replay=this;
        ArHudRuntime.background=false;ArHudRuntime.reset();
    }
    void refresh() {
        // Deliberately model readiness gates, not the entire stock icon selection.
        if(route.aR==0||route.bd==null||display<0)return;
        Maneuver m=maneuvers.get(route.bd[0]);if(m==null)return;
        ArHudRoute.prepare(owner,1,route);
        ArHudSample s=ArHudRoute.take();
        boolean handled=ArHudRuntime.update(cluster,m.icon,0,0,display,0,0,0,0,0,s,now,true);
        if(!handled)ArHudForwarder.forward(true,cluster,m.icon,0,0,display,0,0,0,0,0);
        // Host model of the separate turn-list output. Runtime ticks do not call it.
        if(hudIcon!=m.icon){hudIcon=m.icon;hudCalls++;}
    }
    void event(String line) {
        String[] p=line.split(",");now=Long.parseLong(p[0].trim());String op=p[1].trim();
        if(op.equals("route")) {
            ArHudRoute.begin(route);route.aR=1;route.bd=new int[]{Integer.parseInt(p[2].trim())};
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
    static void replay(String path,boolean candidate,PrintStream out) throws Exception {
        ArHudReplay r=new ArHudReplay(candidate);BufferedReader input=new BufferedReader(new FileReader(path));
        long previous=-1;
        try {
            out.println("time,event,main_calls,main_type,angle,meters,hud_icon,hud_calls");
            String line;int number=0;
            while((line=input.readLine())!=null) {
                number++;line=line.trim();if(line.length()==0||line.startsWith("#"))continue;
                try {
                    long time=Long.parseLong(line.split(",")[0].trim());
                    if(time<0||time<previous)throw new IllegalArgumentException("timestamps must be ordered");
                    r.event(line);previous=time;
                    out.println(r.now+","+line.split(",")[1]+","+r.cluster.calls+","+r.cluster.type+","+
                            r.cluster.angle+","+r.cluster.distance+","+r.hudIcon+","+r.hudCalls);
                } catch(RuntimeException bad){throw new IllegalArgumentException(path+":"+number+": "+bad.getMessage(),bad);}
            }
        } finally {input.close();ArHudRuntime.reset();}
    }
    public static void main(String[] args) throws Exception {
        if(args.length<1||args.length>2||(args.length==2&&!args[1].equals("--refresh-details")))
            throw new IllegalArgumentException("Usage: ArHudReplay scenario.csv [--refresh-details]");
        replay(args[0],args.length==2,System.out);
    }
}
