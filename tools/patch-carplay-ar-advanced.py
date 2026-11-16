#!/usr/bin/env python3
"""Fingerprint and patch the inspected EV6 DiPO/Navi adapters, privately.

Contains only our inserted calls and structural/field metadata, no copied OEM
methods. All method bodies remain in the private disassembly supplied by caller.
"""
import hashlib
from pathlib import Path
import re
import shutil

NC = "Lcom/hkmc/cluster/NavigationCluster;"
RUNTIME = "Lorg/evergreen/carplay/ArHudRuntime;"
TRAP = "Lorg/evergreen/carplay/ArHudTrap;"
U = "com/daudio/app/dipo/e/u.smali"
C = "com/daudio/app/dipo/e/c.smali"
V = "com/daudio/app/dipo/e/v.smali"
CT = "Lcom/daudio/app/dipo/e/c;"
FIELDS = {
    U: {"Am": "Ljava/util/LinkedHashMap;", "Al": "Ljava/util/LinkedHashMap;"},
    C: {"aR": "I", "ba": "I", "bd": "[I"},
    V: {"DM": "I", "DR": "I", "DS": "I", "DU": "I"},
}
TRAP_FIELDS = ("fixedSpeed mobileSpeed overSpeedAlarm redlineSpeed nonSpeed warningZone curveWarning "
               "mergeWarning tollGateCurrency tollGate displayTG barGraphLevel speedLimit busSpeed "
               "speedLimitType speedLimitUnit speedInfoType warningZone2 warningZone3 warningZone4 "
               "displayCountry cameraSafetyAlert").split()


def method(text, signature):
    found = [m for m in re.finditer(r"(?ms)^\.method [^\n]+\n.*?^\.end method", text)
             if m[0].splitlines()[0].split()[-1] == signature]
    if len(found) != 1:
        raise ValueError("expected one method " + signature)
    return found[0]


def replace_method(path, signature, transform):
    text = path.read_text(); m = method(text, signature)
    path.write_text(text[:m.start()] + transform(m[0]) + text[m.end():])


def insert_entry(text, instruction):
    result, count = re.subn(r"(?m)^(    \.registers \d+\n)", lambda m: m[0]+"\n    "+instruction+"\n", text)
    if count != 1:
        raise ValueError("missing register declaration")
    return result


def inspect_dipo(tree):
    tree = Path(tree); sites = []
    for path, fields in FIELDS.items():
        text = (tree / path).read_text()
        for name, type in fields.items():
            if len(re.findall(r"(?m)^\.field [^\n]*\b"+re.escape(name+":"+type)+r"$", text)) != 1:
                raise ValueError("unexpected field layout: " + path + ":" + name)
    for path, signature in ((U,"a(Ljava/lang/Integer;Ljava/lang/Object;)V"), (U,"nT()V"),
                             (U,"nS()V"), (U,"d(Ljava/lang/Object;)V"), (C,"a(ILjava/lang/Object;)V"), (C,"ad()V")):
        text = method((tree/path).read_text(), signature)[0]
        if RUNTIME in text:
            raise ValueError("advanced adapter already present")
        registers = int(re.search(r"\.registers (\d+)", text)[1])
        args = 3 if signature.startswith("a(") else 2 if signature.startswith("d(") else 1
        if registers < args or registers > 16:
            raise ValueError("adapter requires inspected low-register method: " + signature)
        if path == U and signature.startswith("a("):
            if text.count("check-cast p2, " + CT) != 1:
                raise ValueError("missing guidance-object cast")
        if signature == "d(Ljava/lang/Object;)V" and text.count("    return-void") != 1:
            raise ValueError("expected shared maneuver-details return")
        if path == C and signature.startswith("a("):
            if len(re.findall(r"iput (v\d+), p0, "+re.escape(CT+"->ba:I"), text)) != 1:
                raise ValueError("missing unique exact-distance write")
        sites.append({"file":path,"method":signature,"sha256":hashlib.sha256(text.encode()).hexdigest(),"registers":registers})
    return sites


def patch_dipo(tree, plan):
    tree = Path(tree)
    if inspect_dipo(tree) != plan:
        raise ValueError("advanced DiPO plan is stale")
    replace_method(tree/U, "a(Ljava/lang/Integer;Ljava/lang/Object;)V", lambda t: t.replace(
        "check-cast p2, "+CT, "check-cast p2, "+CT+"\n\n    invoke-static {p0, p1, p2}, "+RUNTIME+"->prepare(Ljava/lang/Object;Ljava/lang/Integer;Ljava/lang/Object;)V"))
    replace_method(tree/U, "d(Ljava/lang/Object;)V", lambda t: t.replace(
        "    return-void", "    if-eqz p1, :evergreen_details_done\n\n    invoke-static {p0}, "+RUNTIME+
        "->details(Ljava/lang/Object;)V\n\n    :evergreen_details_done\n    return-void"))
    for signature in ("nT()V", "nS()V"):
        replace_method(tree/U, signature, lambda t: insert_entry(t,"invoke-static {}, "+RUNTIME+"->reset()V"))
    replace_method(tree/C, "a(ILjava/lang/Object;)V", lambda t: re.sub(
        r"(iput (v\d+), p0, "+re.escape(CT+"->ba:I")+r")", lambda m: m[1]+"\n\n    invoke-static {p0, "+m[2]+"}, "+RUNTIME+"->exact(Ljava/lang/Object;I)V", t))
    replace_method(tree/C, "a(ILjava/lang/Object;)V",lambda t:insert_entry(t,
        "invoke-static {p0}, "+RUNTIME+"->begin(Ljava/lang/Object;)V").replace(
        "    return-void","    invoke-static {p0}, "+RUNTIME+"->finish(Ljava/lang/Object;)V\n\n    return-void"))
    replace_method(tree/C, "ad()V", lambda t: insert_entry(t,"invoke-static {p0}, "+RUNTIME+"->forget(Ljava/lang/Object;)V"))


