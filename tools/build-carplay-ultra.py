#!/usr/bin/env python3
"""Build the CarPlay Ultra-Lite test port (src/carplay-ultra) for Ultimate Wireless Projection.

CarPlay Ultra-Lite (github.com/SafariForest/carplay-ultra-lite-gen5w, GPL-3.0-or-later, based on
LIVI by Lasse Heitgres) is an HMG-API mod: Java hooked into DiPO at run time and an in-memory
patch of libCarPlayCore.so. This port puts the same code on disk, the way the rest of this
package works, from the car's stock files:

  patches/common/libCarPlayCore.ultra.lpd    libCarPlayCore.so with our Wi-Fi session patch
                                             (recipes/carplay-core-wifi) and CarPlay Ultra-Lite's
                                             code segment, rebuilt only from the stock library
  patches/<set>/HKMC_DiPO.bridge.ultra.lpd   our bridge DiPO plus CarPlay Ultra-Lite's classes
                                             (unchanged, src/carplay-ultra/upstream/mod/src), the
                                             HMG-API stand-in (src/carplay-ultra/shim) and the
                                             starter (src/carplay-ultra/glue), called from
                                             telechips.CarPlayService
  "3_bridge+ultra ..." lines in each patches/<set>/manifest (picked by scripts/build.sh only
  when UWP-CARPLAY-ULTRA is on the install drive).

The code segment is built from CarPlay Ultra-Lite's own native/ and tools/build.py constants,
and must come out byte for byte as the segment in its 1.3.3 release recipe (the code that ran
on the Ioniq 6). The library has the same md5 on the EV6 and Ioniq 6 (d3a47296...).

usage: build-carplay-ultra.py --stock <set>=<stock tree with app/ and lib/> [...] [--work DIR]
Needs: the env of /work/toolchains/ev-env.sh (javac, d8 via D8_JAR, baksmali, smali,
ANDROID_HOME with platforms/android-19), zig (ZIG) for the ARM code, pyelftools and bsdiff4,
and the host lpatch (tools/lpatch/lpatch-host).
"""
import argparse, hashlib, os, re, shutil, struct, subprocess, sys, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src/carplay-ultra"
UP = SRC / "upstream"
LPATCH = ROOT / "tools/lpatch/lpatch-host"
CORE_STOCK_MD5 = "d3a47296437df56fb1ffc7ac3dfca4cb"
CORE_WIFI_MD5 = "c537d5849f1fa881b0c79333ecfd83e4"   # stock + recipes/carplay-core-wifi
ULTRA = "Lorg/uwp/carplayultra/Ultra;"
SERVICE = "com/daudio/app/dipo/telechips/CarPlayService.smali"
OUR_PACKAGES = ("ioniq6", "hmgapi", "org/uwp")


def run(*a, **k):
    return subprocess.run([str(x) for x in a], check=True, **k)


def md5(b):
    return hashlib.md5(b).hexdigest()


def upstream_build():
    """CarPlay Ultra-Lite's tools/build.py as a namespace (constants and helpers), without its main()."""
    path = UP / "tools/build.py"
    text = path.read_text()
    head, sep, tail = text.rpartition("\nmain()")
    if not sep or tail.strip():
        raise SystemExit("upstream tools/build.py: no trailing main() call")
    ns = {"__file__": str(path), "__name__": "carplay_ultra_build"}
    exec(compile(head, str(path), "exec"), ns)
    return ns


def release_segment():
    for line in (UP / "mod/carplay-climate-m1.recipe").read_text().splitlines():
        if line.startswith("segment "):
            _, va, data = line.split()
            return int(va, 0), bytes.fromhex(data)
    raise SystemExit("no segment line in the release recipe")


