package com.hkmc.cluster;

/** Compile-only signature. Never include this class in the injected dex. */
public class NavigationCluster {
    public static final class NaviTbtTrapInfo {
        public int fixedSpeed,mobileSpeed,overSpeedAlarm,redlineSpeed,nonSpeed,warningZone,curveWarning,
                mergeWarning,tollGateCurrency,tollGate,displayTG,barGraphLevel,speedLimit,busSpeed,
                speedLimitType,speedLimitUnit,speedInfoType,warningZone2,warningZone3,warningZone4,
                displayCountry,cameraSafetyAlert;
    }
    public void updateTBTTrapInfoNew(NaviTbtTrapInfo value) {}
    public void updateTBTTrapInfo(int a0,int a1,int a2,int a3,int a4,int a5,int a6,int a7,int a8,
            int a9,int a10,int a11,int a12,int a13,int a14,int a15,int a16) {}
    public void updateTBTInfo(int type, int sideStreet, float direction, float distance,
            int distanceError, int combiSideStreet, int unit, int bar, int barError) {}
}
