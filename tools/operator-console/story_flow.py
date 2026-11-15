#!/usr/bin/env python3
"""Rear/front collision stories. Prepare before capture; receiver verification stays off camera."""
import argparse,json,pathlib,re,subprocess,time,urllib.parse,urllib.request,xml.etree.ElementTree as ET

parser=argparse.ArgumentParser()
parser.add_argument('mode',choices=['prepare','play','receiver','cabin','restore'])
parser.add_argument('--collision',choices=['REAR','FRONT'],default='REAR')
parser.add_argument('--output',type=pathlib.Path,required=True)
args=parser.parse_args();out=args.output;out.mkdir(parents=True,exist_ok=True)
root=pathlib.Path(__file__).resolve().parents[2]
adb=str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb')
A='io.github.aitm.pleosapp';B=A+'.demo';base='http://127.0.0.1:8790'
token=re.search("const token='([^']+)'",urllib.request.urlopen(base).read().decode())[1]
started=time.monotonic();timeline=[]
def shell(*parts):return subprocess.check_output([adb,'-s','emulator-5554',*map(str,parts)])
assert shell('shell','getprop','ro.hardware').strip() in (b'ranchu',b'goldfish')
def op(**data):
 request=urllib.request.Request(base+'/action',data=json.dumps(data).encode(),headers={'Content-Type':'application/json','X-Console-Token':token})
 return json.load(urllib.request.urlopen(request,timeout=20))
def console(path,**data):
 return json.load(urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8088'+path+'?'+urllib.parse.urlencode(data),method='PUT'),timeout=8))
def state(package=A):
 if package==A:return json.load(urllib.request.urlopen(base+'/state',timeout=5))['app']
 return json.loads(shell('exec-out','run-as',package,'--user','10','cat','files/operator-state.json'))
def wait(predicate,timeout=18,package=A):
 deadline=time.monotonic()+timeout;latest={}
 while time.monotonic()<deadline:
  latest=state(package)
  timeline.append({'elapsed':round(time.monotonic()-started,2),**latest})
  if predicate(latest):return latest
  time.sleep(.3)
 raise AssertionError(latest)
def stage(value):
 op(command='story_stage',stage=value,collision=args.collision)
 print(round(time.monotonic()-started,2),value,flush=True)
def proof(name,package=A):
 data=state(package);(out/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2))
 (out/(name+'.png')).write_bytes(shell('exec-out','screencap','-p'))
 return data
