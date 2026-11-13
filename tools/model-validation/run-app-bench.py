#!/usr/bin/env python3
"""Benchmark official candidates through Anchor's packaged JNI instrumentation test.
Requires the source project's debug APK/test APK installed on an authorized USB device.
Default prints a plan; --run stages a verified model, runs tests and saves raw results.
It never reads calendar/chat, changes network settings, or replaces the imported model.
"""
import argparse,hashlib,json,statistics,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parent

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--model-dir',type=Path,required=True);p.add_argument('--candidate',action='append')
 p.add_argument('--serial');p.add_argument('--output',type=Path,default=ROOT/'app-runs-new')
 p.add_argument('--run',action='store_true');args=p.parse_args()
 manifest=json.loads((ROOT/'candidates.json').read_text())
 selected=[c for c in manifest['candidates'] if not args.candidate or c['id'] in args.candidate]
 selected.sort(key=lambda c:c['bytes'])
 if not selected:p.error('No matching candidates')
 if not args.run:
  print(json.dumps({'action':'dry_run','models':[{'id':c['id'],'path':str(args.model_dir/c['filename'])} for c in selected],'scope':'three 128-token chat runs, one planning example; 1024 context / 2 CPU threads'},indent=2));return
 if not args.serial:p.error('--run requires an explicit --serial')
 args.output.mkdir(parents=True,exist_ok=True)
 base=['adb','-s',args.serial]
 def adb(*cmd,**kw):return subprocess.run(base+list(cmd),capture_output=True,check=True,**kw)
 def stage(path,name):
  adb('push',str(path),'/data/local/tmp/anchor-benchmark-transfer')
  adb('shell','run-as','dev.anchor.app','cp','/data/local/tmp/anchor-benchmark-transfer','files/'+name)
  adb('shell','rm','-f','/data/local/tmp/anchor-benchmark-transfer')
 for c in selected:
  model=args.model_dir/c['filename']
  with model.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
  if model.stat().st_size!=c['bytes'] or digest!=c['sha256']:raise ValueError('Candidate checksum/size mismatch: '+c['id'])
  print('Staging '+c['id'],flush=True)
  try:
   stage(model,'candidate-benchmark.gguf')
   actual=adb('shell','run-as','dev.anchor.app','sha256sum','files/candidate-benchmark.gguf').stdout.decode().split()[0]
   if actual!=digest:raise ValueError('Device transfer checksum mismatch')
   cfg=args.output/'staging-config.json';cfg.write_text(json.dumps({'candidate':c['id'],'sha256':digest}))
   stage(cfg,'candidate-benchmark-config.json');cfg.unlink()
   adb('shell','run-as','dev.anchor.app','rm','-f','files/candidate-benchmark-result.json')
   test=adb('shell','am','instrument','-w','-e','class','dev.anchor.app.CandidateBenchmarkDeviceTest','dev.anchor.app.test/android.test.InstrumentationTestRunner',timeout=1200)
   (args.output/(c['id']+'.instrumentation.txt')).write_bytes(test.stdout+test.stderr)
   result=adb('exec-out','run-as','dev.anchor.app','cat','files/candidate-benchmark-result.json').stdout
   (args.output/(c['id']+'.json')).write_bytes(result);data=json.loads(result)
   print(c['id'],{'error':data['error'],'median_tps':statistics.median([r['tokens_per_second_after_first_token'] for r in data['runs']]) if data['runs'] else None},flush=True)
  finally:
   adb('shell','run-as','dev.anchor.app','rm','-f','files/candidate-benchmark.gguf','files/candidate-benchmark-config.json','files/candidate-benchmark-result.json')
   adb('shell','rm','-f','/data/local/tmp/anchor-benchmark-transfer')
  time.sleep(20)
if __name__=='__main__':main()
