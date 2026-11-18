# 운전자 생체신호·상태 데이터 조사

조사일: 2026-11-15 (Asia/Seoul)

후속 동적 주입 검수에서 착좌 신호의 의미 불일치를 발견했다. 현재 에뮬레이터의 Occupied 원시값 2는 SDK에서 false, Empty 원시값 1은 true로 반환된다. 아래의 단일 GET 성공 기록을 정상적인 탑승/하차 판단 근거로 사용하지 않는다. [시연 동작 검수의 차량 데이터 주입 결과](safetystep-demo-audit.md#차량-데이터-주입-결과)를 참고한다.

## 결론과 확인 범위

스마트캐빈과 ICM 기술 발표에서 생체신호 측정·운전자 모니터링 기능을 확인했다. 다만 현재 프로젝트의 Pleos Vehicle SDK 2.3.2에서 심박·호흡·뇌파·눈 감김 시간·시선 좌표를 읽는 공개 도메인 API는 찾지 못했다. 제조사의 센서 기술, 특정 차량의 탑재 여부, 외부 앱의 접근 권한은 별개다.

초기 조사는 공식 문서와 설치된 SDK AAR의 공개 클래스·메서드·상수 확인이다. 이후 세이프티스탭 UI 구현 과정에서 아래의 에뮬레이터 조회를 추가 검증했다. API 또는 enum의 존재만을 차량·에뮬레이터 지원의 증거로 사용하지 않는다.

### 후속 실행 확인 · 2026-11-15

Pleos 2.1 에뮬레이터에서 Vehicle SDK 2.3.2의 실제 GET 경로로 P 기어, 운전석 문 닫힘, 트렁크 닫힘, 운전석 착좌 false, 벨트 체결 false, 브레이크 입력 false를 받았다. 착좌·벨트 조회에는 `CAR_SEATS` 권한을 추가했다. 이 값은 에뮬레이터가 반환한 부품 상태이며 실제 탑승자의 건강·의식·대피 상태를 입증하지 않는다.

이번 확인은 현재 값 조회와 UI 표시다. 모든 값의 변화 주입·구독, 실차 센서 정확도, DMS·생체신호는 아직 검증하지 않았다. 에어백 전개 조회는 `ERROR_NOT_SUPPORTED_PROPERTY`를 반환했다. 세부 근거는 [세이프티스탭 검수 기록](safetystep-prototype-validation.md)에 남겼다.

## 확인한 항목

| 데이터 | 확인된 근거 | 현재 판단 |
| --- | --- | --- |
| 심박·호흡·뇌파 | 공개 Vehicle 도메인 인터페이스에 조회·구독 API를 찾지 못함 | 현재 MVP의 필수 입력으로 약속하지 않음 |
| 운전자 주의 경고 | `TelltaleType.DRIVER_ATTENTION`, `Safety.checkTelltalesCapabilities`, `getTelltalesInfo`, `registerTelltales` | 경고 상태 수신 후보. 해당 타입의 실제 반환·변화 수신과 의미 확인 필요 |
| 졸음 경고·운전대 손 감지 | `PleosPropertyIds`에 `DMS_DROWSINESS_WARNING`, `HANDS_ON_DETECTION_*`, `DRIVER_MONITORING_SYSTEM_ENABLED` 상수 존재 | 상수 확인 수준. 이를 제공하는 공개 도메인 메서드와 제3자 앱 접근 계약은 확인되지 않음 |
| 좌석 탑승 여부 | `Seat.checkSeatOccupancyCapabilities`, `getCurrentSeatOccupancy`, `registerSeatOccupancy` | 운전석 GET false 반환 확인. 변화·구독·실차 정확도 미검증 |
| 좌석별 안전벨트 | `Seat.checkSeatBeltBuckledCapabilities`, `getCurrentSeatBeltBuckledState`, `registerSeatBeltBuckledState` | 운전석 GET false 반환 확인. 다른 좌석·변화·구독 미검증 |
| 브레이크 페달 작동 | `Brake.checkBrakePedalStatusCapabilities`, `getCurrentBrakePedalStatus`, `registerBrakePedalStatus` | GET false 반환 확인. Boolean이며 압력이나 의도적인 응답을 뜻하지 않음 |

`Telltales`는 `TelltaleData` 목록이며, 각 항목은 type/state/color/priority/warningCount를 갖는다. 심박 수치나 카메라 원본·시선 좌표를 제공하는 모델로 확인된 것은 아니다. 운전자 주의 경고의 정확한 발생 조건, 정차 중 유효성, 사고 후 동작은 미확인이다.

SDK 검색에서 발견된 `VHAL_HEARTBEAT`는 사람의 심박이 아니다. Android 차량 하드웨어 추상화 계층 서비스의 정상 동작을 감시하는 신호다.

## 공식 기술 발표의 의미

- 2022년 스마트캐빈 기술 발표에는 3D 카메라, 운전대 심전도 센서, 뇌파 이어셋을 이용하는 구성이 나온다. 센서와 별도 장치가 필요한 기술 소개이며, Pleos 앱에서 공통으로 받을 수 있다는 설명은 아니다.
- 2025년 ICM 발표는 카메라와 분석 소프트웨어를 통한 자세·행동·생체신호 감지 및 졸음·부주의 경고를 설명한다. 발표 당시 수주 활동을 시작한다고 밝혔으며, 차종별 탑재 여부와 외부 앱에 제공되는 SDK 범위는 별도 확인이 필요하다.
- 공식 SDK 릴리즈 노트의 Vehicle 버전은 설치본과 같은 2.3.2다. 문서 버전과 설치 버전의 차이만으로 생체신호 API가 누락됐다고 볼 근거는 찾지 못했다.
- Virtual Console 가이드에는 Seat Occupancy, Brake Pedal, Telltales 시험 항목이 있다. 공개 가이드에서 생체신호 수치 주입 항목은 찾지 못했다. Telltales 카드 설명만으로 DRIVER_ATTENTION 지원을 확정할 수 없다.

## 서비스 설계에 반영할 방향

현재 응답 확인은 음성·화면 입력과 사용자의 도움 요청을 중심으로 유지한다. 차량 상태 신호는 별도 보조 입력으로 두며, 없거나 오래됐거나 오류인 경우를 정상적으로 처리한다. Gleo의 대화 연동과 차량 센서 데이터 접근도 별도 기능으로 취급한다.

- 음성 무응답만으로 의식 없음 판정을 하지 않는다.
- 착좌는 좌석 점유 정보이며 의식 유무가 아니다. 좌석이 비었다는 신호만으로 안전한 대피 완료를 확정하지 않는다.
- 벨트 해제나 페달 작동만으로 운전자가 정상 상태이거나 자력 대피할 수 있다고 확정하지 않는다.
- 운전자 주의 경고가 없다는 사실을 정상 의식의 증거로 사용하지 않는다.
- 향후 유효한 생체·DMS 신호를 확보해도 응답 확인을 보완하는 입력으로 추가하고, 의료적 판정 기준은 별도 검증한다.

기획 문구 제안: “차량이 제공하는 탑승·운전자 상태 정보와 음성 응답을 함께 활용해 사고 후 응답 확인과 도움 요청을 지원한다. 생체신호 연동은 지원 차량과 SDK 접근 권한이 확보되는 경우 확장한다.”

## 다음 실측과 문의 항목

1. 앱 UID에서 Telltales·Seat Occupancy·Seat Belt·Brake capability → 최초 조회 → 변경 구독을 확인한다. 미지원·권한 오류·값 미확인 상태를 구분한다.
2. 에뮬레이터에서 DRIVER_ATTENTION을 제공하는지 확인하고, 가능하면 상태 변경과 앱 콜백을 대조한다. 목록에 없다는 사실을 경고 OFF로 해석하지 않는다.
3. 주최 측에는 지원 차종·센서, 공개 DMS/생체신호 API 및 권한, 데이터 의미·갱신 조건, 에뮬레이터 재현 방법을 확인한다. 이번 조사에서 문의를 발송하지는 않았다.

## 근거

- [Pleos Vehicle SDK 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)
- [Pleos SDK 릴리즈 노트](https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note)
- [Safety 공개 API 목록](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/entry)
- [Virtual Console 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)
- [스마트캐빈 통합제어기 기술 발표, 2022-06-23](https://www.mobis.com/kr/aboutus/press.do?category=press&idx=5621)
- [차량 내 모니터링 시스템(ICM) 기술 발표, 2025-01-22](https://www.mobis.com/en/aboutus/press.do?category=press&idx=6006)
- [AOSP VHAL 서비스 상태 감시](https://source.android.com/docs/automotive/watchdog/wd_system_health?hl=en)

설치본 확인: `Vehicle:2.3.2`, `vehicle-data:1.1.11`, `vehicle-aidl:1.1.1`. 호스트 JDK 17의 `javap -public`으로 도메인 인터페이스 20개와 관련 모델·상수를 조사했다. 추출한 서명과 검색 결과는 로컬 빌드 산출물 `android/app/build/driver-state-investigation/`에 있다. 새 JDK·Gradle 캐시를 만들지 않았으며 앱 소스·권한·FigJam은 변경하지 않았다.
