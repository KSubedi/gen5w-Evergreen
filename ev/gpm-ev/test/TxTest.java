package com.google.android.projection.protocol;

/**
 * EvCar looks binder transaction numbers up by method name in the car's own
 * interface classes (test/fake stands in for IHEVService), and a name it can't
 * find makes that read unknown instead of guessing a number.
 */
public final class TxTest {
    public static void main(String[] a) {
        int pass = 0;
        // Verified firmware profiles: commands only on these exact framework files.
        check("Ioniq 5 US 260507".equals(Precond.HkmcVehicle.profile(
                "dd72e04bce0a2b4bda1506308ac21f91002c1436553b8156c345cc4313661338",
                "3cf40fb00854c63afa49abea1dad18a0afcbc0dd68b17de9ed7ea3f5aeab4222")), "Ioniq 5 profile");
        pass++;
        check("Kia EV6 US 260507".equals(Precond.HkmcVehicle.profile(
                "b9e7e02b81e1a8cfcf767c6b8bbb28bcf1d2c411a208fbf6a465e7f0c662774a",
                "8af745b41aa28f490953d61531215cd94e059f104b31f6236a8f6c7af23b8062")), "EV6 profile");
        pass++;
        check(Precond.HkmcVehicle.profile(
                "b9e7e02b81e1a8cfcf767c6b8bbb28bcf1d2c411a208fbf6a465e7f0c662774a",
                "3cf40fb00854c63afa49abea1dad18a0afcbc0dd68b17de9ed7ea3f5aeab4222") == null, "mixed files: no profile");
        pass++;
        check("Ioniq 5 2024 SE US 260903".equals(Precond.HkmcVehicle.profile(
                "c52e0825956880d627b32979022705a618200f059476c094d729133d1ff6a3be",
                "bf17e682d1beb349ae8ef2acaae6d6e908fe42e88853d7145ee16b29bb4d9ec1")), "Ioniq 5 260903 profile");
        pass++;
        check("Kia EV6 Canada 260206".equals(Precond.HkmcVehicle.profile(
                "347d05034e6b25e38f8d0446a6e9f02f4c8f5b786c09e583a500235613514c18",
                "80bc04d04822464cfedb8878592f04eab8b16351be7aef6c70ff1ec9aa14d021")), "EV6 Canada profile");
        pass++;
        check(EvCar.HCAN_MD5S.length == 4, "four verified telematics libraries");
        pass++;
        check(EvCar.tx("com.hkmc.hev.IHEVService", "getHEVStatus") == 3, "found by name");
        pass++;
        check(EvCar.tx("com.hkmc.hev.IHEVService", "noSuchMethod") == -1, "missing method");
        pass++;
        check(EvCar.tx("com.hkmc.datc.IDATCService", "getVRClimateStatus") == -1, "missing interface");
        pass++;
        // hscan: no interface class and no 260507 library file here: unknown, not 0x0b
        check(EvCar.hscanTx("getCarSpeed", 0x0b) == -1, "hscan without class or verified library");
        pass++;
        check(EvCar.md5("/nonexistent").equals(""), "md5 of a missing file");
        pass++;
        // without the car's services every read is unknown, never a guess
        check(EvCar.hev(0x31, 1000) == -1 && Float.isNaN(EvCar.climate(EvCar.DATC_TEMP)), "reads unknown");
        pass++;
        System.out.println("TxTest: " + pass + " passed");
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
