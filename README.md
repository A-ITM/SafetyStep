# 세이프티스탭 SafetyStep

고속도로 사고 후 **탑승자가 안전하게 차 밖으로 이동하도록 돕고, 다른 차량이 주변 사고 위치를 확인하도록 지원**하는 Pleos Connect 개발 프로토타입입니다. 사고 후 질문이나 다음 버튼을 반복하는 대신 첫 행동을 음성으로 안내하고, 도움 요청·차량 관측·사고 위치 공유를 한 화면에서 다룹니다.

- 저장소: [A-ITM/SafetyStep](https://github.com/A-ITM/SafetyStep)
- 현재 개발 범위: **전면 실행 에뮬레이터 · Android GPS/NaviHelper · 차량 두 대용 Supabase 파일럿과 로컬 개발 서버**
- [Supabase 프로젝트와 차량 간 사고 공유](docs/backend.md) · [시험 차량 연결 방법](docs/pilot-cloud-android.md)
- [사고 차량의 하차 전후 흐름과 검수 기준](docs/cabin-exit-flow.md)
- [0.7 사고 위치 계약](docs/contracts/accident-location-v07.md) · [0.7 검수 기록](docs/safetystep-v07-validation.md) · [장기 개발 계획 1.3](docs/safetystep-execution-plan.md)
- 이전 0.6: [구현 계획](docs/safetystep-prototype-v06-plan.md) · [TEST 정차 전파 계약과 예제](docs/contracts/prototype-v06.md)
- [인포테인먼트 UI·디자인 시스템 개선](docs/safetystep-infotainment-ui-review.md): 기존 Figma의 네이비 패널·좌석도를 기준으로 주행 경고를 단순화하고, 정차 후 도움 동작을 화면 하단에 고정했습니다.

실제 사고 대응용 배포 앱이 아닙니다. Supabase 접속 권한은 시험 차량 두 대에 한정합니다. 도로·진행 방향 매칭, 실차 자동 사고 기동, 공공 돌발정보는 향후 개발 범위입니다. 입력 출처와 SDK 성공·미지원·시험 값을 구분합니다.

## 이번 버전에서 확인하는 세 흐름

| 흐름 | 확인할 동작 |
| --- | --- |
| 사고 후 지원 | ‘사고 도움받기’ 등 명시적 사고 입력으로 지원 시작. 차량 정지 여부에 맞춰 첫 행동을 안내하며, 응답을 먼저 요구하지 않음. 도움 요청·관측 좌석·신고 참고 위치 유지 |
| 사고 위치 공유 | 사고 입력 + 위치 공유 동의 + 유효한 위치로 서버 등록. 정차 지속이나 탑승자 응답을 기다리지 않으며, 사고 시점에 받은 위치를 한 번 고정해 동일 사건으로 재시도 |
| 주변 사고 경고 | 사용자가 켠 수신 모드에서 현재 위·경도 기준 직선 반경 1,000m의 다른 차량 사고 조회. 한 사건의 종류·약 거리·신선도를 표시하고 가능한 음성 출력 |

일반 정차만으로 사고 지원이나 사고 등록을 시작하지 않습니다. 화면의 **‘사고 후 안전 지원’**, 실제 차량 속도로 확인하는 **‘차량 상태’**, **‘사고 위치 공유 · 서버 등록 완료’**는 서로 다른 상태입니다. 서버 등록 ACK는 다른 차량의 수신·음성 안내 완료를 뜻하지 않습니다. 지도 경로를 맞추지 않으므로 수신 화면은 ‘주변 사고’, ‘약 N m’로 표시하며 직선 거리를 전방 또는 도로 주행 거리로 표현하지 않습니다.

기존 0.6의 `TEST` 도로·방향·연속 정차 기반 `STOPPED_VEHICLE` 전파는 별도의 회귀 시험 경로로 보존합니다. 정상 홈·주행 화면의 사고 전파와 수신은 0.7 위·경도 경로를 사용합니다.

탑승자가 첫 안내를 들을 때 대피와 신고 순서를 함께 전달합니다. 안전하게 내릴 수 있으면 대피하고, 안전한 곳에서 휴대전화로 신고합니다. 이미 이동 불가·도움 필요가 확인된 경우에는 도움 안내를 우선합니다. ‘네’라는 응답을 이동 가능으로 해석하지 않습니다. 무응답을 의식불명으로 판정하지 않습니다.

비상등은 **실제 상태를 읽고 필요한 안내를 제공**합니다. 정상 흐름은 비상등·트렁크 제어를 자동 호출하지 않습니다. 기존 0.5 자동 제어 영상과 현재 구현의 범위가 다릅니다.

운전석이 비고 다른 좌석의 탑승 신호가 남으면 동승자에게 도움 요청 방법을 안내합니다. 탑승 이력이 있고 관측 좌석 전체의 비움이 3초간 확인되면 실내 음성·마이크를 멈추고 사고 위치 공유 상태와 기존 도움 요청 기록을 표시합니다. 안전지대 도착 여부는 차량에서 확인할 수 없습니다. 통신 단절·위치 이탈·정보 만료로 도로 사건을 종료하지 않으며, 도로 사건의 종료가 로컬 도움 상태를 지우지도 않습니다.

## 연결 범위

| 항목 | 0.7의 경계 |
| --- | --- |
| 차량 관측 | `VehicleGateway`를 통한 Pleos SDK 조회. 미지원·오래된 값은 별도로 처리. debug 에뮬레이터의 기존 좌석 어댑터 사용 |
| 음성 | Pleos debug에서 실제 TTS/STT. 첫 안내 완료 후 예외 청취. 실제 시작/완료 콜백으로 기록. demo는 음성 미지원 |
| 위치 조회 | 실제 Android GPS 우선, 사용할 수 없으면 Pleos NaviHelper로 대체. 출처 GPS/NAVI/TEST, 수신 시각·측위 시각·정확도·모의 위치 여부 구분. 없는 정확도·주소·도로 방향을 만들지 않음 |
| 사고 위치 고정 | 사고 입력 15초 전~30초 후에 받은 유효한 위치 한 번을 고정. 유효한 위치가 없으면 전송 보류. 뒤늦게 이동한 현재 위치로 사고 지점을 옮기지 않음. 신고 참고 위치도 해당 사고 위치 사용 |
| 서버 | 파일럿 차량에서 Supabase Auth → Edge Function → Postgres로 등록·조회. 로컬 개발 모드에서는 Deno와 JSON 파일 사용. 반경 1,000m 안의 타 차량 사고를 조회하고 수신 차량의 위치는 저장하지 않음 |
| 사건 수명 | 사고별 본문을 앱 로컬 outbox에 고정해 같은 ID로 재시도. 사고 300초 TTL은 해결을 뜻하지 않으며 최신 상태 미확인으로 표시. 클라우드 조회에서는 유효 시간이 지난 사고를 제외. 로컬 시험 서버에서는 관리자가 별도로 종료 가능 |
| 수신 설정 | 공유 동의와 별개, 처음에는 OFF. 설정 저장. 앱이 전면에서 벗어나면 PAUSED. 위치/연결이 없으면 정상 수신으로 표시하지 않음 |
| 위치 권한 | 정차 중 설정에서 Android 위치 권한 요청. 위치 조회·전송·수신은 전면 실행 범위. 주행 중 자동 권한 창이나 백그라운드 위치 권한 사용 없음 |
| 이전 TEST 경로 | 0.6 TEST 도로 기반 정차 관측·전방 경고는 개발 도구와 회귀 시험용. 일반 정차를 사고로 등록하거나 정상 화면의 GPS 사고 경고로 대체하지 않음 |
| 아직 제외 | 일반 사용자 가입·공공 API, 전국 도로 매칭, 백그라운드 자동 기동, 자동 신고, 감속 재경고, 연구/MAU 도구 |

로컬 개발 서버는 공개된 A/B·관리자 시험 식별자를 사용하므로 루프백에서 실행합니다. 클라우드에서는 차량마다 별도의 Supabase Auth 계정으로 로그인하고 서버가 허용한 두 계정에만 사고 API 접근을 허가합니다. 공유 payload에는 응답·좌석·도움 상태·음성 원문을 넣지 않습니다.

## 개발 환경과 실행

| 항목 | 구성 |
| --- | --- |
| Android | Kotlin 2.0.21 / Compose / min 28, compile·target 35 |
| 빌드 | 기존 JDK 17 / Gradle 8.10.2 / AGP 8.8.2 |
| SDK | Vehicle 2.3.2 / NaviHelper 3.0.5 / TTS·STT 3.3.1 |
| 대상 | Pleos Connect 2.1 AVD, Android 14 arm64 |
| 서버 | Supabase 서울 리전 파일럿 / 로컬 Deno 2.9.6 (8787) |

```sh
git clone https://github.com/A-ITM/SafetyStep.git
cd SafetyStep
./scripts/check-android.sh
./scripts/run-prototype-server.sh
```

서버 명령은 별도 터미널에서 실행합니다. 사고 저장 파일의 기본 경로는 `output/prototype-accidents.json`이며 `./scripts/run-prototype-server.sh --port=8787 --data=경로`로 변경할 수 있습니다. 저장 파일을 지우지 않으면 서버 재시작 뒤에도 사고를 복원합니다. 설치된 Deno가 없으면 `npx deno@2.9.6`을 사용합니다. 호스트의 기존 JDK와 `~/.gradle` 캐시를 재사용합니다. [환경 설정](docs/development.md)을 참고하세요.

Android Studio에서 `android/`를 열어 `demoDebug` 또는 `pleosDebug`를 선택합니다. 아래는 **이미 실행된 Pleos AVD**에 설치하는 예입니다. 연결된 개인 휴대폰을 선택하지 않도록 대상 serial과 hardware를 확인합니다.

```sh
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$ADB" devices -l
"$ADB" -s emulator-5554 shell getprop ro.hardware  # ranchu 또는 goldfish 확인
"$ADB" -s emulator-5554 reverse tcp:8787 tcp:8787
"$ADB" -s emulator-5554 install -r android/app/build/outputs/apk/pleos/debug/app-pleos-debug.apk
"$ADB" -s emulator-5554 install -r android/app/build/outputs/apk/demo/debug/app-demo-debug.apk
"$ADB" -s emulator-5554 shell am start --user 10 -n io.github.aitm.pleosapp/.MainActivity
```

사용자 10은 검증 대상 Pleos 이미지의 예입니다. `demoDebug`는 명시적 샘플을 사용하고, `pleosDebug`는 실제 SDK 연결 실패를 숨기지 않습니다. 실제 차량에 시험 제어를 실행하지 않습니다.

## 검사와 재현

```sh
./scripts/check-android.sh
npx --yes deno@2.9.6 fmt --check supabase/functions supabase/local supabase/tests
npx --yes deno@2.9.6 lint supabase/functions supabase/local supabase/tests
npx --yes deno@2.9.6 check --config supabase/functions/deno.json supabase/functions/accidents/index.ts supabase/functions/health/index.ts supabase/local/serve-prototype.ts supabase/functions/_shared/prototype_test.ts supabase/functions/_shared/accident_test.ts supabase/functions/_shared/accident_repository_test.ts
npx --yes deno@2.9.6 test --allow-read=docs/contracts/fixtures/prototype-v06.json --config supabase/functions/deno.json supabase/functions/_shared/prototype_test.ts
npx --yes deno@2.9.6 test --allow-read=docs/contracts/fixtures/accident-v07.json,output/prototype-accident-tests --allow-write=output/prototype-accident-tests --config supabase/functions/deno.json supabase/functions/_shared/accident_test.ts supabase/functions/_shared/accident_repository_test.ts
npx --yes deno@2.9.6 test --frozen --config supabase/tests/deno.json --allow-read=docs/contracts/fixtures/accident-v07.json,supabase/migrations,supabase/tests/node_modules supabase/tests/
python3 -m unittest discover -s scripts/tests -p test_provision_pilot.py
```

[0.7 검수 기록](docs/safetystep-v07-validation.md): 각 flavor 단위 시험 60개·화면 시험 21개, Deno 48개와 빌드/lint가 통과했습니다. 에뮬레이터 GPS→사고 저장→다른 앱 약 300m 수신, 범위 밖 제외, 서버 재시작·종료, Pleos 주행 중 경고 음성 완료를 확인했습니다. `python3 scripts/verify-accident-location-v07.py --serial emulator-5554`는 새 GPS 사고 경로의 화면·연속 시험이며 실행 조건은 검수 기록을 참고하세요. 아래 0.6 시험 수와 시간 측정은 당시 구현에 대한 이력이며, 0.7의 새 위치 경로를 검증한 결과가 아닙니다.

[이전 0.6 검수 기록](docs/safetystep-v06-validation.md)에서는 두 flavor 빌드·정책 시험과 실제 에뮬레이터 시험을 구분했습니다. 음성이 출력되지 않은 demo 결과를 음성 성공으로 세지 않았습니다. 경고 리드타임은 진행하는 TEST 위치가 사건 지점을 통과한 시각과 실제 음성 완료 시각으로 계산했으며 고정 거리의 예상 TTC와 구분했습니다.

0.6의 2026-11-17 로컬 검수에서 각 flavor 단위 시험 36개·lint·APK 빌드, Deno 계약 시험 25개, 실제 HTTP 요청 46건이 통과했습니다. Pleos 기기 시험 16개와 무응답 정차 공유→수신→철회, 실제 주행 경고 음성을 확인했습니다. 당시 통제 시험 1회에서 첫 행동 안내 완료 6.193초, 경고 음성 완료 후 TEST 지점 통과까지 15.564초였습니다. 실차/운전자 효과 측정 결과는 아닙니다.

이전 0.6 인포테인먼트 UI 개선 검수에서는 각 flavor 단위 시험 37개·lint·APK 빌드와 화면 시험 15개가 통과했습니다. 당시 실제 Pleos 분할 화면에서 밝은/어두운 테마, 동승석 잔류, 도움 요청 유지, TEST 주행 경고를 확인했습니다. 세부 범위는 [UI 검수 기록](docs/safetystep-infotainment-ui-review.md#실행-결과--2026-11-17)에 정리했습니다.

`scripts/smoke-prototype.py`와 `scripts/verify-prototype-v06.py`는 기존 TEST 정차 API·흐름의 회귀 시험 도구입니다. 0.7 정상 UI와 GPS 사고 전파의 검증 명령으로 사용하지 않습니다. 이전 버전의 [재현 조건](docs/safetystep-v06-validation.md#재현-명령과-남은-범위)을 참고하세요.

계측·스크린샷·APK·합성 음성은 로컬 산출물이며 Git에 넣지 않습니다. GitHub Actions는 PR마다 Android와 서버 검사를 실행합니다.

## 문서와 이전 영상

- `android/app/src/main/`: 지원·수신 정책, 상태 저장, 화면, 차량/음성/위치 인터페이스
- `android/app/src/demo/`, `pleos/`, `pleosDebug/`: 환경별 구현
- `supabase/`: 클라우드 사고 API와 Postgres 마이그레이션, 로컬 JSON 저장소와 이전 TEST 정차 API
- `docs/contracts/`: Android/서버 공통 계약·fixture
- `tools/operator-console/`: 기존 콘솔 연결과 **0.5 시나리오·촬영 도구**

기존 `SafetyStep-0.5-automatic-voice.mp4`와 후방/전방 자동 제어 시나리오는 [0.5 설명](docs/safetystep-v05-readme.md) 및 [당시 검수 기록](docs/safetystep-automatic-validation.md)에 보존합니다. 기존 촬영 스크립트는 현재 버전의 자동 검증으로 사용하지 않습니다.

공모전 양식의 **7. 참고자료**에 넣을 주소는 [https://github.com/A-ITM/SafetyStep](https://github.com/A-ITM/SafetyStep)입니다. 제출 설명에는 버전과 시험 범위를 함께 적고, 이전 영상의 자동 제어나 TEST 도로 전방 거리를 0.7의 GPS 사고 공유 기능으로 소개하지 않습니다.
