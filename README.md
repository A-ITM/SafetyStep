# 세이프티스탭 SafetyStep

고속도로 사고·고장 후 **탑승자가 안전하게 차 밖으로 이동하도록 돕고, 접근 차량이 정차 위험을 일찍 알아채도록 지원**하는 Pleos Connect 개발 프로토타입입니다. 사고 후 질문이나 다음 버튼을 반복하는 대신 첫 행동을 음성으로 안내하고, 도움 요청·차량 관측·주변 위험 정보를 한 화면에서 다룹니다.

- 저장소: [A-ITM/SafetyStep](https://github.com/A-ITM/SafetyStep)
- 현재 개발 범위: **0.6 / 전면 실행 에뮬레이터 + TEST 도로 + 로컬 HTTP 서버**
- [5회 검수한 구현 계획](docs/safetystep-prototype-v06-plan.md) · [공통 계약과 예제](docs/contracts/prototype-v06.md) · [장기 개발 계획 1.3](docs/safetystep-execution-plan.md)
- [인포테인먼트 UI·디자인 시스템 개선](docs/safetystep-infotainment-ui-review.md): 기존 Figma의 네이비 패널·좌석도를 기준으로 주행 경고를 단순화하고, 정차 후 도움 동작을 화면 하단에 고정했습니다.

실제 사고 대응용 배포 앱이 아닙니다. 실제 도로 판정·에어백 자동 기동·공공 돌발정보·운영 인증/DB는 아직 연결하지 않았습니다. 입력 출처와 SDK 성공·미지원·시험 값을 구분합니다.

## 이번 버전에서 확인하는 세 흐름

| 흐름 | 확인할 동작 |
| --- | --- |
| 사고·고장 후 지원 | 명시적 시험 입력과 정차 관측 뒤 첫 행동 안내. 응답을 먼저 요구하지 않으며, 도움 요청과 관측 좌석 상태·신고용 위치를 유지 |
| 응답 없는 정차 차량 | 사전 공유 동의 + TEST 본선/갓길 + 연속 정차 조건으로 `STOPPED_VEHICLE` 공유. 응답이나 비상등 켜짐이 없어도 공유 가능 |
| 접근 차량 경고 | 사용자가 켠 수신 모드에서 현재 도로·방향·전방 범위의 사건 수신. 실제 TEST 거리, 정보 신선도, 저속 음성 보류를 표시하고 가능한 음성 출력 |

첫 안내는 주변을 살피고 안전하게 내릴 수 있을 때 차 밖 안전한 곳으로 이동하도록 돕습니다. 이미 이동 불가·도움 필요가 확인된 경우에는 도움 안내를 우선합니다. ‘네’라는 응답을 이동 가능으로 해석하지 않습니다. 무응답을 의식불명으로 판정하지 않습니다.

비상등은 **실제 상태를 읽고 필요한 안내를 제공**합니다. 이번 정상 흐름은 비상등·트렁크 제어를 자동 호출하지 않습니다. 기존 0.5 자동 제어 영상과 이번 0.6 구현의 범위가 다릅니다.

좌석 비움은 관측 가능한 좌석의 상태일 뿐, 전원 대피나 안전지대 도착을 뜻하지 않습니다. 통신 단절·위치 이탈·정보 만료로 도로 사건을 종료하지 않으며, 도로 사건의 종료가 로컬 도움 상태를 지우지도 않습니다.

## 연결 범위

| 항목 | 0.6의 경계 |
| --- | --- |
| 차량 관측 | `VehicleGateway`를 통한 Pleos SDK 조회. 미지원·오래된 값은 별도로 처리. debug 에뮬레이터의 기존 좌석 어댑터 사용 |
| 음성 | Pleos debug에서 실제 TTS/STT. 첫 안내 완료 후 예외 청취. 실제 시작/완료 콜백으로 기록. demo는 음성 미지원 |
| 신고 위치 | NaviHelper의 주소/좌표/heading. 응답 시각과 측위 시각은 구분하며, 없는 정확도·도로 방향을 만들지 않음 |
| 위험 전파 위치 | 별도 **TEST 위치**만 사용. NaviHelper 좌표를 검증된 도로·방향으로 취급하지 않음 |
| 서버 | loopback 메모리 Deno 서버. 동일 도로·방향의 전방 1,000m만 응답. HTTP 폴링과 업로드는 독립 |
| 사건 수명 | 생성 재시도·버전·정차 재출발 철회·시험 관리자 종료. 180초 TTL은 최신 상태 미확인으로 표시. 서버 재시작 시 자료 초기화 |
| 수신 설정 | 공유 동의와 별개, 처음에는 OFF. 설정 저장. 앱이 전면에서 벗어나면 PAUSED. 위치/연결이 없으면 정상 수신으로 표시하지 않음 |
| 아직 제외 | 운영 Auth/DB/공공 API, 전국 도로 매칭, 백그라운드 자동 기동, 자동 신고, 감속 재경고, 연구/MAU 도구 |

A/B와 관리자 Bearer 문자열은 공개 **시험 식별자**입니다. 인증 기능이 아니며 서버를 외부에 공개하면 안 됩니다. 공유 payload에는 응답·좌석·도움 상태·음성 원문을 넣지 않습니다.

## 개발 환경과 실행

| 항목 | 구성 |
| --- | --- |
| Android | Kotlin 2.0.21 / Compose / min 28, compile·target 35 |
| 빌드 | 기존 JDK 17 / Gradle 8.10.2 / AGP 8.8.2 |
| SDK | Vehicle 2.3.2 / NaviHelper 3.0.5 / TTS·STT 3.3.1 |
| 대상 | Pleos Connect 2.1 AVD, Android 14 arm64 |
| 서버 | Deno 2.9.6 / loopback 8787 |

```sh
git clone https://github.com/A-ITM/SafetyStep.git
cd SafetyStep
./scripts/check-android.sh
./scripts/run-prototype-server.sh
```

서버 명령은 별도 터미널에서 실행합니다. 설치된 Deno가 없으면 `npx deno@2.9.6`을 사용합니다. 호스트의 기존 JDK와 `~/.gradle` 캐시를 재사용합니다. [환경 설정](docs/development.md)을 참고하세요.

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
npx --yes deno@2.9.6 fmt --check supabase/functions supabase/local
npx --yes deno@2.9.6 lint supabase/functions supabase/local
npx --yes deno@2.9.6 check --config supabase/functions/deno.json supabase/functions/health/index.ts supabase/local/serve-prototype.ts supabase/functions/_shared/prototype_test.ts
npx --yes deno@2.9.6 test --allow-read=docs/contracts/fixtures/prototype-v06.json --config supabase/functions/deno.json supabase/functions/_shared/prototype_test.ts
```

[0.6 검수 기록](docs/safetystep-v06-validation.md)에는 두 flavor 빌드·정책 시험과 실제 에뮬레이터 시험을 구분합니다. 음성이 출력되지 않은 demo 결과를 음성 성공으로 세지 않습니다. 경고 리드타임은 진행하는 TEST 위치가 사건 지점을 통과한 시각과 실제 음성 완료 시각으로 계산하며 고정 거리의 예상 TTC와 구분합니다.

2026-11-17 로컬 검수에서 각 flavor 단위 시험 36개·lint·APK 빌드, Deno 계약 시험 25개, 실제 HTTP 요청 46건이 통과했습니다. Pleos 기기 시험 16개와 무응답 정차 공유→수신→철회, 실제 주행 경고 음성을 확인했습니다. 통제 시험 1회에서 첫 행동 안내 완료 6.193초, 경고 음성 완료 후 TEST 지점 통과까지 15.564초였습니다. 실차/운전자 효과 측정 결과는 아닙니다.

이후 인포테인먼트 UI 개선 검수에서는 각 flavor 단위 시험 37개·lint·APK 빌드와 화면 시험 15개가 통과했습니다. 실제 Pleos 분할 화면에서 밝은/어두운 테마, 동승석 잔류, 도움 요청 유지, 주행 경고를 확인했습니다. 세부 범위는 [UI 검수 기록](docs/safetystep-infotainment-ui-review.md#실행-결과--2026-11-17)에 정리했습니다.

별도 서버가 켜져 있으면 `python3 scripts/smoke-prototype.py`로 HTTP 계약을 검사합니다. 서버를 재시작한 뒤 `python3 scripts/verify-prototype-v06.py --serial emulator-5554 --phase all --output output/v06-flow`로 설치된 두 앱의 연속 흐름을 확인할 수 있습니다. [검수 기록의 재현 조건](docs/safetystep-v06-validation.md#재현-명령과-남은-범위)을 먼저 읽으세요.

계측·스크린샷·APK·합성 음성은 로컬 산출물이며 Git에 넣지 않습니다. GitHub Actions는 PR마다 Android와 서버 검사를 실행합니다.

## 문서와 이전 영상

- `android/app/src/main/`: 지원·수신 정책, 상태 저장, 화면, 차량/음성/위치 인터페이스
- `android/app/src/demo/`, `pleos/`, `pleosDebug/`: 환경별 구현
- `supabase/`: TEST 사건 API와 로컬 서버
- `docs/contracts/`: Android/서버 공통 계약·fixture
- `tools/operator-console/`: 기존 콘솔 연결과 **0.5 시나리오·촬영 도구**

기존 `SafetyStep-0.5-automatic-voice.mp4`와 후방/전방 자동 제어 시나리오는 [0.5 설명](docs/safetystep-v05-readme.md) 및 [당시 검수 기록](docs/safetystep-automatic-validation.md)에 보존합니다. 기존 촬영 스크립트는 0.6의 자동 검증으로 사용하지 않습니다.

공모전 양식의 **7. 참고자료**에 넣을 주소는 [https://github.com/A-ITM/SafetyStep](https://github.com/A-ITM/SafetyStep)입니다. 제출 설명에는 버전과 시험 범위를 함께 적고, 0.5 영상의 자동 제어를 0.6 기능으로 소개하지 않습니다.