NEW_SIG = "(Lcom/hkmc/cluster/NavigationCluster$NaviTbtTrapInfo;)V"
OLD_SIG = "("+"I"*17+")V"
TRAP_CALL = re.compile(r"(?m)^(\s*)invoke-virtual(/range)?\s+(\{[^}\n]+\}),\s*"+re.escape(NC)+r"->(updateTBTTrapInfoNew|updateTBTTrapInfo)(\([^\n]+)\s*$")


def inspect_navi(tree):
    tree=Path(tree);sites=[]
    for path in sorted(tree.rglob("*.smali")):
        text=path.read_text()
        calls=[line for line in text.splitlines() if NC+"->updateTBTTrapInfo" in line and not line.lstrip().startswith("#")]
        matches=list(TRAP_CALL.finditer(text))
        if len(calls)!=len(matches):raise ValueError("unsupported trap call in "+str(path))
        for m in matches:
            if m[4]=="updateTBTTrapInfoNew" and m[5]==NEW_SIG and not m[2]:
                if not re.fullmatch(r"\{[vp]\d+, [vp]\d+\}",m[3]):raise ValueError("unknown new trap registers")
            elif m[4]=="updateTBTTrapInfo" and m[5]==OLD_SIG and m[2]:
                r=re.fullmatch(r"\{([vp])(\d+) \.\. \1(\d+)\}",m[3])
                if not r or int(r[3])-int(r[2])!=17:raise ValueError("unknown legacy trap registers")
            else:raise ValueError("unknown trap overload")
            containing=[x for x in re.finditer(r"(?ms)^\.method [^\n]+\n.*?^\.end method",text) if x.start()<=m.start()<x.end()]
            if len(containing)!=1:raise ValueError("trap call outside a method")
            body=containing[0][0]
            sites.append({"file":str(path.relative_to(tree)),"method":body.splitlines()[0].split()[-1],
                          "method_sha256":hashlib.sha256(body.encode()).hexdigest(),"api":m[4],"registers":m[3]})
    if not sites:raise ValueError("no navigation trap calls found")
    return sites


def patch_navi(tree, helpers, plan):
    tree=Path(tree)
    if inspect_navi(tree)!=plan:raise ValueError("navigation trap plan is stale")
    if (tree/"org/evergreen/carplay").exists():raise ValueError("helpers already injected")
    for path in tree.rglob("*.smali"):
        text=path.read_text()
        def replace(m):
            name="updateNew" if m[4]=="updateTBTTrapInfoNew" else "update"
            return m[1]+"invoke-static"+(m[2] or "")+" "+m[3]+", "+TRAP+"->"+name+"("+NC+m[5][1:]
        edited=TRAP_CALL.sub(replace,text)
        if edited!=text:path.write_text(edited)
    shutil.copytree(Path(helpers)/"org/evergreen/carplay",tree/"org/evergreen/carplay")


def inspect_framework(tree):
    tree=Path(tree);base=tree/"com/hkmc/cluster"
    text=(base/"NavigationCluster.smali").read_text()
    legacy=method(text,"updateTBTTrapInfo"+OLD_SIG)[0]
    if '.param p12, "barGraphLevel"' not in legacy or '.param p13, "speedLimit"' not in legacy:
        raise ValueError("unverified legacy bar/speed-limit argument layout")
    new=method(text,"updateTBTTrapInfoNew"+NEW_SIG)[0]
    fields=(base/"NavigationCluster$NaviTbtTrapInfo.smali").read_text()
    actual=set(re.findall(r"(?m)^\.field public ([A-Za-z0-9_]+):I$",fields))
    if actual!=set(TRAP_FIELDS):raise ValueError("unknown trap object fields; cannot preserve its data")
    method(fields,"<init>()V")
    return {"bar_index":11,"trap_fields":sorted(actual),"legacy_sha256":hashlib.sha256(legacy.encode()).hexdigest(),
            "new_sha256":hashlib.sha256(new.encode()).hexdigest(),"object_sha256":hashlib.sha256(fields.encode()).hexdigest()}