def hit(text):
 # The recording layout reserves the right native pane for SafetyStep. Focus its
 # inert brand header so UIAutomator reads that window, without restarting it.
 shell('shell','input','tap',1870,150)
 shell('shell','uiautomator','dump','/data/local/tmp/safetystep-story.xml')
 nodes=ET.fromstring(shell('exec-out','cat','/data/local/tmp/safetystep-story.xml')).iter('node')
 matches=[n for n in nodes if n.get('text')==text];assert len(matches)==1,(text,len(matches))
 x1,y1,x2,y2=map(int,re.findall(r'\d+',matches[0].get('bounds')))
 op(command='tap',x=(x1+x2)//2,y=(y1+y2)//2)
def broadcast(package,command,**extras):
 parts=['shell','am','broadcast','--user','10','-a',package+'.OPERATOR','-p',package,'--es','command',command]
 for key,value in extras.items():parts+=['--es',key,value]
 return shell(*parts)
def restore():
 console('/vehicle/controls',speed=0,accelerator=0,brake=0)
 console('/vehicle/gear',gear='Park')
 console('/vehicle/body',door_fl=0,door_fr=0,door_rl=0,door_rr=0,trunk=0,frunk=0)
 for area in ['driver','passenger','rear_left','rear_right']:
  console('/vehicle/seat',kind='occupied',area=area,value='false')
 console('/vehicle/lights',feature='hazard',value='false')
try:
 if args.mode=='prepare':
  restore();shell('reverse','tcp:8787','tcp:8787')
  mode=out/'hazard-control-mode.txt';mode.write_text('EMULATOR_CONSOLE')
  shell('push',mode,'/data/local/tmp/hazard-control-mode.txt')
  shell('shell','run-as',A,'--user','10','cp','/data/local/tmp/hazard-control-mode.txt','files/hazard-control-mode.txt')
  for name in ['driver','mobility']:
   source=root/'android/app/build/speech-validation/story'/f'{name}.pcm'
   assert source.exists(),source
   shell('push',source,'/data/local/tmp/story-'+name+'.pcm')
   shell('shell','run-as',A,'--user','10','cp','/data/local/tmp/story-'+name+'.pcm','files/story-'+name+'.pcm')
  wait(lambda s:s.get('visible') and not s.get('restricted'))
  op(command='reset');op(command='theme',value='DARK');op(command='consent',value=True)
  console('/vehicle/ev-battery',field='charge_port_open',value='false')
  console('/vehicle/seat',kind='occupied',area='driver',value='true')
  console('/vehicle/seat',kind='occupied',area='passenger',value='true')
  stage('driving');wait(lambda s:s.get('incidentId') is None and s.get('screen')=='DRIVE')
  op(command='signal',key='gear',value='Drive');op(command='signal',key='speed',value=40)
  s=wait(lambda s:s.get('restricted') and s.get('incidentId') is None)
  assert not s['warningIds'];proof('00-prepared')
 elif args.mode=='play':
  s=state();assert s.get('incidentId') is None and s.get('screen')=='DRIVING',s
  stage('driving');op(command='signal',key='speed',value=40);proof('01-before-accident')
  # Hold the external road-speed input during the opening shot; no app interaction.
  driving_until=time.monotonic()+6
  while time.monotonic()<driving_until:
   console('/vehicle/controls',speed=40,accelerator=0,brake=0);time.sleep(.6)
  stage('impact');time.sleep(1.7)
  console('/vehicle/controls',speed=0,accelerator=0,brake=100)
  time.sleep(.6);console('/vehicle/gear',gear='Park');console('/vehicle/controls',speed=0,accelerator=0,brake=0)
  wait(lambda s:not s.get('restricted'));time.sleep(1.5)
  stage('response');op(command='scenario',collision=args.collision,rearClear=args.collision=='FRONT',voice='fixture')
  response=wait(lambda s:s.get('screen')=='RESPONSE' and s.get('incidentId'))
  incident=response['incidentId'];(out/'incident-id.txt').write_text(incident)
  wait(lambda s:s.get('voice')=='SPEAKING');proof('02-question')
  wait(lambda s:s.get('screen')=='MOBILITY',timeout=40);stage('mobility');proof('03-mobility')
  s=wait(lambda s:s.get('screen')=='EVACUATION',timeout=40)
  assert s['help']=='CAN_MOVE' and s['responder']=='DRIVER' and s['voiceInput']=='SYNTHETIC_PCM',s
  assert s['hazardLights'] is True and s['hazardCommands']==1 and s['hazardAction']=='CONFIRMED',s
  if args.collision=='FRONT':
   s=wait(lambda s:s.get('trunk')=='CONFIRMED' and s.get('trunkOpen') is True)
   assert s['trunkAttempts']==1 and s['autoTrunkClaimed'] and s['rearClearConfirmed']
  else:
   assert s['trunk']=='HELD' and s['trunkAttempts']==0 and s['trunkOpen'] is False
  assert s['incidentId']==incident
  stage('prepare');wait(lambda s:s.get('stationary') and s.get('hazardLights') is True);proof('04-evacuation');time.sleep(10)
  # This is a physical signal from the external console, not a manufactured SDK observation.
  op(command='signal',key='door_fl',value=1)
  wait(lambda s:s.get('doorOpen') is True);proof('05-door-observed');time.sleep(3)
  stage('departure');time.sleep(2)
  op(command='signal',key='occupied',value=0)
  s=wait(lambda s:s.get('occupied') is False and s.get('cabinPresence')=='OCCUPIED')
  assert not s['cabinClearConfirmed'] and s['screen']=='SUPPORT';proof('05b-passenger-remains');time.sleep(5)
  console('/vehicle/seat',kind='occupied',area='passenger',value='false')
  op(command='signal',key='door_fl',value=0);wait(lambda s:s.get('doorOpen') is False)
  s=wait(lambda s:s.get('screen')=='MONITORING' and s.get('cabinClearConfirmed'))
  stage('safe');s=wait(lambda s:s.get('pending')==0 and s.get('connected') and s.get('consent'))
  assert s['incidentId']==incident and s['hazard']=='ACTIVE' and s['evacuated'] is False
  assert s['trunkAttempts']==(1 if args.collision=='FRONT' else 0) and s['trunkOpen']==(args.collision=='FRONT')
  proof('06-support-hazard-active');time.sleep(7)
  print('PASS STORY',incident,'app_touches=0','elapsed',round(time.monotonic()-started,2),flush=True)
 elif args.mode=='cabin':
  wait(lambda s:s.get('visible') and not s.get('restricted'))
  restore();op(command='reset')
  console('/vehicle/seat',kind='occupied',area='driver',value='true')
  console('/vehicle/seat',kind='occupied',area='passenger',value='true')
  op(command='scenario',collision='REAR',voice='off')
  wait(lambda s:s.get('screen')=='RESPONSE' and s.get('occupied') is True and len(s.get('seats',[]))>=4)
  op(command='transcript',text='저는 운전자예요. 움직일 수 있어요.')
  wait(lambda s:s.get('screen')=='EVACUATION')
  wait(lambda s:s.get('stationary'));proof('cabin-both-occupied')
  console('/vehicle/seat',kind='occupied',area='driver',value='false')
  s=wait(lambda s:s.get('occupied') is False and s.get('cabinPresence')=='OCCUPIED')
  time.sleep(4);assert state()['screen']=='SUPPORT' and not state()['cabinClearConfirmed']
  proof('cabin-passenger-remains')
  console('/vehicle/seat',kind='occupied',area='passenger',value='false')
  wait(lambda s:s.get('screen')=='MONITORING' and s.get('cabinClearConfirmed'))
  s=proof('cabin-empty-monitoring');assert s['hazard']=='ACTIVE' and not s['evacuated'] and s['trunkAttempts']==0
  shell('shell','uiautomator','dump','/data/local/tmp/safetystep-story.xml')
  xml=shell('exec-out','cat','/data/local/tmp/safetystep-story.xml').decode()
  assert '긴급 전화 열기' not in xml and '도움이 필요해요' not in xml
  op(command='theme',value='LIGHT');time.sleep(2);proof('cabin-empty-light')
  op(command='theme',value='DARK')
  print('PASS CABIN: driver out + passenger stays; all empty -> no-touch monitoring; hazard retained',flush=True)
 elif args.mode=='receiver':
  incident=(out/'incident-id.txt').read_text().strip()
  req=urllib.request.Request('http://127.0.0.1:8787/prototype/incidents',headers={'Authorization':'Bearer prototype-vehicle-B'})
  event=next(e for e in json.load(urllib.request.urlopen(req))['events'] if e['id']==incident)
  assert event['owner']=='A' and event['status']=='ACTIVE' and event['source']=='OPERATOR_EVENT'
  assert not any(k in event for k in ['help','response','responder','voiceText','evacuated'])
  (out/'receiver-server-event.json').write_text(json.dumps(event,ensure_ascii=False,indent=2))
  previous=state(B).get('heartbeat',-1)
  shell('shell','am','start','--user','10','-n',B+'/'+A+'.MainActivity')
  wait(lambda s:s.get('visible') and s.get('heartbeat',0)>previous+500,package=B)
  broadcast(B,'reset');wait(lambda s:s.get('incidentId') is None,package=B)
  broadcast(B,'receiver_actual')
  received=wait(lambda s:s.get('screen')=='RECEIVER' and incident in s.get('warningIds',[]) and s.get('connected'),package=B)
  time.sleep(.7)
  assert received['owner']=='B';proof('07-receiver-same-incident',B)
  for mode,decision in [('opposite','DIFFERENT_DIRECTION'),('parallel','DIFFERENT_ROAD'),('passed','PASSED'),('stale','INVALID_POSITION')]:
   broadcast(B,'receiver_scenario',value=mode)
   wait(lambda s:incident not in s.get('warningIds',[]) and any(incident[:8] in row and row.endswith(decision) for row in s.get('received',[])),package=B)
  broadcast(B,'receiver_scenario',value='approach')
  wait(lambda s:incident in s.get('warningIds',[]),package=B)
  print('PASS RECEIVER SAME INCIDENT',incident,'+ exclusions',flush=True)
  shell('shell','am','force-stop','--user','10',B)
  shell('shell','am','start','--user','10','-n',A+'/.MainActivity')
 elif args.mode=='restore':
  restore();wait(lambda s:not s.get('restricted') and s.get('doorOpen') is False and s.get('trunkOpen') is False)
  proof('08-restored')
finally:
 (out/(args.mode+'-timeline.json')).write_text(json.dumps(timeline,ensure_ascii=False,indent=2))
 if args.mode in ['prepare','play'] and __import__('sys').exc_info()[0] is not None:
  restore()
