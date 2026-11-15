#!/usr/bin/env python3
"""Actual-emulator hazard readback, command count, restart and failure checks (no UI taps)."""
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
mode('EMULATOR_CONSOLE');op(command='reset');op(command='signal',key='hazard',value=False)
wait(lambda s:s.get('hazardLights') is False)
op(command='scenario',collision='REAR',voice='off')
s=wait(lambda s:s.get('hazardAction')=='CONFIRMED' and s.get('hazardLights') is True)
assert s['hazardCommands']==1 and s['trunkAttempts']==0 and s['trunk']=='HELD';incident=s['incidentId'];save('01-automatic-on',s)
samples=[]
for _ in range(8):
 time.sleep(.7);s=state();assert s['hazardLights'] is True and s['hazardCommands']==1;samples.append(s)
(out/'blink-observations.json').write_text(json.dumps(samples,ensure_ascii=False,indent=2))
op(command='theme',value='LIGHT');op(command='theme',value='DARK')
previous=state()['heartbeat'];shell('shell','am','force-stop','--user','10',pkg);shell('shell','am','start','--user','10','-n',pkg+'/.MainActivity')
s=wait(lambda s:s.get('visible') and s.get('heartbeat',0)>previous+2000 and s.get('hazardLights') is True)
assert s['incidentId']==incident and s['hazardCommands']==1;save('02-restart-no-repeat',s)
op(command='signal',key='hazard',value=False);wait(lambda s:s.get('hazardLights') is False);time.sleep(3)
s=state();assert s['hazardLights'] is False and s['hazardCommands']==1;save('03-external-off-no-retry',s)
op(command='signal',key='hazard',value=True);wait(lambda s:s.get('hazardLights') is True);op(command='scenario',collision='REAR',voice='off')
s=wait(lambda s:s.get('incidentId')!=incident and s.get('hazardAction')=='CONFIRMED');assert s['hazardCommands']==0;save('04-already-on-no-command',s)
mode('VEHICLE_SERVICE');op(command='signal',key='hazard',value=False);wait(lambda s:s.get('hazardLights') is False);op(command='scenario',collision='REAR',voice='off')
s=wait(lambda s:s.get('hazardAction')=='UNCONFIRMED');assert s['hazardLights'] is False and s['hazardCommands']==1 and '800001' in s['hazardDetail'];save('05-sdk-denied-no-false-completion',s)
op(command='transcript',text='저는 운전자예요. 움직일 수 없어요. 도와주세요.')
s=wait(lambda s:s.get('screen')=='HELP');assert s['hazard']=='ACTIVE' and s['trunkAttempts']==0;save('06-help-survives-control-error',s)
mode('EMULATOR_CONSOLE');op(command='reset')
print('PASS AUTOMATIC CONTROL: readback, blinking, already-on, restart, failure, help continuity',flush=True)
