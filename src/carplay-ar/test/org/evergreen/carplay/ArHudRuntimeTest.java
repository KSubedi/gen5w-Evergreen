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
    static final class Route {private int aR=1;private int[] bd={7};private String bb="0.8";}
    static final class Maneuver {private int DM=2,DR=0,DS=0,DU=90;}
    static final class Owner {
        private final Map<Integer,Object> Am=new HashMap<Integer,Object>();
        float hudDistance=-1;int hudUnit=-1,hudCalls,blanks;
        private void sendHudDist(float d,int u){hudDistance=d;hudUnit=u;hudCalls++;}
        private void sendHudBlank(){blanks++;}
    }

    private static ArHudSample sample(Object owner,int maneuver,float meters,long at) {
        return new ArHudSample(owner,1,maneuver,2,0,0,90,meters,at,true);
    }

    private static void motion() {
        ArHudMotion m=new ArHudMotion();m.gain(1f);m.sample(300,1000);close(m.meters(1250),300,"first sample holds");
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
        m.reset();m.gain(ArHudTuning.DEFAULT_GAIN);m.sample(300,1000);m.sample(280,2000);
        close(m.meters(2250),276,"default projection uses 80 percent of the measured closing rate");
        m.gain(5f);m.sample(260,3000);close(m.meters(3250),256,"out-of-range gain ignored");
        for(int unit=0;unit<5;unit++)for(int meters=0;meters<500;meters+=7)
            close(ArHudTurns.meters(ArHudTurns.fromMeters(meters,unit),unit),meters,"unit inverse "+unit);
    }

    private static void route() throws Exception {
        // Apple Maps' "1,000" (feet) made stock DiPO's parse fail and send distance 0.
        check("1000".equals(ArHudRoute.distanceText("1,000"))&&"12500".equals(ArHudRoute.distanceText("12,500"))
                &&"1000".equals(ArHudRoute.distanceText("1 000"))&&"1000".equals(ArHudRoute.distanceText("1\u00a0000"))
                &&"1234.5".equals(ArHudRoute.distanceText("1,234.5")),"grouping separators removed");
        check("0.8".equals(ArHudRoute.distanceText("0,8"))&&"12.25".equals(ArHudRoute.distanceText("12,25")),"decimal comma becomes a point");
        check("0.8".equals(ArHudRoute.distanceText("0.8"))&&"700".equals(ArHudRoute.distanceText("700"))&&"".equals(ArHudRoute.distanceText(""))
                &&"1,0000".equals(ArHudRoute.distanceText("1,0000"))&&"abc".equals(ArHudRoute.distanceText("abc"))
                &&ArHudRoute.distanceText(null)==null,"anything else unchanged");
        for(String text:new String[]{"1,000","12,500","0,8","1 000"})Float.parseFloat(ArHudRoute.distanceText(text));
        Route shown=new Route();shown.bb="1,000";ArHudRoute.prepare(new Owner(),1,shown);ArHudRoute.take();
        check("1000".equals(shown.bb),"prepare fixes the route update's text before DiPO parses it");
        shown.bb=null;ArHudRoute.prepare(new Owner(),1,shown);ArHudRoute.take();check(shown.bb==null,"no text, nothing changed");
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
        check(c.bar==10,"street-turn bar keeps the stock 457 m scale by default (60 m is its last step)");
        ArHudRuntime.tick(1250,true);check(c.calls==1,"first sample creates no fake motion");
        ArHudRuntime.update(c,67,0,0,900,0,3,3,0,0,sample(owner,7,40,2000),2000,true);
        ArHudRuntime.tick(2250,true);close(c.distance*0.3048f,36,"scheduled smooth arrow uses the damped closing rate");
        ArHudRuntime.tick(3500,true);close(c.distance*0.3048f,16,"bounded scheduled arrow");int sent=c.calls;
        ArHudRuntime.tick(4500,true);check(c.calls==sent,"no repeated held frames");
        ArHudRuntime.tick(7501,true);check(c.calls==sent,"stale phone cannot keep moving");
        int expiries=logged("timer_expired");
        // The car creeps up to a light: the phone's last sample before the silence is a slow one.
        ArHudRuntime.update(c,67,0,0,100,0,3,3,0,0,sample(owner,7,39,8000),8000,true);sent=c.calls;
        ArHudRuntime.tick(62000,true);check(c.calls==sent&&logged("timer_expired")==expiries,"silent phone at a light keeps the lease while the route continues");
        ArHudRuntime.tick(8000+ArHudIpc.HOLD_MS+1,true);check(logged("timer_expired")==expiries+1,"stuck sender bound still ends the lease");
        ArHudRuntime.update(c,67,0,0,300,0,0,0,0,0,sample(owner,8,50,400000),400000,true);
        ArHudRuntime.tick(400250,true);close(c.distance,50,"new maneuver resets velocity");
        ArHudRuntime.update(c,67,0,0,300,0,0,0,0,0,sample(owner,8,40,401000),401000,true);
        ArHudRuntime.tick(401500,true);close(c.distance,36,"projected toward the turn");
        ArHudRuntime.update(c,67,0,0,300,0,0,0,0,0,sample(owner,8,38,402000),402000,true);
        close(c.distance,36,"phone sample slightly above the sent distance holds instead of stepping back");
        ArHudRuntime.tick(402500,true);close(c.distance,35.6f,"resumes once the projection passes the held distance");
        ArHudRuntime.update(c,67,0,0,300,0,0,0,0,0,sample(owner,8,70,403000),403000,true);
        close(c.distance,70,"a large increase (reroute, backing up) goes through");
        ArHudSample rerouting=new ArHudSample(owner,1,8,2,0,0,90,30,404000,true,ArHudSample.STATE_REROUTING,-1,-1);
        sent=c.calls;check(ArHudRuntime.update(c,67,5,45,100,0,0,3,20,0,rerouting,404000,true),"rerouting handled in place");
        check(c.calls==sent+1&&c.type==67&&c.side==5&&c.angle==45&&c.bar==20,"rerouting keeps the OEM frame without an AR arrow");
        ArHudRuntime.tick(404250,true);check(c.calls==sent+1,"no smoothing while rerouting");
        ArHudSample start=new ArHudSample(owner,1,0,11,0,0,0,50,405000,true,6,-1,-1);
        ArHudRuntime.update(c,65,0,0,164,0,0,3,0,0,start,405000,true);
        check(c.type==65&&c.distance==164,"start-of-route cue keeps the small straight icon, no large AR");
        check(ArHudTurns.recoverIcon(11,-1,-1)==65,"start route recovers the straight cue before the zero-icon gate");
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
            ArHudIpc.writeLease(dir,boot,true,70,1000,1000+ArHudIpc.HOLD_MS-500);
            check(ArHudIpc.readLease(target,boot,1000+ArHudIpc.HOLD_MS)==70,"silent phone within the hold keeps the bar");
            check(ArHudIpc.readLease(target,boot,1000+ArHudIpc.HOLD_MS+1)==-1,"stuck sender bound rejected");
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

    private static int logged(String prefix) {
        int n=0;for(String m:android.util.Log.messages)if(m.contains(" "+prefix))n++;return n;
    }

    private static void write(File file,String text) throws Exception {
        FileOutputStream out=new FileOutputStream(file);try{out.write(text.getBytes("UTF-8"));}finally{out.close();}
    }

    private static void glide() {
        ArHudRuntime.background=false;ArHudRuntime.reset();Owner owner=new Owner();Cluster c=new Cluster();
        check(ArHudRuntime.hudDistance(150,3)==150,"inactive: OEM HUD distance retained");
        ArHudRuntime.update(c,67,0,0,150,0,0,3,0,0,sample(owner,7,40,1000),1000,true);
        close(ArHudRuntime.hudDistance(150,3)*0.3048f,40,"active: HUD distance follows the exact arrow distance in the OEM unit");
        close(ArHudRuntime.hudDistance(0.1f,2)*1609.344f,40,"unit conversion for miles");
        check(ArHudRuntime.hudDistance(150,7)==150,"unknown unit retained");
        ArHudRuntime.update(c,67,0,0,150,0,0,3,0,0,sample(owner,7,30,2000),2000,true);
        check(owner.hudCalls==0,"phone-driven sends leave the OEM HUD call alone");
        ArHudRuntime.tick(2250,true);
        check(owner.hudCalls==1&&owner.hudUnit==3,"worker tick re-sends the HUD distance in the display unit");
        close(owner.hudDistance*0.3048f,28,"glided HUD distance (0.8x of 10 m/s over 250 ms)");
        ArHudRuntime.tick(2260,true);check(owner.hudCalls==1,"same whole-foot value not re-sent");
        ArHudRuntime.tick(2500,true);check(owner.hudCalls==2,"next step re-sent");
        Object other=new Object();
        ArHudRuntime.update(c,67,0,0,150,0,0,3,0,0,sample(other,8,30,3000),3000,true);
        ArHudRuntime.tick(3250,true);check(owner.hudCalls==2,"owner without sendHudDist disables the glide quietly");
        ArHudRuntime.reset();
    }

    private static void deferredClear() {
        ArHudRuntime.background=false;ArHudRuntime.reset();Owner owner=new Owner();Cluster c=new Cluster();
        Route route=new Route();Map<Integer,Object> maneuvers=new HashMap<Integer,Object>();
        maneuvers.put(7,new Maneuver());maneuvers.put(8,new Maneuver());owner.Am.put(1,maneuvers);
        ArHudRuntime.prepare(owner,1,route,true);
        ArHudRuntime.update(c,67,0,0,150,0,0,3,0,0,sample(owner,7,40,1000),1000,true);ArHudRuntime.hudSent();ArHudRuntime.end();
        int calls=c.calls;
        route.bd=new int[]{8};ArHudRuntime.prepare(owner,1,route,true);
        check(c.calls==calls&&owner.blanks==0,"replacement does not clear immediately");
        ArHudRuntime.update(c,70,0,0,150,0,0,3,0,0,sample(owner,8,40,2000),2000,true);ArHudRuntime.hudSent();ArHudRuntime.end();
        check(c.calls==calls+1&&c.type==13&&owner.blanks==0,"new maneuver sent in the same callback: no zero frame, no HUD blank");
        calls=c.calls;route.bd=new int[]{9};ArHudRuntime.prepare(owner,1,route,true);ArHudRuntime.end();
        check(c.calls==calls+1&&c.type==0&&owner.blanks==1,"nothing sent for the new maneuver: previous display cleared at the end");
        ArHudRuntime.reset();
    }

    private static void selfHeal() throws Exception {
        ArHudRuntime.background=false;ArHudRuntime.reset();Owner owner=new Owner();Cluster c=new Cluster();
        Route first=new Route();Map<Integer,Object> known=new HashMap<Integer,Object>();
        known.put(7,new Maneuver());owner.Am.put(1,known);
        ArHudRuntime.prepare(owner,1,first,true);
        ArHudRuntime.update(c,67,0,0,150,0,0,3,0,0,sample(owner,7,40,1000),1000,true);
        int logs=android.util.Log.calls;
        ArHudRuntime.tick(1000+ArHudRuntime.QUIET_MS-1,true);
        check(android.util.Log.calls==logs||!android.util.Log.last.contains("input_quiet"),"no quiet record before the threshold");
        ArHudRuntime.tick(1000+ArHudRuntime.QUIET_MS,true);
        check(android.util.Log.last.contains("input_quiet"),"quiet record needs a sender thread");
        ArHudRuntime.tick(1000+ArHudIpc.HOLD_MS,true);
        check(c.type==13&&owner.blanks==0,"a quiet phone inside the hold keeps the display");
        int calls=c.calls;ArHudRuntime.tick(1001+ArHudIpc.HOLD_MS,true);
        check(c.calls==calls+1&&c.type==0&&owner.blanks==1,"expired input clears the main turn and the small HUD");
        ArHudRuntime.tick(2000+ArHudIpc.HOLD_MS,true);
        check(c.calls==calls+1&&owner.blanks==1,"cleared once");
        check(ArHudRuntime.hudDistance(150,3)==150,"after expiry the OEM HUD distance is retained");
        ArHudRuntime.update(c,67,0,0,150,0,0,3,0,0,sample(owner,7,30,400000),400000,true);
        check(c.type==13,"input after expiry resumes the arrow");

        // The 108 drive: 447, 443, 424, 410 m at highway speed, then nothing from the phone.
        ArHudRuntime.reset();owner=new Owner();c=new Cluster();first=new Route();
        known=new HashMap<Integer,Object>();known.put(9,new Maneuver());owner.Am.put(1,known);first.bd=new int[]{9};
        ArHudRuntime.prepare(owner,1,first,true);
        int[][] drive={{447,28208},{443,28707},{424,29248},{410,29880}};
        for(int[] d:drive)ArHudRuntime.update(c,66,0,0,d[0],0,0,0,0,0,new ArHudSample(owner,1,9,8,0,0,0,d[0],d[1],true),d[1],true);
        check(c.type==17,"the exit sign is the default inside the window");
        int expired=logged("timer_expired");
        ArHudRuntime.tick(29880+ArHudMotion.MIN_PASS_MS,true);
        check(c.type==17&&logged("timer_expired")==expired,"twenty silent seconds are not enough at 410 m");
        ArHudRuntime.tick(29880+47000,true);check(c.type==17&&owner.blanks==0,"still inside twice the time to the exit plus ten seconds");
        calls=c.calls;ArHudRuntime.tick(29880+48000,true);
        check(c.calls==calls+1&&c.type==0&&owner.blanks==1&&logged("timer_expired")==expired+1,
                "input lost while moving: cleared once the exit is certainly behind the car");
        ArHudRuntime.tick(29880+60000,true);check(c.calls==calls+1&&owner.blanks==1,"cleared once");
        ArHudRuntime.update(c,66,0,0,200,0,0,0,0,0,new ArHudSample(owner,1,9,8,0,0,0,200,120000,true),120000,true);
        check(c.type==17&&c.distance==200f,"input for the same maneuver brings the sign back");

        // The native exit sign (exit_style=sign) is cleared before the next maneuver's frame.
        File file=File.createTempFile("evergreen-ar-","-tuning");
        try {
            ArHudTuning.path=file.getPath();write(file,"exit_style=sign\n");ArHudTuning.reload();
            ArHudRuntime.reset();owner=new Owner();c=new Cluster();
            Route route=new Route();Map<Integer,Object> maneuvers=new HashMap<Integer,Object>();
            maneuvers.put(7,new Maneuver());maneuvers.put(8,new Maneuver());owner.Am.put(1,maneuvers);
            ArHudRuntime.prepare(owner,1,route,true);
            ArHudRuntime.update(c,66,0,0,300,0,0,0,0,0,new ArHudSample(owner,1,7,8,0,0,0,300,500000,true),500000,true);
            ArHudRuntime.hudSent();ArHudRuntime.end();
            check(c.type==17&&c.angle==90f,"sign style shows the native right exit");
            calls=c.calls;route.bd=new int[]{8};ArHudRuntime.prepare(owner,1,route,true);
            check(c.calls==calls+1&&c.type==0&&owner.blanks==0,"the exit sign is cleared before the next maneuver");
            ArHudRuntime.update(c,70,0,0,150,0,0,3,0,0,sample(owner,8,40,501000),501000,true);ArHudRuntime.hudSent();ArHudRuntime.end();
            check(c.calls==calls+2&&c.type==13&&owner.blanks==0,"the next maneuver follows; its HUD list needs no blank");
            ArHudRuntime.update(c,66,0,0,300,0,0,0,0,0,new ArHudSample(owner,1,8,8,0,0,0,300,502000,true),502000,true);
            calls=c.calls;route.bd=new int[]{9};ArHudRuntime.prepare(owner,1,route,true);ArHudRuntime.end();
            check(c.calls==calls+1&&c.type==0&&owner.blanks==1,"exit sign replaced by nothing: one main clear and the HUD blank");
            ArHudRuntime.reset();
        } finally {file.delete();ArHudTuning.path=null;ArHudTuning.reload();}
    }

    /** Owner logs 2026-11-21/22: the phone left the Wi-Fi, the session never ended, the arrow stayed. */
    private static void deadLink() throws Exception {
        File file=File.createTempFile("evergreen-ar-","-tuning");
        try {
            ArHudTuning.path=file.getPath();write(file,"");ArHudTuning.reload();
            ArHudRecovery.background=false;ArHudRecovery.reset();ArHudRecovery.calls=new StringBuilder();ArHudDebug.recheck();
            Cluster c=new Cluster();ArHudForwarder.lastCluster=c;ArHudForwarder.lastType=67;c.type=67;
            ArHudRecovery.event(1000,ArHudRecovery.SESSION_STARTED,0);
            ArHudRecovery.tick(100000);check(ArHudRecovery.calls.length()==0&&c.type==67,"not armed before the first iAP2 message (wired CarPlay)");
            android.os.SystemClock.now=200000;ArHudRecovery.iap();ArHudRecovery.guidance(1,200000);
            ArHudRecovery.tick(229000);check(ArHudRecovery.calls.length()==0&&c.type==67,"29 s without iAP2: still alive");
            ArHudRecovery.tick(230000);
            check(c.type==0,"dead link: the main frame is cleared at once");
            check(ArHudRecovery.calls.toString().equals("disconnectCarPlay;"),"dead link: the session is ended");
            ArHudRecovery.event(231000,ArHudRecovery.SESSION_ENDED,0);
            ArHudRecovery.tick(232000);check(!ArHudRecovery.calls.toString().contains("restartBonjour"),"advertise after a short pause");
            ArHudRecovery.tick(233000);check(ArHudRecovery.calls.toString().endsWith("restartBonjour;"),"CarPlay advertised again, though the session ended in between");
            ArHudRecovery.tick(400000);check(!ArHudRecovery.calls.toString().contains("restartGuidance"),"no guidance restart over a dead link");
            ArHudRecovery.event(410000,ArHudRecovery.SESSION_STARTED,0);ArHudDebug.recheck();
            android.os.SystemClock.now=411000;ArHudRecovery.iap();
            ArHudRecovery.calls.setLength(0);ArHudRecovery.tick(430000);check(ArHudRecovery.calls.length()==0,"a new session starts fresh");
            // A link that recovers by itself is logged; nothing more happens.
            write(file,"recover=keyframe\n");ArHudTuning.reload();c.type=67;ArHudForwarder.lastType=67;
            ArHudRecovery.tick(441000);check(c.type==0&&ArHudRecovery.calls.length()==0,"recover=keyframe: the display is cleared, the session left alone");
            android.os.SystemClock.now=442000;ArHudRecovery.iap();
            check(android.util.Log.last.contains("link_back after_ms=1000"),"the link coming back is logged");
            // A stop (DiPO's stopTurnByTurn) never leaves a main frame behind.
            ArHudForwarder.lastType=67;c.type=67;ArHudRuntime.reset();
            check(c.type==0,"reset clears a frame still showing");
            int calls=c.calls;ArHudRuntime.reset();check(c.calls==calls,"nothing showing: no extra clear");
        } finally {file.delete();ArHudTuning.path=null;ArHudTuning.reload();ArHudRecovery.calls=null;ArHudRecovery.reset();
            ArHudForwarder.lastCluster=null;ArHudForwarder.lastType=0;android.os.SystemClock.now=-1;}
    }

    /** Owner logs (EV6 US): the picture stalls while the session continues; the phone's guidance goes quiet. */
    private static void recovery() throws Exception {
        File file=File.createTempFile("evergreen-ar-","-tuning");
        try {
            ArHudTuning.path=file.getPath();write(file,"");ArHudTuning.reload();
            ArHudRecovery.background=false;ArHudRecovery.reset();ArHudRecovery.calls=new StringBuilder();
            ArHudRecovery.event(1000,ArHudRecovery.SESSION_STARTED,0);ArHudRecovery.event(1100,ArHudRecovery.SCREEN_STARTED,0);
            ArHudRecovery.tick(2000);check(ArHudRecovery.calls.length()==0,"a running picture needs nothing");
            ArHudRecovery.event(20000,ArHudRecovery.TIMEOUT,0);ArHudRecovery.event(20020,ArHudRecovery.SCREEN_STOPPED,0);
            check(ArHudRecovery.calls.toString().equals("forceKeyFrame;"),"step 1: a key frame at once");
            ArHudRecovery.event(21000,ArHudRecovery.TIMEOUT,0);
            check(ArHudRecovery.calls.toString().equals("forceKeyFrame;"),"one stall, one key frame");
            ArHudRecovery.tick(23900);check(ArHudRecovery.calls.toString().equals("forceKeyFrame;"),"waits before taking the screen");
            ArHudRecovery.tick(24000);check(ArHudRecovery.calls.toString().endsWith("changeModeBorrowScreen;"),"step 2: borrow the screen");
            ArHudRecovery.tick(25000);check(!ArHudRecovery.calls.toString().contains("Unborrow"),"holds it briefly");
            ArHudRecovery.tick(25500);check(ArHudRecovery.calls.toString().endsWith("changeModeUnborrowScreen;"),"and gives it back");
            ArHudRecovery.tick(60000);check(!ArHudRecovery.calls.toString().contains("disconnect"),"no reconnect by default");
            int before=android.util.Log.calls;ArHudRecovery.event(60500,ArHudRecovery.SCREEN_STARTED,0);
            check(android.util.Log.last.contains("recover_ok after_ms=40500 step=2"),"the recovery and its step are logged");
            ArHudRecovery.calls.setLength(0);ArHudRecovery.event(61000,ArHudRecovery.TIMEOUT,0);
            check(ArHudRecovery.calls.toString().equals("forceKeyFrame;"),"a later stall starts again at step 1");
            ArHudRecovery.event(61500,ArHudRecovery.SCREEN_STARTED,0);
            // A decode failure while the picture runs: a key frame, at most every five seconds, never more.
            ArHudRecovery.calls.setLength(0);ArHudRecovery.event(70000,ArHudRecovery.DECODE,1);ArHudRecovery.event(71000,ArHudRecovery.DECODE,1);
            ArHudRecovery.tick(80000);check(ArHudRecovery.calls.toString().equals("forceKeyFrame;"),"decode failure: one rate-limited key frame");
            ArHudRecovery.event(80000,ArHudRecovery.SCREEN_STOPPED,0);ArHudRecovery.calls.setLength(0);
            ArHudRecovery.event(81000,ArHudRecovery.DECODE,3);check(ArHudRecovery.calls.length()==0,"not while the picture is stopped (driver left CarPlay)");
            // recover=reconnect adds step 3; recover=off does nothing.
            write(file,"recover=reconnect\n");ArHudTuning.reload();ArHudRecovery.calls.setLength(0);
            ArHudRecovery.event(90000,ArHudRecovery.TIMEOUT,0);ArHudRecovery.tick(94000);ArHudRecovery.tick(95500);
            ArHudRecovery.tick(105400);check(!ArHudRecovery.calls.toString().contains("disconnect"),"step 3 waits ten seconds after step 2");
            ArHudRecovery.tick(105500);check(ArHudRecovery.calls.toString().endsWith("disconnectCarPlay;"),"step 3: end the session");
            ArHudRecovery.tick(108500);check(ArHudRecovery.calls.toString().endsWith("restartBonjour;"),"then advertise CarPlay again");
            ArHudRecovery.event(110000,ArHudRecovery.SESSION_ENDED,0);ArHudRecovery.event(114000,ArHudRecovery.SESSION_STARTED,0);
            write(file,"recover=off\n");ArHudTuning.reload();ArHudRecovery.calls.setLength(0);
            ArHudRecovery.event(120000,ArHudRecovery.TIMEOUT,0);ArHudRecovery.tick(130000);
            check(ArHudRecovery.calls.length()==0,"recover=off: nothing");
            ArHudRecovery.event(130500,ArHudRecovery.SCREEN_STARTED,0);
            // Guidance: silent for 90 s while active restarts once; any update re-arms; a stop never restarts.
            write(file,"");ArHudTuning.reload();ArHudRecovery.calls.setLength(0);
            ArHudRecovery.guidance(1,200000);ArHudRecovery.tick(289000);check(ArHudRecovery.calls.length()==0,"60-90 s of quiet (a red light) is left alone");
            ArHudRecovery.tick(290000);check(ArHudRecovery.calls.toString().equals("restartGuidance;"),"90 s of silent active guidance: restart");
            ArHudRecovery.tick(400000);check(ArHudRecovery.calls.toString().equals("restartGuidance;"),"once per silence");
            ArHudRecovery.guidance(1,410000);ArHudRecovery.tick(500000);check(ArHudRecovery.calls.toString().equals("restartGuidance;restartGuidance;"),"re-armed by the next update");
            ArHudRecovery.calls.setLength(0);ArHudRecovery.guidance(1,600000);ArHudRecovery.guidanceStopped();ArHudRecovery.tick(800000);
            check(ArHudRecovery.calls.length()==0,"ended guidance is never restarted");
            ArHudRecovery.guidance(1,900000);ArHudRecovery.event(900100,ArHudRecovery.SESSION_ENDED,0);ArHudRecovery.tick(1000000);
            check(ArHudRecovery.calls.length()==0,"no restart without a session");
            write(file,"guidance_restart_s=0\n");ArHudTuning.reload();ArHudRecovery.event(1000100,ArHudRecovery.SESSION_STARTED,0);
            ArHudRecovery.guidance(1,1000200);ArHudRecovery.tick(2000000);check(ArHudRecovery.calls.length()==0,"guidance_restart_s=0 turns it off");
            float[] t=ArHudTuning.parse("recover=keyframe\nguidance_restart_s=120\n");
            check(t[7]==ArHudTuning.RECOVER_KEYFRAME&&t[8]==120f,"recovery tuning parses");
            t=ArHudTuning.parse("recover=maybe\nguidance_restart_s=30\n");
            check(t[7]==ArHudTuning.RECOVER_SCREEN&&t[8]==90f,"unknown mode and out-of-range seconds keep the defaults");
            // The real calls fail safely without DiPO: logged, never thrown.
            // A fresh rate-limit window: the stubbed clock keeps every record above in one second.
            ArHudRecovery.calls=null;ArHudRecovery.reset();ArHudDebug.recheck();before=android.util.Log.calls;
            ArHudRecovery.event(3000000,ArHudRecovery.SESSION_STARTED,0);ArHudRecovery.event(3000100,ArHudRecovery.TIMEOUT,0);
            check(android.util.Log.last.contains("recover_failed forceKeyFrame no_service"),"no DiPO service: logged and skipped");
            write(file,"");ArHudTuning.reload();ArHudDebug.recheck();ArHudRecovery.guidance(1,3000000);ArHudRecovery.tick(3100000);
            check(android.util.Log.last.contains("guidance_restart_failed java.lang.ClassNotFoundException"),"no DiPO classes: logged and skipped");
        } finally {file.delete();ArHudTuning.path=null;ArHudTuning.reload();ArHudRecovery.calls=null;ArHudRecovery.reset();}
    }

    /** 111 drive: a tick whose clock reading predates the phone's latest sample blanked the arrow 25 times. */
    private static void lateTick() {
        ArHudRuntime.background=false;ArHudRuntime.reset();Cluster c=new Cluster();Object owner=new Object();
        ArHudRuntime.update(c,67,0,0,100,0,0,0,0,0,sample(owner,7,100,700000),700000,true);
        int expired=logged("timer_expired"),calls=c.calls;
        ArHudRuntime.tick(699980,true);
        check(logged("timer_expired")==expired&&c.calls==calls&&c.type==13,"a tick timed just before the phone's sample keeps the lease");
        ArHudRuntime.update(c,67,0,0,100,0,0,0,0,0,sample(owner,7,95,701000),701000,true);
        check(c.calls==calls+1&&c.type==13,"the next sample continues the same maneuver without a clear");
        check(ArHudRuntime.tick(706000,true)&&!ArHudRuntime.tick(706250,true),"heartbeat every five seconds, reported to the caller");
        ArHudRuntime.reset();
    }

    private static void tuning() throws Exception {
        File file=File.createTempFile("evergreen-ar-","-tuning");
        try {
            ArHudTuning.path=file.getPath();ArHudTuning.reload();
            write(file,"street_ar_meters=90\ngain=0.6\nbackstep_meters=5\n");
            check(ArHudTuning.streetArMeters(1000)==90f&&ArHudTuning.gain(1000)==0.6f&&ArHudTuning.backstepMeters(1000)==5f,"tuning file read");
            write(file,"street_ar_meters=1250\ngain=2\nbackstep_meters=-1\n");ArHudTuning.reload();
            check(ArHudTuning.streetArMeters(5000)==200f&&ArHudTuning.gain(5000)==0.8f&&ArHudTuning.backstepMeters(5000)==15f,"out-of-range values keep defaults");
            write(file,"# comment\nstreet_ar_meters = 210\nunknown=1\ngain=abc\n");ArHudTuning.reload();
            check(ArHudTuning.streetArMeters(9000)==210f&&ArHudTuning.gain(9000)==0.8f,"comments, spaces, unknown keys and bad numbers tolerated");
            write(file,"street_ar_meters=90\n");ArHudTuning.reload();
            ArHudRuntime.background=false;ArHudRuntime.reset();Cluster c=new Cluster();Object owner=new Object();
            ArHudRuntime.update(c,67,0,0,100,0,0,0,0,0,sample(owner,7,100,20000),20000,true);
            check(c.type==67,"tuned street window defers AR at 100 m");
            ArHudRuntime.update(c,67,0,0,80,0,0,0,0,0,sample(owner,7,80,21000),21000,true);
            check(c.type==13&&c.bar==10&&c.distance==80f,"stock bar scale by default: 80 m of 457 is the last step");
            write(file,"street_ar_meters=90\nbar_meters=90\n");ArHudTuning.reload();
            ArHudRuntime.update(c,67,0,0,80,0,0,0,0,0,sample(owner,7,80,23100),23100,true);
            check(c.type==13&&c.bar==80,"tuned bar window: 80 m of 90 fills the bar to 80");
            write(file,"turn_offset_meters=20\n");ArHudTuning.reload();
            ArHudRuntime.update(c,67,0,0,80,0,0,0,0,0,sample(owner,7,80,25200),25200,true);
            check(c.type==13&&c.distance==100f&&c.bar==10,"turn offset moves the arrow's distance, not the bar");
            check(ArHudRuntime.hudDistance(80f,0)==80f,"the HUD number stays the phone's distance under an offset");
            ArHudRuntime.tick(25450,true);check(c.distance>99f&&c.distance<100.01f,"offset holds across worker ticks");
            // Raw 8 (direction-unspecified exit) under the tuning modes.
            write(file,"exit_side=left\nexit_style=slight\n");ArHudTuning.reload();
            ArHudSample exit=new ArHudSample(owner,1,9,8,0,0,0,300,27300,true);
            check(ArHudRuntime.resolveIcon(0,exit,true)==71,"left mode recovers the slight-left icon");
            ArHudRuntime.update(c,71,0,0,300,0,0,0,0,0,exit,27300,true);
            check(c.type==13&&c.angle==315f&&c.bar==60&&c.distance==300f,"slight style sends the slight-left arrow inside the full window");
            write(file,"exit_side=left\n");ArHudTuning.reload();
            check(ArHudRuntime.resolveIcon(0,exit,true)==71,"sign style (the default) keeps the stock-sendable icon for the far frame and the small HUD");
            ArHudRuntime.update(c,71,0,0,300,0,0,0,0,0,exit,27400,true);
            check(c.type==18&&c.angle==270f&&c.bar==60&&c.distance==300f,"sign style sends the native left exit");
            ArHudSample farExit=new ArHudSample(owner,1,9,8,0,0,0,900,27500,true);
            ArHudRuntime.update(c,71,0,0,900,0,0,0,0,0,farExit,27500,true);
            check(c.type==71,"sign style: outside the window the icon is kept");
            write(file,"exit_side=generic\n");ArHudTuning.reload();
            check(ArHudRuntime.resolveIcon(0,exit,true)==86,"generic mode keeps icon 86");
            check(ArHudRuntime.resolveIcon(0,exit,false)==0,"AR off: a zero icon stays zero (traced, not recovered)");
            check(ArHudRuntime.resolveIcon(71,exit,false)==71,"AR off: a stock icon is kept");
            ArHudRuntime.update(c,86,0,0,300,0,0,0,0,0,exit,29400,true);
            check(c.type==86,"generic mode never sends a native exit");
            file.delete();ArHudTuning.reload();
            check(ArHudTuning.streetArMeters(30000)==200f&&ArHudTuning.barMeters(30000)==457f
                    &&ArHudTuning.turnOffsetMeters(30000)==0f&&ArHudTuning.exitSide(30000)==ArHudTuning.EXIT_AUTO
                    &&ArHudTuning.exitStyle(30000)==ArHudTuning.EXIT_STYLE_SIGN,"removed file returns to defaults");
            ArHudRuntime.reset();
        } finally {file.delete();ArHudTuning.path=null;ArHudTuning.reload();}
    }

    public static void main(String[] args) throws Exception {
        ArHudDebug.flagPath=null; // Host tests read the trace without the car's flag file.
        motion();route();runtime();traps();ipc();tuning();glide();deferredClear();selfHeal();lateTick();recovery();deadLink();
        System.out.println("AR runtime, metadata, interpolation, IPC and OEM frame preservation: "+checks+" checks passed");
    }
}
