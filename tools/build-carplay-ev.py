#!/usr/bin/env python3
"""Build Apple Maps EV routing over CarPlay for the Kia EV6 US 260507.

From the car's stock files (the porting kit's send-privately/stock: app/Iap2ServerApp.apk and
.odex, lib/libiap2session.so) this writes, all reproducible:

  recipes/carplay-ev-identity.recipe          the libiap2session.so patch (lpatch recipe)
  packages/wireless-carplay-aa/comp/3_bridge/files/libcarplayev.so
                                             egmp-carplay-ev-routing's native bridge plus the
                                             identity serializer (src/carplay-ev/native)
  patches/<set>/Iap2ServerApp.bridge.ev.lpd   the bridge Iap2ServerApp plus three calls into
                                             src/carplay-ev/java (CarPlayEv), for each car given
  and the "3_bridge+ev app Iap2ServerApp" line in each patches/<set>/manifest.
Give one --stock <set>=<dir> per car (ev6, neev23_260903): their libiap2session.so must all be
the same verified file.

The library patch, all in place (no size change, nothing moved):
  - the gate (src/carplay-ev/native/gate.s) in the zero padding after .rodata, inside the
    executable segment, whose file size grows to cover it;
  - its handler word in the zero-filled .bss tail of the writable segment, whose memory size
    grows by 4 bytes (same page): set at run time with a plain store;
  - iAP2SessionPacketMakePacketUsingParamListNew's first instruction (push.w) -> b.w gate;
    the gate runs the push.w itself before resuming;
  - the "group 13" enable at 0x129c6, as Open-HMG/hmgapi-wireless-cp-aa's
    carplay-identification-fc7 recipe writes it.
Needs: the env of /work/toolchains/ev-env.sh or equivalent (javac, d8 via D8_JAR, baksmali,
smali, ANDROID_HOME with platforms/android-19), zig or clang for ARM (ZIG or CLANG), and the
host lpatch (tools/lpatch/lpatch-host).
"""
import argparse, hashlib, json, os, re, shutil, struct, subprocess, sys, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src/carplay-ev"
STOCK_MD5 = "fc7cb675b46a44bd893a1edc054c5e71"   # libiap2session.so, US 260507 (EV6, Ioniq 5)
ENTRY = 0x1AA04          # iAP2SessionPacketMakePacketUsingParamListNew: push.w {r4-r11,lr}
GATE = 0x2E340           # after .rodata (ends 0x2e334), zero padding up to 0x2ecb8
HANDLER = 0x35120        # .bss ends here; the page runs to 0x36000
GROUP13 = (0x129C6, "2046132101220123fff7e3fc20460ef08cfbc4f8145638bd00bf",
           "04f58a2000f5ac52d560204604f592240ef08bfbc4f8145638bd")
RX_FILESZ, RW_MEMSZ, RW_VADDR = 0x2E334, 0x5468, 0x2FCB8
# product id prefix -> bundled profile (CarPlayEv.profileFor); 77.4 kWh packs only (checked on the car)
PROFILES = (("CVEV.", SRC / "profile/kia-ev6-us-77kwh.json"),
            ("NEEV", SRC / "profile/hyundai-ioniq5-us-77kwh.json"))
HOOK = "Lorg/egmp/carplay/ev/app/CarPlayEv;"


def run(*a, **k):
    return subprocess.run([str(x) for x in a], check=True, **k)


def md5(b):
    return hashlib.md5(b).hexdigest()


def branch_w(frm, to):
    """Thumb-2 b.w (T4) at frm to to."""
    off = to - (frm + 4)
    if off & 1 or not -(1 << 24) <= off < (1 << 24):
        raise ValueError("b.w out of range")
    s = (off >> 24) & 1
    i1, i2 = (off >> 23) & 1, (off >> 22) & 1
    j1, j2 = (1 - (i1 ^ s)), (1 - (i2 ^ s))
    hi = 0xF000 | (s << 10) | ((off >> 12) & 0x3FF)
    lo = 0x9000 | (j1 << 13) | (j2 << 11) | ((off >> 1) & 0x7FF)
    return struct.pack("<HH", hi, lo)


