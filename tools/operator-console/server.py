#!/usr/bin/env python3
"""Local-only operator console. Vehicle writes are restricted to a running Android emulator."""
import argparse, http.server, json, pathlib, secrets, subprocess, threading, time, urllib.parse, urllib.request

parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=8790);parser.add_argument('--serial',default='emulator-5554');parser.add_argument('--output',type=pathlib.Path,default=pathlib.Path('output/operator-console'));args=parser.parse_args()
ADB=pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb'; PKG='io.github.aitm.pleosapp'
args.output.mkdir(parents=True,exist_ok=True)
def adb(*parts):return subprocess.check_output([str(ADB),'-s',args.serial,*map(str,parts)],timeout=12)
if not args.serial.startswith('emulator-') or adb('shell','getprop','ro.hardware').strip() not in (b'ranchu',b'goldfish'):raise SystemExit('Only ranchu/goldfish emulator targets are allowed')
adb('forward','tcp:8088','tcp:8088')
TOKEN=secrets.token_urlsafe(24); lock=threading.Lock(); stopped=threading.Event()
cache={'vehicle':{},'app':{},'story':{'stage':'ready'},'event':{'title':'상황을 선택하세요','detail':'차량 신호와 실제 앱 화면을 함께 확인합니다.'},'events':[]}; frame=b''; last_app_change=0; last_app_heartbeat=None
def console(path,params=None,method=None):
 url='http://127.0.0.1:8088'+path
 if params is not None:url+='?'+urllib.parse.urlencode(params)
 return json.load(urllib.request.urlopen(urllib.request.Request(url,method=method or ('GET' if params is None else 'PUT')),timeout=5))
def operator(command,**extras):
 if time.monotonic()-last_app_change>4 or not cache['app'].get('visible'):raise ValueError('세이프티스탭 앱을 먼저 열어주세요. 앱 연결을 확인할 수 없습니다.')
 parts=['shell','am','broadcast','--user','10','-a',PKG+'.OPERATOR','-p',PKG,'--es','command',command]
 # adb shell reparses arguments; quote every supplied word, including Korean phrases.
 import shlex
 parts += sum((['--es',k,shlex.quote(str(v))] for k,v in extras.items()),[])
 return adb(*parts).decode()
def event(title,detail):
 row={'time':time.strftime('%H:%M:%S'),'title':title,'detail':detail}
 if title in ['후방 충돌','전방 충돌','주행 중 차량 고장','사고 후 정차','상황 확인 필요','접근 차량','앱 초기화']:cache['scenario']=title
 cache['event']=row;cache['events']=(cache['events']+[row])[-12:]
 with (args.output/'events.jsonl').open('a') as f:f.write(json.dumps(row,ensure_ascii=False)+'\n')
def pump():
 global frame, last_app_change, last_app_heartbeat
 tick=0
 while not stopped.is_set():
  try:
   if tick%3==0:
    console('/browser/session',{'client':'safetystep-operator'},'POST')
    cache['vehicle']=console('/vehicle/state')
   # Refresh the app facts on every captured frame. Updating them only every
   # third frame left occupant captions behind the native seat display.
   try:
    cache['app']=json.loads(adb('exec-out','run-as',PKG,'--user','10','cat','files/operator-state.json'))
    heartbeat=cache['app'].get('heartbeat')
    if heartbeat!=last_app_heartbeat:last_app_change=time.monotonic();last_app_heartbeat=heartbeat
   except Exception:cache['app']={'screen':'연결 중'}
   cache['appConnected']=time.monotonic()-last_app_change<=4 and cache['app'].get('visible',False)
   frame=adb('exec-out','screencap','-p')
  except Exception as e:cache['error']=str(e)
  tick+=1;stopped.wait(.3)
