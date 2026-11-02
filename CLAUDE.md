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
- 실제 차량 데이터가 아닌 값은 화면과 카드에 시뮬레이션으로 표시한다.