def assemble_gate(work):
    zig, clang = os.environ.get("ZIG"), os.environ.get("CLANG")
    o, b = work / "gate.o", work / "gate.bin"
    if clang:
        run(clang, "--target=armv7a-linux-androideabi", "-mthumb", "-c", SRC / "native/gate.s", "-o", o)
        run(os.environ.get("OBJCOPY", "llvm-objcopy"), "-O", "binary", "--only-section=.text", o, b)
    else:
        zig = zig or "zig"
        run(zig, "cc", "-target", "arm-linux-musleabi", "-mcpu=cortex_a9", "-c", SRC / "native/gate.s", "-o", o)
        run(zig, "objcopy", "-O", "binary", "--only-section=.text", o, b)
    g = b.read_bytes()
    assert len(g) == 0x50 and g[0x20:0x24] == b"1GVE" and g[0x40:0x44] == bytes.fromhex("2de9f04f"), "gate layout"
    return bytearray(g)


def patch_library(stock, work):
    b = bytearray(stock)
    assert md5(b) == STOCK_MD5, "not the US 260507 libiap2session.so"
    assert b[ENTRY:ENTRY + 4] == bytes.fromhex("2de9f04f"), "entry"
    off, new, old = GROUP13
    assert b[off:off + 26].hex() == old, "group 13 site"
    assert set(b[RX_FILESZ:0x2ECB8]) == {0}, "padding not free"
    g = assemble_gate(work)
    struct.pack_into("<i", g, 0x2C, HANDLER - (GATE + 0x2C))
    struct.pack_into("<i", g, 0x30, HANDLER - (GATE + 0x04 + 4))
    struct.pack_into("<i", g, 0x4C, ((ENTRY + 4) | 1) - (GATE + 0x48 + 4))
    b[GATE:GATE + len(g)] = g
    b[ENTRY:ENTRY + 4] = branch_w(ENTRY, GATE)
    b[off:off + 26] = bytes.fromhex(new)
    # program headers: the RX LOAD (index 2) covers the gate; the RW LOAD (index 3) the handler
    phoff = struct.unpack_from("<I", b, 0x1C)[0]
    rx, rw = phoff + 2 * 32, phoff + 3 * 32
    assert struct.unpack_from("<8I", b, rx)[:6] == (1, 0, 0, 0, RX_FILESZ, RX_FILESZ), "RX LOAD"
    assert struct.unpack_from("<8I", b, rw)[2] == RW_VADDR and struct.unpack_from("<8I", b, rw)[5] == RW_MEMSZ, "RW LOAD"
    end = GATE + len(g)
    struct.pack_into("<II", b, rx + 16, end, end)
    struct.pack_into("<I", b, rw + 20, HANDLER + 4 - RW_VADDR)
    assert (HANDLER & ~0xFFF) == ((RW_VADDR + RW_MEMSZ) & ~0xFFF), "handler not on the .bss page"
    return bytes(b)


