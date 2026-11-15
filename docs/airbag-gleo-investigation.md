# 에어백 전개 신호·Gleo 재검증

검증일: 2026-11-14 KST. Pleos Connect v2.1 / Android 34 arm64 / 사용자 10.
Vehicle SDK 2.3.2, Vehicle Service 2.3.22, Gleo 4.2.0.15, GAAS 4.2.10-coral-aosp.

**2026-11-15 후속 검증:** 별도 진단 화면에서 TTS·STT SDK 3.3.1의 `ON_DEVICE` 경로로
질문 재생 콜백과 합성 한국어 음성 인식에 성공했습니다. 인터넷 차단 상태에서도 확인했습니다.
후속으로 macOS 마이크 접근 권한을 바로잡아 실제 노트북 마이크 발화의 텍스트 수신도 확인했습니다.
이 직접 SDK 시험과 Gleo 대화 경로는 구분합니다. 같은 날 별도 Gleo 시험에서는 앱 실행 안내의
TTS 완료 콜백도 확인했지만, 마이크부터 Gleo 기능 실행까지의 연속 대화와 사고 상태 연결은 미검증입니다.
자세한 결과는 [음성 응답 경로 검증](speech-response-validation.md)을 참고하세요.

## 2026-11-15: 자동 실행 범위 재확인

사용자가 음성 인식의 기술 가능성 검증을 마치고 판정 로직은 별도로 구성하기로 하여,
추가 마이크 시험 없이 Gleo의 실행 경로를 구분해 확인했다.

| 경로 | 이번 관측 | 확인 범위 |
| --- | --- | --- |
| 사용자 요청 → 종료된 우리 앱 열기 | Gleo가 `settings.launch.installedApp`을 선택해 A 앱 실행 | 텍스트 테스트 입력으로 실제 Gleo의 앱 기동 확인 |
| 사용자 요청 → 앱의 특정 기능 실행 | 시작·사고 신고·응답 기록 세 표현 모두 이번에는 일반 앱 열기로 처리 | 이전 START/REPORT/GET 성공 기록과 달리 이번 실행에서는 재현되지 않음. 스키마 로드 성공만으로 기능 선택 성공을 보장할 수 없음 |
| 전면의 우리 앱 → Gleo 실행 요청 | 앱 UID에서 `ACTION_ASSIST` 호출 허용. Gleo `MainActivity`가 서비스 시작 후 종료 | 대화창 표시·질문·응답 대기 시작은 관측되지 않음 |
| 사고 신호 → Gleo가 먼저 상태 질문 | 공개 인터페이스 및 이번 실행에서 검증하지 못함 | 사고 맥락 전달·선제 질문·백그라운드 기동은 별도 지원 확인 필요 |
| Gleo 음성 출력 | 앱 실행 안내 문장 뒤 `UpdateTtsDone` 확인 | 아래 11월 14일 TTS 오류가 이번 요청에서는 재현되지 않음. 회복 원인은 분리 검증하지 않음 |

첫 두 요청은 A 앱을 강제 종료한 뒤 보냈다. 세 번째 요청은 앱이 열린 상태에서 보냈다.
입력은 아래에 설명한 내부 ASR 텍스트 테스트 수신기를 사용했으며 실제 마이크 발화가 아니다.
Gleo 로그에서 A/B의 `Loaded schema.json`과 `functionCount=3`을 확인했지만,
세 요청에서 앱의 `GLEO_CALLBACK`은 관측되지 않았다. 앱이 열리는 것만으로 사고 대응 시작이나
응답 기록이 완료됐다고 처리하면 안 된다. 이번 관측만으로 라우팅 차이의 원인을 단정하지 않는다.

