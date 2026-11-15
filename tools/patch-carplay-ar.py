#!/usr/bin/env python3
"""Inject the opt-in AR forwarder into a private, disassembled Evergreen DiPO.

Rewrites virtual/range calls without changing register allocation. Refuses unknown
overloads, unexpected call counts and repeat application. All inputs must be a
private copy; this tool writes no OEM code to the repository.
"""
import argparse
from pathlib import Path
import re
import shutil

NC = "Lcom/hkmc/cluster/NavigationCluster;"
SIG = "(IIFFIIIII)V"
FORWARD = "Lorg/evergreen/carplay/ArHudForwarder;->update(" + NC + SIG[1:]
CALL = re.compile(r"^(\s*)invoke-virtual/range\s+(\{[^}\n]+\}),\s*"
                  + re.escape(NC + "->updateTBTInfo" + SIG) + r"\s*$", re.M)


def patch(tree, helpers, expected_calls):
    tree, helpers = Path(tree), Path(helpers)
    if tree.resolve() == helpers.resolve() or expected_calls < 1:
        raise ValueError("separate input tree and helpers, positive expected call count required")
    sources = sorted(tree.rglob("*.smali"))
    if not sources:
        raise ValueError("no disassembled DiPO classes found")
    if (tree / "org/evergreen/carplay").exists():
        raise ValueError("AR helpers already present; start from the original Evergreen dex")
    own = helpers / "org/evergreen/carplay"
    if {p.name for p in helpers.rglob("*.smali")} != {"ArHudTurns.smali", "ArHudForwarder.smali"}:
        raise ValueError("build exactly the two AR helper classes first (no OEM stubs)")
    for name in ("ArHudTurns.smali", "ArHudForwarder.smali"):
        if not (own / name).is_file():
            raise ValueError("helper package does not match org.evergreen.carplay")
    edits, count = [], 0
    for path in sources:
        text = path.read_text()
        calls = [line for line in text.splitlines()
                 if NC + "->updateTBTInfo" in line and not line.lstrip().startswith("#")]
        matches = list(CALL.finditer(text))
        if len(calls) != len(matches):
            raise ValueError("unsupported cluster call shape in " + str(path))
        for match in matches:
            regs = re.fullmatch(r"\{([vp])(\d+)\s+\.\.\s+\1(\d+)\}", match[2])
            if not regs or int(regs[3]) - int(regs[2]) != 9:
                raise ValueError("expected receiver plus nine one-word arguments in " + str(path))
        count += len(matches)
        if matches:
            edits.append((path, CALL.sub(lambda m: m[1] + "invoke-static/range " + m[2] + ", " + FORWARD, text)))
    if count != expected_calls:
        raise ValueError("expected %d cluster calls, found %d; inspect this firmware first" % (expected_calls, count))
    # Validate the complete tree before making any changes.
    for path, text in edits:
        path.write_text(text)
    shutil.copytree(own, tree / "org/evergreen/carplay")
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tree", type=Path)
    parser.add_argument("--helpers", type=Path, default=Path(__file__).resolve().parents[1] / "src/carplay-ar/build/smali")
    parser.add_argument("--expected-calls", type=int, required=True)
    args = parser.parse_args()
    try:
        print("Forwarded %d DiPO cluster calls" % patch(args.tree, args.helpers, args.expected_calls))
    except ValueError as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
