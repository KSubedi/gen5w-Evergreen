package org.evergreen.carplay;

import com.hkmc.cluster.NavigationCluster;
import com.hkmc.cluster.NavigationCluster.NaviTbtTrapInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/** Synthetic route and OEM service fixtures; no manufacturer implementation. */
public final class ArHudRuntimeTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if(!ok)throw new AssertionError(message); }
    private static void close(float a,float b,String message) {check(Math.abs(a-b)<0.02f,message+": "+a+" vs "+b);}

    static final class Cluster extends NavigationCluster {
        int calls,type,scale,bar,side,combi; float distance,angle; int[] legacy; NaviTbtTrapInfo modern;
        public void updateTBTInfo(int type,int side,float angle,float distance,int err,int combi,int unit,int bar,int barErr) {
            calls++;this.type=type;this.side=side;this.angle=angle;this.distance=distance;this.combi=combi;scale=unit;this.bar=bar;
        }
        public void updateTBTTrapInfoNew(NaviTbtTrapInfo frame) {modern=ArHudTrap.copy(frame);legacy=null;calls++;}
        public void updateTBTTrapInfo(int a0,int a1,int a2,int a3,int a4,int a5,int a6,int a7,int a8,
                int a9,int a10,int a11,int a12,int a13,int a14,int a15,int a16) {
            legacy=new int[]{a0,a1,a2,a3,a4,a5,a6,a7,a8,a9,a10,a11,a12,a13,a14,a15,a16};modern=null;calls++;
        }
    }
    static final class Route {private int aR=1;private int[] bd={7};}
    static final class Maneuver {private int DM=2,DR=0,DS=0,DU=90;}
    static final class Owner {private final Map<Integer,Object> Am=new HashMap<Integer,Object>();}

    private static ArHudSample sample(Object owner,int maneuver,float meters,long at) {
        return new ArHudSample(owner,1,maneuver,2,0,0,90,meters,at,true);
    }

    private static void motion() {
        ArHudMotion m=new ArHudMotion();m.sample(300,1000);close(m.meters(1250),300,"first sample holds");
        m.sample(280,2000);close(m.meters(2250),275,"closing rate advances quarter second");
        close(m.meters(3500),250,"maximum 1.5-second extrapolation");close(m.meters(4500),250,"stale exact holds");
        m.sample(280,3000);close(m.meters(3250),280,"stationary new sample stops motion");
        m.sample(290,4000);close(m.meters(4250),290,"distance increase stops motion");
        m.sample(100,5000);close(m.meters(5250),100,"implausible jump stops motion");
        m.sample(90,9000);close(m.meters(9250),90,"long gap stops motion");
        m.sample(70,10000);close(m.meters(10250),65,"resumes with fresh consecutive samples");
        m.sample(0,100);close(m.meters(10250),65,"out-of-order callback ignored");
        m.sample(-1,11000);check(m.meters(11250)<0,"invalid distance resets");
        m.reset();m.sample(10,1000);m.sample(0,2000);close(m.meters(2250),0,"never runs below zero");
        for(int unit=0;unit<5;unit++)for(int meters=0;meters<500;meters+=7)
            close(ArHudTurns.meters(ArHudTurns.fromMeters(meters,unit),unit),meters,"unit inverse "+unit);
    }

    private static void route() throws Exception {
        Owner owner=new Owner();Route route=new Route();Map<Integer,Object> maneuvers=new HashMap<Integer,Object>();
        maneuvers.put(7,new Maneuver());owner.Am.put(1,maneuvers);
        ArHudRoute.prepare(owner,1,route);ArHudSample s=ArHudRoute.take();
        check(s!=null&&s.exactAt<0,"default field zero must not become an exact sample");
        ArHudRoute.exact(route,300,1000);ArHudRoute.prepare(owner,1,route);s=ArHudRoute.take();
        check(s!=null&&s.owner==owner&&s.component==1&&s.maneuver==7,"route identity captured");
        check(s.type==2&&s.angle==90&&s.drivingSide==0&&s.junction==0,"raw metadata captured");
        close(s.exactMeters,300,"raw exact meters");check(s.exactAt==1000,"raw receipt time");
        check(ArHudRoute.take()==null,"prepared metadata consumed once");
        Maneuver next=new Maneuver();maneuvers.put(8,next);maneuvers.put(9,next);
        ArHudRoute.begin(route);route.bd=new int[]{8};ArHudRoute.finish(route);
        ArHudRoute.prepare(owner,1,route);check(ArHudRoute.take().exactAt<0,"new maneuver never borrows old exact distance");
        ArHudRoute.begin(route);ArHudRoute.exact(route,280,2000);route.bd=new int[]{9};ArHudRoute.finish(route);
        ArHudRoute.prepare(owner,1,route);s=ArHudRoute.take();
        check(s.maneuver==9&&s.exactAt==2000,"distance before maneuver list binds at packet completion");
        route.bd=new int[]{7};ArHudRoute.forget(route);
        ArHudRoute.forget(route);ArHudRoute.prepare(owner,1,route);check(ArHudRoute.take().exactAt<0,"route reset forgets raw sample");
        ArHudRoute.exact(route,-1,2000);ArHudRoute.prepare(owner,1,route);check(ArHudRoute.take().exactAt<0,"unsigned invalid sentinel rejected");
        ArHudRoute.prepare(owner,1,route);
        Thread clear=new Thread(new Runnable(){public void run(){ArHudRoute.clear();}});clear.start();clear.join();
        check(ArHudRoute.take()==null,"source change invalidates other-thread pending context");
        ArHudRoute.prepare(new Object(),1,route);check(ArHudRoute.take()==null,"missing fields fail to baseline");
        route.aR=0;ArHudRoute.prepare(owner,1,route);check(ArHudRoute.stopped(),"route stop recognized");ArHudRoute.clear();
    }

    private static void runtime() {
        ArHudRuntime.background=false;ArHudRuntime.reset();Object owner=new Object();Cluster c=new Cluster();
        check(ArHudRuntime.update(c,67,0,0,1000,0,3,3,0,0,sample(owner,7,60,1000),1000,true),"advanced handled");
        check(c.calls==1&&c.type==13&&c.scale==3&&c.combi==3,"native turn preserves original units/combi");
        close(c.distance*0.3048f,60,"first exact distance sent");
        ArHudRuntime.tick(1250,true);check(c.calls==1,"first sample creates no fake motion");
        ArHudRuntime.update(c,67,0,0,900,0,3,3,0,0,sample(owner,7,40,2000),2000,true);
        ArHudRuntime.tick(2250,true);close(c.distance*0.3048f,35,"scheduled smooth arrow");
        ArHudRuntime.tick(3500,true);close(c.distance*0.3048f,10,"bounded scheduled arrow");int sent=c.calls;
        ArHudRuntime.tick(4500,true);check(c.calls==sent,"no repeated held frames");
        ArHudRuntime.tick(7501,true);check(c.calls==sent,"stale phone cannot keep moving");
        ArHudRuntime.update(c,67,0,0,300,0,0,0,0,0,sample(owner,8,50,8000),8000,true);
        ArHudRuntime.tick(8250,true);close(c.distance,50,"new maneuver resets velocity");
        ArHudRuntime.update(c,67,0,0,100,0,0,0,0,0,sample(new Object(),8,40,9000),9000,true);
        ArHudRuntime.tick(9250,true);close(c.distance,40,"new owner resets velocity");
        check(!ArHudRuntime.update(c,3,0,0,0,0,0,0,0,0,sample(owner,8,0,10000),10000,true),"explicit native arrival passes through");
        check(!ArHudRuntime.update(c,67,0,0,100,0,0,0,0,0,sample(owner,8,80,10000),10000,false),"basic switch uses proven adapter");
        check(!ArHudRuntime.update(c,67,0,0,100,-1,0,0,0,0,sample(owner,8,80,10000),10000,true),"error flags pass through");
        ArHudRuntime.update(c,67,123,45,10,0,5,1,44,0,sample(owner,8,10000,11000),11000,true);
        check(c.type==67&&c.side==123&&c.angle==45&&c.scale==1&&c.bar==44,"far frames preserve all original fields");
        close(c.distance,10,"far display untouched");
        ArHudRuntime.reset();sent=c.calls;ArHudRuntime.tick(11250,true);check(c.calls==sent,"disconnect cancels pending tick");
    }

    private static void traps() throws Exception {
        ArHudTrap.background=false;Cluster c=new Cluster();NaviTbtTrapInfo frame=new NaviTbtTrapInfo();
        Map<String,Integer> expected=new HashMap<String,Integer>();int i=1;
        for(Field f:NaviTbtTrapInfo.class.getFields()){f.setInt(frame,i++);expected.put(f.getName(),f.getInt(frame));}
        ArHudTrap.updateNew(c,frame);check(c.modern!=frame,"OEM input is not mutated");
        frame.speedLimit=99;ArHudTrap.tick(70);
        for(Field f:NaviTbtTrapInfo.class.getFields())
            check(f.getInt(c.modern)==(f.getName().equals("barGraphLevel")?70:expected.get(f.getName())),"preserved object field "+f.getName());
        ArHudTrap.tick(-1);
        for(Field f:NaviTbtTrapInfo.class.getFields())check(f.getInt(c.modern)==expected.get(f.getName()),"expired lease restores OEM "+f.getName());
        frame.speedLimit=35;frame.speedLimitUnit=2;ArHudTrap.updateNew(c,frame);ArHudTrap.tick(20);
        check(c.modern.speedLimit==35&&c.modern.speedLimitUnit==2,"new OEM limit supersedes cached limit");
        ArHudTrap.update(c,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17);ArHudTrap.tick(40);
        check(ArHudTrap.BAR_INDEX==11,"firmware-verified bar index");
        for(i=0;i<17;i++)check(c.legacy[i]==(i==11?40:i+1),"legacy field "+i+" preserved");
        ArHudTrap.tick(-1);for(i=0;i<17;i++)check(c.legacy[i]==i+1,"legacy restored "+i);
        ArHudTrap.updateNew(c,frame);ArHudTrap.tick(10);check(c.modern.speedLimit==35,"object/legacy switching preserves latest format");
    }

    private static void ipc() throws Exception {
        File dir=File.createTempFile("evergreen-ar-","-ipc");dir.delete();check(dir.mkdir(),"test IPC directory");
        File target=new File(dir,"progress");String boot="00000000-0000-0000-0000-000000000000";
        try {
            check(ArHudIpc.writeLease(dir,boot,true,70,1000,1100),"atomic lease write");
            check(ArHudIpc.readLease(target,boot,1200)==70,"valid lease");
            check(ArHudIpc.readLease(target,"different-boot",1200)==-1,"other boot rejected");
            check(ArHudIpc.readLease(target,boot,1000)==-1,"clock reversal rejected");
            check(ArHudIpc.readLease(target,boot,4201)==-1,"missing heartbeat rejected");
            check(ArHudIpc.readLease(target,boot,6101)==-1,"stale phone rejected");
            ArHudIpc.writeLease(dir,boot,false,0,2000,2000);check(ArHudIpc.readLease(target,boot,2100)==-1,"explicit stop restores original");
            for(int bar:new int[]{-1,1,9,91,100,255}){
                ArHudIpc.writeLease(dir,boot,true,bar,2000,2000);check(ArHudIpc.readLease(target,boot,2100)==-1,"invalid bar "+bar);
            }
            ArHudIpc.writeLease(dir,boot,true,50,3000,2000);check(ArHudIpc.readLease(target,boot,3100)==-1,"sample after publication rejected");
            ArHudIpc.writeLease(dir,boot,true,50,2000,2000);
            FileOutputStream stream=new FileOutputStream(target,true);stream.write(1);stream.close();
            check(ArHudIpc.readLease(target,boot,2100)==-1,"trailing bytes rejected");
            stream=new FileOutputStream(target);stream.write(new byte[500]);stream.close();
            check(ArHudIpc.readLease(target,boot,2100)==-1,"oversized file rejected");
            stream=new FileOutputStream(target);stream.write(new byte[7]);stream.close();
            check(ArHudIpc.readLease(target,boot,2100)==-1,"truncated file rejected");
        } finally {target.delete();new File(dir,"progress.tmp").delete();dir.delete();}
    }

    public static void main(String[] args) throws Exception {
        motion();route();runtime();traps();ipc();
        System.out.println("AR runtime, metadata, interpolation, IPC and OEM frame preservation: "+checks+" checks passed");
    }
}