def build_native(out, work):
    """libcarplayev.so: no libc or NDK; stub libc/libdl/liblog record the imports (as libev6aac)."""
    zig, clang = os.environ.get("ZIG", "zig"), os.environ.get("CLANG")
    jdk = Path(os.environ.get("JAVA_HOME") or Path(shutil.which("javac")).resolve().parent.parent)
    inc = ["-I", SRC / "native/compat", "-I", jdk / "include", "-I", jdk / "include/linux"]
    if clang:
        cc = [clang, "--target=armv7a-linux-androideabi", "-mthumb", "-march=armv7-a", "-mfloat-abi=softfp", "-fuse-ld=lld",
              "-Wl,--no-rosegment"]
    else:
        cc = [zig, "cc", "-target", "arm-linux-musleabi", "-mcpu=cortex_a9", "-mthumb", "-mfloat-abi=soft"]
    for name, body in (("libc", "void*memset(void*d,int c,unsigned n){return d;}"),
                       ("libdl", "void*dlopen(const char*f,int m){return 0;} void*dlsym(void*h,const char*s){return 0;}")):
        (work / (name + ".c")).write_text(body + "\n")
        run(*cc, "-shared", "-nostdlib", "-Wl,-soname," + name + ".so", "-o", work / (name + ".so"), work / (name + ".c"))
    # No unwind tables (they would import __aeabi_unwind_cpp_pr*, which the head unit's libc
    # lacks); every import must be in libc.so or libdl.so (checked below).
    flags = ["-Os", "-fPIC", "-ffreestanding", "-fno-exceptions", "-fno-unwind-tables", "-fno-asynchronous-unwind-tables",
             "-fno-stack-protector", "-shared", "-nostdlib", "-Wl,-z,now", "-Wl,--hash-style=sysv",
             "-Wl,--build-id=none", "-Wl,-soname,libcarplayev.so", "-Wl,--no-undefined"]
    run(*cc, *flags, *inc, "-o", out, SRC / "native/bridge_jni.c", SRC / "native/identity.c",
        "-Wl,--no-as-needed", work / "libdl.so", work / "libc.so", cwd=SRC / "native")
    data = Path(out).read_bytes()
    assert data[:4] == b"\x7fELF" and data[4] == 1 and struct.unpack_from("<H", data, 18)[0] == 40, "ARM32 ELF"
    needed, undefined = elf_imports(data)
    provider = {"dlopen": "libdl.so", "dlsym": "libdl.so", "memset": "libc.so"}
    assert set(undefined) <= set(provider), "unexpected imports: %s" % sorted(set(undefined) - set(provider))
    assert {provider[u] for u in undefined} <= set(needed) <= {"libc.so", "libdl.so"}, "NEEDED %s" % needed
    return data


def elf_imports(b):
    """(NEEDED libraries, undefined dynamic symbols) of an ARM32 shared library."""
    phoff, phnum = struct.unpack_from("<I", b, 0x1C)[0], struct.unpack_from("<H", b, 0x2C)[0]
    loads, dyn = [], None
    for i in range(phnum):
        t, o, v, _, fs, _, _, _ = struct.unpack_from("<8I", b, phoff + i * 32)
        if t == 1: loads.append((v, o, fs))
        if t == 2: dyn = (o, fs)
    v2o = lambda v: next(v - va + o for va, o, fs in loads if va <= v < va + fs)
    tags = {}
    for i in range(0, dyn[1], 8):
        t, v = struct.unpack_from("<iI", b, dyn[0] + i)
        if t == 0: break
        tags.setdefault(t, []).append(v)
    strtab, symtab = v2o(tags[5][0]), v2o(tags[6][0])
    name = lambda off: b[off:b.index(b"\0", off)].decode()
    nsym = struct.unpack_from("<I", b, v2o(tags[4][0]) + 4)[0]
    undefined = [name(strtab + struct.unpack_from("<I", b, symtab + i * 16)[0]) for i in range(1, nsym)
                 if struct.unpack_from("<H", b, symtab + i * 16 + 14)[0] == 0]
    return [name(strtab + x) for x in tags.get(1, [])], undefined


def java_string(s):
    out = []
    for ch in s:
        if ch in '"\\':
            out.append("\\" + ch)
        elif ch == "\n":
            out.append("\\n")
        elif 0x20 <= ord(ch) < 0x7F:
            out.append(ch)
        else:
            out.append("\\u%04x" % ord(ch))
    return "".join(out)


