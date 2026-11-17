#!/usr/bin/env python3
from pathlib import Path
import hashlib,zipfile
root=Path(__file__).resolve().parents[1]
out=root/'dist/Evergreen-AR-export-stop-clean.zip'
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
    for name in ('NEMESIS.data','NEMESIS.sh','stopped-recorder.sh'):
        z.write(root/'kits/ar-finish'/name,name)
    z.writestr('original-md5.txt',hashlib.md5((root/'kits/ar-installer/ar-debug.sh').read_bytes()).hexdigest()+'\nf31bb1512f745219cd22dfd5bb907190\n')
    z.writestr('README.txt','For EV6 US 260507 with AR 1.2.0.104 or 1.2.0.105. Extract to an empty USB and run parked in READY after your drive.\n'
        'Keep USB connected until Export and cleanup complete. Then remove it and share AR-DEBUG-FINISHED privately.\n'
        'Stops persistent recording, verifies copied log checksums, then deletes the verified internal logs.\n'
        'If verification fails, internal logs are kept and recording stays disabled. Retry with a working USB.\n'
        'AR keeps working. Recording stays disabled at reboot; reinstalling a diagnostic build may enable it again.\n'
        'Normal Android in-memory logcat still exists; this stops the extra recorder writing to internal storage.\n')
print(out)
