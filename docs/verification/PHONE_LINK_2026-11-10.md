# 차량 앱 ↔ 서버 ↔ 휴대폰 앱 연동 검증 (2026-11-10)

휴대폰 쪽을 별도 저장소의 Android 앱 [drivelog-mobile](https://github.com/hyundai-drivelog-mobile/drivelog-mobile)(main `46388fe`)로 바꾼 뒤, 세 부분이 실제로 이어지는지 처음부터 끝까지 확인한 기록이다.

## 구조

| 단계 | 차량 앱 (`app/`) | 서버 (`server/`) | 휴대폰 앱 (drivelog-mobile) |
|---|---|---|---|
| 연결 코드 | `POST /api/v1/link/codes`, `GET /link/codes/{poll}`로 연결 여부 확인 | 6자리 코드와 토큰 발급 | — |
| 연결 | 코드 표시 | `POST /api/v1/link/claim` | 6자리 입력 → `claim` |
| 기록 전달 | 저장 시 `PUT /api/v1/records/{id}` (보관함으로 재시도) | 경로 `[위도, 경도, 시각]` 저장 | `GET /api/v1/journey`로 조회 |
| 카드 편집 | — | `PUT /records/{id}/photo`, `PUT /records/{id}/card` | 사진 고르기, 카드 저장 |

세 부분의 API는 이미 같은 형식이었다. 코드 변경은 차량 연결 화면의 안내 문구뿐이다(아래).

## 환경

- 서버: `SPRING_PROFILES_ACTIVE=demo ./gradlew bootRun` (H2 파일 DB, 호스트 8080)
- 차량 앱: debug, `-PphoneLinkEnabled=true` (서버 주소 `http://10.0.2.2:8080/`)
- 휴대폰 앱: debug (기본 서버 `http://10.0.2.2:8080`). 휴대폰 에뮬레이터를 따로 띄울 메모리가 없어 같은 `DRIVELOG_Pleos` 에뮬레이터에 함께 설치했다.
- 휴대폰 저장소는 Android Gradle 플러그인이 한글 경로를 거부해 `D:\AndroidDev\drivelog-mobile`(ASCII 경로)에서 빌드했다.

## 결과

| 단계 | 결과 |
|---|---|
| 차량 홈 `휴대폰 연결` | 연결 코드(6자리, 5분)를 서버에서 받아 표시 |
| 휴대폰 앱 `차량과 연결`에 코드 입력 → `연동하기` | "차량이 연결되어 있어요", 차량 홈 버튼은 `휴대폰 연결 해제`로 바뀜 |
| 차량에서 주행 재현(5.6km) 후 `저장하고 마치기` | "기록이 휴대폰에 도착했어요 · 동기화 완료" |
| 휴대폰 앱 홈 | 최근 드라이브에 `송정 해수욕장 · 5.5 km · 4분`, 어두운 지도 위 경로 |

## 바꾼 것

- 차량 연결 화면(`CarScreens.kt` LinkScreen)의 안내가 "휴대폰 브라우저로 웹 주소에 접속"이었다. "휴대폰에서 DRIVELOG 앱 열기 → 앱의 \"차량과 연결\" 화면에 코드 입력"으로 바꿨다. 서버가 주는 `webUrl`은 더 이상 표시하지 않는다.

## 남은 것

- 제출용 release는 `phoneLinkEnabled` 없이 빌드한 차량 전용이라 연결 버튼이 없다. 심사에서 휴대폰까지 보이려면 휴대폰이 닿는 HTTPS 서버와 `-PphoneLinkEnabled=true`, 그리고 release의 서버 주소 설정이 필요하다.
- 휴대폰 앱의 사진 업로드와 카드 저장은 이번에 누르지 않았다(API 형식만 서버와 대조).
- 시험 후 두 앱의 데이터, 시험용 서버 DB(`server/data/`), 내비 안내를 지웠다.
