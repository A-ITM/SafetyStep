# 세이프티스탭 차량 연동 검증 화면

> 아래 사고 흐름·자동 제어·촬영 스크립트는 **0.5 당시 절차**다. 0.6의 행동 우선 흐름과 HTTP 계약은 [현재 README](../../README.md) 및 [0.6 계약](../../docs/contracts/prototype-v06.md)을 따른다. 기존 `story_flow.py`를 0.6 합격 검사로 사용하지 않는다.

기존 **Pleos Connect Virtual Console**은 에뮬레이터의 8088 포트에서 제공된다. 이 도구는 그 콘솔을 대체하지 않는다. `/recording`은 기존 콘솔을 iframe으로 그대로 표시하고, 실제 ADB 화면을 옆에 배치한다. 사고 유형과 응답 시험 입력만 앱 보조 입력으로 구분한다. `/`는 이번 작업에서 추가한 간이 운영 화면이다.

## 실행

에뮬레이터의 사용자 10에서 Pleos debug APK를 열고 실행한다.

```sh
python3 tools/operator-console/server.py --output output/operator-console
```

- 기존 콘솔: `http://127.0.0.1:8088/`
- 기존 콘솔 + 실제 차량 화면: `http://127.0.0.1:8790/recording`
- 간이 운영 화면: `http://127.0.0.1:8790/`

서버는 loopback에만 바인딩하며 `emulator-*` / `ranchu`, `goldfish` 대상만 허용한다. SDK 제어도 앱의 debug 에뮬레이터 제한을 유지한다. 외부 서버나 ChatGPT 저장소로 전송하지 않는다. 앱이 종료되거나 화면에서 사라지면 보조 입력은 연결 오류를 표시한다. 서버 재시작 후 페이지도 새로고침한다.

## 입력과 관측

- 사고 상황: 앱에 명시적 시험 이벤트를 주입한다. 실제 충돌, 에어백 전개 감지, Gleo 자동 호출을 입증하지 않는다. 이 프리셋은 P·0km/h, 운전석 탑승, 문·트렁크·프렁크 닫힘도 설정한다.
- 실제 차량 신호: 왼쪽 기존 콘솔의 Emulation Controls / Body / Gear 등을 조작한다. 실제 SDK 관측과 기본 차량 그림 변화를 오른쪽에서 확인한다.
- 이동 가능 문장: 운영자 텍스트 입력이다. STT 인식으로 표시하지 않는다.
- 합성 음성: `files/response-test.pcm`의 한국어 16kHz mono PCM을 실제 Pleos STT SDK에 전달한다. SDK 인식과 분기는 검증하지만 노트북 마이크 시험은 아니다. PCM 파일이 없으면 먼저 준비해야 한다.
- 실제 음성 연결: 간이 운영 화면 `/`에서 제공한다. 노트북 마이크 연결과 OS 권한은 별도 확인한다.
- 실제 화면: 2560×1440 스크린샷을 계속 갱신한다. 누르면 해당 위치를 에뮬레이터에 전달한다. 영상은 이 페이지가 열린 브라우저 창을 녹화한다. 부드러운 원본 화면 녹화보다 프레임 갱신 빈도가 낮다.

## 현재 본편 촬영 · 0.5

본편 주소는 `http://127.0.0.1:8790/story`다. 후방 충돌과 조건부 전방 충돌을 각각 사고 전 주행 → 두 차례 음성 응답 → 후방 개방 보류 또는 전방 조건부 자동 개방 → 문 열림 → 운전자 하차·동승자 잔류 → 전 좌석 비움 후 조작 없는 위험 알림 유지로 이어간다. 기존 콘솔을 그대로 함께 표시하며 접근 차량 장면은 본편에 넣지 않는다.

`story_flow.py prepare --output <로컬 경로>`로 촬영 전에 초기화한다. 앱은 우측 패널, 기본 내비는 가운데에 둔다. `play`는 본편을 재현하며, 촬영이 끝난 뒤 `receiver`로 같은 사건 ID를 차량 B가 받는지 확인한다. `cabin`은 동승자 잔류·전 좌석 비움·하차 후 버튼 제거·다크/라이트를 별도 검수하고, `restore`는 P·정지·문/트렁크 닫힘으로 복구한다. 모두 `--output`이 필요하다.

응답 재현은 `files/story-driver.pcm`과 `files/story-mobility.pcm`을 실제 Pleos TTS/STT에 연결한다. 사고 입력은 `OPERATOR_EVENT`, 도로 위치는 로컬 가상 좌표다. 실제 충돌 감지나 Gleo의 자동 앱 기동을 입증하지 않는다.

새 clone에서는 먼저 `python3 tools/operator-console/prepare_speech_fixtures.py`로 시험용 합성 음성 두 개를 생성한다. macOS에 이미 설치된 한국어 Yuna 음성을 사용하며 다른 음성은 `--voice`로 지정한다. 다운로드나 마이크 녹음 없이 `android/app/build/speech-validation/story/`에 16kHz mono WAV와 1.5초 무음 꼬리가 붙은 PCM을 만든다. 기존 파일은 덮어쓰지 않는다. 이 디렉터리는 Git에서 제외된다.

