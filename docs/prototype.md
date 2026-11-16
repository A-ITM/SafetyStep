# 초기 기술 프로토타입 기록

> 이 문서는 0.1 단계의 수동 진단 흐름을 보존한 기록입니다. 현재 세이프티스탭 0.6의 실행·시나리오는 [README](../README.md)와 [0.6 검수 기록](safetystep-v06-validation.md)을 기준으로 확인하세요. 아래 버튼·음성 모의·Gleo 콜백 설명은 당시 구현 기록이며 현재 행동 우선 지원 흐름과 다릅니다.

2026-11-14. 디자인 전 단계의 동작 검증용 앱입니다. 고속도로 사고·고장 정차 후 응답 확인,
대피 지원 상태, 접근 차량 경고의 연결을 검증합니다. **정차된 에뮬레이터 전용**이며 실제 사고 대응용 앱이 아닙니다.

## 실행

기존 JDK 17과 호스트 Gradle 캐시를 재사용합니다. Android Studio는 `android/`를 열면 됩니다.
AVD는 **Cold boot**로 실행하고, Gleo 테스트 전 Android 설정에서 `AndroidWifi` 연결을 확인합니다.
호스트에 인터넷이 있어도 에뮬레이터에 기본 네트워크가 없을 수 있습니다.

```sh
./scripts/check-android.sh
./scripts/run-prototype-server.sh
```

서버 터미널을 유지한 상태에서 별도 터미널에서 실행합니다. `adb`는 Android SDK의 `platform-tools/adb`입니다.

```sh
adb reverse tcp:8787 tcp:8787
adb install -r android/app/build/outputs/apk/pleos/debug/app-pleos-debug.apk
adb install -r android/app/build/outputs/apk/demo/debug/app-demo-debug.apk
adb shell am start -n io.github.aitm.pleosapp/io.github.aitm.pleosapp.MainActivity
```

**Pleos의 실제 차량 상태 변경을 시험할 때는 Virtual Console도 브라우저에 열어 둡니다.**

```sh
adb forward tcp:8088 tcp:8088
open http://127.0.0.1:8088/
```

이 v2.1 이미지에서는 브라우저 페이지가 보내는 주기적 세션 신호가 차량 시뮬레이션을 활성화합니다.
화면 하단 `DDS ONLINE`을 확인한 뒤 트렁크 제어를 실행합니다. 포트 포워딩이나 HTTP 상태 조회만으로는
활성화되지 않습니다. 콘솔이 비활성 상태면 SET 요청은 완료돼도 실제 상태가 갱신되지 않을 수 있습니다.
브라우저를 연 뒤 같은 APK로 트렁크 `GET=100`과 차량 화면의 열림을 확인했습니다.
자세한 원인과 증거는 [트렁크 조사 기록](trunk-investigation.md)에 있습니다.

`pleosDebug`는 차량 A, `demoDebug`는 차량 B입니다. 동일 에뮬레이터에 둘 다 설치되며 서로 다른 앱 데이터와
클라이언트 ID를 사용합니다. 두 실차의 직접 V2V 통신을 검증한 것은 아닙니다. 각각의 에뮬레이터에 설치할 때도
각 장치에 `adb -s SERIAL reverse tcp:8787 tcp:8787`을 설정하면 같은 서버에 연결할 수 있습니다.

차량 A에서 공유 동의 체크 → 사고·고장 주입 → 서버 ACK를 확인합니다. 차량 B를 열면 같은 방향 접근 시
모의 전방 400m 경고가 나옵니다. 반대 방향·평행도로·이미 지난 지점·오래된 위치 버튼으로 경고 제외를 확인합니다.
대피 보고 버튼은 탑승자 상태만 갱신하고, 별도의 테스트 사건 해제 버튼만 해당 사건을 종료합니다.

```sh
adb shell am start -n io.github.aitm.pleosapp.demo/io.github.aitm.pleosapp.MainActivity
```

## 구현 구분

