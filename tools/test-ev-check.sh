#!/bin/sh
# Off-car test of tools/ev-check.py with made-up kit reports built from ev/car-reference.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail=0
ok() { if eval "$2"; then echo "ok    $1"; else echo "FAIL  $1"; fail=1; fi; }
# The first verified car (Ioniq 5) as a matching report; the EV6 section is tested below.
awk '/^car /{n++} n==1 && /^(framework|code|file) /' "$ROOT/ev/car-reference" > "$T/ref"
awk '/^car /{n++} n==2 && /^(framework|code|file) /' "$ROOT/ev/car-reference" > "$T/ref6"
{ echo "prop ro.build.product=NEEV23.USA.0000.V016.010.260507"
  while read k f h; do if [ "$k" = code ]; then echo "code $f md5=$h"; else echo "file $f md5=$h"; fi; done < "$T/ref"; } > "$T/match"
run() { python3 "$ROOT/tools/ev-check.py" "$1" > "$T/out" 2>&1 && echo 0 || echo $?; }
ok "every file matches: fits" '[ "$(run "$T/match")" = 0 ] && grep -q "EV build fits" "$T/out"'
sed 's/^\(file \/system\/framework\/automotive-service.odex md5=\).*/\1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/' "$T/match" > "$T/fw"
ok "framework differs: review needed" '[ "$(run "$T/fw")" = 1 ] && grep -q "vehicle-command review" "$T/out"'
sed 's/^\(code \/system\/app\/HKMC_GPM.odex md5=\).*/\1bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/' "$T/match" > "$T/code"
ok "GPM code differs: rebuild" '[ "$(run "$T/code")" = 1 ] && grep -q "GPM app code differs" "$T/out" && ! grep -q "framework differs" "$T/out"'
grep -v hcan "$T/match" > "$T/old"
ok "older kit: asks for a new report" '[ "$(run "$T/old")" = 1 ] && grep -q "older kit" "$T/out"'
{ echo "prop ro.build.product=CVEV.USA.0000.V015.012.260507"
  while read k f h; do if [ "$k" = code ]; then echo "code $f md5=$h"; else echo "file $f md5=$h"; fi; done < "$T/ref6"; } > "$T/ev6"
ok "Kia EV6 US 260507: fits" '[ "$(run "$T/ev6")" = 0 ] && grep -q "EV build fits" "$T/out" && grep -q "Kia EV6 US 260507" "$T/out"'
ok "Ioniq 5 named as the closest car" '[ "$(run "$T/match")" = 0 ] && grep -q "closest verified car: Hyundai Ioniq 5" "$T/out"'
echo "no product here" > "$T/bad"
ok "not a report" '[ "$(run "$T/bad")" = 2 ]'
[ $fail = 0 ]
