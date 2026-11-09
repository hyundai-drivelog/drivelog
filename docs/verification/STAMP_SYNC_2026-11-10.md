# 차량 → 서버 → 모바일 스탬프 연동 (2026-11-10)

## 구현

- 차량: 기존 `JourneyStore`의 오프라인 전송 대기열을 사용한다. 토큰 없이 저장한 기록은 연결 시 기존 대기열에 없는 것까지 전송한다. `phoneLinkEnabled=false` 기본 제출 모드는 전송하지 않는다.
- 서버: 인증된 `GET /api/v1/stamps`. 저장 기록 전체에서 첫 방문을 계산한다. `/journey` 최근 200건 한도는 적용하지 않는다. 기존 기록 DB가 원본이므로 새 테이블은 없다.
- 목록: 차량의 `app/stamps/catalog.json`을 서버 `processResources`에서 `stamps/catalog.json`으로 포함한다. 서버 프로젝트만 별도로 복사해 배포 소스를 만들 경우에도 이 원본 파일이 필요하다.
- 판정: 실제 주행, 거리 0.1km 이상, 경로 2점 이상, 마지막 관측 위치에서 1km 이내인 가장 가까운 명소 하나. 중복은 최초 주행 시작 시각 기준 한 건. 기록 삭제 후 남은 기록으로 재계산한다. 차량의 기존 날짜 표시 의미를 유지한다.
- 서버의 기존 SQL 매핑이 `distance_km`을 읽은 뒤 다른 컬럼을 읽고 `wasNull()`을 호출하던 문제를 함께 수정했다. 거리 미상은 null로 유지하고 방문에서 제외한다.
- 모바일 경로: `D:\AndroidDev\drivelog-mobile`. `DriveLogApi`, `MobileModel`, `RecordSyncWorker`에 스탬프 조회를 추가하고 `StampCache`·`StampScreen`을 추가했다. 홈의 준비 중이던 도전과제 대신 `내 스탬프`를 연결했다. 기존 테마·페이지 간격·머리글과 파일 캐시 구조를 재사용한다. 라이브러리 추가는 없다.
- 모바일은 차량 `StampArt.kt`의 동일한 Canvas 선화·이중 원형 테두리를 사용한다. 서버의 `place.art`와 `place.tilt`로 그림과 명소별 기울기를 적용하고 장소·지역·설명·첫 방문 날짜를 함께 표시한다. 그림은 앱에 포함되어 오프라인에서도 표시한다. 구버전 캐시에 art·tilt가 없으면 ID·기울기 0으로 처리한다.
- 캐시는 기존 토큰 해시별 디렉터리의 `stamps.json`이다. 네트워크 오류 시 기존 파일과 화면 데이터를 유지한다. 계정 토큰 변경 시 화면을 초기화한다. 데모는 실제 스탬프를 표시하지 않는다.
- 앱 전경 복귀·기록 동기화·스탬프 화면 진입·새로고침과 15분 WorkManager 주기 조회에서 갱신한다. 즉시 푸시는 없으며, 스탬프 알림도 추가하지 않았다. 기록 삭제는 모바일의 다음 성공한 조회 때 반영된다.

## 검증

- 모바일: `:app:assembleDebug :app:testDebugUnitTest` 성공.
- 차량: `:app:assembleDebug :app:testDebugUnitTest -PphoneLinkEnabled=true` 성공. Pleos SDK 포함.
- 서버: `test` 성공. H2 demo 프로필의 실제 HTTP 컨트롤러·DB 흐름으로 차량 업로드 → 모바일 스탬프 조회, 중복 전송, 인증 누락, 다른 계정 격리, 시뮬레이션·null 거리 제외, 기록 삭제 반영을 확인했다. 별도 판정 테스트로 0.1km 경계·첫 방문 선택·다음 방문으로 대체·범위 밖 도착도 확인했다.
- 한글 저장소 경로에서는 Gradle 테스트가 `ClassNotFoundException`으로 실행되지 않았다. 임시 init script `D:\AndroidDev\drivelog-stamp-verification.init.gradle`로 빌드 출력만 ASCII 경로에 두고 차량·서버 테스트를 실행했다. 저장소 원본 위치는 유지했다.
- 차량 APK: `D:\AndroidDev\drivelog-stamp-verification\vehicle-app\outputs\apk\debug\app-debug.apk`.
- 모바일 APK: `D:\AndroidDev\drivelog-mobile\app\build\outputs\apk\debug\app-debug.apk`.
- 이번에는 앱을 에뮬레이터나 실제 폰에 설치하거나 서버를 배포하지 않았다. 기기 UI, 오프라인 재실행, WorkManager 실제 실행과 실차 GPS 판정은 런타임 검증이 남아 있다. PostgreSQL 운영 DB 검증과 소셜 계정 인증은 이번 범위가 아니다.

## 적용

1. 새 서버를 빌드·실행한다. 기존 DB와 계정은 유지한다. Docker가 준비되지 않은 로컬 개발에서는 기존 `demo` 프로필을 사용할 수 있지만 demo DB는 운영 DB와 별개다.
2. 차량은 `-PphoneLinkEnabled=true`로 빌드하고, 두 앱을 업데이트한다. 진행 중인 차량 기록이 있으면 설치 전에 보존 여부를 확인한다.
3. 기존 연결 토큰은 계속 사용할 수 있다. 연결 전에 보관한 차량 기록을 새로 전송하려면 차량에서 휴대폰 연결을 다시 진행하고 모바일의 기존 토큰으로 6자리 코드를 입력한다.
4. 새 기록 또는 대기 기록의 서버 전송을 기다린 뒤 모바일 홈 `내 스탬프` → `새로고침`으로 확인한다. 시뮬레이션 주행에는 스탬프가 생기지 않는다.
5. 에뮬레이터 개발 기본 주소는 `http://10.0.2.2:8080`이다. 실제 폰에서는 차량과 폰 모두 접근할 수 있는 HTTPS 서버를 사용한다. 차량 release는 `-PreleaseApiBaseUrl=https://호스트/`, 모바일 release는 `-PreleaseApiUrl=https://호스트`로 설정한다.

현재 사용자 요청에 따라 모바일의 보조 방문 기록으로 추가했다. 사진 한 장으로 주행 카드를 만드는 기존 중심 흐름과 3곳 목표·진행률을 요구하지 않는 구조는 유지했다.
