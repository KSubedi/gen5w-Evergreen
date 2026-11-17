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
        expected(raw(22,0,0,0,false,71,200,1),13,315,40,"exit left: slight arrow by default");
        expected(raw(23,0,0,0,false,66,200,1),13,45,40,"exit right: slight arrow by default");
        expected(ArHudTurns.maneuver(22,0,0,0,false,71,200,1,0,true),18,270,40,"exit left: native sign style");
        expected(ArHudTurns.maneuver(23,0,0,0,false,66,200,1,0,true),17,90,40,"exit right: native sign style");
        expected(raw(0,0,1,0,false,99,200,1),32,90,40,"roundabout from known exit icon");
        expected(raw(0,1,1,0,false,195,200,1),33,90,40,"clockwise icon set");
        check(raw(2,0,1,90,true,67,200,1)==null,"roundabout cannot become an ordinary right turn");
        check(raw(3,0,0,0,false,65,100,0)==null,"route stopped");
        check(raw(3,-1,0,0,false,65,100,1)==null,"unknown traffic side");
        check(raw(3,0,-1,0,false,65,100,1)==null,"unknown junction");
        check(raw(8,0,0,0,false,86,100,1)==null,"ambiguous off-ramp retained without a side");
        expected(ArHudTurns.maneuver(8,0,0,0,false,66,200,1,1),13,45,40,"sided generic exit: slight right arrow");
        expected(ArHudTurns.maneuver(8,0,0,0,false,71,200,1,-1),13,315,40,"sided generic exit: slight left arrow");
        expected(ArHudTurns.maneuver(8,0,0,0,false,66,200,1,1,true),17,90,40,"sign style: native right exit");
        expected(ArHudTurns.maneuver(8,0,0,0,false,71,200,1,-1,true),18,270,40,"sign style: native left exit");
        check(ArHudTurns.maneuver(8,0,0,0,false,86,200,1,0)==null&&ArHudTurns.maneuver(8,0,0,0,false,86,200,1,0,true)==null,"generic side keeps the icon only");
        check(ArHudTurns.exit(8)&&ArHudTurns.exit(22)&&ArHudTurns.exit(23)&&!ArHudTurns.exit(2)&&!ArHudTurns.exit(14),"exit maneuvers");
        check(ArHudTurns.maneuver(1,0,0,0,false,70,200,1,1)[0]==13,"exit side ignored for ordinary turns");
        // Raw 8 side: tuning mode first, then the exit angle's sign, then the traffic side.
        check(ArHudTurns.exitSide(8,0,0,ArHudTuning.EXIT_AUTO)==1&&ArHudTurns.exitSide(8,1,0,ArHudTuning.EXIT_AUTO)==-1,"auto: traffic side default");
        check(ArHudTurns.exitSide(8,0,30,ArHudTuning.EXIT_AUTO)==1&&ArHudTurns.exitSide(8,0,-30,ArHudTuning.EXIT_AUTO)==-1
                &&ArHudTurns.exitSide(8,0,330,ArHudTuning.EXIT_AUTO)==-1,"auto: exit angle sign wins");
        check(ArHudTurns.exitSide(8,0,2,ArHudTuning.EXIT_AUTO)==1&&ArHudTurns.exitSide(8,0,180,ArHudTuning.EXIT_AUTO)==1,"auto: near-zero and reverse angles fall back");
        check(ArHudTurns.exitSide(8,0,-30,ArHudTuning.EXIT_RIGHT)==1&&ArHudTurns.exitSide(8,0,30,ArHudTuning.EXIT_LEFT)==-1
                &&ArHudTurns.exitSide(8,0,30,ArHudTuning.EXIT_GENERIC)==0,"forced modes");
        check(ArHudTurns.exitSide(23,0,0,ArHudTuning.EXIT_AUTO)==0&&ArHudTurns.exitSide(2,0,90,ArHudTuning.EXIT_LEFT)==0,"only raw 8 has a chosen side");
        check(ArHudTurns.recoverIcon(8,0,0,1)==66&&ArHudTurns.recoverIcon(8,0,0,-1)==71&&ArHudTurns.recoverIcon(8,0,0,0)==86
                &&ArHudTurns.recoverIcon(8,-1,-1,1)==66,"sided exit icons need no other geometry");
        // The stock US app refuses the sided exit icons; no recovery of ours may hand them to the cluster.
        for(int raw=0;raw<64;raw++)for(int side=-1;side<=1;side++)for(int traffic=0;traffic<=1;traffic++) {
            int icon=ArHudTurns.recoverIcon(raw,traffic,0,side);
            check(icon!=81&&icon!=82,"no recovered sided exit icon: raw "+raw);
        }
        check(ArHudTurns.recoverIcon(2,0,0,1)==67,"exit side ignored for other recoveries");
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

        float[] tuning=ArHudTuning.parse("street_ar_meters=60\ngain=0.9\nbackstep_meters=0\n");
        check(tuning[0]==60f&&tuning[1]==0.9f&&tuning[2]==0f,"tuning parse");
        tuning=ArHudTuning.parse("street_ar_meters=29\ngain=0.49\nbackstep_meters=51\n");
        check(tuning[0]==125f&&tuning[1]==0.8f&&tuning[2]==15f,"tuning below/above range keeps defaults");
        check(ArHudTuning.parse(null)[0]==125f,"missing tuning file keeps defaults");
        tuning=ArHudTuning.parse("bar_meters=125\nturn_offset_meters=20\nexit_side=left\n");
        check(tuning[3]==125f&&tuning[4]==20f&&tuning[5]==ArHudTuning.EXIT_LEFT,"bar window, turn offset and exit side parse");
        tuning=ArHudTuning.parse("bar_meters=29\nturn_offset_meters=61\nexit_side=sideways\n");
        check(tuning[3]==457f&&tuning[4]==0f&&tuning[5]==ArHudTuning.EXIT_AUTO,"bar/offset/side defaults: 457 m, 0 m, auto");
        tuning=ArHudTuning.parse("exit_side=right\n");check(tuning[5]==ArHudTuning.EXIT_RIGHT,"exit side right");
        tuning=ArHudTuning.parse("exit_side=generic\n");check(tuning[5]==ArHudTuning.EXIT_GENERIC,"exit side generic");
        tuning=ArHudTuning.parse("exit_side=auto\n");check(tuning[5]==ArHudTuning.EXIT_AUTO,"exit side auto");
        check(ArHudTuning.parse(null)[6]==ArHudTuning.EXIT_STYLE_SIGN&&ArHudTuning.parse("exit_style=arrows\n")[6]==ArHudTuning.EXIT_STYLE_SIGN,"exit style defaults to the native sign");
        check(ArHudTuning.parse("exit_style=sign\n")[6]==ArHudTuning.EXIT_STYLE_SIGN&&ArHudTuning.parse("exit_style=sign\nexit_style=slight\n")[6]==ArHudTuning.EXIT_STYLE_SLIGHT,"exit style parse");
        check(ArHudTuning.exitStyleName(ArHudTuning.EXIT_STYLE_SIGN).equals("sign")&&ArHudTuning.exitStyleName(7).equals("slight"),"exit style names");
        check(ArHudTuning.exitSideName(ArHudTuning.EXIT_LEFT).equals("left")&&ArHudTuning.exitSideName(9).equals("auto"),"exit side names");
        tuning=ArHudTuning.parse("street_ar_meters=NaN\ngain=Infinity\n");
        check(tuning[0]==125f&&tuning[1]==0.8f,"non-finite tuning rejected");
        check(ArHudTurns.bar(60,125)==40&&ArHudTurns.bar(124,125)==90&&ArHudTurns.bar(0,125)==10&&ArHudTurns.bar(126,125)==0,"bar over a street window");
        check(ArHudTurns.bar(229)==50,"default bar window unchanged");
        check(ArHudTurns.cueOnly(11)&&!ArHudTurns.cueOnly(3)&&!ArHudTurns.cueOnly(8),"start route is a cue only");
        check(raw(11,0,0,0,false,65,50,1)==null,"start route never becomes a native arrow");
        check(ArHudTurns.recoverIcon(11,0,0)==65&&ArHudTurns.recoverIcon(18,0,0)==0,"start route recovers straight; start with U-turn stays unmapped");

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
