# 기술 프로토타입 검증 기록

검증일: 2026-11-14 KST. 브랜치: `codex/secondary-accident-prototype`.
기능 범위와 실행 명령은 [프로토타입 안내](prototype.md)를 참고합니다.

**후속 조사에서 트렁크 제어 성공을 확인했습니다.** 초기 실패 원인은 Virtual Console 브라우저 세션이
없어 차량 시뮬레이션이 비활성 상태였던 것입니다. 콘솔을 연 뒤 동일 APK에서 `GET=100`과 차량 화면의
실제 열림이 확인됐습니다. [원인 조사와 재검증](trunk-investigation.md)을 참고하세요.

**Gleo 후속 조사에서는 앱 호출 오류를 수정하고 실제 Gleo의 함수 선택·앱 실행·콜백 해석을 확인했습니다.**
입력은 ASR 텍스트 테스트이며, 11월 14일에는 STT/TTS 장애가 관측됐습니다. 에어백 전개는 연결·Cold boot 후에도 미지원입니다.
자세한 범위는 [에어백·Gleo 재검증](airbag-gleo-investigation.md)에 기록했습니다.

**2026-11-15 음성 SDK 직접 검증:** debug 진단 화면에서 온디바이스 한국어 TTS 재생 콜백과
합성 PCM의 STT 인식 결과를 확인했습니다. 인터넷 차단 상태에서도 동작했습니다.
후속으로 호스트 마이크 권한을 바로잡고 실제 사람 발화의 텍스트 수신도 확인했습니다.
정확한 응답 분류와 사고 상태 전환까지 연결한 결과는 아닙니다.
[음성 응답 경로 검증](speech-response-validation.md)에 콜백 순서·말끝 누락·무음 시험을 기록했습니다.

**2026-11-15 Gleo 자동 실행 재확인:** 텍스트 요청으로 종료된 앱 열기는 성공했지만,
이번 세 요청은 특정 기능 호출 대신 일반 앱 열기로 처리됐습니다. Gleo의 앱 실행 안내 TTS 완료 콜백은 확인했습니다.
전면의 우리 앱에서 표준 `ACTION_ASSIST`를 보내는 것도 허용됐으나, Gleo 서비스 시작 후 돌아왔고
대화창·선제 질문은 관측되지 않았습니다. 사고 후 자동 대화 성공으로 표현하지 않습니다.
현재 결과와 11월 14일 성공 조건의 차이는 [재검증 기록](airbag-gleo-investigation.md)에 구분했습니다.

## 실행 환경

- 호스트: macOS arm64, 기존 Homebrew JDK 17, 기존 `$HOME/.gradle` 캐시
- 빌드: Gradle 8.10.2 / AGP 8.8.2 / Kotlin 2.0.21
- 에뮬레이터: `Pleos_Connect_v2_1`, Android API 34 arm64, `emulator-5554`, 화면 사용자 10
- SDK: Vehicle 2.3.2, 설치된 Vehicle Service 2.3.22
- 차량 A: `io.github.aitm.pleosapp` / `pleosDebug`
- 차량 B: `io.github.aitm.pleosapp.demo` / `demoDebug`
- 서버: Deno 2.9.6, 호스트 `127.0.0.1:8787`, `adb reverse` 연결

A/B는 같은 에뮬레이터에 설치한 서로 다른 앱입니다. 앱 데이터와 테스트 차량 식별자는 분리되어 있지만,
두 실차·실도로·무선 V2V 통신 환경을 검증한 것은 아닙니다.

## 자동 검증 결과

| 검사 | 결과 | 확인한 범위 |
| --- | --- | --- |
| Android 빌드 / lint | 두 flavor 통과 | 실행 APK와 계측 테스트 APK 생성 |
| Kotlin 단위 테스트 | 7개 × 두 flavor = 14회 통과 | 기존 상태·경고 테스트와 Gleo의 자기 패키지 action 처리·외부 접두어 거부 |
| Deno | fmt / lint / check 통과, 테스트 4개 통과 | 중복·버전·소유자 검증, 입력 제한, 만료와 해제 구분 |
| Android 계측 테스트 | 3개 × 두 flavor = 6회 통과 | 로컬 상태 저장, 공유 필드 제한, 실제 HTTP A/B 교환, SDK 결과 기록 |
| 화면 통합 테스트 | 8개 시나리오 통과 | 아래 목록 및 실제 통신 단절/복구 |
| 화면 확인 | 캡처 확인 | 경고 표시와 Gleo 계약 결과가 기본 Compose 화면에서 읽히는지 확인 |

계측 테스트의 SDK probe는 지원 여부를 기록하는 검사입니다. 테스트 통과가 모든 차량 기능의 지원을 뜻하지 않습니다.

화면 통합 테스트 `python3 scripts/smoke-prototype.py`의 최종 결과는 `passed`입니다.

1. A에서 사건 한 건 생성, 실제 서버 ACK 확인, 중복 시작 시 같은 사건 유지.
2. 모의 질문 후 15초 + 재질문 15초 경과 시 `UNCONFIRMED`. 의식 소실로 판정하지 않음.
3. 뒤늦은 동승자 응답과 대피 보고 수용. 도로 위험은 `ACTIVE` 유지. 응답/건강 관련 필드는 전파하지 않음.
4. 프로세스 강제 종료 후 사건과 탑승자 상태 복원.
5. B에서 같은 방향 접근 경고. 반대 방향, 평행도로, 통과 지점, 오래된 위치는 제외.
6. `adb reverse` 제거로 실제 통신 단절. 미전송 해제를 접수 완료로 표시하지 않으며, 재시작 후 전송 대기열 복원.
7. 연결 복구 후 대기 중인 해제 전송 및 ACK. B에서 해당 사건 경고 중단.
8. ADB로 패키지명을 포함한 Gleo 형식 Intent를 주입해 manifest 해석, 종료된 앱 기동, 후속 응답 처리 확인.
   `asking` → `success` 콜백 구성 및 전송. Activity 직접 지정 없이 검사하도록 후속 조사에서 보강.

