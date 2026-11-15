#!/usr/bin/env python3
"""Actual SDK front-collision automatic trunk checks (no UI taps)."""
import argparse,json,pathlib,re,subprocess,time,urllib.request
p=argparse.ArgumentParser();p.add_argument('--output',type=pathlib.Path,required=True);args=p.parse_args();out=args.output;out.mkdir(parents=True,exist_ok=True)
adb=str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb');pkg='io.github.aitm.pleosapp';base='http://127.0.0.1:8790'
token=re.search("const token='([^']+)'",urllib.request.urlopen(base).read().decode())[1]
def shell(*v):return subprocess.check_output([adb,'-s','emulator-5554',*map(str,v)])
assert shell('shell','getprop','ro.hardware').strip() in (b'ranchu',b'goldfish')
def state():return json.load(urllib.request.urlopen(base+'/state'))['app']
def wait(predicate,timeout=18):
 deadline=time.monotonic()+timeout
 while time.monotonic()<deadline:
  s=state()
  if predicate(s):return s
  time.sleep(.4)
 raise AssertionError(s)
def op(**data):
 wait(lambda s:s.get('visible') and not s.get('restricted'))
 req=urllib.request.Request(base+'/action',data=json.dumps(data).encode(),headers={'Content-Type':'application/json','X-Console-Token':token})
 return json.load(urllib.request.urlopen(req))
def mode(value):
 f=out/'hazard-control-mode.txt';f.write_text(value);shell('push',f,'/data/local/tmp/hazard-control-mode.txt');shell('shell','run-as',pkg,'--user','10','cp','/data/local/tmp/hazard-control-mode.txt','files/hazard-control-mode.txt')
 wait(lambda s:s.get('hazardControlMode')==value)
def save(name,s=None):
 (out/(name+'.json')).write_text(json.dumps(s or state(),ensure_ascii=False,indent=2))
 (out/(name+'.png')).write_bytes(shell('exec-out','screencap','-p'))
 print('PASS',name,flush=True)
mode('EMULATOR_CONSOLE');op(command='reset');op(command='signal',key='trunk',value=0)
wait(lambda s:s.get('trunkOpen') is False)
op(command='scenario',collision='FRONT',rearClear=False,voice='off')
wait(lambda s:s.get('collision')=='FRONT' and s.get('screen')=='RESPONSE')
op(command='transcript',text='저는 운전자예요. 움직일 수 있어요.')
wait(lambda s:s.get('screen')=='EVACUATION');time.sleep(3)
s=state();assert s['trunkAttempts']==0 and not s['trunkOpen'] and not s['autoTrunkClaimed'];save('01-front-unknown-clearance-held',s)
op(command='scenario',collision='FRONT',rearClear=True,voice='off')
wait(lambda s:s.get('screen')=='RESPONSE')
op(command='transcript',text='저는 운전자예요. 움직일 수 없어요. 도와주세요.')
wait(lambda s:s.get('screen')=='HELP');time.sleep(3)
s=state();assert s['trunkAttempts']==0 and not s['trunkOpen'];save('02-help-held',s)
op(command='scenario',collision='FRONT',rearClear=True,voice='off')
wait(lambda s:s.get('screen')=='RESPONSE')
op(command='transcript',text='저는 운전자예요. 움직일 수 있어요.')
s=wait(lambda s:s.get('trunk')=='CONFIRMED' and s.get('trunkOpen') is True,30)
assert s['trunkAttempts']==1 and s['autoTrunkClaimed'] and not s['sample'];incident=s['incidentId'];save('03-front-automatic-sdk-open',s)
op(command='theme',value='LIGHT');time.sleep(1);save('04-front-open-light');op(command='theme',value='DARK')
previous=state()['heartbeat'];shell('shell','am','force-stop','--user','10',pkg);shell('shell','am','start','--user','10','-n',pkg+'/.MainActivity')
s=wait(lambda s:s.get('visible') and s.get('heartbeat',0)>previous+2000 and s.get('trunkOpen') is True)
assert s['incidentId']==incident and s['trunkAttempts']==1 and s['autoTrunkClaimed'] and not s['rearClearConfirmed'];save('05-restart-no-duplicate',s)
op(command='signal',key='trunk',value=0);wait(lambda s:s.get('trunkOpen') is False);time.sleep(3)
s=state();assert s['trunkAttempts']==1 and not s['trunkOpen'];save('06-closed-not-reopened',s)
op(command='scenario',collision='REAR',rearClear=True,voice='off');wait(lambda s:s.get('screen')=='RESPONSE')
op(command='transcript',text='저는 운전자예요. 움직일 수 있어요.');wait(lambda s:s.get('screen')=='EVACUATION');time.sleep(3)
s=state();assert s['trunk']=='HELD' and s['trunkAttempts']==0 and not s['trunkOpen'];save('07-rear-still-held',s)
op(command='reset');print('PASS FRONT: actual SDK open, all conditions, idempotency, rear hold',flush=True)
