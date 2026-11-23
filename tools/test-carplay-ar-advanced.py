#!/usr/bin/env python3
"""Synthetic advanced-adapter and three-app build tests; no OEM code fixtures."""
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT=Path(__file__).resolve().parents[1]


def load(name):
    spec=importlib.util.spec_from_file_location(name.replace('-','_'),ROOT/'tools'/(name+'.py'))
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m


advanced=load('build-carplay-ar-advanced');adapter=load('patch-carplay-ar-advanced')
simple=load('patch-carplay-ar');base=load('build-carplay-ar');verify=load('check-carplay-ar-build')
installer=load('build-carplay-ar-installer')
HELPERS=ROOT/'src/carplay-ar/build/smali';LPATCH=ROOT/'tools/lpatch/lpatch-host'
NC='Lcom/hkmc/cluster/NavigationCluster;'


def write(path,text):
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)


def dipo(tree):
    args='\n'.join('    const/4 v%d, 0x0'%i for i in range(10))
    write(tree/adapter.U,'''.class public Lcom/daudio/app/dipo/e/u;
.super Ljava/lang/Object;
.field private Am:Ljava/util/LinkedHashMap;
.field private Al:Ljava/util/LinkedHashMap;
.field public static leafiaOwner:Lcom/daudio/app/dipo/e/b;
.method private a(Ljava/lang/Integer;Ljava/lang/Object;)V
    .registers 15
    check-cast p2, Lcom/daudio/app/dipo/e/c;
'''+args+'''
    if-eqz v0, :cond_e

    if-eqz v1, :cond_e
    invoke-virtual/range {v0 .. v9}, Lcom/hkmc/cluster/NavigationCluster;->updateTBTInfo(IIFFIIIII)V
    :cond_e
    return-void
.end method
.method private sendHudBlank()V
    .registers 3
    const/4 v0, 0x0
    const/4 v1, 0x0
    invoke-virtual {v0, v1}, Lcom/hkmc/cluster/NavigationCluster;->updateTPTurnListNew([B)V
    return-void
.end method
.method private sendHud(ILjava/lang/Integer;Lcom/daudio/app/dipo/e/c;)V
    .registers 6
    const/4 v0, 0x0
    const/4 v1, 0x0
    invoke-virtual {v0, v1}, Lcom/hkmc/cluster/NavigationCluster;->updateTPTurnListNew([B)V
    return-void
.end method
.method private sendHudDist(FI)V
    .registers 3
    return-void
.end method
.method public c(Ljava/lang/Object;)V
    .registers 3
    return-void
.end method
.method public d(Ljava/lang/Object;)V
    .registers 3
    return-void
.end method
.method private nT()V
    .registers 1
    return-void
.end method
.method public nS()V
    .registers 1
    return-void
.end method
''')
    write(tree/adapter.C,'''.class public Lcom/daudio/app/dipo/e/c;
.super Ljava/lang/Object;
.field private aR:I
.field private aS:I
.field private ba:I
.field private bd:[I
.field private bb:Ljava/lang/String;
.method public a(ILjava/lang/Object;)V
    .registers 4
    const/4 v0, 0x0
    iput v0, p0, Lcom/daudio/app/dipo/e/c;->ba:I
    iput-object v0, p0, Lcom/daudio/app/dipo/e/c;->bb:Ljava/lang/String;
    return-void
.end method
.method public ad()V
    .registers 1
    return-void
.end method
''')
    tel='Lcom/daudio/app/dipo/telechips/'
    write(tree/adapter.B,'.class public Lcom/daudio/app/dipo/e/b;\n.super Ljava/lang/Object;\n'+
          ''.join('.method public final %s()V\n    .registers 1\n    return-void\n.end method\n'%m for m in ('M','N')))
    for name in ('CarPlayTransferPriority','CarPlayConstraint'):
        write(tree/('com/daudio/app/dipo/telechips/%s.smali'%name),'.class public L%s;\n.super Ljava/lang/Object;\n'
              '.field public static UserInitiated:%s%s;\n'%(tel[1:]+name,tel,name))
    callbacks=''.join('.method public %s\n    .registers %d\n    const/4 v0, 0x0\n    return-void\n.end method\n'%(m,r)
                      for m,r in (('onScreenTimeout()V',4),('onNotifyDecodeFailure(I)V',5),('onScreenStarted()V',4),
                                  ('onScreenStopped()V',4),('onSessionStarted(I)V',5),('onSessionFinalized(I)V',6),
                                  ('oniAPSendMessage([BI)V',7)))
    controls=''.join('.method %s %s\n    .registers 4\n    const/4 v0, 0x0\n    %s\n.end method\n'%(kind,m,ret) for kind,m,ret in (
        ('public','forceKeyFrame()'+tel+'CarPlayReturn;','return-object v0'),
        ('public','changeModeBorrowScreen('+tel+'CarPlayTransferPriority;'+tel+'CarPlayConstraint;Ljava/lang/String;)V','return-void'),
        ('public','changeModeUnborrowScreen(Ljava/lang/String;)V','return-void'),
        ('public','disconnectCarPlay()'+tel+'CarPlayReturn;','return-object v0'),
        ('private','restartBonjour()'+tel+'CarPlayReturn;','return-object v0')))
    write(tree/adapter.CPS,'.class public '+tel+'CarPlayService;\n.super Ljava/lang/Object;\n'+callbacks+controls)
    write(tree/'com/daudio/app/dipo/telechips/CarPlayReturn.smali','.class public '+tel+'CarPlayReturn;\n.super Ljava/lang/Object;\n')
    write(tree/adapter.V,'.class public Lcom/daudio/app/dipo/e/v;\n.super Ljava/lang/Object;\n'+
          ''.join('.field private '+x+':I\n' for x in ('DM','DR','DS','DU','DV'))+'.field private DT:[I\n')


