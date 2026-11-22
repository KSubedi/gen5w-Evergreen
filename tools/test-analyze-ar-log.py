#!/usr/bin/env python3
"""Synthetic EvergreenAR records only; no car logs are included."""
import importlib.util,json,subprocess,sys,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('analyze_ar_log',ROOT/'tools/analyze-ar-log.py')
tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)

def record(t,seq,msg,pid=1060,tid=2489,drop=0):
    return '11-16 18:28:%02d.%03d  %d  %d W EvergreenAR: v107 t=%d seq=%d dropped=%d %s critical_dropped=0'%(t//1000%60,t%1000,pid,tid,t,seq,drop,msg)

LINES=[record(1000,1,'sender_prepare component=5 current=0 state=6'),
       record(1001,2,'icon_gate original=0 recovered=65 raw=11 angle=0 traffic=0 junction=0 state=6 mstate=1 angles=0'),
       record(1002,3,'sender_return unconsumed_metadata=true'),
       record(2000,4,'exact meters=300 at=2000'),record(2001,5,'parser_finish state=1 current=2 exact_touched=true receipt=true'),
       record(2002,6,'sender_prepare component=5 current=2 state=1'),record(2003,7,'maneuver_replaced clear_deferred'),
       record(2004,8,'main_input icon=67 distance=1000.0 unit=3 dist_error=0 bar_error=0 advanced=true metadata=true state=1 mstate=2'),
       record(2005,9,'main_send type=67 meters=300.0'),record(2006,10,'main_sent type=67 meters=300.0 exact=true age=6 force=true bar=0'),
       record(2007,11,'clear_skipped new_maneuver_sent'),record(2008,12,'sender_return unconsumed_metadata=false'),
       record(3000,13,'exact meters=100 at=3000'),record(3001,14,'sender_prepare component=5 current=2 state=1'),
       record(3002,15,'main_input icon=67 distance=330.0 unit=3 dist_error=0 bar_error=0 advanced=true metadata=true state=1 mstate=3'),
       record(3003,16,'main_send type=13 meters=100.0'),record(3004,17,'main_sent type=13 meters=100.0 exact=true age=4 force=true bar=80'),
       record(3250,18,'main_send type=13 meters=98.0',tid=3421),record(3251,19,'main_sent type=13 meters=98.0 exact=true age=251 force=false bar=70',tid=3421),
       record(4000,20,'exact meters=99 at=4000'),record(4001,21,'sender_prepare component=5 current=2 state=1'),
       record(4002,22,'main_send type=13 meters=99.0'),record(4003,23,'main_sent type=13 meters=99.0 exact=true age=3 force=true bar=70'),
       record(10000,24,'timer_expired advanced=true input_age=6000',tid=3421),
       record(11000,25,'exact meters=99 at=11000'),record(11001,26,'sender_prepare component=5 current=2 state=0'),record(11002,27,'reset'),
       record(1000,1,'trap_input modern limit=35 unit=2 bar=0',pid=1326,tid=1693),
       'W/EvergreenAR( 1060): turn icon=67 raw=2 traffic=0 junction=0 angle=0 angles=0 state=1 mstate=3 exact=true meters=100 result=native output=13 bar=80']

class AnalyzeTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.dir=Path(self.tmp.name)/'AR-DEBUG-FINISHED';self.dir.mkdir()
        (self.dir/'flow.log').write_text('\n'.join(LINES[14:])+'\n');(self.dir/'flow.log.1').write_text('\n'.join(LINES[:14])+'\n')
        (self.dir/'recent-logcat.txt').write_text('\n'.join('W/EvergreenAR( 1060): '+l.split('EvergreenAR: ',1)[1] for l in LINES[:3])+'\n')
    def test_summary_counts_and_dedup(self):
        events,turns=tool.load(self.dir);self.assertEqual(len(events),28);self.assertEqual(len(turns),1)
        report=tool.analyze(events,turns);p=report['processes']['1060']
        self.assertEqual(p['role'],'dipo');self.assertEqual(report['processes']['1326']['role'],'navigation')
        self.assertEqual(p['icon_gate'],{'raw11->65':1});self.assertEqual(p['exact']['count'],4)
        self.assertEqual(p['main_sent_types'],{'13':3,'67':1});self.assertEqual(p['interpolated_sends'],1)
        self.assertEqual(p['phone_sample_after_tick'],{'stepped_back':1,'continued':0})
        self.assertEqual(p['expiries'][0]['silent_s'],7);self.assertFalse(p['expiries'][0]['moving']);self.assertEqual(p['replacement_to_next_send_ms'],[3])
        self.assertEqual(p['clears'],{'clear_skipped':1});self.assertEqual(p['main_send_latency_ms']['max'],1)
        self.assertEqual([s['maneuver'] for s in p['segments']],['0','2','2'])
        self.assertEqual(p['segments'][1]['mstate'],{'2':1,'3':1})
        self.assertIn('icon=67 raw=2 result=native output=13',report['turn_records'])
    def test_replay_trace_is_sanitized_and_runs(self):
        out=self.dir.parent/'replays';paths=tool.write_replays(tool.load(self.dir)[0],out);self.assertEqual(len(paths),1)
        text=paths[0].read_text()
        self.assertIn('details,0,11,0,65',text);self.assertIn('packet,2,300,1',text);self.assertIn('packet,2,100,1',text)
        self.assertIn('tick',text);self.assertTrue(text.rstrip().endswith('stop'))
        for forbidden in ('wall','11-16','Street','name'):self.assertNotIn(forbidden,text)
        result=subprocess.run(['sh',str(ROOT/'tools/replay-carplay-ar.sh'),str(paths[0]),'--live'],capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stderr)
        rows=[l.split(',') for l in result.stdout.strip().splitlines()[1:]]
        self.assertEqual(rows[-1][1],'stop');self.assertTrue(any(r[3]=='13' for r in rows),'native turn reached in replay')
    def test_crash_and_quiet_records_are_reported(self):
        with (self.dir/'flow.log').open('a') as f:
            f.write('11-16 18:29:00.000  1060  3421 W EvergreenAR: v109 input_quiet thread=Thread-12 state=WAITING frames=java.lang.Object.wait:-2<a.b.c:10\n')
            f.write('11-16 18:29:01.000  1060  2489 W EvergreenAR: v109 uncaught thread=main exception=java.lang.IllegalStateException frames=a.b.c:10\n')
        (self.dir/'recent-logcat.txt').write_text('W/EvergreenAR( 1060): v109 uncaught thread=main exception=java.lang.IllegalStateException frames=a.b.c:10\n')
        records=tool.thread_records(self.dir)
        self.assertEqual(records['pid 1060: uncaught thread=main exception=java.lang.IllegalStateException frames=a.b.c:10'],2)
        self.assertEqual(len(records),2)
        events,turns=tool.load(self.dir)
        self.assertEqual(tool.analyze(events,turns,records)['thread_records'],records)
        self.assertNotIn('thread_records',tool.analyze(events,turns))
    def test_reused_pid_is_split_by_version_and_boot(self):
        later=[record(500,1,'sender_prepare component=5 current=0 state=6').replace('v107','v111'),
               record(900000,2,'exact meters=40 at=900000').replace('v107','v111'),
               record(700,1,'sender_prepare component=5 current=1 state=1').replace('v107','v111'),
               record(1700,2,'exact meters=90 at=1700').replace('v107','v111')]
        with (self.dir/'flow.log').open('a') as f:f.write('\n'.join(later)+'\n')
        events,turns=tool.load(self.dir);report=tool.analyze(events,turns)['processes']
        self.assertEqual([k for k in report if k.startswith('1060')],['1060/1','1060/2','1060/3'])
        self.assertEqual([report[k]['version'] for k in ('1060/1','1060/2','1060/3')],['v107','v111','v111'])
        self.assertEqual([report[k]['records'] for k in ('1060/1','1060/2','1060/3')],[27,2,2])
        self.assertEqual(report['1060/1']['exact']['count'],4);self.assertEqual(report['1060/3']['segments'][0]['maneuver'],'1')
        self.assertEqual(report['1326']['version'],'v107')
        names=sorted(x.name for x in tool.write_replays(events,self.dir.parent/'split'))
        self.assertEqual(names,['replay-pid1060-1.csv','replay-pid1060-2.csv','replay-pid1060-3.csv'])
    def test_health_records_show_a_silent_link(self):
        def health(n,rx,busy=20,extra=' wifi=wlan0:60/-41/-256/12/0/0 tcp=900/700/3'):
            return record(20000+5000*n,100+n,'health dt=5000 busy=%d wait=1 load=1.50 free_kb=%d threads=80 proc=50 top=DipoPlayer_Hand:30,Binder_1:5 net=wlan0:%d/20000,p2p0:0/0%s'%(busy,300000-n,rx,extra))
        lines=[health(n,2500000) for n in range(6)]+[health(n,0,busy=95,extra=' wifi=wlan0:20/-78/-256/400/9/2 tcp=50/600/120') for n in range(6,9)]+[health(n,2400000,extra='') for n in range(9,12)]
        lines.append(record(90000,200,'health dt=-1 busy=-1 wait=-1 load=- free_kb=- threads=0 proc=0 top=- net=-'))
        with (self.dir/'flow.log').open('a') as f:f.write('\n'.join(lines)+'\n')
        events,turns=tool.load(self.dir);h=tool.analyze(events,turns)['processes']['1060']['health']
        self.assertEqual(h['samples'],12);self.assertEqual(h['busy_percent'],{'p50':20,'p95':95,'max':95})
        self.assertEqual(h['interfaces']['wlan0']['rx_bytes_per_s']['max'],500000);self.assertEqual(h['interfaces']['p2p0']['rx_bytes_per_s']['max'],0)
        self.assertEqual(h['busiest_threads_ticks'],{'DipoPlayer_Hand':360,'Binder_1':60})
        self.assertEqual(h['receive_quiet'],[{'wall':'11-16 18:28:50.000','t':45000,'seconds':15,'busy_percent_max':95,'tcp_retransmitted':360,'level_dbm_min':-78}])
        self.assertEqual(h['radio']['wlan0'],{'level_dbm':{'p50':-41,'p5':-78,'min':-78},'retries_per_sample':{'p50':12,'p95':400,'max':400},'missed_beacons':6})
        self.assertEqual(h['tcp'],{'retransmitted_per_sample':{'p50':3,'p95':120,'max':120},'retransmitted_percent':6.3})
        self.assertEqual(h['free_kb_min'],299989);self.assertEqual(h['late_samples'],[])
        result=subprocess.run([sys.executable,'-I',str(ROOT/'tools/analyze-ar-log.py'),str(self.dir)],capture_output=True,text=True)
        self.assertIn("receive quiet for 10 s or more (wall, s, max busy %, TCP retransmits, min dBm; median 500000 bytes/s): [('11-16 18:28:50.000', 15, 95, 360, -78)]",result.stdout)
    def test_screen_recovery_and_hotspot_events(self):
        rec=lambda t,seq,msg:record(t,seq,msg,pid=1070).replace('v107','v113')
        lines=[rec(30000,300,'screen session_started'),rec(31000,301,'screen started'),
               '11-16 18:28:59.000  1500  1500 W EvergreenAR: v113 wifi kernel_s=3977.580579 event=ch_switch freq=5785',
               '11-16 18:28:59.000  1500  1500 W EvergreenAR: v113 wifi kernel_s=3977.580579 event=ch_switch freq=5785',
               '11-16 18:29:06.000  1500  1500 W EvergreenAR: v113 wifi kernel_s=3984.100000 event=deauth reason=4',
               rec(62000,302,'screen timeout'),rec(62001,303,'recover step=1 key_frame'),rec(62002,304,'screen stopped recovering_ms=20 step=1'),
               rec(66000,305,'recover step=2 borrow_screen after_ms=4000'),rec(67500,306,'recover step=2 return_screen after_ms=5500'),
               rec(68500,307,'recover_ok after_ms=6500 step=2'),rec(200000,308,'guidance_restart silent_ms=91000 state=1'),
               rec(200001,309,'guidance_restart_failed java.lang.NoSuchFieldException'),
               rec(300000,310,'link_dead silent_ms=30000 guidance_state=1'),rec(300001,311,'recover step=link end_session'),
               rec(330000,312,'link_back after_ms=30000')]
        with (self.dir/'flow.log').open('a') as f:f.write('\n'.join(lines)+'\n')
        wifi=tool.wifi_records(self.dir);self.assertEqual([w['event'] for w in wifi],['ch_switch','deauth'])
        self.assertEqual(wifi[0]['freq'],5785);self.assertEqual(wifi[1]['reason'],4)
        events,turns=tool.load(self.dir);report=tool.analyze(events,turns,None,wifi)
        sc=report['processes']['1070']['screen']
        self.assertEqual(sc['events']['timeout'],1);self.assertEqual(sc['events']['step 2 borrow_screen'],1)
        self.assertEqual(sc['recovered'],[{'wall':'11-16 18:28:08.500','after_ms':6500,'step':2},{'wall':'11-16 18:28:30.000','after_ms':30000,'step':'link'}])
        self.assertEqual(sc['guidance_restarts'],[{'wall':'11-16 18:28:20.000','silent_ms':91000}])
        self.assertEqual(sc['failures'],['guidance_restart_failed java.lang.NoSuchFieldException','link_dead silent_ms=30000'])
        self.assertEqual(sc['events']['link_dead'],1);self.assertEqual(sc['events']['step link end_session'],1)
        self.assertEqual(sc['stalls'],[{'wall':'11-16 18:28:02.000','channel_switch_within_60s':[('11-16 18:28:59.000',5785)]}])
        self.assertEqual(report['wifi']['channels'],{5785:1})
        result=subprocess.run([sys.executable,'-I',str(ROOT/'tools/analyze-ar-log.py'),str(self.dir)],capture_output=True,text=True)
        self.assertIn("picture recovered (wall, after ms, step): [('11-16 18:28:08.500', 6500, 2), ('11-16 18:28:30.000', 30000, 'link')]",result.stdout)
        self.assertIn("hotspot kernel events: {'ch_switch': 1, 'deauth': 1}",result.stdout)
    def test_android_auto_process(self):
        rec=lambda t,seq,msg:record(t,seq,msg,pid=1080,tid=1500).replace('v107','v114')
        lines=[rec(1000,1,'auto_turn event=4 side=2 angle=0 number=0'),rec(1001,2,'auto_distance meters=600 e3=1970000 unit=6'),
               rec(1002,3,'auto_input icon=67 distance=1970.0 unit=3 bar=0 enabled=true advanced=true'),rec(1003,4,'auto_passed icon=67 distance=1970.0 unit=3'),
               rec(2001,5,'auto_distance meters=180 e3=590000 unit=6'),rec(2003,6,'auto_sent type=13 dir=90 meters=180.0 exact=true bar=30 force=true'),
               rec(2250,7,'auto_sent type=13 dir=90 meters=176.0 exact=true bar=30 force=false'),rec(9001,8,'auto_distance meters=160 e3=0 unit=6'),
               rec(9500,9,'auto_turn event=8 side=2 angle=0 number=0'),rec(30000,10,'auto_expired input_age=310000 passed=true'),
               rec(31000,11,'auto_status status=2'),rec(31001,12,'auto_stopped type=17'),
               '11-16 18:28:02.000  1080  1500 W EvergreenAR: aaturn event=4 side=2 angle=0 number=0 icon=67 exact=true meters=180 result=native output=13 dir=90 bar=30 unit=3']
        with (self.dir/'flow.log').open('a') as f:f.write('\n'.join(lines)+'\n')
        events,turns=tool.load(self.dir);report=tool.analyze(events,turns)
        a=report['processes']['1080']['android_auto'];self.assertEqual(report['processes']['1080']['role'],'android_auto')
        self.assertEqual(a['maneuvers'],{'turn right':1,'off_ramp right':1});self.assertEqual(a['native_sent'],{'13':1});self.assertEqual(a['glide_sends'],1)
        self.assertEqual(a['gpm_frames_passed'],{'67':1});self.assertEqual(a['distance_updates'],3);self.assertEqual(a['distance_silences_over_5s'],[7000])
        self.assertEqual(a['expiries'],[{'wall':'11-16 18:28:30.000','input_age':310000,'passed':True}]);self.assertEqual(a['stops'],1)
        self.assertEqual(report['android_auto_records'],{'turn right icon=67 result=native output=13 dir=90':1})
        self.assertNotIn('event=4',' '.join(report['turn_records']))
        result=subprocess.run([sys.executable,'-I',str(ROOT/'tools/analyze-ar-log.py'),str(self.dir)],capture_output=True,text=True)
        self.assertIn("maneuvers from Android Auto: {'turn right': 1, 'off_ramp right': 1}",result.stdout)
    def test_cli(self):
        result=subprocess.run([sys.executable,'-I',str(ROOT/'tools/analyze-ar-log.py'),str(self.dir),'--json'],capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stderr);self.assertEqual(json.loads(result.stdout)['processes']['1060']['records'],27)
        self.assertNotEqual(subprocess.run([sys.executable,'-I',str(ROOT/'tools/analyze-ar-log.py'),str(self.dir/'missing')],capture_output=True).returncode,0)
if __name__=='__main__':unittest.main()
