# 음성 응답 경로 검증

검증일: 2026-11-15 KST. Pleos Connect v2.1 / Android 34 arm64 / 사용자 10.
TTS·STT SDK 3.3.1, Speech Service가 포함된 CAAS 4.2.10-coral-aosp.

## 결론과 범위

`ON_DEVICE` / `ko-KR`로 직접 호출한 TTS·STT SDK는 현재 에뮬레이터에서 동작한다.
질문 재생 시작·완료 콜백과 합성 한국어 PCM의 최종 인식 텍스트를 받았다.
Wi-Fi·모바일 데이터를 끄고 `Active default network: none`을 확인한 뒤에도
질문 재생 콜백과 동일한 한국어 인식 결과가 나왔다. 테스트 후 네트워크 설정을 복원했다.

후속 시험에서는 사용자가 실제 질문을 들었다고 확인했고, MacBook Pro 마이크를 통한
한국어 발화가 최종 텍스트로 전달되는 것도 확인했다. 아래에 실제 발화 결과와 설정 원인을 기록했다.
이 결과는 Gleo의 음성 경로 복구나 사고 상태 분기까지의 통합 검증을 뜻하지 않는다.
기존 사고 대응 화면의 버튼 입력과 응답 타이머에는 이 진단 기능을 연결하지 않았다.
후속 Gleo 별도 시험에서는 앱 실행 안내의 TTS 완료 콜백을 확인했다.
사용자 요청에 따른 앱 열기, 특정 기능 선택, 앱에서 Gleo 선제 대화 시작은 서로 구분하며
현재 관측은 [Gleo 자동 실행 재검증](airbag-gleo-investigation.md)에 기록했다.

## 구현

- `pleosDebug`에만 TTS·STT 의존성 및 서비스 권한을 추가했다. `demo`와 release에는 추가하지 않았다.
- `SpeechProbeActivity`는 에뮬레이터에서만 실행한다. 차량 제어·사건 생성·외부 신고를 하지 않는다.
- 질문 재생, 마이크 인식, 질문 완료 후 마이크 인식, 합성 PCM 입력을 각각 시험한다.
- 마이크 입력은 화면 버튼으로 시작한다. 자동 실행 인자는 TTS와 합성 PCM만 허용한다.
- 진단 제한 시간은 요청당 15초다. 사용자 무응답 판정 시간이나 의학적 기준이 아니다.
- 화면을 벗어나거나 중지하면 SDK 사용을 중단한다. 진단 로그와 인식 텍스트는 앱 내부 파일에만 저장한다.
- `TTS_SERVICE`와 `STT_SERVICE` 권한의 실제 허용 상태를 확인했다.

## 관측 결과

음성 파일은 macOS의 Yuna 한국어 음성으로 생성한 시험 데이터다.
16 kHz / 16-bit little-endian / mono PCM을 SDK의 `sendAudio()`에 전달했다.
아래 결과로 실제 발화 정확도나 사고 환경 성능을 추정하지 않는다.

| 시험 | 관측 | 판단 |
| --- | --- | --- |
| 온라인 질문 재생 | `TTS_READY → TTS_STARTED → TTS_DONE`, 0보다 큰 RMS | SDK 재생 경로 확인. 청취 확인은 별도 |
| 오프라인 질문 재생 | 동일한 시작·완료 콜백 | 해당 환경의 온디바이스 재생 경로 확인 |
| 온라인·오프라인 합성 음성 | `저는 운전자예요 도움이 필요해요` | 두 조건에서 동일한 최종 텍스트 |
| 응답 확인 문장 | `네 들려요` | 합성 입력 인식 |
| 이동 불가·도움 요청 | 처음 `움직일 수 없어요 도와주세`; 파일 끝에 1초 무음 추가 시 `움직일 수 없어요 도와주세요` | 종료 경계에 따른 말끝 누락 관측 |
| 동승자 응답 | 처음 `저는 동승자예요 운전자는 대답이 없어`; 1초 무음 추가 시 `저는 동승자예요 운전자는 대답이 없어요` | 종료 경계에 따른 말끝 누락 관측 |
| 오프라인 무음 PCM 3초 | 최종 텍스트 없이 진단 15초 시간 초과 | 인식 결과 없음. 탑승자 무응답·의식 소실로 판정하지 않음 |
| 실제 노트북 마이크 | macOS 접근 권한 수정과 에뮬레이터 재실행 후 실제 발화의 최종 텍스트 수신 | 입력 경로 확인. 오인식과 문장 경계 문제는 별도 설계 대상 |