def build_classes(session_md5, work):
    """Our classes as smali (stubs compiled for javac only, never dexed)."""
    profiles = ",\n".join('        {"%s", "%s"}' % (prefix, java_string(json.dumps(json.loads(path.read_text()),
                          separators=(",", ":"), sort_keys=True))) for prefix, path in PROFILES)
    gen = work / "gen/org/egmp/carplay/ev/app"
    gen.mkdir(parents=True)
    (gen / "Generated.java").write_text(
        "package org.egmp.carplay.ev.app;\n// generated by tools/build-carplay-ev.py\n"
        "final class Generated {\n    private Generated() {}\n"
        '    static final String SESSION_MD5 = "%s";\n    static final String[][] PROFILES = {\n%s\n    };\n}\n'
        % (session_md5, profiles))
    android = Path(os.environ["ANDROID_HOME"]) / "platforms/android-19/android.jar"
    sources = sorted((SRC / "java").rglob("*.java")) + [gen / "Generated.java"]
    stubs = sorted((SRC / "stubs").rglob("*.java"))
    classes = work / "classes"
    run("javac", "-nowarn", "-source", "8", "-target", "8", "-bootclasspath", android, "-d", classes, *sources, *stubs)
    ours = sorted((classes / "org").rglob("*.class"))
    dex = work / "dex"
    dex.mkdir()
    run("java", "-cp", os.environ["D8_JAR"], "com.android.tools.r8.D8", "--min-api", "19", "--lib", android,
        "--classpath", classes, "--output", dex, *ours)
    run("baksmali", "d", "--api", "18", dex / "classes.dex", "-o", work / "ours-smali")
    return work / "ours-smali"


def insert_calls(path, method, call, where):
    """Add an invoke-static to a method: before every return-void, or at its start."""
    text = path.read_text()
    m = re.search(r"^\.method [^\n]*" + re.escape(method) + r"\n(.*?)^\.end method", text, re.S | re.M)
    if not m:
        raise SystemExit("method not found: %s in %s" % (method, path.name))
    body = m.group(1)
    if call in body:
        raise SystemExit("already patched: " + method)
    if where == "start":
        anchor = re.search(r"^\s*\.(registers|locals) \d+\n", body, re.M)
        new = body[:anchor.end()] + "\n    " + call + "\n" + body[anchor.end():]
    else:
        n = body.count("return-void")
        if n == 0:
            raise SystemExit("no return-void in " + method)
        new = body.replace("    return-void", "    " + call + "\n\n    return-void")
    path.write_text(text[:m.start(1)] + new + text[m.end(1):])


