#!/usr/bin/env python3
"""Run src/carplay-ultra/native/gate.S under qemu-arm, on both paths (src/carplay-ultra/test/gate/harness.S).

The harness jumps into each gate as the library's b.w does and checks where it lands and with
which registers and stack: without cpc-on, the library's own instruction(s) redone; with it,
CarPlay Ultra-Lite's stubs with everything as at the site. The gate's table is filled the way
tools/build-carplay-ultra.py fills it. qemu-user's -L prefix supplies the cpc-on file.
Needs zig (ZIG) with ld.lld, pyelftools, qemu-arm-static (QEMU)."""
import os, shutil, struct, subprocess, sys, tempfile
from pathlib import Path
from elftools.elf.elffile import ELFFile

ROOT = Path(__file__).resolve().parent.parent
GATE = ROOT / "src/carplay-ultra/native/gate.S"
HARNESS = ROOT / "src/carplay-ultra/test/gate/harness.S"
TARGETS = ["h_orig_info", "h_ret_info", "h_logcat", "h_ret_setup", "h_ret_stream",
           "h_stub_info", "h_stub_setup", "h_stub_stream"]   # gate.S's G_* order


def run(*a, **k):
    return subprocess.run([str(x) for x in a], **k)


def main():
    zig = os.environ.get("ZIG", "zig")
    qemu = os.environ.get("QEMU", "qemu-arm-static")
    w = Path(tempfile.mkdtemp(prefix="gate-test-"))
    try:
        sys.exit(test(w, zig, qemu))
    finally:
        shutil.rmtree(w, ignore_errors=True)


def test(w, zig, qemu):
    arm = ["-target", "thumb-freestanding-eabi", "-mcpu=generic+v7a"]
    for src, obj in ((GATE, "gate.o"), (HARNESS, "harness.o")):
        run(zig, "cc", *arm, "-c", src, "-o", w / obj, check=True)
    (w / "t.ld").write_text("ENTRY(_start)\nSECTIONS {\n  . = 0x10000;\n"
                            "  .text : { *(.text.harness) *(.text.gate) *(.text .text.*) }\n}\n")
    run(zig, "ld.lld", "-static", "-T", w / "t.ld", "-o", w / "t.elf", w / "harness.o", w / "gate.o", check=True)
    b = bytearray((w / "t.elf").read_bytes())
    with open(w / "t.elf", "rb") as f:
        e = ELFFile(f)
        s = {x.name: x["st_value"] for x in e.get_section_by_name(".symtab").iter_symbols() if x.name}
        text = e.get_section_by_name(".text")
        base, off = text["sh_addr"], text["sh_offset"]
    table = s["gate_table"]
    for k, name in enumerate(TARGETS):
        struct.pack_into("<i", b, off + table - base + 4 * k, s[name] - table)
    (w / "t.elf").write_bytes(b)
    os.chmod(w / "t.elf", 0o755)
    prefix = w / "root"
    on = prefix / "data/data/com.daudio.app.dipo/files/cpc-on"
    on.parent.mkdir(parents=True)
    failed = 0
    for label, args, make in (("off (no cpc-on)", [], False), ("on (cpc-on)", ["on"], True)):
        if make:
            on.write_bytes(b"")
        elif on.exists():
            on.unlink()
        rc = run(qemu, "-L", prefix, w / "t.elf", *args).returncode
        what = "ok" if rc == 0 else "FAIL (check %d: %s)" % (rc, {1: "gate_info", 2: "gate_setup", 3: "gate_stream"}.get(rc, "?"))
        print("%-6s gate %s: info, setup and stream sites" % (what.split()[0], label) + ("" if rc == 0 else " - " + what))
        failed += rc != 0
    return 1 if failed else 0


if __name__ == "__main__":
    main()