| 부분 | 현재 구현 |
| --- | --- |
| 차량 상태 | Pleos Vehicle 2.3.2의 capability와 실제 GET 콜백 사용 |
| 사고 신호 | 전개 capability 확인 및 구독 코드. 최초 true는 새 사고로 판단하지 않으며 false→true만 후보로 처리 |
| 응답 흐름 | 1회 재질문, 응답 미확인/음성 장애 구분, 운전자/동승자 구분, 늦은 응답 수용 |
| 대피·도움 | 자가 보고 상태 관리. 대피와 도로 위험 상태 분리 |
| 트렁크 | 명시적 버튼, debug 에뮬레이터, P 기어, 쓰기 capability 확인 후 요청하고 GET으로 결과 확인 |
| Gleo | 3개 action의 schema, cold/warm Activity Intent 처리, asking/success/fail callback |
| 전파 | 로컬 Deno 서버와 실제 HTTP 요청. ACK, 중복·역순·권한 검증, 영속 outbox |
| 경고 | 모의 도로 ID/진행 방향/도로상 거리/위치 최신성으로 필터링, 같은 사건·버전의 이벤트 반복 억제 |
| 복원 | SharedPreferences에 사건·탑승자 상태·동의·전개 이전 값·전송 대기열 저장 |

시연의 도로 ID, 1000m 사건 위치, 600m 수신 위치와 25m/s 속도는 명시적 모의 입력입니다.
실제 NaviHelper/FusedLocation 도로 매칭은 구현하지 않았습니다. 15초 응답 대기, 180초 사건 유효 시간,
15초 위치 유효 시간, 20초 접근 시간 기준은 **실험용 기본값**입니다.

앱은 음성 질문이 실제 출력됐다고 가정하지 않습니다. `모의 질문 · 15초 응답 대기` 버튼을 눌러야 타이머를
시작합니다. 음성 입력·출력은 현재 화면 버튼으로 모의합니다. 앱이 가려지면 타이머를 멈추고, 복귀 후 명시적으로
다시 시작합니다. 긴급 통화 중단 버튼도 모의 이벤트이며 실제 통화 상태 감지는 아직 연결하지 않았습니다.

## Gleo 계약 검증과 실제 대화의 차이

스키마는 각 debug flavor의 `assets/schema.json`에 있습니다. A의 호출 이름·런처 이름은 `비트박스 메이트`,
B는 `비트박스 수신차량`입니다. 공개 규격에 맞춘 function은 다음 세 개입니다.

- `START_ROADSIDE_SUPPORT`: 사건 생성/재개, 추가 상태를 묻는 `asking` 응답
- `REPORT_OCCUPANT_STATUS`: `responder=DRIVER|PASSENGER`, `help=CAN_MOVE|NEEDS_HELP` 기록
- `GET_ROADSIDE_STATUS`: 현재 사건 상태와 구조 접수 미확인 여부 반환

`ACTION_PARAM` JSON과 개별 문자열 extras를 처리합니다. 입력 누락은 `asking`, 잘못된 JSON은 `fail`입니다.
외부 Intent는 차량 제어를 실행하지 않습니다. 외부 호출과 로컬 테스트는 debug 빌드에서만 제공되며,
Gleo/다른 앱의 호출자 인증은 제품화 전에 정해야 합니다.

공개 콜백 action `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE`와 `packageName`/`response` extras를
사용합니다. 현재 에뮬레이터의 Gleo 앱 `ai.umos.ambientai`는 해당 수신기를 동적으로 등록합니다.
따라서 정적 receiver 조회 결과가 0이어도 앱 존재를 확인하고 그 패키지로 콜백을 전송합니다.
**전송 성공은 Gleo의 처리·음성 대화 성공을 뜻하지 않습니다.**

설치된 Gleo 4.2는 Intent action에 패키지명을 붙입니다. manifest와 처리 코드는
`io.github.aitm.pleosapp.START_ROADSIDE_SUPPORT` 형식을 수용하며 기존 bare action도 로컬 호환용으로 유지합니다.

```sh
adb shell am start --user 10 -p io.github.aitm.pleosapp -a io.github.aitm.pleosapp.START_ROADSIDE_SUPPORT
adb shell am start --user 10 -p io.github.aitm.pleosapp -a io.github.aitm.pleosapp.REPORT_OCCUPANT_STATUS --es responder DRIVER --es help NEEDS_HELP
```

