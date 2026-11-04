# A+ ITM · Pleos App

현대자동차그룹 차량용 앱 개발 공모전을 위한 Android 개발 저장소입니다.
서비스 주제는 아직 확정하지 않았으며, 차량 SDK와 서버 연결을 확인하는 개발용 화면부터 준비했습니다.
기획 자료는 [planning-archive](https://github.com/A-ITM/planning-archive)에 모읍니다.

## 기본 구성

| 항목 | 설정 |
| --- | --- |
| Android 앱 | Kotlin 2.0.21 / Jetpack Compose |
| 빌드 | JDK 17 / Gradle 8.10.2 / Android Gradle Plugin 8.8.2 |
| Android SDK | min 28 / compile 35 / target 35 |
| 차량 SDK | Vehicle 2.3.2 / NaviHelper 3.0.5 |
| 백엔드 방향 | Supabase Auth / Postgres / Edge Functions, 기능에 따라 추가 |
| 자동 검증 | Android lint·APK 빌드 / Edge Function 형식·정적 검사 |

```text
android/                 Android Studio에서 열 폴더
  app/src/main/          공통 화면, 상태 관리, HTTPS 연결 확인
  app/src/demo/          샘플 차량 데이터
  app/src/pleos/         Pleos SDK 구현과 차량 권한
supabase/                Edge Functions와 향후 DB 마이그레이션
scripts/                 빌드 검증 스크립트
docs/                    팀 환경 세팅과 백엔드 구성 안내
```

## 시작하기

1. 저장소를 clone하고 Android Studio에서 `android/`를 엽니다.
2. 기존 JDK 17을 Gradle JDK로 지정합니다. 터미널의 `JAVA_HOME`도 같은 JDK로 설정합니다.
3. Android SDK Platform 35와 Build Tools 35.0.0을 준비합니다. SDK 경로는 `android/local.properties` 또는 `ANDROID_HOME`으로 지정합니다.
4. Build Variants에서 `demoDebug`를 선택하면 일반 Android 기기/에뮬레이터에서 개발할 수 있습니다.
5. Pleos 전용 에뮬레이터에서는 `pleosDebug`를 선택합니다. [상세 설치 순서](docs/development.md)를 따릅니다.

프로젝트 루트에서 두 flavor의 lint와 APK 빌드를 실행합니다.

```sh
./scripts/check-android.sh
```

스크립트는 프로젝트 소유자의 Mac에서 기존 Homebrew JDK 17과 `/Users/yeongj/.gradle`을 재사용합니다.
다른 개발 환경에서는 본인의 기존 JDK 17 경로를 `JAVA_HOME`으로 지정합니다.
호스트 빌드에 Docker나 별도 Gradle 캐시가 필요하지 않습니다.

## 두 실행 모드

| Variant | 동작 | 실행 조건 |
| --- | --- | --- |
| `demoDebug` | 샘플 운전석 문 상태를 명시적으로 표시 | API 28 이상 Android 환경 |
| `pleosDebug` | 지원 범위 확인 후 SDK로 운전석 문 상태 조회 | 호환되는 Pleos Vehicle Service |

Pleos 조회 실패를 샘플 데이터로 대체하지 않습니다. SDK 초기화만으로 연결 성공을 표시하지 않으며,
실제 capability·조회 콜백 결과를 표시합니다. 현재는 읽기만 구현했고 차량 제어와 주행 중 사용 허용은 구현하지 않았습니다.
NaviHelper는 빌드 의존성만 준비되어 있으며 실제 경로 요청은 아직 없습니다.

## Supabase 연결

현재는 공개 `health` Edge Function과 앱의 HTTPS 호출 경로만 준비되어 있습니다.
Supabase 클라우드 프로젝트, DB 테이블, 사용자 로그인, 외부 AI 호출은 아직 생성·연결하지 않았습니다.
백엔드를 설정하지 않아도 앱을 빌드하고 차량 기능을 개발할 수 있습니다.

프로젝트를 준비하고 함수를 배포한 다음:

```sh
cp android/developer.properties.example android/developer.properties
```

파일의 `SUPABASE_URL`에 프로젝트 HTTPS 주소를 넣고 앱을 다시 빌드합니다.
앱에서 **서버 연결 확인**을 누르면 `/functions/v1/health`를 호출합니다.
이 확인은 HTTPS와 함수 실행만 검증하며 DB/Auth의 정상 동작까지 의미하지 않습니다.
자세한 역할 분리와 배포 순서는 [Supabase 안내](docs/backend.md)를 참고하세요.

## 팀 작업 방식

- 작업 브랜치에서 수정하고 `main`으로 PR을 만듭니다.
- PR마다 CI가 두 APK를 빌드합니다. 실행 결과의 Artifacts에서 개발용 APK를 받을 수 있습니다.
- `local.properties`, `developer.properties`, `.env`, 서명 키와 빌드 결과물은 Git에 올리지 않습니다.
- 현재 APK는 개발용 debug 서명입니다. 공모전 제출용 서명·버전·배포 방법은 제출 전에 정합니다.
- `io.github.aitm.pleosapp`은 초기 application ID입니다. 앱 등록 전에 최종 값을 확정합니다.

## 확인 상태

2026-11-04 로컬 검증: `demoDebug`·`pleosDebug`의 Android lint와 APK 빌드 통과,
health 함수의 Deno 형식·lint·타입 검사 및 로컬 HTTP 응답(GET 200 / POST 405) 확인.

빌드와 실제 환경 검증은 구분합니다. Pleos 에뮬레이터에서의 SDK 실행 및 Supabase 원격 연결은
해당 환경이 준비된 후 [검증 순서](docs/development.md#실행-검증)를 진행해야 합니다.

## 참고

- [Pleos 개발 환경](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- [Vehicle 공식 샘플](https://github.com/Pleos-Playground/pleos-sample-vehicle-app)
- [NaviHelper 공식 샘플](https://github.com/Pleos-Playground/pleos-sample-navi-app)
- [SDK 제공 상태](https://document.pleos.ai/docs/pleos-only/create-my-project)
- [Supabase Kotlin 문서](https://supabase.com/docs/reference/kotlin/introduction)

Gradle Wrapper는 Gradle 8.10.2 공식 배포 소스를 사용하며 Apache-2.0 라이선스가 적용됩니다.
SDK 라이브러리는 Maven에서 내려받으며 이 저장소에 재배포하지 않습니다.