### 실제 발화 및 macOS 권한 원인

사용자가 진단 화면에서 직접 버튼을 누르고 말했으며, 질문이 실제로 들렸다고 확인했다.
첫 시도는 `STT_READY` 이후 매우 작은 입력 값만 관측되고 15초 진단 시간 초과로 종료됐다.
MacBook Pro 마이크 선택, 입력 음량 89, Android 마이크 음소거 해제, 열린 노트북 덮개를 확인했다.

macOS 마이크 설정의 `ChatGPT` 접근 권한이 꺼져 있었다. OS 접근 로그에는 에뮬레이터
`qemu-system-aarch64`의 책임 앱이 `com.openai.codex` / `ChatGPT.app`으로 기록됐고,
마이크 접근 결과는 `authValue=0`이었다. 즉 호스트 입력을 켜는 에뮬레이터 명령이 성공해도
macOS 권한이 자동으로 허용되는 것은 아니다.

사용자의 명시적 승인 후 이 권한을 잠시 켜고 에뮬레이터를 재실행했다.
이후 같은 OS 접근 검사 결과가 `authValue=2`로 바뀌었고 실제 발화 입력이 전달됐다.

| 요청한 시험 문장 | 실제 관측 | 해석 |
| --- | --- | --- |
| `네, 들려요` | 중간 결과 `들려요`, 최종 결과 `틀려요` | 사람의 발화 입력은 확인했지만 정확한 전사는 실패 |
| `저는 운전자예요. 움직일 수 없어요. 도와주세요.` | 앞선 두 시도에서 `저는 운전자에요`로 종료; 마지막 시도는 `저는 운전자의 움직일 수 없어요 도와주세요` | 도움 요청·이동 불가 표현을 포함한 텍스트 수신. 조사 오인식 및 첫 문장 종료 문제 관측 |

진단 코드는 첫 최종 결과를 받으면 `stop()`하므로, 첫 문장이 완료됐다는 이벤트를 전체 답변의
완료로 취급하면 뒤 문장을 놓칠 수 있다. 이를 모두 SDK 인식 성능 문제로 해석하지 않는다.
제품에서는 질문을 나누거나 여러 발화 구간을 수집하는 대화 상태를 별도로 설계해야 한다.
이 시험은 소수의 개발 환경 관측이며 인식 정확도나 실제 사고 대응 성능의 측정이 아니다.

사용자는 이 수준에서 음성 인식 가능성 검증을 마치고, 판정은 별도 구성하기로 했다.
시험 후 에뮬레이터 호스트 마이크 연결을 끄고 macOS `ChatGPT` 마이크 설정도 원래의 꺼짐으로 복원했다.
실행 중 프로세스가 권한을 유지할 수 있다는 macOS 안내에 따라 해당 에뮬레이터를 종료하고,
복원된 권한 상태에서 다시 실행했다. ChatGPT 자체를 종료하거나 다른 앱의 권한을 변경하지 않았다.

### 종료 콜백보다 늦은 최종 텍스트

현재 서비스에서 `onEndedRecognition()` 직후 `onUpdated(..., completed=true)`가 도착했다.
초기 진단 화면은 종료 이벤트에서 세션을 닫아 늦게 온 최종 텍스트를 완료 결과로 반영하지 못했다.
이를 수정하고 동일 입력을 재시험해 `RECOGNITION_COMPLETE`가 기록되는 것을 확인했다.

진단 화면은 종료 후 1초간 최종 결과를 기다린다. 이것은 관측을 위한 임시 유예 시간이며
제품의 응답 판정 기준이 아니다. 실제 대응 기능에는 SDK 콜백 순서와 세션 취소·지연 결과를
명시적으로 다루는 상태 관리가 필요하다. 부분 인식만으로 행동 가능 여부나 신고·제어를 확정하지 않는다.