표준 ASSIST 경로는 `pleosDebug`의 에뮬레이터 전용 `GleoLaunchProbeActivity`에서 확인했다.
ADB는 진단 Activity만 실행했고, 그 Activity가 일반 앱 UID `1010174`에서
`Intent(ACTION_ASSIST).setPackage("ai.umos.ambientai")`를 실행했다.
Android 로그에 `BAL_ALLOW_VISIBLE_WINDOW`와 실행 결과 `0`이 기록됐다.
Gleo의 `onCreate → startAmbientAiService → onDestroy` 이후 진단 화면으로 돌아왔으며
Gleo 질문은 나오지 않았다. `startActivity()`가 예외 없이 반환된 것과 대화 시작은 다른 결과다.
앱이 종료되거나 백그라운드에 있는 경우의 자동 기동 허용 여부도 이 시험으로 증명되지 않는다.
`ACTION_VOICE_COMMAND`는 이 이미지에서 처리 Activity를 찾지 못했다.

공개 [Gleo Interface](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts)는
사용자 요청에 따른 Gleo → 앱 Action → Callback 흐름을 설명한다.
[asking 콜백](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)은
이미 요청된 Action에 추가 정보나 확인이 필요한 경우의 후속 상호작용이다.
사고가 발생했다는 이유만으로 앱이 이 콜백을 보내 선제 대화를 시작해도 된다는 계약으로 해석하지 않는다.

프로토타입 설계에서는 사고 트리거, 질문·음성 입력, 응답 판정을 분리한다.
선제 질문의 기술 경로는 직접 검증한 Pleos TTS/STT SDK를 후보로 두고,
Gleo는 사용자 요청에 의한 진입·기능 호출 경로를 별도로 유지한다.
Gleo가 사고 맥락을 받아 먼저 질문하는 지원 계약이 확인되면 같은 응답 처리 흐름에 연결한다.
현재 코드는 이 자동 대화 흐름이나 판정 로직을 새로 연결하지 않았다.

진단 Activity 추가 후 `./scripts/check-android.sh`의 두 flavor 빌드·lint·단위 테스트가 통과했다.
증거는 로컬 `android/app/build/speech-validation/`의 `gleo-auto-start-current.log`,
`gleo-auto-report-current.log`, `gleo-report-function-current.log`, `gleo-assist-launch.log`,
`gleo-assist-launch.png`에 있다. 빌드 로그는 `android/app/build/gleo-launch-build-check.log`다.
원시 로그의 에뮬레이터 시각은 호스트와 차이가 있어 검증일은 호스트 KST 기준으로 기록했다.
호스트 마이크 연결과 macOS ChatGPT 마이크 권한은 꺼짐으로 복원한 상태에서 이 시험을 수행했다.

## 2026-11-14 결론

| 항목 | 최종 관측 | 판단 |
| --- | --- | --- |
| 에어백 전개 | Cold boot, 인터넷 연결, DDS ONLINE 상태에서도 capability 오류 60001 | 현재 이미지의 전개 속성 미지원. 콘솔 활성화로 해결된 트렁크와 다름 |
| Gleo → 앱 실행 | 실제 Gleo가 패키지 접두어를 붙여 호출. 기존 manifest는 접두어 없는 action만 지원 | 앱 연동 오류 확인·수정 |
| 앱 선택 | A/B 호출 이름이 같고 런처 이름도 스키마와 불일치 | 호출 이름·런처 이름·기능 설명을 정리하고 Gleo 스키마 재등록 확인 |
| Gleo 기능 수행 | 텍스트 테스트 입력 → A 앱 cold start → START/REPORT/GET → 콜백 해석 및 한국어 답변 문장 생성 성공 | 실제 Gleo의 함수 선택·앱 왕복은 확인. 마이크/STT를 통과한 검증은 아님 |
| 음성 입력·출력 | STT/TTS 모두 4초 시간 초과와 NETWORK_ERROR 재현 | 별도 음성 채널 장애가 남음. 정확한 서버/인증/전송 원인은 미확정 |
| 사고 시 선제 대화 | 공개 Interface에서 앱이 사고를 이유로 Gleo 세션을 여는 API는 확인하지 못함 | 내부 테스트용 broadcast를 제품 기능으로 사용하지 않음 |