통신 단절 검사 중 기존 HTTP 연결이 살아 있으면 포트 연결 설정을 지워도 요청이 계속 성공하는 현상을 확인했습니다.
로컬 테스트 전용 클라이언트에 `Connection: close`를 적용한 뒤, 실제 요청 실패와 복구를 확인했습니다.

## 실제 SDK와 Gleo 확인 결과

| 기능 | 관측 결과 | 해석 |
| --- | --- | --- |
| 운전석 문 GET | 원시 값 `0` | 실제 SDK 조회 성공 |
| 기어 GET | `GEAR_PARK` | 실제 SDK 조회 성공 |
| 트렁크 GET | 원시 값 `0` | 실제 SDK 조회 성공 |
| 에어백 전개 capability | 연결된 인터넷·Cold boot·DDS ONLINE에서도 `SEAT_AIRBAGS_DEPLOYED` 오류 60001 | 이 이미지에서는 전개 감지 검증 불가. 모의 사건으로 이후 흐름 검증 |
| 트렁크 SET | 초기에는 SET 후 GET=0. Virtual Console 브라우저 세션 활성화 후 동일 APK에서 GET=100 및 열린 차량 화면 확인 | **에뮬레이터 열림 확인**. 초기 비활성 상태에서는 실패/미확인 표시가 정상 동작 |
| Gleo schema | Gleo 로그에서 두 앱의 `schema.json` 로드 확인 | 앱 action 정의가 읽힘 |
| Gleo callback 수신기 | `ai.umos.ambientai`의 동적 수신기 확인 | 공개 callback action으로 대상 패키지에 전송 |
| Gleo Intent 계약 | 패키지 접두어를 포함한 action으로 cold start / 후속 REPORT, callback 대상 1개 확인 | 기존 prefix 불일치 수정. ADB 계약 검증 |
| 실제 Gleo 앱 왕복 | ASR 텍스트 입력으로 A의 START / REPORT / GET 선택, cold start, asking/success 콜백 해석, 한국어 응답 문장 생성 | 마이크/STT를 건너뛴 실제 Gleo 연동 확인. 입력 표현에 따른 라우팅 차이는 남음 |
| Gleo 음성 | 연결 복구·Cold boot 후에도 STT/TTS 4초 NETWORK_ERROR | 실제 음성 대화 미완료. 음성 서비스 경로의 세부 원인은 미확정 |

트렁크 테스트는 debug 빌드·에뮬레이터 여부·P 기어·쓰기 capability를 확인한 뒤 명시적 버튼으로 실행했습니다.
차량 제어는 외부 Gleo Intent로 실행되지 않습니다. 에어백 경고등을 에어백 전개 신호로 대체하지 않았습니다.

## 증거 파일

빌드 산출물과 로그는 Git에서 제외되며, 로컬 `android/app/build/`에 있습니다.

| 경로 | 내용 |
| --- | --- |
| `prototype-smoke/results.json` | 8개 화면 통합 시나리오 최종 결과 |
| `prototype-smoke/sdk-probe-pleos.json` | 실제 Vehicle SDK 조회 및 에어백 미지원 결과 |
| `prototype-smoke/sdk-probe-demo.json` | 명시적 샘플 gateway 결과 |
| `prototype-smoke/b-warning.png` | B의 전방 400m 모의 위험 경고 |
| `prototype-smoke/a-offline-outbox.png` | A의 통신 실패와 전송 대기 상태 |
| `prototype-smoke/a-restored.png` | 프로세스 재시작 후 복원된 상태 |
| `prototype-smoke/a-gleo-intent-contract.png` | Gleo 형식 REPORT 처리와 callback 전송 표시 |
| `prototype-smoke/logcat.txt` | 통합 테스트 실행 로그 |
| `test-results/testDemoDebugUnitTest/` / `test-results/testPleosDebugUnitTest/` | JUnit XML 결과 |
| `outputs/apk/demo/debug/` / `outputs/apk/pleos/debug/` | 설치한 APK |

## 남은 기술 검증

- Gleo 마이크/STT/TTS를 통한 실제 음성 대화, 다양한 발화의 안정적 앱 선택, 사고 신호에 의한 선제 음성 세션.
- 에어백 전개 이벤트를 지원하는 환경에서 신호 확인. 트렁크는 에뮬레이터의 열림까지 확인했으며 실차 동작은 검증 범위 밖.
- NaviHelper/위치 기반 도로 매칭과 진행 방향 판별. 현재 도로·거리·속도는 모의 값.
- 백그라운드/미실행 상태의 보호 흐름, 실제 통화 우선 처리. 현재는 전경 동작과 명시적 재개만 구현.
- Supabase 클라우드 Auth/RLS, DB 영속성, Realtime. 현재는 loopback 메모리 서버로 전송 계약을 검증.

현재 결과는 기술 연결과 상태 흐름의 검증이며 실제 사고 감지 성능, 의료 판단, 구조 접수, 도로 안전성의 검증은 아닙니다.
