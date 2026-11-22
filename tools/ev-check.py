#!/usr/bin/env python3
"""Does the EV build fit a car? Compares a Leafia kit report (LEAFIA-REPORT.txt, or an
issue body with one pasted in) with ev/car-reference, the files of each car the EV build
was made and checked on (Ioniq 5 US 260507, Kia EV6 US 260507).

  tools/ev-check.py LEAFIA-REPORT.txt      (or - for stdin)

Needs a report from a kit that lists automotive-service.odex and com.hkmc.ext.hcan.odex
(kits after 2026-11-06). Exit 0 = every file matches, 1 = something differs or is missing,
2 = bad input. Reads hashes only; works without any stock files.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF = os.path.join(ROOT, 'ev', 'car-reference')
JAVA = os.path.join(ROOT, 'ev', 'gpm-ev', 'src', 'com', 'google', 'android', 'projection',
                    'protocol', 'EvCar.java')


def reference():
    """{car: [(kind, path, md5)]}, in file order."""
    cars, car = {}, None
    for l in open(REF):
        if l.startswith('car '):
            car = l[4:].strip(); cars[car] = []
            continue
        p = l.split()
        if len(p) == 3 and not p[0].startswith('#'):
            if car is None:
                sys.exit('ev/car-reference: a file line before the first "car" line')
            cars[car].append(tuple(p))
    return cars


def check_reference(cars):
    """every car's hcan md5 must be one the EV code accepts"""
    m = re.search(r'HCAN_MD5S\s*=\s*\{([^}]*)\}', open(JAVA).read())
    accepted = re.findall(r'"([0-9a-f]{32})"', m.group(1)) if m else []
    for car, ref in cars.items():
        hcan = [h for k, f, h in ref if f.endswith('com.hkmc.ext.hcan.odex')]
        if len(hcan) != 1 or hcan[0] not in accepted:
            sys.exit('ev/car-reference: %s hcan md5 %s is not in EvCar.HCAN_MD5S %s' % (car, hcan, accepted))


def parse(text):
    product, files, codes = None, {}, {}
    for l in text.splitlines():
        l = l.strip()
        m = re.match(r'prop ro\.build\.product=(\S+)', l)
        if m:
            product = m.group(1)
        m = re.match(r'(file|code) (\S+) (md5=([0-9a-f]{32})|none|missing|absent)', l)
        if m:
            (files if m.group(1) == 'file' else codes)[m.group(2)] = m.group(4)
    return product, files, codes


WHAT = {
    'framework': 'vehicle framework',
    'code': 'GPM app code',
    'file': 'Android Auto library',
}


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    cars = reference()
    check_reference(cars)
    text = sys.stdin.read() if sys.argv[1] == '-' else open(sys.argv[1], encoding='utf-8', errors='replace').read()
    product, files, codes = parse(text)
    if not product:
        print('no "prop ro.build.product=" line: not a Leafia kit report')
        return 2
    print('car: %s' % product)
    # The verified car this report matches best (fewest differing files).
    def differing(ref):
        return sum(1 for kind, path, want in ref if (codes if kind == 'code' else files).get(path) != want)
    name = min(cars, key=lambda c: differing(cars[c]))
    ref = cars[name]
    print('closest verified car: %s' % name)
    bad = {'framework': [], 'code': [], 'file': []}
    old_kit = False
    for kind, path, want in ref:
        have = (codes if kind == 'code' else files).get(path, 'unreported')
        if path not in (codes if kind == 'code' else files):
            if kind == 'framework':
                old_kit = True
        state = 'same' if have == want else ('not in report' if have == 'unreported' else 'differs')
        print('  %-9s %-45s %s' % (kind, path, state))
        if state != 'same':
            bad[kind].append(path)
    print()
    if old_kit:
        print('This report is from an older kit that doesn\'t list every framework file: '
              'run the current Leafia kit and send its report.')
        return 1
    if not any(bad.values()):
        print('EV build fits: the same framework, GPM code and Android Auto library as the '
              '%s. No new vehicle-command review is needed; the car still needs its own energy '
              'model if its battery and efficiency differ, and a car test.' % name)
        return 0
    if bad['framework']:
        print('The vehicle framework differs: the EV code would stay off (it reads and sends '
              'nothing on another framework). Adding it needs that car\'s framework files '
              '(port kit), its command numbers found and checked, and a vehicle-command review.')
    if bad['code']:
        print('The GPM app code differs: the EV GPM has to be rebuilt onto this car\'s GPM '
              '(port kit stock files).')
    if bad['file']:
        print('The Android Auto library differs: the EV library patch has to be ported to it '
              'by hand (port kit stock files).')
    return 1


if __name__ == '__main__':
    sys.exit(main())
