#!/usr/bin/env python3
"""Stage a public, hash-verified AR delta snapshot over the normal patch tree.

No private stock files or complete OEM apps are needed to package a release.
Compile helpers first so the snapshot can be checked against the tagged source.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]


def prepare(version, output):
    spec = importlib.util.spec_from_file_location('ar_installer', ROOT/'tools/build-carplay-ar-installer.py')
    installer = importlib.util.module_from_spec(spec); spec.loader.exec_module(installer)
    if version != installer.VERSION:
        raise ValueError('release version does not match the AR installer')
    snapshot = ROOT/'releases/carplay-ar'/version
    report = json.loads((snapshot/'ar-build.json').read_text())
    helpers = ROOT/'src/carplay-ar/build/smali/org/evergreen/carplay'
    actual = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in helpers.glob('*.smali')}
    if actual != report['helper_sha256']:
        raise ValueError('compiled helpers differ from the tested release snapshot')
    allowed = {'ev6/manifest'} | {b['patch'] for b in report['builds']}
    files = {str(p.relative_to(snapshot/'patches')) for p in (snapshot/'patches').rglob('*') if p.is_file()}
    if files != allowed:
        raise ValueError('unexpected release snapshot files')
    output = Path(output).resolve()
    if output.exists():
        raise ValueError('choose a new staging directory')
    output.mkdir(parents=True)
    shutil.copytree(ROOT/'patches', output/'patches')
    for name in sorted(allowed):
        shutil.copy2(snapshot/'patches'/name, output/'patches'/name)
    shutil.copy2(snapshot/'ar-build.json', output/'ar-build.json')
    installer.verifier().validate(output/'patches', output/'ar-build.json')
    return output/'patches'


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--version',required=True);p.add_argument('--out',type=Path,required=True)
    a=p.parse_args()
    try: print(prepare(a.version,a.out))
    except (ValueError,OSError) as e:p.exit(1,str(e)+'\n')
