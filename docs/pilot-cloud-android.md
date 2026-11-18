# Android 클라우드 파일럿 연결

기본 실행은 기존 loopback 로컬 시험이다. 클라우드는 앱 개인 파일로 명시적으로 선택한다. 선택한 파일이 잘못됐거나 세션이 만료되어도 로컬 서버로 전환하지 않는다. 파일럿은 debug 앱의 전면 실행과 실제 Supabase Auth 사용자 두 명으로 시험한다. 운영용 자동 기동이나 긴급 신고 서비스는 아니다.

## 사용자와 서버 준비

- 같은 Supabase 프로젝트에 서로 다른 Auth 사용자 두 명을 만든다. 이메일 로그인이 가능하도록 확인하고 서버의 파일럿 허용 목록에서 사용자 UUID를 A/B에 각각 연결한다. A는 `pleos`, B는 `demo` 앱이다.
- `accidents` Edge Function과 데이터베이스 변경을 적용한다. `GET /functions/v1/accidents/session`은 사용자 JWT를 검증하고 허용된 차량의 `{ "owner": "A" }` 또는 `{ "owner": "B" }`를 반환해야 한다. 다른 사용자는 거절해야 한다.
- 프로젝트의 공개 `sb_publishable_...` 키만 사용한다. 서비스 키·관리자 키·DB 비밀번호를 앱 설정이나 명령 인수에 넣지 않는다.

## 주차한 시험 장치에 연결

저장소 루트에서 실행한다. 스크립트는 이메일·비밀번호를 숨김 입력받아 Auth에 로그인하고 `/auth/v1/user` 및 `/functions/v1/accidents/session`으로 사용자·차량을 확인한다. 비밀번호와 refresh token은 장치에 저장하지 않는다.

```sh
python3 scripts/provision-pilot.py \
  --serial YOUR_DEVICE_SERIAL --user 10 --owner A \
  --url https://YOUR_PROJECT_REF.supabase.co \
  --publishable-key sb_publishable_YOUR_PUBLIC_KEY
```

B 장치에서는 `--owner B`를 사용한다. `--user`를 생략하면 `am get-current-user`를 조회한다. Pleos 자동차 AVD는 보통 사용자 10이며 일반 휴대전화는 보통 0이다. 선택한 사용자에 해당 debug 앱이 설치돼 있어야 한다.

연결 후 앱을 다시 연다. 주차 화면의 설정에는 `클라우드 파일럿 · A/B`와 대상 프로젝트, 세션 진단을 표시한다. 앱도 첫 요청 전에 `/session`을 검증한다. 이전 로컬 지원 사건은 클라우드에서 새 좌표를 붙여 다시 공유하지 않는다. 새 파일럿 사고 입력을 시작한다. 같은 클라우드에 저장된 전송 대기는 같은 사용자로 재연결하면 유지된다.

자동 검수 호출자는 `--credentials-stdin`으로 `{ "email": "...", "password": "..." }` JSON을 subprocess의 비공개 stdin에 전달할 수 있다. 비밀번호를 셸 인수나 로그에 넣지 않는다. Python의 `provision_document(origin, key, owner, email, password)` 함수도 같은 검증 후 설정 객체를 반환한다. 반환값에는 토큰이 있으므로 출력하거나 저장소 파일로 저장하지 않는다.

스크립트는 선택한 Android 사용자에 `am force-stop --user N`을 실행하고 `run-as PACKAGE --user N`으로 원자적으로 개인 파일을 쓴 뒤 같은 개인 디렉터리와 파일 크기를 확인한다. 토큰은 stdin으로만 이동하고 명령 인수·stdout·호스트 파일에는 쓰지 않는다.

## 세션·저장 범위

앱의 `no_backup/safetystep-pilot.json`에 다음 런타임 필드가 들어간다. APK에는 사용자 자격 증명이 포함되지 않는다.

```text
mode=cloud
supabaseUrl=https://PROJECT.supabase.co
publishableKey=공개 publishable 키
owner=A 또는 B
userId=Auth 사용자 UUID
accessToken=서버에서 검증한 사용자 access JWT
provisionedAt=주입 시각(epoch ms)
expiresAt=min(JWT 만료, 주입 시각+1시간)(epoch ms)
```

프로젝트 JWT가 더 길어도 장치 세션은 최대 1시간이다. 더 짧은 JWT는 원래 만료를 따른다. 만료 후 동일한 스크립트로 다시 로그인하고 앱을 연다. 앱은 refresh token을 보관하거나 자동 갱신하지 않는다. `401/403`, 만료 및 사용자 불일치는 재연결 가능한 오류로 취급하여 전송 대기 본문을 영구 거절로 바꾸지 않는다. 5분이 지난 사고를 새 사고로 바꾸거나 위치·발생 시각을 갱신하지 않는다.

사고 outbox, 등록 ACK, 경고 음성 완료 기록은 로컬 또는 `프로젝트 origin + A/B + 사용자 UUID`별로 분리한다. 기존 `accident-location-v07` 로컬 저장은 보존한다. 클라우드 선택 시 기존 TEST 구간의 로컬 전송/조회도 실행하지 않는다. 수신 좌표와 토큰을 새 원격 저장소나 로그에 저장하지 않는다.

## 로컬 모드로 명시적으로 복귀

```sh
python3 scripts/provision-pilot.py --serial YOUR_DEVICE_SERIAL --user 10 --owner A --local
```

이 명령은 앱을 중지하고 클라우드 설정 파일만 제거한다. 앱을 다시 열면 로컬 기본 모드가 된다. 클라우드 outbox를 로컬로 이동하지 않는다. 단순 오류나 만료는 이 명령을 대신 실행하지 않는다.

## 진단과 검수

- `PILOT_CONFIG_INVALID`: 설정 형식·HTTPS origin·publishable 키·사용자 JWT·차량 flavor를 확인한다. 민감한 설정 파일을 화면이나 로그로 출력하지 않는다.
- `PILOT_SESSION_EXPIRED`: 다시 로그인하여 주입한다. 장치 시각도 확인한다.
- `PILOT_OWNER_MISMATCH`: 서버 허용 목록과 A/B 사용자·앱 flavor 연결이 다르다.
- `PILOT_AUTH_REQUIRED`: 사용자 JWT, 공개 키, 함수의 사용자 검증 및 허용 목록을 확인한다. 로컬로 재시도하지 않는다.
- 새 클라우드 연결 직전의 로컬 사건은 전송하지 않는지, 같은 클라우드 세션을 다시 연결하면 같은 ID/본문으로 재시도하는지 확인한다.
- A/B 장치의 실제 현재 좌표로 가까운 사건과 먼 사건을 구분하고, 공유 OFF·세션 만료·네트워크 단절 때 확인되지 않은 상태가 유지되는지 확인한다.

공식 근거: [Supabase 비밀번호 인증](https://supabase.com/docs/guides/auth/passwords), [API 키](https://supabase.com/docs/guides/getting-started/api-keys), [JWT](https://supabase.com/docs/guides/auth/jwts), [Edge Function 인증](https://supabase.com/docs/guides/functions/auth).