## 설정을 점검·수정한 내용

- 최초에는 Wi-Fi가 켜져 있었지만 연결된 네트워크가 없고 Android 기본 네트워크도 없었음.
  Android 설정에서 에뮬레이터의 `AndroidWifi`에 연결. 이후 `INTERNET & VALIDATED` 확인.
- AVD 설정은 Quick boot였지만 저장된 snapshot은 없었음. 따라서 이전 실패를 Quick boot 탓으로 단정할 수 없음.
  공식 권장대로 `fastboot.forceColdBoot=yes`, `fastboot.forceFastBoot=no`로 바꾸고
  `-no-snapshot-load -no-snapshot-save`로 실제 Cold boot 수행. 앱 데이터는 유지.
- 재부팅 후 `adb forward tcp:8088 tcp:8088`, `adb reverse tcp:8787 tcp:8787`을 복구.
  Virtual Console을 다시 로드하고 `DDS ONLINE`, `body.emulator_active=true`,
  `body.dds_ready.all=true`, `gear.bridge_ready=true` 확인.
- 앱의 `pleos.car.permission.CAR_SAFETY`와 Gleo의 `RECORD_AUDIO`는 허용되어 있었음.
  Android 마이크 음소거 상태도 확인했으나 호스트 마이크의 실제 음성 전달은 별도 미검증.
- HTTP proxy는 설정되지 않았음. 재부팅 뒤에도 기본 네트워크가 검증된 연결 상태였음.

Cold boot와 Extended Controls의 Microphone 설정은 [공식 에뮬레이터 설정 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator)를 참고.

## 에어백: 권한·DDS 문제와 구분

앱의 `checkAirbagDeployedCapabilities()`는 설치된 SDK에서 `SEAT_AIRBAGS_DEPLOYED`를 요청한다.
현재 Vehicle Service는 이 속성의 capability 조회 단계에서 실패한다.

```text
getCapabilityConfigByKey() propKey SEAT_AIRBAGS_DEPLOYED
getPropertyConfig(SdkPropertyKey(key=SEAT_AIRBAGS_DEPLOYED))
IllegalArgumentException: 60001
ERROR_NOT_SUPPORTED_PROPERTY
```

같은 실행에서 운전석 문 `0`, 기어 `GEAR_PARK`, 트렁크 `0` 조회는 성공했다.
좌석별 GET/구독 전에 전개 속성 자체의 capability 조회가 실패하므로 운전석 area 선택 문제로 볼 근거도 없다.
이 결과를 Pleos 전체 또는 실차의 전개 신호 미지원으로 일반화하지 않는다.

Virtual Console에 있는 `Airbag`은 **Telltales 경고등**이다.
로컬 feature catalog의 제어는 `/vehicle/telltales/simulator`, `type=Airbag`, `state=Off|On|Blink`이며
속성은 `TELLTALES`이다. `SEAT_AIRBAGS_DEPLOYED`의 전개 이벤트와 다른 신호이므로 사고 발생으로 대체하지 않았다.

확인된 범위에서는 앱 권한 추가나 콘솔 재연결만으로 전개 신호를 사용할 수 없다.
다음에는 운영 측에 v2.1 이미지의 해당 속성 지원 여부, 지원 이미지/서비스 버전, 전개 이벤트 주입 방법을 확인해야 한다.
그 전까지 사고 이후 흐름은 명시적 모의 사건으로 검증한다.

## Gleo: 발견한 앱 연동 오류와 수정

수정 전 실제 Gleo 로그:

```text
sendActionCall action: io.github.aitm.pleosapp.demo.GET_ROADSIDE_STATUS
No Activity found to handle Intent
```

문서 예시의 bare action을 받는 구현만으로는 설치된 Gleo 4.2의 호출을 수신하지 못했다.
기존 ADB 검사는 Activity를 `-n`으로 직접 지정했기 때문에 manifest의 실제 action 해석 실패를 발견하지 못했다.

