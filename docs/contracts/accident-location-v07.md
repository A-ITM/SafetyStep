# 사고 위치 전파 · 0.7 프로토타입 계약

2026-11-17. 단순 정차 관측과 사고 입력을 분리한다. 기존 0.6 TEST 구간 전파는 회귀 시험용으로 보존하고, 정상 앱의 사고 전파는 실제 위·경도 기반의 새 경로를 사용한다. 루프백 개발 서버와 전면 실행 에뮬레이터 범위다.

## 화면과 시작 조건

- 일반 정차는 사고 지원을 시작하지 않는다. 명시적 사고 입력(사용자 사고 지원, SDK 사고 입력, 콘솔 사고 재현)으로 지원 세션을 만든다.
- 사고 위치 등록은 사고 입력 + 위치 공유 동의 + 유효한 위치로 실행한다. 정차 지속이나 탑승자 응답을 기다리지 않는다.
- 정지 여부는 대피 안내를 선택하는 조건이다. 사고 상황, 차량 상태, 사고 위치 공유 상태를 별도 표시한다.
- 서버 등록 ACK만으로 다른 차량의 안내 완료를 선언하지 않는다.
- 수신 화면은 지도 경로 매칭이 없으므로 '주변 사고', '약 N m'로 표시한다. 직선 거리를 '전방' 또는 도로 주행 거리라고 표현하지 않는다.

## 위치

`LocationOrigin`: GPS, NAVI, TEST. `AccidentLocation`의 JSON 키는 정확히 아래 9개다.

```json
{"latitude":37.4127,"longitude":127.0946,"receivedAt":1794880000000,"fixObservedAt":1794880000000,"accuracyMeters":5.0,"headingDegrees":null,"address":null,"origin":"GPS","mock":false}
```

위경도는 유한하고 범위 안이어야 한다. address는 null 또는 300자 이하 문자열이다. receivedAt은 실제 콜백/조회 수신 시각이다. GPS는 fixObservedAt과 0 < accuracyMeters <= 100을 요구하며 수신 시각 대비 측위 시각이 15초 이내여야 한다(미래 허용 5초). NAVI는 제공되지 않는 측위 시각·정확도를 null로 유지한다. TEST는 명시적인 debug 주입만 허용한다. 현재 위치로 사용할 때 receivedAt과 제공된 fixObservedAt은 각각 현재 15초 이내여야 한다. mock은 실제 Location의 모의 여부이며 에뮬레이터 시험을 실차 측위로 주장하지 않는다.

GPS를 우선 읽고, 사용할 수 없으면 NaviHelper의 현재 위치를 출처와 한계를 유지하여 사용한다. 주소는 현재 좌표와 별개 Nav 응답을 합성하지 않는다. 위치가 없으면 전송을 보류한다. 사고 위치는 사고 입력 기준 15초 전~30초 후에 받은 위치 한 번으로 고정하며, 뒤늦게 이동한 위치로 사건을 계속 옮기지 않는다.

## API

기존 테스트 식별자 `Bearer prototype-vehicle-A/B`, loopback 전용. JSON + Cache-Control:no-store. 실서비스 Auth가 아니다. 저장은 서버의 지정 로컬 JSON 파일에 원자적으로 수행하고 성공한 뒤 ACK한다. 재시작 후 사건을 복원한다. 최대 500건, 초과 시 429. 원시 음성/좌석/탑승자 상태는 공유하지 않는다.

`POST /prototype/accidents`의 정확한 키:

```json
{"id":"accident-001","owner":"A","version":1,"eventType":"ACCIDENT","status":"ACTIVE","source":"OPERATOR_EVENT","simulation":true,"occurredAt":1794880000000,"expiresAt":1794880300000,"location":{"latitude":37.4127,"longitude":127.0946,"receivedAt":1794880000000,"fixObservedAt":1794880000000,"accuracyMeters":5.0,"headingDegrees":null,"address":null,"origin":"GPS","mock":true}}
```

source는 MANUAL_TEST/SDK_AIRBAG/GLEO_REQUEST/OPERATOR_EVENT 중 하나다. STOP_OBSERVATION으로 사고를 등록하지 않는다. expiresAt=occurredAt+300000. 최초 등록의 occurredAt은 현재 기준 하루 이내/미래 5초 이내이고 만료 전이어야 한다. location.receivedAt은 occurredAt-15000~occurredAt+30000. 같은 소유자·ID·완전히 같은 본문 재요청은 만료 검사보다 먼저 duplicate ACK한다. 다른 내용은 409, 다른 소유자는 403. 201/200 응답은 `{accepted:true,duplicate:boolean,event:AccidentEvent}`.

`POST /prototype/accidents/nearby`는 읽기 조회다. 본문 `{requestId,location}`. 실제 최신 위치만 사용하며 이전 위치의 시각을 갱신하지 않는다. 서버는 자기 사건을 제외하고 Haversine 직선 반경 1000m 안의 사건만 반환한다. 응답 `{requestId,serverNow,radiusMeters:1000,complete:true,events:[AccidentEvent]}`. 만료는 해결을 뜻하지 않으며 원래 상태/시각을 유지하여 최신 상태 미확인으로 표시한다. 별도 도로/방향 확정은 하지 않는다. 수신 위치는 저장하지 않는다.

`POST /prototype/admin/accidents/{id}/resolve`: 테스트 관리자만 `{expectedVersion:1,reason:"CONTROLLED_TEST"}`로 종료. 상태 RESOLVED/version2, 나머지 필드 불변. 이미 같은 종료면 duplicate ACK. 좌석 비움·구간 이탈·통신 단절로 사건을 종료하지 않는다. 오류 envelope는 기존 `{error:{code,message}}` 형식을 재사용한다.

## 앱 연결 및 검수

- 사고별 등록 본문을 로컬 outbox에 고정 저장하고 동일 ID로 재시도한다. 공유 동의가 꺼지면 새 전송을 멈춘다. 새 사고/초기화가 과거 요청 결과로 덮이지 않게 한다.
- 위치 조회, 업로드, 주변 조회를 전면 실행 범위에서 수행한다. 현재 GPS/Navi 위치가 없으면 수신 미확인. 연결 실패는 기존 관련 경고의 최신 상태 미확인이다.
- 같은 사건의 음성은 완료된 경우 1회. 차내 사고 지원 음성이 우선한다.
- 단순 정차→사고 등록 0건, 사고 입력→정지 전 위치 등록, 위치 없음/공유 OFF, 재시도 동일 ID, 서버 재시작, 근처/먼 차량 필터, 자기 사건 제외, 같은 사건 수신·음성, 밝은/어두운 실제 화면을 검수한다.

근거: [Android foreground location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions), [LocationManager](https://developer.android.com/reference/android/location/LocationManager), [Pleos Navi sample](https://github.com/Pleos-Playground/pleos-sample-navi-app). 도로 방향 매칭·운영 배포·실차 자동 사고 기동은 이 변경의 검증 범위가 아니다.
