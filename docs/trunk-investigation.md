# 트렁크 열림 미확인 원인 조사

조사일: 2026-11-14 KST. 대상: Pleos Connect v2.1.0 / Vehicle SDK 2.3.2 / Vehicle Service 2.3.22.

## 결론

**Virtual Console의 브라우저 세션이 없어 차량 시뮬레이션이 비활성 상태였던 것이 이번 실패의 원인입니다.**
`http://127.0.0.1:8088/`를 Chrome에서 열자 DDS 연결이 활성화됐으며, 앱 코드를 변경하거나 재설치하지 않고
같은 트렁크 버튼을 눌러 `GET=100`과 에뮬레이터 차량의 열린 트렁크를 확인했습니다.

초기 조사에서 트렁크를 환경의 미지원 기능으로 확정하지 않고 미확인으로 기록했으나, 후속 조사로
지원 기능임을 확인했습니다. 에어백 전개 capability의 `ERROR_NOT_SUPPORTED_PROPERTY`는 별도 문제로 남아 있습니다.

## 요청은 어디까지 전달됐나

초기 실패 시 로그에서 다음이 확인됐습니다.

1. `DOOR_POS`의 트렁크 영역 `536870912` / `0x20000000`은 읽기·쓰기 가능, 범위 `0..100`.
2. 앱은 P 기어를 확인하고 `DoorArea(DoorValue.REAR)`에 값 `100` 요청.
3. `CONTROL_CAR_DOORS` 권한과 요청 값 검증 통과.
4. Vehicle Service가 차량 HAL에 `prop=373295872` / `0x16400B00`, 영역 `536870912`, 값 `100` 전달.
5. HAL에서 `set trunk position 1` 기록. SDK의 0..100 값이 하위 계층의 0..1 값으로 변환된 것.
6. SET 성공 콜백 이후 반복 GET은 계속 `0`. 약 12초 뒤 별도 GET도 `0`.

따라서 이번 현상은 잘못된 트렁크 영역, `1`과 `100`의 혼동, 앱 제어 권한 누락, 짧은 재조회 시간 때문에
발생한 것이 아닙니다. 공식 가이드도 SET 콜백의 값은 요청 값이며, 실제 상태는 GET이나 변경 알림으로
확인해야 한다고 설명합니다.

## 원인을 가른 비교

| 관측 | 브라우저 콘솔 없음 | 브라우저 콘솔 열림 |
| --- | --- | --- |
| `/vehicle/state`의 `body.emulator_active` | `false` | `true` |
| `gear.bridge_ready` | `false` | `true` |
| `body.dds_ready.trunk_position` / `trunk_actual_position` | 둘 다 `false` | 둘 다 `true` |
| 콘솔 표시 | `DDS OFFLINE` | `DDS ONLINE` |
| 콘솔 자체 트렁크 변경 요청 | HTTP 503, `{"error":"emulator_inactive"}` | 브라우저 활성화 이후 앱 SDK 요청으로 상태 변화 확인 |
| 앱 SET 후 GET | `0` | `100` |
| 콘솔의 트렁크 상태 | `false`, position `0.0` | `true`, position `1.0` |
| 차량 화면 | 닫힘 | 열림 및 트렁크 닫힘 안내 표시 |

성공 로그에서는 HAL의 `set trunk position 1`이 `18:02:12.400`에 기록됐고,
SDK GET은 `18:02:12.419`에 `100`을 반환했습니다. 기존 재조회 시간 안에 확인됐으므로 앱의 대기 시간을
늘릴 필요는 없었습니다. 시각은 에뮬레이터 로그의 시각입니다.

## 브라우저가 필요한 이유

설치된 Virtual Console HTML에는 다음 동작이 있습니다.

- 페이지 로드 시 `POST /browser/session?client=...` 호출.
- 같은 요청을 1초 간격으로 전송.
- 페이지를 떠나거나 닫을 때 `closing=1` 전송.

이 세션 신호가 없는 상태에서는 HTTP 서버 자체는 살아 있어서 `/vehicle/state` 조회는 가능하지만,
차량 시뮬레이션은 비활성 상태였습니다. 즉, `adb forward`를 설정하거나 HTML을 다운로드한 것만으로는
실제 브라우저에서 콘솔을 실행한 것과 같지 않습니다. 이 결론은 이번 설치 이미지의 코드와 전후 동작에서 확인한 것입니다.

SDK 요청 처리와 실제 차량 상태 갱신은 별개입니다. 비활성 상태에서도 SET 완료 콜백이 올 수 있으므로,
현재 앱의 후속 GET 확인은 유지합니다. 권한·SDK·에뮬레이터 이미지를 변경하거나 보안 설정을 완화하지 않았습니다.

## 재현 및 해결

에뮬레이터를 실행한 상태에서:

```sh
adb forward tcp:8088 tcp:8088
open http://127.0.0.1:8088/
```

1. 브라우저 페이지를 열어 두고 하단 `DDS ONLINE`을 확인합니다.
2. 차량 A 앱에서 P 기어와 정차 상태를 확인합니다.
3. `트렁크 열기 요청 (에뮬레이터)`을 누릅니다.
4. `SDK 트렁크 열림 확인 · GET=100`과 차량 화면의 열림을 함께 확인합니다.
5. 다시 닫을 때는 Virtual Console의 Body → TRUNK를 사용합니다.

브라우저 종료 또는 세션 신호 중단 시 시뮬레이션 활성 상태를 다시 확인합니다.
앱 화면의 별도 SDK 조회란은 마지막 수동 조회 결과이므로 최신 상태가 필요하면 `SDK 조회 · 에어백 구독`을 누릅니다.

## 로컬 증거

`android/app/build/trunk-investigation/`에 저장했습니다. 빌드 디렉터리라 Git에는 포함되지 않습니다.

- `reproduction-logcat.txt`: 최초 실패 재현의 capability, 권한, SET, 반복 GET.
- `state-before.json`: 브라우저 미연결 상태의 비활성/DDS false.
- `console-trunk-1.json`: 콘솔 직접 요청의 `emulator_inactive` 응답.
- `state-browser-open.json`: 브라우저 개방 후 활성/DDS true.
- `sdk-open-logcat.txt`: 동일 APK의 SDK SET → GET=100.
- `state-sdk-open.json`: 트렁크 true / position 1.0.
- `app-browser-open.xml`, `trunk-open.png`: 앱의 실제 읽기 결과와 차량 화면.

차량 HAL에서 별도의 SecureStorage 권한 오류도 관측됐으나 이를 변경하지 않고 트렁크가 정상 동작했으므로,
이번 트렁크 실패의 직접 원인으로 판단하지 않았습니다.

## 공식 참고

- [Virtual Console 셋업](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator): 포트 포워딩 후 PC 브라우저로 콘솔 실행.
- [Virtual Console 사용](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide): Trunk / Frunk 상태 시뮬레이션 지원.
- [Vehicle SDK Sample App](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app): SET 요청 완료와 실제 상태 GET의 구분.