수정 사항:

1. debug manifest에 `${applicationId}.<functionName>` 세 개를 등록.
2. `GleoContract`에서 자기 패키지 접두어만 정규화. 기존 로컬 bare action은 호환 유지하며 다른 앱 접두어는 무시.
3. A는 `비트박스 메이트`, B는 `비트박스 수신차량`으로 invokeWord와 debug 런처 이름을 일치시킴.
   각 함수 설명에도 앱 이름과 A/B 역할을 명시.
4. Smoke 검사를 `-n` 직접 기동에서 `-p`와 패키지 포함 action을 사용하는 방식으로 변경해 manifest 해석도 검사.

APK 교체 직후에는 이전 호출 이름으로 앱을 고르는 현상이 있었고, Cold boot 직후에는 라우팅 스키마가 빈 상태도 관측됐다.
Gleo 프로세스를 다시 시작한 후 `Loaded schema.json`과 `ToolRepository ... converted` 로그로 등록 완료를 확인했다.
최종 성공은 이름 정리·스키마 재등록·요청에 '앱'을 명시한 조건에서 관측한 것이며,
각 변경의 효과를 독립적으로 측정한 결과나 모든 자연어 표현의 성공을 보장하는 결과는 아니다.

### 실제 Gleo 왕복 결과

앱 action을 ADB로 직접 호출한 테스트와 구분하기 위해, 설치된 Gleo의 ASR 텍스트 테스트 수신기에 한국어를 넣었다.
마이크/STT 단계만 건너뛰고 Gleo의 함수 선택, Intent 전송, 앱 콜백 수신·해석, 답변 생성 과정을 실행했다.
첫 요청 전 차량 A 앱을 강제 종료했다.

| 테스트 입력 | Gleo가 선택한 A의 함수 | 결과 |
| --- | --- | --- |
| 비트박스 메이트 앱에서 도로 지원 시작 기능을 실행해줘 | START_ROADSIDE_SUPPORT | 종료된 앱 기동, 새 사건, asking 콜백 해석 |
| 비트박스 메이트 앱에 운전자 응답을 기록해줘. 나는 운전자이고 도움이 필요해. | REPORT_OCCUPANT_STATUS | ACTION_PARAM의 DRIVER / NEEDS_HELP 파싱, RESPONDED 기록, success 콜백 해석 |
| 비트박스 메이트 앱에서 현재 도로 지원 상태를 조회해줘 | GET_ROADSIDE_STATUS | 같은 사건과 응답 상태 반환, success 콜백 해석 |

Gleo 로그의 `processAppActionResponse`와 `builtin.actionCallbackStringToJson ... status=success`를 확인했다.
START에 대해 운전자/동승자와 필요한 도움을 묻는 한국어 문장을 생성했고, GET에서는 도움 필요 상태 및
긴급 출동 미접수 상태를 반영한 문장을 생성했다. **문장 생성 후 TTS는 실패했으므로 실제 발화 성공이라고 표현하지 않는다.**
REPORT 요청에도 앱 이름을 다시 넣었으므로 생략된 문맥만으로 이어지는 연속 음성 대화의 검증도 아니다.

에뮬레이터에 설치된 이 Gleo 버전의 진단용 입력 예시:

```sh
adb shell 'am broadcast --user 10 -a ai.umos.intent.action.ASR -p ai.umos.ambientai --es ASR "비트박스 메이트 앱에서 도로 지원 시작 기능을 실행해줘"'
```

이 수신기는 설치된 버전에서 확인한 **내부 테스트 기능**이며 공개 앱 연동 API가 아니다.
제품 코드에 추가하지 않았고, 사고 감지 후 Gleo를 선제 실행하는 방법으로 사용하지 않는다.
schema 변경 후 로컬 진단에서 등록을 새로 확인하려면 다음처럼 Gleo 프로세스를 재시작할 수 있다.