def action(data):
 with lock:
  command=data.get('command')
  if command=='scenario':
   if not cache.get('appConnected'):raise ValueError('세이프티스탭 앱을 먼저 열어주세요.')
   collision=data.get('collision');assert collision in ['REAR','FRONT','BREAKDOWN','OTHER','UNKNOWN']
   console('/vehicle/controls',{'speed':0,'accelerator':0,'brake':0});console('/vehicle/gear',{'gear':'Park'})
   console('/vehicle/body',{'door_fl':0,'trunk':0,'frunk':0});console('/vehicle/seat',{'kind':'occupied','area':'driver','value':'true'})
   operator('scenario',collision=collision,rearClear=str(data.get('rearClear') is True).lower(),voice=data.get('voice') if data.get('voice') in ['fixture','off'] else 'live')
   event({'REAR':'후방 충돌','FRONT':'전방 충돌','BREAKDOWN':'주행 중 차량 고장','OTHER':'사고 후 정차','UNKNOWN':'상황 확인 필요'}[collision], '사고 이벤트 주입 → P·정지 / 운전석 탑승 / 응답 확인 시작')
  elif command=='story_stage':
   stage=data.get('stage');assert stage in ['ready','driving','impact','response','mobility','prepare','departure','safe']
   cache['story']={'stage':stage,'collision':'FRONT' if data.get('collision')=='FRONT' else 'REAR','changedAt':time.time()}
   event('사고 차량 흐름',stage)
  elif command=='consent':
   assert type(data.get('value')) is bool
   operator('consent',value=str(data['value']).lower())
   event('가상 위치 공유',str(data['value'])+' · 로컬 시험 도로만 사용')
  elif command=='transcript':
   text=str(data.get('text',''))[:200];assert text
   operator('transcript',text=text);event('응답 문장 입력',text+' · 운영자 입력, 마이크 인식과 구분')
  elif command=='voice':operator('voice');event('음성 연결','Pleos TTS → 실제 마이크 STT 요청')
  elif command=='pcm':operator('pcm');event('합성 음성 입력','Pleos TTS → 테스트 PCM → 실제 STT 인식. 마이크 시험 제외')
  elif command=='receiver':operator('receiver');event('접근 차량','샘플 전방 위험 사건 · 같은 방향 400m')
  elif command=='reset':operator('reset');event('앱 초기화','기존 도로 사건 종료와 별개')
  elif command=='theme':
   value=data.get('value');assert value in ['LIGHT','DARK','SYSTEM'];operator('theme',value=value);event('화면 테마',value)
  elif command=='signal':
   key=data.get('key');value=data.get('value')
   if key in ['door_fl','trunk']:
    assert value in [0,1];console('/vehicle/body',{key:value})
   elif key=='occupied':
    assert value in [0,1];console('/vehicle/seat',{'kind':'occupied','area':'driver','value':str(bool(value)).lower()})
   elif key=='gear':
    assert value in ['Park','Drive'];console('/vehicle/gear',{'gear':value})
   elif key=='speed':
    assert type(value)==int and 0<=value<=120;console('/vehicle/controls',{'speed':value,'accelerator':0,'brake':0})
   elif key=='hazard':
    assert type(value) is bool;console('/vehicle/lights',{'feature':'hazard','value':str(value).lower()})
   else:raise ValueError('Unsupported signal')
   event('차량 신호 변경',f'{key} → {value} · Virtual Console → 차량 서비스')
  elif command=='tap':
   x,y=int(data['x']),int(data['y']);assert 0<=x<2560 and 0<=y<1440
   adb('shell','input','tap',x,y);event('차량 화면 터치',f'{x}, {y}')
  else:raise ValueError('Unsupported action')
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def send(self,body,kind='application/json',status=200):
  self.send_response(status);self.send_header('Content-Type',kind);self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
 def do_GET(self):
  path=urllib.parse.urlsplit(self.path).path
  if path in ['/recording','/story']:
   filename='story.html' if path=='/story' else 'recording.html'
   self.send(pathlib.Path(__file__).with_name(filename).read_text().replace('__TOKEN__',TOKEN).encode(),'text/html; charset=utf-8')
  elif path=='/':self.send(pathlib.Path(__file__).with_name('index.html').read_text().replace('__TOKEN__',TOKEN).encode(),'text/html; charset=utf-8')
  elif path=='/state':self.send(json.dumps(cache,ensure_ascii=False).encode())
  elif path=='/screen.png':self.send(frame,'image/png')
  else:self.send(b'{}',status=404)
 def do_POST(self):
  try:
   assert self.path=='/action'
   assert self.headers.get('X-Console-Token')==TOKEN
   origin=self.headers.get('Origin');assert origin in [None,f'http://127.0.0.1:{args.port}',f'http://localhost:{args.port}']
   length=int(self.headers.get('Content-Length','0'));assert 0<length<=4096
   action(json.loads(self.rfile.read(length)));self.send(b'{"ok":true}')
  except Exception as e:self.send(json.dumps({'error':str(e)}).encode(),status=400)
threading.Thread(target=pump,daemon=True).start()
print(f'Operator console: http://127.0.0.1:{args.port}',flush=True)
try:http.server.ThreadingHTTPServer(('127.0.0.1',args.port),Handler).serve_forever()
finally:
 stopped.set()
 try:console('/browser/session',{'client':'safetystep-operator','closing':1},'POST')
 except Exception:pass
