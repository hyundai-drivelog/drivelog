# DRIVELOG 상태 사운드

2026-11-11 사용자 요청으로 짧은 상태 효과음을 추가했다. 모든 음원은 `scripts/generate-sounds.py`에서 사인파·배음·부드러운 어택/릴리스로 직접 합성한다. 외부 샘플·라이선스 음원을 포함하지 않는다.

| 음원 | 길이 | 적용 |
|---|---|---|
| [시작](../../app/src/main/res/raw/sound_start.wav) | 320ms | 실제 기록 시작 후. 내비 안내가 없을 때 기존 음성 안내 |
| [저장](../../app/src/main/res/raw/sound_saved.wav) | 440ms | 주행·여행의 로컬 저장 성공 후 |
| [확인](../../app/src/main/res/raw/sound_confirm.wav) | 180ms | 장소 저장/해제, 여행에 주행 추가 성공, 효과음 켜기 |
| [오류](../../app/src/main/res/raw/sound_error.wav) | 300ms | 주행·여행 저장 실패 |

배경음·반복음·주행 중 거리별 알림은 추가하지 않았다. 일반 효과음은 정차·앱 전경에서만 요청한다. 음성 중복 제보 후 자동 저장 결과는 화면 안내로 바꾸고, 내비 안내 중 및 도착/종료 후 4초는 앱 음성과 효과음을 생략한다. 내비 화면·녹음 시작과 맞물려 포커스를 잃으면 효과음과 대기 중인 후속 안내는 취소한다. [후속 수정 검증](../verification/NAVIGATION_AUDIO_2026-11-11.md)을 우선한다.

## 재생과 사용자 설정

- 홈 우측 상단 ‘설정 → 효과음’ 스위치로 변경한다. 기본 켜짐이며 앱 재실행 후에도 유지한다. 효과음 설정은 기존 음성 안내 설정을 바꾸지 않는다.
- 44.1kHz·16bit·모노 PCM, 총 약 110KB. SoundPool 스트림은 최대 1개다. 샘플 피크는 0.128~0.149이며 재생 gain은 0.55다. 사용자의 시스템 음량은 변경하지 않는다.
- 재생 전에 sonification 오디오 포커스를 요청하고, 정상 종료·재생 실패·포커스 상실·비활성화 때 반납한다. 연속 요청은 250ms 이내 중복을 생략한다. 로드되지 않은 샘플은 나중에 뒤늦게 재생하지 않는다.
- Pleos TTS 사용 중·준비 중 및 음성 인식 중에는 별도 효과음을 생략한다. 효과음 뒤 음성은 재생 종료 후 요청한다. 효과음이 꺼져 있거나 로드·포커스 요청이 실패하면 기존 음성 안내 경로로 바로 진행한다.
- SDK 없는 시뮬레이션 빌드에도 효과음과 설정을 제공한다.

참고: [Android SoundPool](https://developer.android.com/reference/android/media/SoundPool), [Android 오디오 포커스](https://developer.android.com/media/optimize/audio-focus). 실제 차량의 믹싱과 다른 앱의 오디오 우선순위는 플랫폼 정책을 따른다.

## 검증

- 확장 debug `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug -PphoneLinkEnabled=true --offline` 성공. 단위 테스트 94개, lint 오류 0·경고 63·안내 1. 마지막 진단 로그 추가 후 빌드·테스트도 재실행해 성공했다.
- `:app:compileDebugKotlin -PpleosSdkEnabled=false --offline` 성공.
- 합성 스크립트에서 길이·클리핑 검증. 외부 음원 다운로드 없음.
- emulator-5554 사용자 10에 설치 직전 진행 기록이 없음을 확인하고 비공개 백업 뒤 데이터 유지 업데이트.
- 홈의 켜짐→꺼짐→켜짐 표시, 꺼짐 상태에서 앱 종료·재실행 후 설정 유지 확인. 최종 효과음 켜짐으로 복원.
- 확인음 재생 시 `DriveLogSound: playing CONFIRM; stream=1` 확인. Android audio dump에서 sonification 포커스 요청과 약 0.3초 뒤 반납 확인. 종료 후 포커스 소유자가 비어 있음.
- 기존 주행 3건·여행 1개·6.9204776281268305km 보존. 앞선 아이콘 검증 전 백업과 비교해 전체 journey-store.json 내용도 동일하다. 새 주행을 만들지 않았다.

에뮬레이터에서 확인한 것은 재생 스트림 시작·포커스·설정 동작이다. 실제 스피커 청감·실차 음량·내비/통화와의 실제 혼합, 기록 시작/저장 모든 이벤트의 재생과 TTS 순서는 이번에 귀로 검증하지 않았다. 원본 WAV는 위 링크에서 따로 들어볼 수 있다. 캡처와 비공개 백업은 Git 제외 `.local-qa/sounds-2026-11-11/`에 보관한다.