좌석은 SDK capability의 5개 area를 사용한다. SDK 2.3.2 Boolean 변환 문제 때문에 debug 에뮬레이터에서만 동일 권한의 Vehicle Service/AIDL 1.1.1 원시값을 읽는다. 0은 미확인, 1은 비움, 2는 탑승이며 반전이나 콘솔 화면 값 대입을 사용하지 않는다. 모든 area의 최신 비움이 3초 유지되고 탑승 기준 상태가 있었을 때만 MONITORING으로 전환한다. 이것으로 도로 위험을 종료하거나 안전지대 도착을 확정하지 않는다.

## 과거 검사

`verify_flow.py`는 0.4의 수동 체크박스 흐름을 보관한 도구다. 현재 본편이나 최신 검수에 사용하지 않는다.

## 0.5 자동 대응 검수

최신 절차: `docs/safetystep-automatic-plan.md`.
- `verify_automatic.py --output <절대경로>`: 비상등 실제 점멸·중복 방지·SDK 거절·도움 유지.
- `story_flow.py prepare --output <절대경로>` 후 `play`: 사고 전부터, 앱 터치 없이 음성 및 좌석 변화.
- 동일 출력 경로의 `receiver`: 같은 사건 수신 및 반대 방향/다른 도로/통과 제외 검사.
- `cabin`: 동승자 잔류와 전 좌석 비움 검사. `restore`: 외부 차량 신호를 정차/닫힘/빈 좌석으로 복원.

`prepare`는 debug 에뮬레이터의 앱 파일 `hazard-control-mode.txt`에 `EMULATOR_CONSOLE`을 명시 저장한다. 이 모드에서 앱이 에뮬레이터 내부 Virtual Console로 비상등 ON을 요청하며 Vehicle Service로 실제 점멸을 확인한다. 기본값 `VEHICLE_SERVICE`의 직접 제어는 현재 이미지에서 권한 오류 800001로 거절된다. 실패 후 자동 대체하지 않는다. 콘솔과 검수 기록에 두 경로를 구분한다.

일반 운영 콘솔의 사고 버튼은 음성 질문을 자동 시작한다. 자동 시험은 `voice=fixture`(실제 STT에 합성 음성) 또는 `voice=off`(상태 분기 검사)를 명시한다. 사용자의 마이크 권한을 변경하지 않는다. 예전 `verify_flow.py`의 체크박스 단계 시나리오는 0.5에서 사용하지 않는다.

## 전방 충돌과 설명 자막

`story_flow.py prepare --collision FRONT --output <경로>` 후 동일 인수의 `play`로 전방 편을 재현한다. 전방 편은 `rearClear=true`를 명시한 후방 개방 공간 확인 조건이다. 앱이 이동 가능 응답과 실제 정차를 확인한 뒤 실제 Pleos SDK로 트렁크를 1회 연다. `verify_front.py --output <경로>`는 조건 불충족·SDK 열림·재시작 중복 방지·후방 보류를 확인한다.

촬영은 전용 브라우저 창을 선택해 `/story`와 기존 콘솔을 함께 기록한다. 원본과 같은 폴더의 `recording-metadata.json`에 흐름 시작/종료 시각, `flow_exit_code=0`, `capture_window_id`를 남긴다. 앱은 우측, 기본 차량 화면은 좌측, 내비는 중앙으로 유지한다.

`build_video_edit.py <출력 루트> --rear-folder <후방 폴더> --front-folder <전방 폴더> --name <파일명>`은 실제 telemetry와 흐름 로그로 `video-edit.json` 및 SRT를 만든다. `swift tools/operator-console/compose_video.swift <video-edit.json>`은 로컬 AVFoundation으로 두 영상을 연결하고 별도 하단 영역에 자막을 넣는다. 원본 2560×1410 창에서 브라우저 상단 122px를 제거하고 2560×1440·30fps로 출력한다. 원본에 소리가 없으면 완성본도 무음이다. 같은 출력 파일은 덮어쓰지 않는다.

전용 Chrome 창을 앞에 둔 후 `record_stories.py --output <출력 루트> --window-id <실제 창 ID>`로 두 장면을 차례로 녹화할 수 있다. 약 3분 동안 해당 창이 가려지지 않도록 유지한다. 앱 흐름 성공과 영상 갱신 여부는 별개이므로 `verify_video.swift <video-edit.json>`의 프레임·자막 결과를 보고 최종 화면을 직접 검수한다.

## 음성 포함 편집

`swift tools/operator-console/add_video_audio.swift <audio-edit.json>`은 로컬 원본 영상에 WAV 음성과 화자·응답 자막을 넣는다. JSON의 `holds`에는 유지할 원본 프레임 시각과 추가 길이를, `cues`에는 음원 경로·시작·길이·화자·문구를 지정한다. 음성 구간의 중첩과 영상 끝 초과를 거부하고 기존 출력 파일을 덮어쓰지 않는다.

0.5의 앱 음성은 실제 에뮬레이터 TTS 출력 녹음이고, 운전자 음성은 기존 STT 시험용 합성 WAV다. 대화 구간의 원본 프레임 유지로 발화 시간을 확보하므로 실시간 인식 속도 측정에 사용하지 않는다. `swift tools/operator-console/verify_video_audio.swift <audio-edit.json>`으로 완성본의 오디오 트랙·구간별 프레임·자막 OCR과 디코딩 PCM을 `audio-video-qa/`에 추출해 검수한다. 실제 청취 평가와 자동 신호·재인식 검사는 구분한다.