def release_code(ns, code, syms, table):
    """The code segment to ship: CarPlay Ultra-Lite 1.3.3's release bytes, the code that ran on the Ioniq 6.

    Built here from the same source, it differs only by compiler (upstream allows for the same:
    CPC_HOOKS_O keeps "the C code that has run on the car" as one object). Before using the release
    bytes, check they fit this build: same address, entry stubs where the release recipe branches,
    the same filled table at the same place (so the same library targets), the same description blob."""
    seg_va = ns["SEG_VA"]
    want_va, want = release_segment()
    assert want_va == seg_va, "release segment address"
    if bytes(code) == want:
        print("code segment: %d bytes, built byte for byte as CarPlay Ultra-Lite 1.3.3's release" % len(code))
        return bytearray(want)
    branches = {int(l.split()[1], 0): int(l.split()[2], 0)
                for l in (UP / "mod/carplay-climate-m1.recipe").read_text().splitlines() if l.startswith("branch ")}
    for at, _, stub in ns["SITES"]:
        assert branches.get(at) == (syms[stub] & ~1) - seg_va, "release recipe branch at %#x" % at
    t, n = table - seg_va, 4 * len(ns["TABLE"])
    assert want[t:t + n] == code[t:t + n], "release table differs (other library targets)"
    vspi = (UP / "data/vspi.bplist").read_bytes()
    v = syms["cpc_vspi"] - seg_va
    assert want.find(vspi) >= 0 and code[v:v + len(vspi)] == vspi, "description blob"
    print("code segment: using CarPlay Ultra-Lite 1.3.3's release bytes (%d bytes; this compiler gives %d, "
          "same stubs, table and description)" % (len(want), len(code)))
    return bytearray(want)


GATE_FOR = {"cpc_stub_info": "gate_info", "cpc_stub_setup": "gate_setup", "cpc_stub_stream": "gate_stream"}


def cpc_index(name):
    m = re.search(r"^#define %s\s+(\d+)" % name, (UP / "native/cpc.h").read_text(), re.M)
    if not m:
        raise SystemExit("cpc.h: no " + name)
    return int(m.group(1))


def literal_sum(stock, ldr_at, wide):
    """r0 after "ldr r0, [pc, #imm]" (16-bit) or "ldr.w r0, [pc, #imm]" at ldr_at, then "add r0, pc" """
    if wide:
        hw1, hw2 = struct.unpack_from("<HH", stock, ldr_at)
        assert hw1 == 0xF8DF and hw2 >> 12 == 0, "ldr.w r0, [pc, #imm] at %#x" % ldr_at
        imm, add_at = hw2 & 0xFFF, ldr_at + 4
    else:
        hw, = struct.unpack_from("<H", stock, ldr_at)
        assert hw >> 8 == 0x48, "ldr r0, [pc, #imm] at %#x" % ldr_at
        imm, add_at = (hw & 0xFF) * 4, ldr_at + 2
    assert struct.unpack_from("<H", stock, add_at)[0] == 0x4478, "add r0, pc at %#x" % add_at
    word, = struct.unpack_from("<I", stock, ((ldr_at + 4) & ~3) + imm)
    return (word + add_at + 4) & 0xFFFFFFFF


def build_gate(ns, stock, syms, gate_va, zig, arm, work):
    """Our on/off gate (src/carplay-ultra/native/gate.S) after the release segment: (bytes, symbols)"""
    from elftools.elf.elffile import ELFFile
    tab = ns["TABLE"]
    logcat = tab[cpc_index("T_LOGCAT")]
    # the off paths redo the replaced instructions: check they compute what the gate loads
    assert literal_sum(stock, 0x12430, True) == logcat, "SETUP site literal"
    assert literal_sum(stock, 0x11718, False) == logcat, "stream site literal"
    run(zig, "cc", *arm, "-c", SRC / "native/gate.S", "-o", work / "gate.o")
    (work / "gate.ld").write_text("SECTIONS {\n  . = %#x;\n  .gate : { *(.text.gate) *(.text .text.*) }\n"
                                  "  /DISCARD/ : { *(.ARM.exidx*) *(.ARM.attributes) *(.comment) *(.note*) }\n}\n" % gate_va)
    run(zig, "ld.lld", "-static", "--no-dynamic-linker", "-e", "0", "-T", work / "gate.ld", "-o", work / "gate.elf",
        work / "gate.o")
    with open(work / "gate.elf", "rb") as f:
        e = ELFFile(f)
        names = [x.name for x in e.iter_sections() if x["sh_flags"] & 2]
        assert names == [".gate"], "gate must be one allocated section, got %s" % names
        sec = e.get_section_by_name(".gate")
        assert sec["sh_addr"] == gate_va
        gate = bytearray(sec.data())
        g = {x.name: x["st_value"] for x in e.get_section_by_name(".symtab").iter_symbols() if x.name}
    targets = [tab[cpc_index("T_ORIG_INFO")], tab[cpc_index("T_RET_INFO")], logcat, tab[cpc_index("T_RET_SETUP")],
               tab[cpc_index("T_RET_STREAM_STOCK")], syms["cpc_stub_info"], syms["cpc_stub_setup"], syms["cpc_stub_stream"]]
    table = g["gate_table"]
    for k, t in enumerate(targets):
        struct.pack_into("<i", gate, table - gate_va + 4 * k, t - table)
    for name in GATE_FOR.values():
        assert g[name] & 1, name + " is not Thumb"
    print("gate: %d bytes at %#x (on/off by DiPO's files/cpc-on)" % (len(gate), gate_va))
    return bytes(gate), g