PCM 뒤의 1초 무음 추가는 시험 파일의 끝 경계를 비교한 실험이다.
이 결과를 실제 마이크 인식의 필수 대기 시간이나 보정 방법으로 일반화하지 않는다.

## 실제 발화 시험

다음 명령은 기존 JDK 17과 `$HOME/.gradle` 캐시를 사용한다.

```sh
./scripts/check-android.sh
adb install -r --user 10 android/app/build/outputs/apk/pleos/debug/app-pleos-debug.apk
adb emu avd hostmicon
adb shell am start --user 10 -n io.github.aitm.pleosapp/.speech.SpeechProbeActivity
```

1. 화면의 `3. 질문 완료 후 마이크 응답 인식`을 누른다.
2. 질문이 실제로 들렸는지 확인하고, 질문이 끝나면 `네, 들려요`라고 말한다.
3. 최종 텍스트를 확인한다. 이어서 새 시험에서 `움직일 수 없어요. 도와주세요`,
   `저는 동승자예요. 운전자는 대답이 없어요`를 각각 시험한다.
4. 무음, 작은 목소리, 주변 대화, 중간에 끊긴 발화, 마이크 차단을 구분해 기록한다.
5. 질문 재생 성공·청취 확인·입력 신호·최종 텍스트·응답 분류·처리 시간을 각각 기록한다.

마이크를 통한 결과가 없으면 먼저 호스트 마이크 접근, 에뮬레이터 입력, SDK 오류를 확인한다.
이를 사람의 무응답으로 바꾸지 않는다. 음성을 들었는지와 사람이 누구인지는 텍스트 수신만으로 보장되지 않는다.

## 기획·통합 구현에 반영할 항목

- 정상적인 질문 재생과 청취 기회를 확보한 뒤 응답 확인을 시작한다.
- 인식 결과 없음, 음성 서비스 오류, 이해하기 어려운 응답을 서로 구분한다.
- `움직일 수 없어요` 같은 부정 표현과 동승자 발화를 별도 검증한다.
- 실제 음성에서 응답자·도움 필요 여부를 분류하는 로직과 사건 상태 전환은 후속 구현·검증 대상이다.
- 긴급 통화의 음성 우선권, 사고에 의한 앱 자동 기동, Gleo에서 이어지는 대화는 이번 시험 범위 밖이다.

## 검증·증거

`./scripts/check-android.sh`: 두 flavor의 빌드·lint 통과, 기존 단위 테스트 7개 × 2 = 14회 통과.
SDK 실행 결과는 위 자동 검사와 별도로 실제 에뮬레이터에서 관측했다.

로컬 증거는 Git에서 제외되는 `android/app/build/speech-validation/`에 저장했다.

- `automated-results.json`: 시나리오별 이벤트와 최종 텍스트.
- `tts-online.jsonl`, `tts-offline.jsonl`: 질문 재생 콜백.
- `stt-pcm-online-fixed.jsonl`, `stt-pcm-offline.jsonl`: 최종 텍스트 처리 수정 후 인식 결과.
- `stt-*.jsonl`, `phrase-results.json`: 문장별 입력 결과와 무음·파일 끝 경계 시험.
- `network-original.json`, `network-offline.txt`, `network-restored.txt`: 연결 차단과 복원 증거.
- `manual-ready.png`: 사람이 시험할 수 있도록 준비한 진단 화면.
- `human-trial-1.jsonl` ~ `human-trial-3.jsonl`: 권한 수정 전후 실제 발화 시험. 3번 파일은 추가 시도들을 함께 포함한다.
- `host-microphone-denial.log`, `host-microphone-after-grant.log`: 에뮬레이터에 적용된 호스트 권한 결과.
- `human-trial-2.png`, `human-trial-3.png`: 실제 발화 인식 결과 화면.
- 합성 음성 파일과 PCM은 동일 폴더에 있으며 사람의 녹음 파일은 생성하지 않았다.

빌드 로그는 `android/app/build/speech-build-check.log`에 있다.

공식 API 근거:

- [SpeechToText 소개 및 ON_DEVICE](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro)
- [TextToSpeech 소개 및 ON_DEVICE](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro)
- [STT 이벤트 리스너](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners)
- [PCM 오디오 전송](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data)