def build_app(stock, ours_smali, work, lpatch, variant="ev6"):
    work = work / variant
    work.mkdir()
    base = work / "bridge.dex"
    run(lpatch, "delta", ROOT / "patches" / variant / "Iap2ServerApp.bridge.lpd", stock / "app/Iap2ServerApp.odex", base,
        stdout=subprocess.DEVNULL)
    tree = work / "app-smali"
    run("baksmali", "d", "--api", "18", base, "-o", tree)
    shutil.copytree(ours_smali / "org", tree / "org", dirs_exist_ok=False)
    svc = tree / "com/tcc/iap2server/Iap2Service.smali"
    info = tree / "com/tcc/iap2server/Iap2InitInformationMsg.smali"
    insert_calls(svc, "public onCreate()V", "invoke-static {p0}, " + HOOK + "->onCreate(Landroid/app/Service;)V", "end")
    insert_calls(svc, "public onDestroy()V", "invoke-static {}, " + HOOK + "->onDestroy()V", "start")
    insert_calls(info, "public checkSupportsFeatures()V", "invoke-static {}, " + HOOK + "->afterCheckSupportsFeatures()V", "end")
    for f, field in ((svc, "mHandleIap2:J"), (svc, "mLock:Ljava/lang/Object;"),
                     (info, "iAP2UsedVehicelInformation:I"), (info, "iAP2UsedVehicleStatus:I")):
        if (".field private static " + field) not in f.read_text():
            raise SystemExit("field not found: " + field)
    if ".super Landroid/app/Service;" not in svc.read_text()[:600]:
        raise SystemExit("Iap2Service is not an android.app.Service")
    dex = work / "Iap2ServerApp.ev.dex"
    run("smali", "a", "-j", "1", "--api", "18", "-o", dex, tree)
    apk = work / "Iap2ServerApp.ev.apk"
    run(lpatch, "zipadd", stock / "app/Iap2ServerApp.apk", dex, apk, stdout=subprocess.DEVNULL)
    return dex, apk


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--stock", action="append", required=True,
                   help="<patch set>=<the car's stock tree (app/, lib/)>; a bare path means ev6")
    p.add_argument("--work", type=Path, help="keep intermediate files here (default: temporary)")
    a = p.parse_args()
    stocks = []
    for item in a.stock:
        variant, _, path = item.rpartition("=") if "=" in item else ("ev6", "", item)
        if not (ROOT / "patches" / variant / "Iap2ServerApp.bridge.lpd").is_file():
            p.error("no bridge Iap2ServerApp for patch set " + variant)
        stocks.append((variant, Path(path)))
    lpatch = ROOT / "tools/lpatch/lpatch-host"
    work = Path(a.work or tempfile.mkdtemp(prefix="carplay-ev-"))
    work.mkdir(parents=True, exist_ok=True)
    try:
        stock_lib = (stocks[0][1] / "lib/libiap2session.so").read_bytes()
        for variant, stock in stocks:
            assert (stock / "lib/libiap2session.so").read_bytes() == stock_lib, variant + ": another libiap2session.so"
        patched = patch_library(stock_lib, work)
        (work / "libiap2session.stock.so").write_bytes(stock_lib)
        (work / "libiap2session.so").write_bytes(patched)
        run(sys.executable, ROOT / "tools/mkrecipe.py", "carplay-ev-identity", "/system/lib/libiap2session.so",
            work / "libiap2session.stock.so", work / "libiap2session.so",
            "--desc", "libiap2session: identification gate for Apple Maps EV routing (vehicle identity), "
                      "group 13 enable (tools/build-carplay-ev.py)",
            stdout=open(ROOT / "recipes/carplay-ev-identity.recipe", "w"))
        native = ROOT / "packages/wireless-carplay-aa/comp/3_bridge/files/libcarplayev.so"
        native.parent.mkdir(parents=True, exist_ok=True)
        build_native(native, work)
        Path(str(native) + ".md5").write_text(md5(native.read_bytes()) + "\n")
        ours = build_classes(md5(patched), work)
        print("libiap2session.so patched md5", md5(patched))
        print("libcarplayev.so md5", md5(native.read_bytes()))
        for variant, stock in stocks:
            dex, apk = build_app(stock, ours, work, lpatch, variant)
            lpd = ROOT / "patches" / variant / "Iap2ServerApp.bridge.ev.lpd"
            run(sys.executable, ROOT / "tools/mkdelta.py", stock / "app/Iap2ServerApp.odex", dex, lpd)
            check = work / variant / "roundtrip.dex"
            run(lpatch, "delta", lpd, stock / "app/Iap2ServerApp.odex", check, stdout=subprocess.DEVNULL)
            assert check.read_bytes() == dex.read_bytes(), "delta round trip"
            apk_md5 = md5(apk.read_bytes())
            m = ROOT / "patches" / variant / "manifest"
            lines = [l for l in m.read_text().splitlines() if not l.startswith("3_bridge+ev app Iap2ServerApp ")]
            lines.append("3_bridge+ev app Iap2ServerApp " + variant + "/Iap2ServerApp.bridge.ev.lpd " + apk_md5)
            m.write_text("\n".join(lines) + "\n")
            print(variant, "Iap2ServerApp EV apk md5", apk_md5)
    finally:
        if not a.work:
            shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