def build_core(stock, work):
    """(libCarPlayCore with CarPlay Ultra-Lite only, with our Wi-Fi patch too)"""
    from elftools.elf.elffile import ELFFile
    ns = upstream_build()
    assert ns["STOCK_MD5"] == CORE_STOCK_MD5 and md5(stock) == CORE_STOCK_MD5, "not the stock libCarPlayCore.so"
    seg_va = ns["SEG_VA"]
    zig = os.environ.get("ZIG", "zig")
    # zig ignores -mthumb on an arm-* target (ARM code, 60 % larger); a thumb-* target gives Thumb-2
    arm = ["-target", "thumb-freestanding-eabi", "-mcpu=generic+v7a"] + ns["ARM"][4:]
    nat = UP / "native"
    vspi = (UP / "data/vspi.bplist").resolve().as_posix()
    run(zig, "cc", *arm, "-I", nat, '-DVSPI_FILE="%s"' % vspi, "-c", nat / "seg.S", "-o", work / "seg.o")
    run(zig, "cc", *arm, "-I", nat, "-c", nat / "hooks.c", "-o", work / "hooks.o")
    (work / "seg.ld").write_text("ENTRY(cpc_stub_info)\nSECTIONS {\n  . = %#x;\n"
                                 "  .seg : { *(.text.entry) *(.text .text.*) *(.rodata .rodata.*) }\n"
                                 "  /DISCARD/ : { *(.ARM.exidx*) *(.ARM.attributes) *(.comment) *(.note*) }\n}\n" % seg_va)
    run(zig, "ld.lld", "-static", "--no-dynamic-linker", "-T", work / "seg.ld", "-o", work / "seg.elf",
        work / "seg.o", work / "hooks.o")
    with open(work / "seg.elf", "rb") as f:
        e = ELFFile(f)
        names = [s.name for s in e.iter_sections() if s["sh_flags"] & 2]
        assert names == [".seg"], "segment must be one allocated section, got %s" % names
        assert not any(s.name.startswith(".rel") for s in e.iter_sections()), "segment has relocations"
        seg = e.get_section_by_name(".seg")
        assert seg["sh_addr"] == seg_va
        code = bytearray(seg.data())
        symtab = e.get_section_by_name(".symtab")
        syms = {s.name: s["st_value"] for s in symtab.iter_symbols() if s.name}
        undef = [s.name for s in symtab.iter_symbols() if s.name and s["st_shndx"] == "SHN_UNDEF"]
        assert not undef, "undefined symbols: %s" % undef
    plt = ns["plt_targets"](stock)
    table = syms["cpc_table"]
    for k, t in enumerate(ns["TABLE"]):
        struct.pack_into("<i", code, table - seg_va + 4 * k, (plt[t] if isinstance(t, str) else t) - table)
    code = release_code(ns, code, syms, table)
    gate_va = seg_va + ((len(code) + 3) & ~3)
    gate, gsyms = build_gate(ns, stock, syms, gate_va, zig, arm, work)
    p = bytearray(stock)
    for at, before, stub in ns["SITES"]:
        assert p[at:at + 4].hex() == before, "site %#x isn't the stock instruction" % at
        p[at:at + 4] = ns["bw"](at, gsyms[GATE_FOR[stub]])
    for at, before, after in ns["SETUP_LOG"]:
        assert p[at:at + 2].hex() == before, "setup log site %#x isn't stock" % at
        p[at:at + 2] = bytes.fromhex(after)
    assert len(p) <= seg_va, "library longer than the segment address"
    p += b"\0" * (seg_va - len(p)) + code
    p += b"\0" * (gate_va - len(p)) + gate
    load = len(p) - seg_va
    phoff, = struct.unpack_from("<I", p, 0x1C)
    phnum, = struct.unpack_from("<H", p, 0x2C)
    for k in range(phnum):
        if struct.unpack_from("<I", p, phoff + 32 * k)[0] == 0x6474E551:   # PT_GNU_STACK stands in for the PT_LOAD
            struct.pack_into("<8I", p, phoff + 32 * k, 1, seg_va, seg_va, seg_va, load, load, 5, 0x1000)
            break
    else:
        raise SystemExit("no PT_GNU_STACK to stand in for the segment's PT_LOAD")
    ultra = bytes(p)
    (work / "core.ultra.so").write_bytes(ultra)
    run(LPATCH, "apply", ROOT / "recipes/carplay-core-wifi.recipe", work / "core.ultra.so", work / "core.both.so",
        stdout=subprocess.DEVNULL)
    both = (work / "core.both.so").read_bytes()
    check = run(LPATCH, "check", ROOT / "recipes/carplay-core-wifi.recipe", work / "core.both.so",
                capture_output=True, text=True).stdout
    assert "status=APPLIED" in check, "the Wi-Fi recipe doesn't read APPLIED on the combined library:\n" + check
    (work / "core.stock.so").write_bytes(stock)
    run(LPATCH, "apply", ROOT / "recipes/carplay-core-wifi.recipe", work / "core.stock.so", work / "core.wifi.so",
        stdout=subprocess.DEVNULL)
    assert md5((work / "core.wifi.so").read_bytes()) == CORE_WIFI_MD5, "Wi-Fi recipe result"
    return ultra, both