def gpm(tree):
    """A synthetic GPM turn manager with the five methods the Android Auto adapter touches."""
    nc='Lcom/hkmc/cluster/NavigationCluster;'
    call='    invoke-virtual/range {v0 .. v9}, '+nc+'->updateTBTInfo(IIFFIIIII)V\n'
    zero=''.join('    const/4 v%d, 0x0\n'%i for i in range(10))
    write(tree/adapter.GPM,'.class public Lcom/google/android/projection/sink/navigation/GpmNaviTBTManager;\n.super Ljava/lang/Object;\n'
          '.method public onNextTurn(Ljava/lang/String;IIII)V\n    .registers 12\n    return-void\n.end method\n'
          '.method public onNextTurnDistance(III)V\n    .registers 6\n    return-void\n.end method\n'
          '.method public onStatus(I)V\n    .registers 4\n    return-void\n.end method\n'
          '.method private updateTBTInfo()V\n    .registers 11\n'+zero+call+'    return-void\n.end method\n'
          '.method private releaseGuidance()V\n    .registers 12\n'+zero+call+'    return-void\n.end method\n')
    write(tree/'com/google/android/projection/sink/navigation/a.smali','.class final Lcom/google/android/projection/sink/navigation/a;\n.super Ljava/lang/Object;\n'
          '.method public x()V\n    .registers 1\n    # invokes: Lcom/google/android/projection/sink/navigation/GpmNaviTBTManager;->updateTBTInfo()V\n    return-void\n.end method\n')


def navi(tree):
    write(tree/'synthetic/Nav.smali','''.class public Lsynthetic/Nav;
.super Ljava/lang/Object;
.method public static modern(Lcom/hkmc/cluster/NavigationCluster;Lcom/hkmc/cluster/NavigationCluster$NaviTbtTrapInfo;)V
    .registers 2
    invoke-virtual {p0, p1}, Lcom/hkmc/cluster/NavigationCluster;->updateTBTTrapInfoNew(Lcom/hkmc/cluster/NavigationCluster$NaviTbtTrapInfo;)V
    return-void
.end method
.method public static legacy(Lcom/hkmc/cluster/NavigationCluster;IIIIIIIIIIIIIIIII)V
    .registers 18
    invoke-virtual/range {p0 .. p17}, Lcom/hkmc/cluster/NavigationCluster;->updateTBTTrapInfo(IIIIIIIIIIIIIIIII)V
    return-void
.end method
.method public static nativeTurn(Lcom/hkmc/cluster/NavigationCluster;IIFFIIIII)V
    .registers 10
    invoke-virtual/range {p0 .. p9}, Lcom/hkmc/cluster/NavigationCluster;->updateTBTInfo(IIFFIIIII)V
    return-void
.end method
''')


