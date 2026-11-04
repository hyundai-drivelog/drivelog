# DRIVELOG

Pleos Connect 차량 앱(`app/`), Spring 서버와 모바일 웹(`server/`).

설계나 API 연동을 바꾸기 전에 반드시 읽는다.

- [docs/guides/PLEOS_DESIGN_PRINCIPLES.md](docs/guides/PLEOS_DESIGN_PRINCIPLES.md): Pleos 설계 원칙, PRD에서 바뀐 결정, 미결 사항
- [docs/guides/PLEOS_API_INTEGRATION.md](docs/guides/PLEOS_API_INTEGRATION.md): API 선택 규칙, 퍼미션, NaviHelper 등 연동 방법, 에뮬레이터 검증

핵심 규칙

- 표준 데이터(기어, 속도)는 AAOS Car API, 특화 기능과 내비 연동은 Pleos SDK.
- 경로 안내는 기본 내비에 위임하고, 요청은 사용자가 눌렀을 때만 보낸다.
- 시스템 바, 독, 운전 뷰는 플랫폼 소유. 앱에서 그리지 않는다.
- 주행 중에는 기록 화면 또는 잠금 안내만 렌더링한다.
- 애니메이션은 정차 중에만 켠다. 새 효과는 `LocalMotionEnabled`를 확인한다.
- 실제 차량 데이터가 아닌 값은 화면과 카드에 시뮬레이션으로 표시한다.
- 색상과 컴포넌트 규격은 Pleos 토큰이 목업보다 우선한다.
- 지도는 정차 중에만 받는다. 경로 좌표는 외부로 보내지 않고 기기에서 그린다.
- 음성 인식 결과는 검색창에만 넣는다. 내비 검색은 사용자가 `찾기`를 눌렀을 때만 연다.
- 실제 연동 없는 목업 화면(휴대폰 연동, 추천 코스)은 debug 빌드에서만 보이고, 서버나 저장 기록을 바꾸지 않는다.
- 커뮤니티에 공개하는 코스는 경로 앞뒤 500m를 자르고 출발지를 동네 단위로만 표시한다.

에뮬레이터 작업

- 재설치(`adb install -r`)는 진행 중인 기록을 끊는다. 먼저 `files/recording`이 비어 있는지 확인하고, 비어 있지 않으면 사용자에게 묻는다.
- 호스트 마이크(`adb emu avd hostmicon`)는 주변 소리까지 인식한다. 사용자에게 알리지 않고 켜지 않는다.
- 복구 화면의 보관과 삭제는 사용자의 결정이다. 시험 기록과 안내는 시험 후 지운다.
