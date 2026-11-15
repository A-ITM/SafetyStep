# Supabase 백엔드 방향

2026-11-14 기술 프로토타입에는 loopback Deno 서버와 공유 사건 검증 로직을 추가했습니다.
[현재 동작과 서버의 경계](prototype.md#로컬-서버의-경계)를 참고하세요. 아래 내용은 향후 Supabase 클라우드 연결 방향입니다.

제품 단계에는 Supabase를 사용해 서버 운영 부담을 줄이는 구성을 권장합니다.
클라우드 프로젝트는 아직 연결하지 않았습니다. 데이터 모델과 로그인 흐름은 서비스 주제가 정해진 뒤 추가합니다.

| 처리 | 위치 |
| --- | --- |
| Pleos SDK 연결, 차량 상태 조회, 즉시 필요한 판단 | Android 앱 |
| 최근 결과 캐시, 통신 단절 시 기본 화면 | Android 앱 |
| 사용자 인증 | Supabase Auth |
| 사용자 설정·공유 기록 | Supabase Postgres + RLS |
| 외부 API 비밀키가 필요한 요청, 짧은 추천 처리 | Supabase Edge Functions |

앱에서 모든 차량 상태를 계속 서버로 보내는 구조는 기본값으로 두지 않습니다.
제품 기능에 필요한 이벤트와 데이터만 전송하도록 설계합니다.

## 지금 준비한 것

- `supabase/config.toml`: 함수 설정
- `supabase/functions/health/index.ts`: 데이터 접근이 없는 공개 GET endpoint
- 앱의 `BackendHealthClient`: HTTPS health 호출, 타임아웃, 오류 표시
- `android/developer.properties.example`: 프로젝트 URL 주입 예시
- CI: Deno 형식·lint·타입 검사

현재 health 함수는 사용자 인증, DB, Storage, 유료 API를 호출하지 않습니다.
`verify_jwt = false`는 이 공개 연결 확인 함수에만 적용합니다.
사용자 데이터나 유료 API를 처리하는 함수는 별도로 사용자 인증·권한 확인을 구현해야 합니다.

## 클라우드 연결 순서

팀 Supabase 프로젝트의 소유자와 리전을 정하고 프로젝트를 생성합니다.
아래 `YOUR_PROJECT_REF`는 실제 프로젝트 ID로 바꿉니다. CLI는 공식 설치 가이드를 따릅니다.

```sh
supabase login
supabase functions deploy health --project-ref YOUR_PROJECT_REF
```

배포 후 다음 요청으로 확인합니다.

```sh
curl --fail https://YOUR_PROJECT_REF.supabase.co/functions/v1/health
```

예상 응답:

```json
{"status":"ok","service":"pleos-app"}
```

`android/developer.properties`에 설정한 뒤 다시 빌드합니다.

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
```

health 확인에는 API key가 필요하지 않습니다. 이후 DB/Auth를 붙일 때는 Android 앱에
publishable key만 사용하고, 사용자별 접근은 Auth와 RLS로 제한합니다.
secret key, 기존 service_role key, DB 비밀번호, 외부 AI API key는 앱에 넣지 않습니다.
빌드 시 환경변수로 넣어도 APK 안의 값은 읽을 수 있습니다.

Kotlin 연동 라이브러리 `supabase-kt`는 Supabase 문서에 소개되어 있으나 커뮤니티 유지보수 라이브러리입니다.
Auth/DB 연동을 추가할 때 Kotlin·Ktor 호환 버전을 함께 고정합니다. 현재 starter는 단순 HTTPS만 사용합니다.

## 함수 로컬 검사

기존 Deno가 있다면 프로젝트 루트에서 실행합니다.

```sh
deno fmt --check supabase/functions
deno lint supabase/functions
deno check --config supabase/functions/deno.json supabase/functions/health/index.ts
deno run --allow-net --config supabase/functions/deno.json supabase/functions/health/index.ts
```

마지막 명령은 순수 health 핸들러를 로컬 Deno 서버로 실행합니다.
Supabase 게이트웨이·JWT·DB 동작은 재현하지 않습니다. 전체 Supabase 로컬 스택은 CLI와 Docker가 필요하며,
단순 Android 개발과 health 정적 검사에는 필요하지 않습니다.

Edge Functions는 외부 API I/O 중심의 짧은 요청에 사용합니다. 무거운 모델 추론이나 긴 CPU 연산이
필요해지면 별도 실행 환경을 검토합니다. 현재 공식 제한은 메모리 256 MB, 요청당 CPU 2초입니다.
요금제·용량·프로젝트 정지 정책은 실제 배포 시점에 확인합니다.

## 공식 문서

- [Kotlin 연동](https://supabase.com/docs/reference/kotlin/introduction)
- [API key 구분](https://supabase.com/docs/guides/getting-started/api-keys)
- [Row Level Security](https://supabase.com/docs/guides/database/postgres/row-level-security)
- [Edge Functions 시작하기](https://supabase.com/docs/guides/functions/quickstart)
- [함수별 설정](https://supabase.com/docs/guides/functions/function-configuration)
- [실행 제한](https://supabase.com/docs/guides/functions/limits)