def framework(tree):
    write(tree/'com/hkmc/cluster/NavigationCluster.smali','.class public '+NC+'\n.super Ljava/lang/Object;\n'+
          '.method public updateTBTTrapInfo'+adapter.OLD_SIG+'\n    .registers 18\n'+
          '    .param p12, "barGraphLevel"\n    .param p13, "speedLimit"\n    return-void\n.end method\n'+
          '.method public updateTBTTrapInfoNew'+adapter.NEW_SIG+'\n    .registers 2\n    return-void\n.end method\n')
    write(tree/'com/hkmc/cluster/NavigationCluster$NaviTbtTrapInfo.smali',
          '.class public Lcom/hkmc/cluster/NavigationCluster$NaviTbtTrapInfo;\n.super Ljava/lang/Object;\n'+
          ''.join('.field public '+f+':I\n' for f in adapter.TRAP_FIELDS)+
          '.method public constructor <init>()V\n    .registers 1\n    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n    return-void\n.end method\n')


class AdvancedTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.dipo=self.root/'dipo';self.navi=self.root/'navi';self.fw=self.root/'fw'
        self.gpm=self.root/'gpm'
        dipo(self.dipo);navi(self.navi);framework(self.fw);gpm(self.gpm)

    def test_adapter_hooks_assemble_and_reject_repeat(self):
        plan=adapter.inspect_dipo(self.dipo);self.assertEqual(len(plan),17)
        adapter.patch_dipo(self.dipo,plan)
        simple.patch(self.dipo,HELPERS,1)
        base.run('smali','a','-j','1','--api','18','-o',self.root/'patched.dex',self.dipo)
        for name in ('prepare','reset','exact','forget','begin','finish','details','resolveIcon','end','hudDistance'):
            self.assertTrue(any('ArHudRuntime;->'+name+'(' in p.read_text() for p in self.dipo.rglob('*.smali')))
        with self.assertRaises(ValueError):adapter.patch_dipo(self.dipo,plan)
        hud=adapter.method((self.dipo/adapter.U).read_text(),'sendHudDist(FI)V')[0]
        self.assertIn('->hudDistance(FI)F\n\n    move-result p1',hud)
        service=(self.dipo/adapter.CPS).read_text()
        self.assertEqual(service.count('ArHudRecovery;->screen(Ljava/lang/Object;II)V'),6)
        self.assertIn('invoke-static {}, Lorg/evergreen/carplay/ArHudRecovery;->iap()V',adapter.method(service,'oniAPSendMessage([BI)V')[0])
        decode=adapter.method(service,'onNotifyDecodeFailure(I)V')[0]
        self.assertIn('const/4 v0, 0x2\n\n    invoke-static {p0, v0, p1}, Lorg/evergreen/carplay/ArHudRecovery;->screen',decode)
        timeout=adapter.method(service,'onScreenTimeout()V')[0]
        self.assertIn('const/4 v0, 0x1\n\n    const/4 v1, 0x0\n\n    invoke-static {p0, v0, v1}',timeout)

    def test_recovery_needs_every_reflected_dipo_member(self):
        for path,old,new in ((adapter.CPS,'restartBonjour()','restartBonjourX()'),(adapter.B,'final M()V','final Q()V'),
                             (adapter.U,'static leafiaOwner','static leafiaOther'),
                             ('com/daudio/app/dipo/telechips/CarPlayConstraint.smali','UserInitiated','Never')):
            dipo(self.dipo);f=self.dipo/path;f.write_text(f.read_text().replace(old,new))
            with self.assertRaises(ValueError,msg=path):adapter.inspect_dipo(self.dipo)
        dipo(self.dipo);f=self.dipo/adapter.CPS;f.write_text(f.read_text().replace('    .registers 4\n    const/4 v0, 0x0\n    return-void\n.end method\n.method public onNotify','    .registers 1\n    return-void\n.end method\n.method public onNotify',1))
        with self.assertRaises(ValueError):adapter.inspect_dipo(self.dipo)

    def test_android_auto_hooks_and_calls_assemble(self):
        plan=adapter.inspect_gpm(self.gpm);self.assertEqual(len(plan),5)
        adapter.patch_gpm(self.gpm,HELPERS,plan)
        base.run('smali','a','-j','1','--api','18','-o',self.root/'gpm.dex',self.gpm)
        text=(self.gpm/adapter.GPM).read_text()
        self.assertEqual(text.count('ArHudAuto;->update('),2);self.assertNotIn('ArHudForwarder;->update(',text)
        self.assertIn('invoke-static {p2, p3, p4, p5}, Lorg/evergreen/carplay/ArHudAuto;->turn(IIII)V',text)
        self.assertIn('invoke-static {p1, p2, p3}, Lorg/evergreen/carplay/ArHudAuto;->distance(III)V',text)
        self.assertIn('invoke-static {p1}, Lorg/evergreen/carplay/ArHudAuto;->status(I)V',text)
        self.assertTrue((self.gpm/'org/evergreen/carplay/ArHudAuto.smali').is_file())
        with self.assertRaises(ValueError):adapter.patch_gpm(self.gpm,HELPERS,plan)

    def test_android_auto_refuses_unknown_gpm(self):
        plan=adapter.inspect_gpm(self.gpm)
        f=self.gpm/adapter.GPM;f.write_text(f.read_text().replace('.method public onStatus(I)V\n    .registers 4','.method public onStatus(I)V\n    .registers 5'))
        with self.assertRaises(ValueError):adapter.patch_gpm(self.gpm,HELPERS,plan)
        gpm(self.gpm);extra=self.gpm/'com/google/android/projection/sink/Other.smali'
        write(extra,'.class public Lcom/google/android/projection/sink/Other;\n.super Ljava/lang/Object;\n.method public y()V\n    .registers 10\n'
              +''.join('    const/4 v%d, 0x0\n'%i for i in range(10))+'    invoke-virtual/range {v0 .. v9}, Lcom/hkmc/cluster/NavigationCluster;->updateTBTInfo(IIFFIIIII)V\n    return-void\n.end method\n')
        with self.assertRaises(ValueError):adapter.inspect_gpm(self.gpm)
        extra.unlink();f.write_text(f.read_text().replace('.method public onNextTurnDistance(III)V','.method public onNextTurnDistance(IIII)V'))
        with self.assertRaises(ValueError):adapter.inspect_gpm(self.gpm)

    def test_stale_or_unknown_metadata_fails_before_edits(self):
        plan=adapter.inspect_dipo(self.dipo)
        path=self.dipo/adapter.C;path.write_text(path.read_text().replace('.registers 4','.registers 5'))
        before={str(p):p.read_bytes() for p in self.dipo.rglob('*.smali')}
        with self.assertRaises(ValueError):adapter.patch_dipo(self.dipo,plan)
        self.assertEqual(before,{str(p):p.read_bytes() for p in self.dipo.rglob('*.smali')})
        path.write_text(path.read_text().replace('private ba:I','private ba:J'))
        with self.assertRaises(ValueError):adapter.inspect_dipo(self.dipo)

    def test_trap_overloads_rewrite_but_native_turns_do_not(self):
        native=simple.inspect(self.navi);plan=adapter.inspect_navi(self.navi)
        self.assertEqual(len(plan),2)
        adapter.patch_navi(self.navi,HELPERS,plan)
        self.assertEqual([s for s in simple.inspect(self.navi) if not s['file'].startswith('org/')],native)
        self.assertTrue(all(s['file'].startswith('org/') for s in adapter.inspect_navi(self.navi)))
        base.run('smali','a','-j','1','--api','18','-o',self.root/'navi.dex',self.navi)

    def test_unknown_trap_registers_or_signature_rejected(self):
        path=self.navi/'synthetic/Nav.smali'
        path.write_text(path.read_text().replace('{p0 .. p17}','{p0 .. p16}'))
        with self.assertRaises(ValueError):adapter.inspect_navi(self.navi)

    def test_framework_requires_exact_bar_index_and_all_object_fields(self):
        api=adapter.inspect_framework(self.fw);self.assertEqual(api['bar_index'],11)
        path=self.fw/'com/hkmc/cluster/NavigationCluster.smali'
        path.write_text(path.read_text().replace('.param p12, "barGraphLevel"','.param p11, "barGraphLevel"'))
        with self.assertRaises(ValueError):adapter.inspect_framework(self.fw)
        framework(self.fw)
        path=self.fw/'com/hkmc/cluster/NavigationCluster$NaviTbtTrapInfo.smali'
        path.write_text(path.read_text()+'.field public newWarning:I\n')
        with self.assertRaises(ValueError):adapter.inspect_framework(self.fw)

    def test_optional_ipc_permissions_do_not_block_projection_install(self):
        work=self.root/'post-test';(work/'scripts').mkdir(parents=True)
        write(work/'scripts/post.sh','echo delegated > post.ran\n')
        stub=work/'bin';stub.mkdir()
        write(stub/'chown','#!/bin/sh\nexit 1\n');(stub/'chown').chmod(0o755)
        script=(ROOT/'kits/ar-installer/ar-post.sh').read_text()
        script=script.replace('/data/',str(work)+'/data/').replace('exec /system/bin/sh','exec /bin/sh')
        script='\n'.join('export PATH='+str(stub)+':/usr/bin:/bin' if line.startswith('export PATH=') else line for line in script.splitlines())+'\n'
        write(work/'post.sh',script)
        result=subprocess.run(['sh','post.sh'],cwd=work,capture_output=True)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertTrue((work/'post.ran').exists())
        self.assertIn(b'WARN: AR progress state unavailable',result.stdout)

    def test_debug_service_uses_bounded_own_tag_rotation(self):
        work=self.root/'service';work.mkdir();stub=work/'bin';stub.mkdir()
        logcat=stub/'logcat'
        logcat.write_text('#!/bin/sh\necho "$*" > "'+str(work/'args')+'"\nkill -TERM "$PPID"\n')
        logcat.chmod(0o755)
        source=(ROOT/'kits/ar-installer/ar-debug.sh').read_text()
        source=source.replace('/data/nemesis/wireless.carplay.aa-ar-debug',str(work/'logs'))
        source='\n'.join('export PATH='+str(stub)+':/usr/bin:/bin' if x.startswith('export PATH=') else x for x in source.splitlines())+'\n'
        script=work/'service.sh';script.write_text(source)
        result=subprocess.run(['sh',str(script)],timeout=5,capture_output=True)
        self.assertEqual(result.returncode,-15)
        args=(work/'args').read_text()
        self.assertIn('-r 1024 -n 31 -s EvergreenAR:W *:S',args)
        self.assertEqual((work/'logs').stat().st_mode & 0o777,0o700)
        self.assertIn('bounded 32x1MiB',(work/'logs/STATUS.txt').read_text())

    def test_debug_service_copies_hotspot_events_without_addresses(self):
        self.maxDiff=None
        work=self.root/'wifi';work.mkdir();stub=work/'bin';stub.mkdir()
        def tool(name,body):(stub/name).write_text('#!/bin/sh\n'+body);(stub/name).chmod(0o755)
        tool('dmesg','cat "'+str(work/'kernel')+'"\n')
        tool('log','echo "$*" >> "'+str(work/'logged')+'"\n')
        tool('logcat','sleep 2; kill -TERM "$PPID"\n')
        (work/'kernel').write_text(
            '<6>[   10.552688] CFG80211-ERROR) wl_cfg80211_del_station : Disconnect STA : c6:2b:cc:2e:b0:af scb_val.val 3\n'
            '<6>[   10.602161] CFG80211-ERROR) wl_notify_connect_status_ap : ** AP/GO Link up event **\n'
            '<4>[  415.307040] dhd_module_init in\n'
            '<6>[ 3977.580579] CFG80211-ERROR) wl_cfg80211_ch_switch_notify : Channel switch notification for freq: 5785 chanspec: 0xd89f\n'
            '<6>[ 4121.925019] CFG80211-ERROR) wl_notify_connect_status_ap : event WLC_E_DEAUTH(5) status 0 reason 4\n'
            '<6>[ 4121.925019] CFG80211-ERROR) wl_notify_connect_status_ap : event WLC_E_DEAUTH(5) status 0 reason 4\n'
            '<6>[18015.159439] CFG80211-ERROR) wl_notify_connect_status_ap : event WLC_E_DEAUTH_IND(6) status 0 reason 8\n')
        source=(ROOT/'kits/ar-installer/ar-debug.sh').read_text()
        source=source.replace('/data/nemesis/wireless.carplay.aa-ar-debug',str(work/'logs'))
        source='\n'.join('export PATH='+str(stub)+':/usr/bin:/bin' if x.startswith('export PATH=') else x for x in source.splitlines())+'\n'
        script=work/'service.sh';script.write_text(source)
        subprocess.run(['sh',str(script)],timeout=10,capture_output=True)
        lines=(work/'logged').read_text().splitlines()
        self.assertEqual(lines,['-p w -t EvergreenAR v121 wifi kernel_s=10.602161 event=ap_up',
                                '-p w -t EvergreenAR v121 wifi kernel_s=3977.580579 event=ch_switch freq=5785',
                                '-p w -t EvergreenAR v121 wifi kernel_s=4121.925019 event=deauth reason=4',
                                '-p w -t EvergreenAR v121 wifi kernel_s=18015.159439 event=deauth_by_phone reason=8'])
        self.assertNotIn(':af',(work/'logged').read_text())
        self.assertFalse((work/'logs/.wifi').exists())

    def test_baseline_service_waits_for_a_request_and_keeps_only_frame_lines(self):
        work=self.root/'baseline';(work/'pkg').mkdir(parents=True);stub=work/'bin';stub.mkdir()
        write(stub/'dumpsys','#!/bin/sh\n[ "$1" = com.hkmc.micom.cluster ] || exit 1\n'
              'echo "  NaviTbtInfo: type=TURN direction=90 distance=120"\necho "  dest name: Secret Street"\necho "  barGraphLevel=40 speedLimit=35"\n')
        write(stub/'sleep','#!/bin/sh\n[ "$1" = 10 ] && [ -f "'+str(work/'pkg/done')+'" ] && kill -TERM "$PPID"\n[ "$1" = 10 ] && : > "'+str(work/'pkg/done')+'"\nexit 0\n')
        for s in ('dumpsys','sleep'):(stub/s).chmod(0o755)
        source=(ROOT/'kits/ar-installer/ar-baseline.sh').read_text()
        source=source.replace('/data/nemesis/wireless.carplay.aa-ar-debug',str(work/'logs')).replace('/data/nemesis/apps/wireless.carplay.aa',str(work/'pkg'))
        source='\n'.join('export PATH='+str(stub)+':/usr/bin:/bin' if x.startswith('export PATH=') else x for x in source.splitlines())+'\n'
        script=work/'service.sh';script.write_text(source)
        write(work/'pkg/ar-baseline.requested','1\n')
        result=subprocess.run(['sh',str(script)],timeout=20,capture_output=True)
        self.assertEqual(result.returncode,-15,result.stderr)
        self.assertFalse((work/'pkg/ar-baseline.requested').exists())
        log=(work/'logs/cluster-baseline.log').read_text()
        self.assertIn('minutes=1',log);self.assertEqual(log.count('--- sample'),60)
        self.assertIn('type=TURN direction=90 distance=120',log);self.assertIn('barGraphLevel=40',log)
        self.assertNotIn('Secret Street',log);self.assertIn('cluster baseline end',log)
        self.assertEqual((work/'logs').stat().st_mode & 0o777,0o700)

    def test_three_build_delta_roundtrips_and_enabled_installer(self):
        stock=self.root/'stock';(stock/'app').mkdir(parents=True);(stock/'framework').mkdir()
        for name in ('core.odex','automotive.jar','automotive.odex'):(stock/'framework'/name).write_bytes(b'our synthetic framework '+name.encode())
        patches=self.root/'input-patches';shutil.copytree(ROOT/'patches',patches)
        lines=[];wanted=[]
        for name,tree in (('HKMC_DiPO',self.dipo),('HKMC_Navi_ExSL',self.navi),('HKMC_GPM',self.gpm)):
            original=stock/'app'/(name+'.odex');original.write_bytes(('our synthetic original '+name).encode())
            apk=stock/'app'/(name+'.apk')
            with zipfile.ZipFile(apk,'w') as z:z.writestr('AndroidManifest.xml','our synthetic app manifest')
            for path in (original,apk):wanted.append(path.name+' '+base.md5(path))
            dex=self.root/(name+'.dex');base.run('smali','a','-j','1','--api','18','-o',dex,tree)
            delta=patches/'ev6'/(name+'.synthetic.lpd')
            base.run(sys.executable,ROOT/'tools/mkdelta.py',original,dex,delta)
            result=self.root/(name+'-base.apk');base.run(LPATCH,'zipadd',apk,dex,result)
            for part in (('2_tbt','3_bridge') if name=='HKMC_DiPO' else ('2_tbt',)):
                lines.append(' '.join((part,'app',name,'ev6/'+delta.name,base.md5(result))))
        (patches/'ev6/stock').write_text('\n'.join(wanted)+'\n');(patches/'ev6/manifest').write_text('\n'.join(lines)+'\n')
        api=adapter.inspect_framework(self.fw)
        with mock.patch.object(advanced,'framework',return_value=api):
            plan=advanced.inspect(stock,patches,LPATCH)
            stale=copy.deepcopy(plan);stale['framework_api']['bar_index']=8
            with self.assertRaises(ValueError):advanced.build(stock,self.root/'stale',HELPERS,stale,patches,LPATCH)
            self.assertFalse((self.root/'stale').exists())
            output=self.root/'advanced';report=advanced.build(stock,output,HELPERS,plan,patches,LPATCH)
        self.assertEqual(len(report['builds']),4);verify.validate(output/'patches',output/'ar-build.json')
        self.assertEqual(next(b for b in report['builds'] if b['name']=='HKMC_GPM')['calls'],2)
        self.assertTrue(report['features']['android_auto_ar'])
        self.assertFalse(list(output.rglob('*.apk')));self.assertFalse(list(output.rglob('*.dex')))
        package=self.root/'advanced.zip';installer.build(output/'patches',package,True)
        with zipfile.ZipFile(package) as outer:
            # One drive: NEMESIS runs our script, which installs or collects the logs.
            self.assertIn('type=execute',outer.read('NEMESIS.data').decode());self.assertIn('wireless.carplay.aa.zip',outer.read('NEMESIS.data').decode())
            self.assertEqual(outer.read('AR_VERSION').decode().strip(),installer.VERSION)
            self.assertEqual(outer.read('NEMESIS.sh'),(ROOT/'kits/ar-combo/NEMESIS.sh').read_bytes())
            self.assertTrue((outer.getinfo('NEMESIS.sh').external_attr>>16)&0o111);self.assertIn('hotspot.sh',outer.namelist())
            with zipfile.ZipFile(io.BytesIO(outer.read('packages/wireless.carplay.aa.zip'))) as inner:
                self.assertIn('postinstall=scripts/ar-post.sh',inner.read('NEMESIS.data').decode())
                self.assertIn('name=ar-baseline',inner.read('NEMESIS.data').decode())
                deployed=json.loads(inner.read('AR_BUILD.json'));self.assertTrue(deployed['features']['progress_adapter'])
                self.assertTrue(deployed['trace_enabled']);self.assertIn('ar-trace.enabled',inner.namelist())
                self.assertTrue((inner.getinfo('svc/ar-baseline.sh').external_attr>>16)&0o111)
                self.assertIn(b'chown 1000',inner.read('scripts/ar-post.sh'))
        quiet=self.root/'quiet.zip';installer.build(output/'patches',quiet,True,False)
        with zipfile.ZipFile(quiet) as outer:
            with zipfile.ZipFile(io.BytesIO(outer.read('packages/wireless.carplay.aa.zip'))) as inner:
                self.assertNotIn('ar-trace.enabled',inner.namelist());self.assertFalse(json.loads(inner.read('AR_BUILD.json'))['trace_enabled'])
        # A car with an EV build of GPM needs its AR build too.
        evpatches=self.root/'ev-patches';shutil.copytree(output/'patches',evpatches)
        with (evpatches/'ev6/manifest').open('a') as m:m.write('2_tbt+ev app HKMC_GPM ev6/HKMC_GPM.ev.lpd 0123456789abcdef0123456789abcdef\n')
        with self.assertRaises(ValueError):verify.validate(evpatches,output/'ar-build.json')
        bad=copy.deepcopy(report);bad['builds']=bad['builds'][:3]
        (output/'bad.json').write_text(json.dumps(bad))
        with self.assertRaises(ValueError):verify.validate(output/'patches',output/'bad.json')


if __name__=='__main__':unittest.main()
