#!/usr/bin/env python3
import urllib.request,json,time,re,subprocess,pathlib,xml.etree.ElementTree as ET
import argparse
parser=argparse.ArgumentParser(description='Replay a local emulator verification flow; mutates test vehicle signals.')
parser.add_argument('--output',type=pathlib.Path,default=pathlib.Path('output/operator-console/verification'))
parser.add_argument('--port',type=int,default=8790)
args=parser.parse_args();base=f'http://127.0.0.1:{args.port}';adb=str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb');out=args.output;out.mkdir(parents=True,exist_ok=True)
token=re.search("const token='([^']+)'",urllib.request.urlopen(base).read().decode())[1]
def op(**d):
 json.load(urllib.request.urlopen(urllib.request.Request(base+'/action',data=json.dumps(d).encode(),headers={'Content-Type':'application/json','X-Console-Token':token})))
def state():return json.load(urllib.request.urlopen(base+'/state'))['app']
def waitfor(key,value,seconds=12):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  a=state()
  if a.get(key)==value:return a
  time.sleep(.4)
 raise AssertionError((key,value,a))
def hit(text):
 subprocess.run([adb,'-s','emulator-5554','shell','uiautomator','dump','/data/local/tmp/safetystep-ui.xml'],stdout=subprocess.DEVNULL,check=True)
 data=subprocess.check_output([adb,'-s','emulator-5554','exec-out','cat','/data/local/tmp/safetystep-ui.xml'])
 nodes=[n for n in ET.fromstring(data).iter('node') if n.get('text')==text]
 if not nodes:raise AssertionError('Missing '+text)
 r=list(map(int,re.findall(r'\d+',nodes[0].get('bounds'))));op(command='tap',x=(r[0]+r[2])//2,y=(r[1]+r[3])//2);time.sleep(.4)
def proof(name):
 a=state();(out/(name+'.json')).write_text(json.dumps(a,ensure_ascii=False,indent=2));(out/(name+'.png')).write_bytes(subprocess.check_output([adb,'-s','emulator-5554','exec-out','screencap','-p']));print(name,a['screen'],a['trunk'],flush=True)
op(command='scenario',collision='BREAKDOWN');waitfor('screen','RESPONSE');time.sleep(2)
op(command='transcript',text='저는 운전자예요. 움직일 수 있어요.');waitfor('screen','EVACUATION')
hit('차량이 완전히 멈췄어요');hit('안전한 이동 경로를 확인했어요');hit('열기 요청');hit('조건 확인 · 열기 요청');waitfor('trunk','CONFIRMED');proof('trunk-confirmed');time.sleep(2)
op(command='signal',key='door_fl',value=1);waitfor('doorOpen',True);proof('door-open');time.sleep(2)
op(command='signal',key='door_fl',value=0);waitfor('doorOpen',False)
hit('안전한 곳으로 이동할게요');waitfor('screen','SUPPORT');proof('support-hazard-retained');time.sleep(2)
op(command='theme',value='LIGHT');time.sleep(2)
op(command='scenario',collision='REAR');waitfor('screen','RESPONSE');op(command='transcript',text='저는 운전자예요. 움직일 수 있어요.');waitfor('screen','EVACUATION');hit('차량이 완전히 멈췄어요');hit('안전한 이동 경로를 확인했어요');a=waitfor('trunk','HELD');assert a['trunkAttempts']==0 and a['trunkOpen']==False;proof('rear-held');time.sleep(3)
op(command='receiver');op(command='theme',value='DARK');op(command='signal',key='gear',value='Drive');op(command='signal',key='speed',value=20);waitfor('restricted',True);proof('recorded-driving');time.sleep(3)
op(command='signal',key='speed',value=0);op(command='signal',key='gear',value='Park')
print('PASS',flush=True)
