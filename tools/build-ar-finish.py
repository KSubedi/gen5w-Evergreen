#!/usr/bin/env python3
from pathlib import Path
import hashlib,zipfile
root=Path(__file__).resolve().parents[1]
out=root/'dist/Evergreen-AR-export-stop-clean.zip'
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
    for name in ('NEMESIS.data','NEMESIS.sh','stopped-recorder.sh'):
        z.write(root/'kits/ar-finish'/name,name)
    # Recorders the kit may replace: the current one and those of 1.3.0.119, 1.3.0.118, 1.3.0.117, 1.3.0.116, 1.3.0.115, 1.3.0.114, 1.3.0.113, 1.2.0.112, 111, 110, 109, 108, 107, 106, 105 and 104.
    z.writestr('original-md5.txt',hashlib.md5((root/'kits/ar-installer/ar-debug.sh').read_bytes()).hexdigest()+
        '\nbe1e16854ae6ca699aeb9136a424864b\nb8624055495e8bdcc44c7157ec48f0c3\n4f061d60da9f07cf10091eb598fbd21d\n265c31019d0fb1c8299ed8b4390806fb\nd04eade5fc525b957e0e3b06103de8c3\n98ae2371e43a4b3ff551d6893226ef64\n96287b7adca195f5c0397293b1c656d1\n4c3e9a90503bb24171555444a5264798\n539db9e21bbfc34f23791c6c739e91a6\nbc45a7a7349652587705cf0f4a5308e4\n4663ae44f5f801104f68c61d9be4c0f1\nf2b7493215b591b8e43643d6e4ee4f0f\n4e9ad176d7bcc9a6f2fef9ed1acb7c86\n18459287a2f367ecc5925bf938b3bbe6\n4ae12b09d2651153f227a846fcadf5fe\nf31bb1512f745219cd22dfd5bb907190\n')
    z.writestr('README.txt','For EV6 US 260507 with AR 1.2.0.104 to 1.3.0.120. Extract to an empty USB and run parked in READY after your drive.\n'
        'The USB root must hold NEMESIS.sh and NEMESIS.data themselves, not another zip or a folder around them.\n'
        'Keep USB connected until Export and cleanup complete. Then remove it and share AR-DEBUG-FINISHED privately.\n'
        'Stops persistent recording, verifies copied log checksums, then deletes the verified internal logs.\n'
        'Any stock-navigation cluster baseline capture is exported and removed the same way.\n'
        'If verification fails, internal logs are kept and recording stays disabled. Retry with a working USB.\n'
        'If NEMESIS reports a failure, the reason is in AR-EXPORT-ERROR.txt on the USB and an unverified copy of the\n'
        'logs is in AR-DEBUG-SNAPSHOT. Share both; nothing has been deleted from the head unit in that case.\n'
        'AR keeps working. Recording stays disabled at reboot; reinstalling a diagnostic build may enable it again.\n'
        'Normal Android in-memory logcat still exists; this stops the extra recorder writing to internal storage.\n')
print(out)
