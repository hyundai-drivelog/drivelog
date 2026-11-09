# DRIVELOG

Pleos Connect 차량 앱(`app/`), Spring 서버와 모바일 웹(`server/`). 휴대폰 앱은 별도 저장소 `hyundai-drivelog-mobile/drivelog-mobile`(6자리 코드로 연결). QR 카드 웹 페이지(`web/`)는 2026-11-10에 뺐다.

설계나 API 연동을 바꾸기 전에 반드시 읽는다.

- [docs/guides/PLEOS_DESIGN_PRINCIPLES.md](docs/guides/PLEOS_DESIGN_PRINCIPLES.md): Pleos 설계 원칙, PRD에서 바뀐 결정, 미결 사항
- [docs/guides/PLEOS_API_INTEGRATION.md](docs/guides/PLEOS_API_INTEGRATION.md): API 선택 규칙, 퍼미션, NaviHelper 등 연동 방법, 에뮬레이터 검증

핵심 규칙

- 표준 데이터(기어, 속도)는 AAOS Car API, 특화 기능과 내비 연동은 Pleos SDK.
- 경로 안내는 기본 내비에 위임하고, 요청은 사용자가 눌렀을 때만 보낸다.
- 내비의 추천 경로선은 SDK가 좌표를 주지 않아 그리지 않는다. 내비가 준 거리·시간을 보이고 `OverviewRoute`로 내비 화면을 연다. 다른 경로 서비스로 선을 만들지 않는다.
- 시스템 바, 독, 운전 뷰는 플랫폼 소유. 앱에서 그리지 않는다.
- 주행 중에는 기록 화면 또는 잠금 안내만 렌더링한다. 기록 화면은 장소, 분 단위 시간, 거리만 보인다.
- 애니메이션은 정차 중에만 켠다. 새 효과는 `LocalMotionEnabled`를 확인한다.
- 실제 차량 데이터가 아닌 값은 화면과 카드에 시뮬레이션으로 표시한다.
- 색상과 컴포넌트 규격은 Pleos 토큰이 목업보다 우선한다. 카드 경로는 흰 선이며 속도별 색은 넣지 않는다.
- 지도는 정차 중에만 받는다. 경로 좌표는 외부 서비스로 보내지 않고 기기에서 그린다.
- 휴대폰 전달은 사용자가 QR을 띄웠을 때만, 카드 페이지 주소의 `#` 뒤에 담아 그 휴대폰으로만 보낸다. `cardWebUrl` 없이 빌드하면 QR을 보이지 않는다.
- 음성 인식 결과는 검색창에만 넣는다. 내비 검색은 사용자가 `찾기`를 눌렀을 때만 연다.
- 실제 연동 없는 목업 화면(휴대폰 미리보기, 추천 코스)은 debug 빌드에서만 보이고, 서버나 저장 기록을 바꾸지 않는다.
- 커뮤니티에 공개하는 코스는 경로 앞뒤 500m를 자르고 출발지를 동네 단위로만 표시한다.
- release 서명 키와 `keystore.properties`는 git에 넣지 않는다. 제출 APK는 `:app:packageSubmissionApk -PsubmissionTeam=<팀명>`으로 만든다.

에뮬레이터 작업

- 재설치(`adb install -r`)는 진행 중인 기록을 끊는다. 설치할 때마다 직전에 `files/recording`이 비어 있는지 확인하고, 비어 있지 않으면 멈추고 사용자에게 묻는다. 사용자도 에뮬레이터를 쓰므로 세션 중 한 번 확인한 것으로 끝내지 않는다.
- 호스트 마이크(`adb emu avd hostmicon`)는 주변 소리까지 인식한다. 사용자에게 알리지 않고 켜지 않는다.
- 복구 화면의 보관과 삭제는 사용자의 결정이다. 시험 기록과 안내는 시험 후 지운다.
- 주행 재현은 `scripts/demo-drive.ps1 -Prepare`로 시작 위치를 옮긴 뒤 기록을 시작하고, 다시 `-Prepare` 없이 실행한다. 순서가 바뀌면 거리가 0.0km로 남는다.
- 에뮬레이터가 시작하자마자 꺼지면 숨은 충돌 보고 창을 확인한다. 게임 보안 프로그램이 켜져 있으면 실행되지 않았다. 좁은 창 확인에 `wm size`는 쓰지 않고 창 바로 분할한다.
