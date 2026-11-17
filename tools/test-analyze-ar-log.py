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
    def test_cli(self):
        result=subprocess.run([sys.executable,'-I',str(ROOT/'tools/analyze-ar-log.py'),str(self.dir),'--json'],capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stderr);self.assertEqual(json.loads(result.stdout)['processes']['1060']['records'],27)
        self.assertNotEqual(subprocess.run([sys.executable,'-I',str(ROOT/'tools/analyze-ar-log.py'),str(self.dir/'missing')],capture_output=True).returncode,0)
if __name__=='__main__':unittest.main()