def build_classes(work):
    android = Path(os.environ["ANDROID_HOME"]) / "platforms/android-19/android.jar"
    sources = sorted((UP / "mod/src").rglob("*.java")) + sorted((SRC / "shim").rglob("*.java")) \
        + sorted((SRC / "glue").rglob("*.java"))
    classes = work / "classes"
    run("javac", "-nowarn", "-source", "8", "-target", "8", "-encoding", "UTF-8", "-bootclasspath", android,
        "-d", classes, *sources)
    dex = work / "dex"
    dex.mkdir()
    run("java", "-cp", os.environ["D8_JAR"], "com.android.tools.r8.D8", "--release", "--min-api", "19",
        "--lib", android, "--output", dex, *sorted(classes.rglob("*.class")))
    run("baksmali", "d", "--api", "18", dex / "classes.dex", "-o", work / "ours-smali")
    return work / "ours-smali"


def method_body(text, method, path):
    m = re.search(r"^\.method [^\n]*" + re.escape(method) + r"\n(.*?)^\.end method", text, re.S | re.M)
    if not m:
        raise SystemExit("method not found: %s in %s" % (method, path.name))
    return m


def insert_calls(path, method, start=None, end=None):
    """Add invoke-static lines to a method: start after .registers, end before every return-void."""
    text = path.read_text()
    m = method_body(text, method, path)
    body = m.group(1)
    if ULTRA in body:
        raise SystemExit("already patched: " + method)
    if start:
        anchor = re.search(r"^\s*\.registers \d+\n", body, re.M)
        if not anchor:
            raise SystemExit("no .registers in " + method)
        body = body[:anchor.end()] + "\n    " + start + "\n" + body[anchor.end():]
    if end:
        if "return-void" not in body or re.search(r"^\s*return(-object|-wide)? ", body, re.M):
            raise SystemExit("unexpected returns in " + method)
        body = body.replace("    return-void", "    " + end + "\n\n    return-void")
    path.write_text(text[:m.start(1)] + body + text[m.end(1):])