이 명령은 ADB 계약 테스트입니다. 별도로 Gleo의 ASR 텍스트 테스트 입력을 사용해 Gleo 자체가 A 앱을
cold start하고 세 함수를 선택·호출하며 콜백을 해석하는 것까지 확인했습니다. 마이크/STT를 통과한 검증은 아닙니다.
한국어 질문 문장은 생성됐지만 STT/TTS의 4초 NETWORK_ERROR가 남아 실제 음성 대화는 미완료입니다.
사고 신호에 의한 선제 세션과 임의의 자연어 표현에 대한 안정성도 별도 과제입니다.
원인·수정·재현 절차는 [에어백·Gleo 재검증](airbag-gleo-investigation.md)을 참고합니다.

## 로컬 서버의 경계

공유 로직은 `supabase/functions/_shared/prototype.ts`, 로컬 진입점은 `supabase/local/serve-prototype.ts`입니다.
서버는 `127.0.0.1:8787`에만 바인딩됩니다. debug 앱의 HTTP 예외도 `127.0.0.1`만 허용합니다.
샘플 신원 `prototype-vehicle-A/B`는 공개 테스트 식별자이며 인증 비밀키가 아닙니다.

전송 필드는 사건 ID·소유 테스트 차량·버전·관측/만료 시각·모의 도로/방향/거리·상태·출처·simulation 표시뿐입니다.
부상·음성·응답자·응답 상태·대피 상태는 공유하지 않습니다. 알 수 없는 필드는 서버에서 거부합니다.
소유자만 사건을 해제하고, 중복 요청은 동일 ACK를 반환하며, 역순/충돌 갱신은 거부합니다.
미접수 사건이 만료되면 복구 후 새 사고처럼 재전파하지 않습니다.

서버 데이터는 메모리이므로 서버 재시작 시 사라집니다. 앱의 ACK 완료 사건을 서버 재시작 후 자동 재등록하지 않습니다.
Supabase 클라우드, 실제 Auth/RLS, DB 영속성, Realtime, 공공 돌발정보, 배포·운영은 이번 프로토타입에 포함하지 않습니다.
공유 동의 해제는 아직 보내지 않은 요청을 제거하며 이미 접수된 사건을 도로 안전으로 해제하지 않습니다.

## 검증 방법

```sh
./scripts/check-android.sh assembleDemoDebugAndroidTest assemblePleosDebugAndroidTest
deno fmt --check supabase/functions supabase/local
deno lint supabase/functions supabase/local
deno check --config supabase/functions/deno.json supabase/local/serve-prototype.ts supabase/functions/_shared/prototype_test.ts
deno test --config supabase/functions/deno.json supabase/functions/_shared/prototype_test.ts
python3 scripts/smoke-prototype.py
```

Android 계측 테스트는 서버 실행 및 `adb reverse`를 설정한 상태에서 실행합니다. Pleos 자동차 에뮬레이터는
headless system user 0과 실제 화면 사용자(현재 10)가 다르므로 `adb shell am get-current-user`로 확인합니다.

```sh
adb install -r android/app/build/outputs/apk/androidTest/pleos/debug/app-pleos-debug-androidTest.apk
adb shell am instrument --user 10 -w io.github.aitm.pleosapp.test/androidx.test.runner.AndroidJUnitRunner
```

Smoke 스크립트는 모의 사건 생성, 30초 무응답, 늦은 응답, 대피, 프로세스 재시작, 경고 조건, 실제 adb reverse
통신 단절/복구, Gleo 형태의 cold/warm Intent를 검사합니다. 결과·스크린샷·로그는
`android/app/build/prototype-smoke/`에 저장됩니다. 이 검증은 디자인 품질이나 실제 도로 안전성을 검증하지 않습니다.

## 다음 기술 검증

Gleo 실제 음성 세션과 선제 대화, 전개 신호를 지원하는 환경, 실제 도로·방향 매칭, 앱 수명주기와 통화 우선 처리,
Supabase Auth/RLS/DB를 차례로 검증합니다. 시연 기능의 성공을 차량 안전 성능으로 표현하지 않습니다.

## 근거

- [Gleo App Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)
- [Gleo Intent/Callback 코드](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples)
- [Gleo Callback](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)
- [Virtual Console](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)
- 설치된 Vehicle 2.3.2 AAR의 실제 인터페이스와 Pleos v2.1 에뮬레이터 실행 결과
