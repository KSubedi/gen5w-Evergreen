#!/usr/bin/env python3
"""Generate exact-distance/interpolation/progress patches for US EV6 260507.

Uses the owner's private stock files and already committed Evergreen deltas.
Exports only three new deltas, their complete patch tree, and fingerprint plans.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]


def module(name):
    spec=importlib.util.spec_from_file_location(name.replace('-','_'),ROOT/'tools'/(name+'.py'))
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result


base=module('build-carplay-ar')
adapter=module('patch-carplay-ar-advanced')
simple=module('patch-carplay-ar')


def inputs(stock,patches):
    lines, entries, fingerprint=base.inputs(stock,'ev6',patches)
    wanted=dict(line.split() for line in (patches/'ev6/stock').read_text().splitlines() if line.strip())
    for name in ('HKMC_Navi_ExSL.apk','HKMC_Navi_ExSL.odex'):
        if base.md5(stock/'app'/name)!=wanted.get(name):raise ValueError('stock checksum mismatch: '+name)
        fingerprint[name]=base.sha256(stock/'app'/name)
    navi=[line.split() for line in lines if line.split()[:3]==['2_tbt','app','HKMC_Navi_ExSL']]
    if len(navi)!=1:raise ValueError('expected one navigation build')
    entries+=navi
    framework={p.name:base.sha256(p) for p in sorted((stock/'framework').iterdir()) if p.suffix in ('.jar','.odex')}
    if not all(n in framework for n in ('core.odex','automotive.jar','automotive.odex')):
        raise ValueError('full matching framework collection required')
    return lines,entries,fingerprint,framework


def framework(stock,temp):
    # Use the existing porting tool's full ordered boot classpath, Dalvik API 18.
    fw=stock/'framework';out=temp/'framework'
    port=module('port')
    port.deodex(str(fw/'automotive.odex'),str(fw),str(out))
    return adapter.inspect_framework(out)


def reconstruct(stock,patches,entry,temp,lpatch):
    part,kind,name,delta,want=entry;key=part+'-'+name
    dex=temp/(key+'-base.dex');apk=temp/(key+'-base.apk');tree=temp/key
    base.run(lpatch,'delta',patches/delta,stock/'app'/(name+'.odex'),dex)
    base.run(lpatch,'zipadd',stock/'app'/(name+'.apk'),dex,apk)
    if base.md5(apk)!=want:raise ValueError('base Evergreen APK checksum mismatch: '+name)
    base.run('baksmali','d',dex,'-o',tree)
    sites=adapter.inspect_dipo(tree) if name=='HKMC_DiPO' else adapter.inspect_navi(tree)
    report={'part':part,'name':name,'base_patch':delta,'base_patch_sha256':base.sha256(patches/delta),
            'base_dex_sha256':base.sha256(dex),'sites':sites}
    if name=='HKMC_Navi_ExSL':report['native_turn_sites']=simple.inspect(tree)
    return tree,report


def inspect(stock,patches=None,lpatch=None):
    stock=Path(stock).resolve();patches=Path(patches or ROOT/'patches').resolve()
    lpatch=Path(lpatch or ROOT/'tools/lpatch/lpatch-host').resolve()
    _,entries,fingerprint,fw=inputs(stock,patches)
    report={'schema':2,'variant':'ev6','mode':'advanced','stock_sha256':fingerprint,'framework_sha256':fw,'builds':[]}
    with tempfile.TemporaryDirectory(prefix='ev6-ar-inspect-') as temp:
        temp=Path(temp);report['framework_api']=framework(stock,temp)
        for entry in entries:
            _,plan=reconstruct(stock,patches,entry,temp,lpatch);report['builds'].append(plan)
    return report


def build(stock,output,helpers,plan,patches=None,lpatch=None):
    stock=Path(stock).resolve();output=Path(output).resolve();helpers=Path(helpers).resolve()
    patches=Path(patches or ROOT/'patches').resolve();lpatch=Path(lpatch or ROOT/'tools/lpatch/lpatch-host').resolve()
    if output.exists() or output.is_relative_to(stock) or (output.is_relative_to(ROOT) and not output.is_relative_to(ROOT/'dist')):
        raise ValueError('choose a NEW private output directory or ignored dist directory')
    if {p.name for p in helpers.rglob('*.smali')}!=simple.HELPER_NAMES:raise ValueError('build the eleven helpers first')
    lines,entries,fingerprint,fw=inputs(stock,patches)
    if not isinstance(plan,dict) or plan.get('schema')!=2 or plan.get('mode')!='advanced' or plan.get('variant')!='ev6':
        raise ValueError('an advanced EV6 inspection plan is required')
    if plan.get('stock_sha256')!=fingerprint or plan.get('framework_sha256')!=fw:raise ValueError('plan inputs changed')
    output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='ev6-ar-advanced-') as temp:
        temp=Path(temp);api=framework(stock,temp)
        if api!=plan.get('framework_api'):raise ValueError('framework API plan changed')
        actual={'schema':2,'variant':'ev6','mode':'advanced','stock_sha256':fingerprint,'framework_sha256':fw,
                'framework_api':api,'builds':[]}
        staged=temp/'patches';shutil.copytree(patches,staged)
        report={'schema':2,'variant':'ev6','on_car_tested':False,'enabled_by_default':False,
                'baseline_on_car_confirmed':'1.2.0.101','stock_sha256':fingerprint,'framework_sha256':fw,'framework_api':api,
                'features':{'icon_ar':True,'metadata_adapter':True,'distance_adapter':True,'progress_adapter':True},
                'helper_sha256':{p.name:base.sha256(p) for p in sorted(helpers.rglob('*.smali'))},'builds':[]}
        for entry in entries:
            part,kind,name,old_patch,want=entry
            tree,inspected=reconstruct(stock,patches,entry,temp,lpatch);actual['builds'].append(inspected)
            expected=next((p for p in plan['builds'] if p.get('part')==part and p.get('name')==name),None)
            if expected!=inspected:raise ValueError('adapter plan changed for '+part+'/'+name)
            if name=='HKMC_DiPO':
                adapter.patch_dipo(tree,expected['sites']);simple.patch(tree,helpers,1)
            else:adapter.patch_navi(tree,helpers,expected['sites'])
            key=part+'-'+name;dex=temp/(key+'.dex');repeat=temp/(key+'-repeat.dex')
            for target in (dex,repeat):base.run('smali','a','-j','1','--api','18','-o',target,tree)
            if dex.read_bytes()!=repeat.read_bytes():raise ValueError('non-deterministic assembly')
            checked=temp/(key+'-check');base.run('baksmali','d',dex,'-o',checked)
            remaining=simple.inspect(checked)
            own=[p for p in remaining if p['file'].startswith('org/evergreen/carplay/')]
            original=[p for p in remaining if not p['file'].startswith('org/evergreen/carplay/')]
            if len(own)!=3 or original!=inspected.get('native_turn_sites',[]):
                raise ValueError('unexpected direct turn calls after adapter injection')
            if name=='HKMC_Navi_ExSL':
                sites=adapter.inspect_navi(checked)
                if len(sites)!=3 or any(p['file']!='org/evergreen/carplay/ArHudTrap.smali' for p in sites):
                    raise ValueError('unwrapped navigation trap call')
            delta='ev6/'+name+'.'+part.split('_',1)[1]+'.advanced-ar.lpd'
            base.run('python3',ROOT/'tools/mkdelta.py',stock/'app'/(name+'.odex'),dex,staged/delta)
            roundtrip=temp/(key+'-roundtrip.dex');base.run(lpatch,'delta',staged/delta,stock/'app'/(name+'.odex'),roundtrip)
            if dex.read_bytes()!=roundtrip.read_bytes():raise ValueError('delta round trip mismatch')
            apk=temp/(key+'-result.apk');base.run(lpatch,'zipadd',stock/'app'/(name+'.apk'),dex,apk);checksum=base.md5(apk)
            lines=[' '.join((part,kind,name,delta,checksum)) if line.split()==entry else line for line in lines]
            report['builds'].append({'part':part,'name':name,'patch':delta,'patch_sha256':base.sha256(staged/delta),
                                     'apk_md5':checksum,'dex_sha256':base.sha256(dex),'calls':1 if name=='HKMC_DiPO' else len(expected['sites'])})
        if actual!=plan:raise ValueError('inspection plan contains unexpected fields or builds')
        (staged/'ev6/manifest').write_text('\n'.join(lines)+'\n')
        with tempfile.TemporaryDirectory(prefix='.ar-publish-',dir=output.parent) as publish:
            result=Path(publish)/'result';result.mkdir();shutil.copytree(staged,result/'patches')
            (result/'ar-build.json').write_text(json.dumps(report,indent=2)+'\n')
            (result/'call-sites.json').write_text(json.dumps(actual,indent=2)+'\n');os.rename(result,output)
    return report


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--stock',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    p.add_argument('--inspect',action='store_true');p.add_argument('--plan',type=Path)
    p.add_argument('--helpers',type=Path,default=ROOT/'src/carplay-ar/build/smali');a=p.parse_args()
    try:
        if a.inspect:
            report=inspect(a.stock)
            with a.out.open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
        else:
            if not a.plan:p.error('--plan is required after reviewing --inspect output')
            report=build(a.stock,a.out,a.helpers,json.loads(a.plan.read_text()))
    except (ValueError,OSError,subprocess.CalledProcessError) as e:p.exit(1,str(e)+'\n')
    print(json.dumps(report,indent=2))
