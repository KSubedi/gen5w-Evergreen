package org.evergreen.carplay;

import java.util.Arrays;

/** Protocol scenarios and state boundaries; no Android or manufacturer files. */
public final class ArHudAdvancedTest {
    private static int checks;
    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
    private static int[] raw(int type, int side, int junction, int angle, boolean present, int icon, float meters, int state) {
        return ArHudTurns.maneuver(type, side, junction, angle, present, icon, meters, state);
    }
    private static void expected(int[] got, int type, int angle, int bar, String what) {
        check(Arrays.equals(got, new int[] {type,angle,bar}), what);
    }
    public static void main(String[] args) {
        // A phone app can omit its optional exit angle; direction still comes from type.
        expected(raw(1,0,0,0,false,70,200,1),13,270,40,"left without angle");
        expected(raw(2,0,0,0,false,67,200,1),13,90,40,"right without angle");
        expected(raw(2,0,0,120,true,68,200,1),13,135,40,"angle quantized");
        expected(raw(1,0,0,-60,true,71,200,1),13,315,40,"negative angle");
        expected(raw(2,0,0,-90,true,67,200,1),13,90,40,"contradictory angle falls back");
        expected(raw(4,1,0,0,false,73,200,1),31,90,40,"left-hand U-turn");
        expected(raw(13,0,0,0,false,174,200,1),25,270,40,"keep left");
        expected(raw(53,0,0,0,false,173,200,1),25,90,40,"change highway right");
        expected(raw(22,0,0,0,false,82,200,1),18,270,40,"exit left");
        expected(raw(23,0,0,0,false,81,200,1),17,90,40,"exit right");
        expected(raw(0,0,1,0,false,99,200,1),32,90,40,"roundabout from known exit icon");
        expected(raw(0,1,1,0,false,195,200,1),33,90,40,"clockwise icon set");
        check(raw(2,0,1,90,true,67,200,1)==null,"roundabout cannot become an ordinary right turn");
        check(raw(3,0,0,0,false,65,100,0)==null,"route stopped");
        check(raw(3,-1,0,0,false,65,100,1)==null,"unknown traffic side");
        check(raw(3,0,-1,0,false,65,100,1)==null,"unknown junction");
        check(raw(8,0,0,0,false,86,100,1)==null,"ambiguous off-ramp retained");
        check(raw(12,0,0,0,false,112,0,1)==null,"rounded zero never invents arrival");
        expected(raw(12,0,0,0,false,112,-1,2),3,0,0,"explicit arrival needs no inferred distance");
        check(raw(1,0,0,0,false,70,Float.NaN,1)==null,"invalid exact distance");
        check(raw(1,0,0,0,false,70,458,1)==null,"exact distance outside window");
        check(ArHudTurns.meters(Float.MAX_VALUE,1)==-1,"unit multiplication overflow");
        for (int icon=0;icon<256;icon++) {
            int[] t=ArHudTurns.convert(icon,100,0,0,0);
            if (t==null) continue;
            check(t[1]>=0 && t[1]<360 && t[1]%45==0,"valid angle for icon "+icon);
            check(t[2]>=10 && t[2]<=90 && t[2]%10==0,"valid bar for icon "+icon);
        }

        ArHudDistance distance = new ArHudDistance();
        check(distance.meters(1000)==-1,"no sample");
        distance.sample(100,10,1000);
        check(distance.meters(1250)==97.5f,"250 ms interpolation");
        check(distance.meters(2500)==85,"1.5 second bound");
        check(distance.meters(9000)==85,"stale data holds rather than running past turn");
        distance.sample(1,10,900); // delayed callback
        check(distance.meters(1250)==97.5f,"out of order sample ignored");
        check(distance.meters(999)==-1,"clock reversal rejected");
        distance.sample(10,0,3000);
        check(distance.meters(3500)==10,"unknown speed holds");
        distance.sample(10,Float.NaN,4000);
        check(distance.meters(4500)==10,"NaN speed holds");
        distance.sample(1,0.1f,5000);
        check(distance.meters(6000)==0,"distance never becomes negative");
        distance.reset();
        check(distance.meters(6000)==-1,"source/route/maneuver reset clears old distance");
        distance.sample(-1,10,7000);
        check(distance.meters(7000)==-1,"invalid sample clears state");

        ArHudProgress progress = new ArHudProgress();
        int[] trap = new int[17];
        for(int i=0;i<trap.length;i++) trap[i]=100+i;
        check(progress.merge(trap,5,true,1000)==trap,"no phone data leaves native frame alone");
        progress.update(60,1000);
        for(int index=0;index<17;index++) {
            int[] merged=progress.merge(trap,index,true,1500);
            check(merged!=trap,"copy rather than mutate native arguments");
            for(int i=0;i<17;i++) check(merged[i]==(i==index?60:100+i),"only verified bar argument changes");
        }
        for(int i=0;i<17;i++) check(trap[i]==100+i,"original native frame retained");
        check(progress.value(true,6000)==60,"lease boundary");
        check(progress.merge(trap,5,true,6001)==trap,"stale progress restores native behavior");
        check(progress.merge(trap,5,false,1500)==trap,"route ended restores native behavior");
        check(progress.merge(trap,17,true,1500)==trap,"unverified index rejected");
        check(progress.merge(new int[16],5,true,1500).length==16,"unknown overload untouched");
        progress.update(20,900);
        check(progress.value(true,1500)==60,"old progress packet ignored");
        progress.update(61,2000);
        check(progress.value(true,2000)==-1,"malformed progress clears lease");
        progress.update(0,3000);
        check(progress.value(true,3000)==0,"explicit zero can clear bar");
        progress.clear();
        check(progress.value(true,3000)==-1,"clear removes lease");
        System.out.println("AR metadata, distance and progress: "+checks+" checks passed");
    }
}