def build_dipo(stock, ours, work, variant, patches, base_name):
    work = work / variant
    work.mkdir()
    base = work / "bridge.dex"
    run(LPATCH, "delta", patches / variant / base_name, stock / "app/HKMC_DiPO.odex", base,
        stdout=subprocess.DEVNULL)
    tree = work / "smali"
    run("baksmali", "d", "--api", "18", base, "-o", tree)
    for pkg in OUR_PACKAGES:
        if (tree / pkg).exists():
            raise SystemExit("DiPO already has a %s package" % pkg)
        shutil.copytree(ours / pkg, tree / pkg)
    svc = tree / SERVICE
    if ".super Landroid/app/Service;" not in svc.read_text()[:600]:
        raise SystemExit("CarPlayService is not an android.app.Service")
    insert_calls(svc, "public onCreate()V",
                 end="invoke-static/range {p0 .. p0}, %s->onCreate(Landroid/app/Service;)V" % ULTRA)
    insert_calls(svc, "public onSessionStarted(I)V",
                 start="invoke-static/range {p0 .. p1}, %s->started(Ljava/lang/Object;I)V" % ULTRA,
                 end="invoke-static {}, %s->startedDone()V" % ULTRA)
    insert_calls(svc, "public onSessionFinalized(I)V",
                 start="invoke-static/range {p0 .. p1}, %s->finalized(Ljava/lang/Object;I)V" % ULTRA,
                 end="invoke-static {}, %s->finalizedDone()V" % ULTRA)
    dex = work / "HKMC_DiPO.ultra.dex"
    run("smali", "a", "-j", "1", "--api", "18", "-o", dex, tree)
    apk = work / "HKMC_DiPO.ultra.apk"
    run(LPATCH, "zipadd", stock / "app/HKMC_DiPO.apk", dex, apk, stdout=subprocess.DEVNULL)
    return dex, apk


def delta(src, dst, lpd, work):
    run(sys.executable, ROOT / "tools/mkdelta.py", src, dst, lpd)
    check = work / ("roundtrip-" + lpd.name)
    run(LPATCH, "delta", lpd, src, check, stdout=subprocess.DEVNULL)
    assert check.read_bytes() == Path(dst).read_bytes(), "delta round trip " + lpd.name


def set_manifest(patches, variant, lines):
    m = patches / variant / "manifest"
    keep = [l for l in m.read_text().splitlines() if not l.startswith("3_bridge+ultra ")]
    m.write_text("\n".join(keep + lines) + "\n")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--stock", action="append", required=True, help="<patch set>=<stock tree (app/, lib/)>")
    p.add_argument("--work", type=Path, help="keep intermediate files here (default: temporary)")
    p.add_argument("--patches", type=Path, default=ROOT / "patches",
                   help="the patch tree to read the bridge DiPO from and write to (default: patches/); "
                        "an AR release tree for an AR installer")
    p.add_argument("--dipo-base", default="HKMC_DiPO.bridge.lpd",
                   help="the DiPO build to add CarPlay Ultra-Lite to, in <patches>/<set>/ (an AR installer: "
                        "HKMC_DiPO.bridge.advanced-ar.lpd, so the AR and CarPlay recovery code stays)")
    a = p.parse_args()
    a.patches = a.patches.resolve()
    stocks = []
    for item in a.stock:
        variant, _, path = item.partition("=")
        if not path or not (a.patches / variant / a.dipo_base).is_file():
            p.error("no %s for patch set %s" % (a.dipo_base, variant))
        stocks.append((variant, Path(path)))
    work = Path(a.work or tempfile.mkdtemp(prefix="carplay-ultra-"))
    work.mkdir(parents=True, exist_ok=True)
    try:
        core = (stocks[0][1] / "lib/libCarPlayCore.so").read_bytes()
        for variant, stock in stocks:
            assert (stock / "lib/libCarPlayCore.so").read_bytes() == core, variant + ": another libCarPlayCore.so"
        _, both = build_core(core, work)
        lpd = a.patches / "common/libCarPlayCore.ultra.lpd"
        delta(work / "core.stock.so", work / "core.both.so", lpd, work)
        core_line = "3_bridge+ultra file libCarPlayCore.so common/libCarPlayCore.ultra.lpd " + md5(both)
        print("libCarPlayCore.so (Wi-Fi + CarPlay Ultra-Lite) md5", md5(both))
        ours = build_classes(work)
        for variant, stock in stocks:
            dex, apk = build_dipo(stock, ours, work, variant, a.patches, a.dipo_base)
            lpd = a.patches / variant / "HKMC_DiPO.bridge.ultra.lpd"
            delta(stock / "app/HKMC_DiPO.odex", dex, lpd, work / variant)
            apk_md5 = md5(apk.read_bytes())
            set_manifest(a.patches, variant, [core_line,
                                   "3_bridge+ultra app HKMC_DiPO %s/HKMC_DiPO.bridge.ultra.lpd %s" % (variant, apk_md5)])
            print(variant, "HKMC_DiPO CarPlay Ultra-Lite apk md5", apk_md5)
    finally:
        if not a.work:
            shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