```sh
adb shell am force-stop --user 10 ai.umos.ambientai
adb shell am start --user 10 -n ai.umos.ambientai/.SettingsActivity
# 로그에서 Loaded schema.json 및 ToolRepository ... converted 확인 후 테스트
```

## 2026-11-14 음성 오류 관측

연결 복구·Cold boot·Gleo 재시작 후에도 STT는 `Timed out waiting for 4000 ms`,
`NETWORK_ERROR / EC_STT / Status443`, TTS는 `NETWORK_ERROR / EC_TTS / Status442`가 발생했다.
여기서 `Status443`은 Gleo 내부 오류 표시이며 HTTP 상태 코드라는 뜻이 아니다.
TTS가 음성 데이터를 받지 못한 로그도 있어 마이크 설정만으로 두 실패를 설명할 수 없다.

동일 호스트의 Gleo REST 요청 `/bsm/v1/runbsm`, `/gleoplus/chat-ami`는 HTTP 200으로 응답했다.
따라서 인터넷 전체 단절은 해소됐지만 STT/TTS의 gRPC 경로는 별도 확인이 필요하다.
이를 서버 장애·인증 실패·방화벽 문제 중 하나로 단정할 직접 증거는 아직 없다.
CORAL 서버 설정이나 인증 값을 임의 변경하지 않았다.

당시 남은 확인 사항은 해당 에뮬레이터 버전의 음성 서비스 엔드포인트/계정 사용 조건, gRPC 연결 오류의 세부 사유,
호스트 마이크 입력 전달이었다. 11월 15일 호스트 마이크 전달과 직접 SDK 경로는 검증했고,
별도 Gleo 시험에서 TTS 완료 콜백도 관측했다. 실제 마이크부터 Gleo 기능 실행까지는 아직 통합 검증하지 않았다.
무응답을 의식 소실로 판단하거나 텍스트 생성만으로 질문이 들렸다고 가정하지 않는다.
현재 앱은 실제 질문 출력 확인을 연결하지 않았으며 응답 타이머는 기존 모의 버튼으로만 시작한다.

사고 감지 → Gleo 선제 세션은 위 앱 실행 테스트와 별개이다.
공개 [Gleo Interface](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts)는
Gleo가 앱을 호출하고 앱이 콜백하는 흐름을 설명한다. 이 문서만으로 사고 이벤트에 의한 앱 주도 세션 시작을 보장할 수 없다.

## 검증·증거

`./scripts/check-android.sh`로 두 flavor의 빌드·lint, 단위 테스트 7개 × 2 = 14회 통과.
실제 Gleo 왕복과 수정한 manifest 경로는 에뮬레이터에서 별도로 확인했다.

로컬 증거는 Git에서 제외되는 `android/app/build/airbag-gleo-investigation/`에 있다.

- `airbag-coldboot-internet.log`, `.xml`: 연결·Cold boot 후 전개 속성 오류.
- `console-coldboot-state.json`: DDS 및 차량 시뮬레이션 활성 상태.
- `gleo-text-logcat.txt`: 수정 전 실제 Gleo의 Activity 해석 실패.
- `gleo-named-schema-load.log`: 최종 schema 등록.
- `gleo-named-start.log`, `gleo-named-report.log`, `gleo-named-get.log`: 실제 Gleo 왕복 및 TTS 실패.
- `stt-after-coldboot.log`: 재부팅·연결 복구 후 STT 실패.
- `gleo-integration-result.png`: Gleo 입력으로 기록된 상태와 SDK 전개 오류 화면.
- `build-check.log`, `smoke-after-gleo.log`: 최종 빌드와 회귀 검사 결과.

원시 로그에는 장치 식별자 등이 포함될 수 있어 외부에 전송하지 않았다. 운영 측 문의 시에는 필요한 오류 줄과 버전만 추린다.

추가 공식 근거:

- [Schema 등록](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema)
- [Callback 규격](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)
- [Vehicle 오류 코드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-error-code)
