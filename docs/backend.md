# SafetyStep 사고 공유 서버

공모전용 Supabase 조직과 프로젝트 이름은 모두 **SafetyStep**이다. 프로젝트는 서울(`ap-northeast-2`) 리전에서 실행된다. 파일럿 차량 두 대는 같은 HTTPS 서버를 사용한다. [팀 프로젝트 대시보드](https://supabase.com/dashboard/project/gcdcnuutczpjdaunaigt)

| 순서 | 차량과 서버가 하는 일 |
| --- | --- |
| 1 | 공유 동의를 켠 사고 차량 A가 사고 시점의 유효한 GPS 또는 내비 위치를 등록한다. |
| 2 | Edge Function이 Supabase Auth로 사용자 신원을 확인하고 허용된 차량과 대조한다. Postgres에 사건이 저장되어야 앱이 등록 완료를 표시한다. |
| 3 | 위험 알림을 켠 차량 B가 자신의 현재 위치로 조회한다. 서버는 직선거리 1km 이내의 유효한 타 차량 사고를 반환한다. B 앱은 주변 사고와 약 거리를 표시한다. |
| 4 | B가 범위를 벗어나 정상 조회 결과에서 사건이 사라지면 B의 해당 안내를 내린다. A의 하차와 B의 이탈은 도로 사건을 해소하는 근거로 사용하지 않는다. |

앱에 파일럿 사용자 세션을 설정하면 클라우드에 연결한다. 기본 설치는 기존 `adb reverse` 기반 로컬 시험을 지원한다. 계정과 서버가 바뀌어도 이전 서버의 전송 대기열이나 등록 확인을 새 서버에 가져가지 않는다. [Android 시험 차량 준비](pilot-cloud-android.md)

## API와 데이터

기본 URL은 `https://gcdcnuutczpjdaunaigt.supabase.co/functions/v1`이다.

| 경로 | 역할 |
| --- | --- |
| `GET /health` | 공개 연결 확인. 사고 데이터와 사용자 세션에 접근하지 않는다. |
| `GET /accidents/session` | 호출자의 차량 역할 A 또는 B 확인. |
| `POST /accidents` | 사고 등록. 같은 차량이 같은 ID와 본문을 재전송하면 저장된 사건으로 응답한다. |
| `POST /accidents/nearby` | 현재 위치와 조회 ID로 주변 사고 조회. 유효 시간은 5분이다. |

사고 함수에는 프로젝트의 publishable key와 파일럿 사용자의 단기 access token을 보낸다. 두 차량은 각자 다른 Auth 사용자다. 서버만 사용하는 허용 목록은 `ACCIDENT_PILOT_USER_A_ID`와 `ACCIDENT_PILOT_USER_B_ID`로 설정한다. 사고 테이블은 비공개 스키마에 있고 모바일 사용자는 직접 읽거나 쓸 권한이 없다. 서버는 사고 발생 위치를 저장한다. 조회 차량의 위치와 탑승자의 좌석·음성은 사고 테이블에 기록하지 않는다. 5분이 지나면 다른 차량의 조회에서 빠지고, 중복 등록 확인용 기록은 24시간이 지나면 다음 데이터 요청 또는 매시 정각 정리 작업에서 삭제한다. [서버 계약과 보관 정책](../supabase/PILOT.md)

## 에뮬레이터에서 확인한 결과

2026-11-18, Pleos 에뮬레이터 한 대에서 독립된 앱 두 개를 차례로 실행했다. A는 `pleosDebug`, B는 `demoDebug`이며 서로 다른 Supabase Auth 계정을 사용했다. 로컬 사고 서버용 `adb reverse`를 해제하고 에뮬레이터 GPS에 시험 좌표를 입력했다.

| 확인한 시점 | 결과 |
| --- | --- |
| A에서 사고 입력 | 클라우드 서버 등록 완료. 서울 리전 Postgres에서 같은 사건 ID와 사고 좌표를 확인했다. |
| B가 사고 지점에서 약 300m | 전면 수신 화면에 A와 같은 사건 ID의 주변 사고가 표시됐다. |
| B가 약 1.67km로 이동 | 정상 조회 결과가 0건으로 바뀌고 B의 사고 안내가 내려갔다. |
| 계정과 사건 격리 | 공개 키만 보낸 요청은 401. A는 자신의 사고를 주변 사고로 받지 않았다. |

거리 기준은 직선 반경 1km다. 이번 시험은 정차한 B의 경고 화면까지 검증했으며 두 실차 간 주행이나 진행 방향 매칭은 별도 검증 항목이다.

## 팀 개발 환경

Supabase CLI는 브라우저에서 `supabase login`을 마친 개발자 계정의 프로젝트 권한을 사용한다. 이 프로젝트에 조직 SAML을 설정할 필요는 없다. 예전 `SUPABASE_ACCESS_TOKEN` 환경 변수가 로그인보다 우선하여 401이 발생하는 셸에서는 해당 명령에만 `env -u SUPABASE_ACCESS_TOKEN`을 붙인다.

다음은 Supabase CLI 3.2.0에서 팀 프로젝트를 명시하는 배포 명령이다. SQL 변경 목록은 dry run에서 확인한다.

```sh
npx supabase@3.2.0 link --project-ref gcdcnuutczpjdaunaigt
npx supabase@3.2.0 db push --remote=gcdcnuutczpjdaunaigt --dry-run
npx supabase@3.2.0 db push --remote=gcdcnuutczpjdaunaigt
npx supabase@3.2.0 functions deploy accidents --remote=gcdcnuutczpjdaunaigt --use-api
```

로컬 개발 서버의 실행 방법과 설정은 [README](../README.md)에 있다. 로컬 사고 API와 클라우드 함수가 사용하는 사고 데이터 형식은 [0.7 위치 계약](contracts/accident-location-v07.md)을 참고한다.
