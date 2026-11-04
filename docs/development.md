# 개발 환경

확인일: 2026-11-04 (KST)

## 빌드 버전

AGP 8.8.2와 Gradle 8.10.2는 Pleos Vehicle 공식 샘플의 빌드 구성을 참고했습니다.
공식 샘플의 버전 카탈로그에는 Vehicle 2.3.1이 들어 있었으나 해당 Maven artifact가 404를 반환하여,
공식 문서의 기준 버전이며 Maven 배포를 확인한 2.3.2로 고정했습니다.
Kotlin은 2.0.21로 고정했습니다. 공식 샘플의 minSdk 23 대신 공모전 개발 가이드의 minSdk 28을 적용했습니다.

- 참고한 Vehicle 샘플 commit: `959981d960ec71144accb27af5ae5d341e72a644`
- 참고한 NaviHelper 샘플 commit: `76ed592487a1eab7ba102b9d4b321705426886fe`
- Gradle 배포 ZIP과 Wrapper JAR의 SHA-256을 공식 checksum과 대조했습니다.

## Android Studio

1. `android/`를 프로젝트로 엽니다.
2. Settings → Build, Execution, Deployment → Build Tools → Gradle에서 기존 JDK 17을 선택합니다.
3. SDK Manager에서 Android API 35 및 Build Tools 35.0.0을 확인합니다.
4. Gradle Sync 후 Build Variants에서 `demoDebug` 또는 `pleosDebug`를 선택합니다.

프로젝트 소유자의 Mac 빌드 설정:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export GRADLE_USER_HOME=/Users/yeongj/.gradle
export ANDROID_HOME=/Users/yeongj/Library/Android/sdk
```

새 JDK를 설치하거나 작업별 Gradle home을 만들지 않습니다. 다른 팀원의 OS에서는 기존 JDK 17과 Android SDK 경로를 사용합니다.

## Pleos 에뮬레이터

[공식 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator) 기준입니다.

1. SDK Manager → SDK Update Sites에 Pleos Connect System Image를 추가합니다.
2. Mac ARM용 주소: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
3. Windows/Linux용 주소: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
4. SDK Platforms에서 Show Package Details를 켜고 API 34의 Pleos Connect 시스템 이미지를 설치합니다.
5. Device Manager → Create Device → Automotive → Pleos Connect v2.1.0을 선택합니다.
6. RAM 4 GB, Cold boot, API 34 Pleos 이미지를 지정합니다.
7. 에뮬레이터를 시작하고 `pleosDebug`를 실행합니다.

`compileSdk/targetSdk = 35`는 빌드 및 앱 동작 정책 설정이고, 에뮬레이터의 API 34와는 별개입니다.
실제 대상 장비에서 동작 검증은 필요합니다.

## 실행 검증

### Demo

```sh
cd android
./gradlew installDemoDebug
```

- 앱이 열리고 샘플 데이터 표시가 보이는지 확인합니다.
- 문 상태 조회 시 샘플임을 명시한 값이 표시되어야 합니다.
- Supabase 미설정 상태에서도 앱이 실행되어야 합니다.

### Pleos

```sh
cd android
./gradlew installPleosDebug
adb forward tcp:8088 tcp:8088
```

브라우저에서 `http://127.0.0.1:8088/`를 열면 [Virtual Console](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)을 사용할 수 있습니다.

- 문 상태 조회에서 capability·GET 응답을 확인합니다.
- Virtual Console에서 운전석 문 상태를 바꾸고 앱에서 다시 조회합니다. 현재 자동 구독은 구현하지 않았습니다.
- 미지원 기능·권한 오류·서비스 연결 실패·8초 시간 초과가 성공처럼 표시되지 않는지 확인합니다.
- 이 개발 화면은 정차된 테스트 환경용입니다. 서비스 기능 개발 때 `CarUxRestrictions`와 앱 카테고리 정책을 적용합니다.

### 백엔드

[Supabase 안내](backend.md)에 따라 health 함수를 배포하고 URL을 설정합니다.
네트워크가 켜진 상태에서 health 응답을 확인한 뒤 네트워크를 끄고 실패가 표시되는지 확인합니다.
차량 SDK 조회와 서버 연결 버튼은 서로 독립적으로 동작해야 합니다.

## SDK 범위

공개 신청 문서상 Vehicle State와 NaviHelper는 신청 없이 사용 가능하고,
Vehicle Control·FusedLocation·TTS·STT는 준비 중으로 안내되어 있습니다.
팀 계정의 실제 권한, 차량 capability, 공모전 장비 지원 범위는 별도로 확인해야 합니다.
