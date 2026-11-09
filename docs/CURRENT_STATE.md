# 다음 세션용 현재 상태

## 최신 인계 — 커밋·PR 준비 및 로컬 연동 완료 (2026-11-10)

이 절이 아래 이전 인계의 미설치·Docker 미확인 설명보다 우선한다. 차량·서버는 `feature/drive-card-redesign`, 모바일은 `feature/car-link` 브랜치에서 작업한다. 두 저장소의 코드·문서 변경을 각각 커밋하고 main 대상 PR로 제출한다. PR은 병합하지 않는다.

- PostgreSQL Compose 컨테이너 `drivelog-local-postgres-1`(17.11, healthy)과 local 프로파일 서버(8080)를 실행했다. `/actuator/health`와 `/api/v1/health`가 UP이었다. 기존 Docker 컨테이너·볼륨이 없는 것을 확인한 뒤 새 DB를 만들었으며, 자격 증명은 Git 제외 `server/.env`에만 있다. 초기화하거나 비밀번호를 재생성하지 않는다.
- AVD `DRIVELOG_Pleos`, `emulator-5554`, Android 사용자 10에서 차량·모바일 앱을 6자리 코드로 연결했다. 차량은 `-PphoneLinkEnabled=true` 디버그 빌드다. 모바일 앱은 데이터 유지 업데이트했다. 실제 휴대폰은 연결되지 않았고, 동일 에뮬레이터 내 두 앱 간 검증이다.
- 사용자 요청으로 광안리 → 해운대 약 5.6km·4분을 에뮬레이터 위치·기어로 재현해 저장했다. 목적지 이름은 `베니키아호텔 해운대`, 도착 좌표로 인정된 스탬프는 `해운대 해수욕장`이다. 차량 1/56·서버 동기화 완료·모바일 최근 기록 및 첫 방문 도장을 확인했다. 실제 차량 주행으로 설명하지 않는다. 요청한 기록과 스탬프는 보존하고 기어는 Park로 돌렸다.
- 홈의 `내 스탬프`에 남은 `준비 중` 문구는 실제 화면 동작과 불일치하는 후속 UI 정리 사항이다. 클릭하면 정상 조회된다. 문구만으로 구버전 설치라고 판단하지 않는다.
- PR 준비 시 차량 `:app:assembleDebug :app:testDebugUnitTest -PphoneLinkEnabled=true`, 서버 `test bootJar`, 모바일 `:app:assembleDebug :app:testDebugUnitTest`가 모두 성공했다. Gradle은 기존 결과를 UP-TO-DATE로 재사용했다. 차량·서버는 아래 ASCII 출력 init script를 사용했다.
- 다음 세션은 두 PR의 리뷰·CI를 확인하고, 실제 휴대폰의 HTTPS 접근, 사진 업로드·PNG 내보내기, 오프라인·WorkManager·실차 GPS 검증을 이어간다. 기존 테스트 기록을 지우거나 차량 앱을 재설치하기 전에는 진행 기록을 매번 확인한다.
- 개인 IDE 설정 `.idea/`, 로컬 자격 증명·DB·업로드, 제출용 `apk/` 바이너리는 소스 PR에 포함하지 않는다. 로컬 파일은 보존한다.

### 재개 명령과 로컬 산출물

1. `server/`에서 `docker start drivelog-local-postgres-1`로 기존 DB를 시작한다. 새 환경에서는 README의 Compose 설정 절차를 따른다.
2. 서버 실행 전에 `server/.env`의 DB 환경변수를 현재 프로세스에 안전하게 주입한다. Spring은 `.env`를 자동 로딩하지 않는다. JDK 21로 아래 JAR를 `server/` 작업 디렉터리에서 local 프로파일로 실행한다. 이미 8080이 UP이면 중복 실행하지 않는다.
3. 두 앱의 에뮬레이터 서버 주소는 `http://10.0.2.2:8080`이다. 기존 연결 토큰과 DB를 유지하면 다시 페어링할 필요가 없다.
4. 주행 재현은 `scripts/demo-drive.ps1 -Serial emulator-5554 -Prepare` → 앱 기록 시작 → 같은 스크립트를 `-Prepare` 없이 실행 → Park에서 저장 순서다. PowerShell 정책이 막으면 해당 프로세스에만 `-ExecutionPolicy Bypass -File`을 사용한다. 사용자 요청 없이 새 주행을 만들지 않는다.

- init script: `D:/AndroidDev/drivelog-stamp-verification.init.gradle`
- 서버 JAR: `D:/AndroidDev/drivelog-stamp-verification/server-drivelog-server/libs/drivelog-server-0.0.1-SNAPSHOT.jar`
- 서버 로그: `D:/AndroidDev/tmp/drivelog-server/stdout.log`, `stderr.log`
- 도장 화면 캡처: `D:/AndroidDev/tmp/drivelog-server/haeundae-stamp.png` (PC 로컬 자료)

저장일: 2026-11-10. 기존 문서 요약에 이번 세션의 차량·서버·모바일 스탬프 연동과 도장 그림 이식 결과를 반영했다. 아래 스탬프 작업은 실제 코드 변경과 빌드·테스트 결과에 근거하며, 기존 release·에뮬레이터 검증과 구분한다.

## 다음 세션 시작 지점 — 이번 세션 인계

사용자는 Pleos의 스탬프를 자체 서버를 통해 사용자 모바일에 저장하고, 차량 도장 그림도 동일하게 표시하도록 요청했다. 구현·빌드·테스트까지 완료했으며 실제 설치·배포는 하지 않았다. 사용자의 마지막 요청은 이 상태를 다음 세션에 유지하도록 문서에 저장하는 것이다.

### 작업 위치와 핵심 파일

- 차량·서버 저장소: `C:\Users\컴퓨터\StudioProjects\drivelog`.
- 모바일 별도 저장소: `D:\AndroidDev\drivelog-mobile`. 이 저장소의 변경이 차량 저장소 Git에 포함된다고 가정하지 않는다. 모바일 `README.md`, `codex.md`, `docs/MOBILE_DESIGN_SOURCES.md`를 확인했다.
- 차량: `app/src/main/java/com/drivelog/app/core/journey/JourneyStore.kt`의 `setToken`에서 연결 전 로컬 기록도 중복 없이 전송 대기열에 포함한다.
- 서버: `server/src/main/java/com/drivelog/server/domain/journey/StampCollection.java` 신규, `JourneyService.java`, `JourneyController.java`, `server/build.gradle.kts` 수정. 인증된 `GET /api/v1/stamps`를 추가했고 카탈로그는 `app/stamps/catalog.json`에서 직접 패키징한다. DB 마이그레이션은 없다. 거리 null을 잘못 읽던 SQL 매핑도 수정했다.
- 모바일 `app/src/main/java/com/drivelog/mobile/` 아래: `data/DriveLogApi.kt`, `data/RecordSyncWorker.kt`, `feature/MobileModel.kt`, `feature/home/HomeScreen.kt`, `navigation/MobileNavHost.kt` 수정. `data/StampCache.kt`, `feature/journey/StampScreen.kt`, `feature/journey/StampArt.kt` 신규.
- 모바일 홈의 준비 중이던 `도전과제`를 `내 스탬프`로 바꿨다. 기존 사진·주행 카드 중심 흐름을 유지하고 소셜 로그인 없이 기존 차량 연결 토큰으로 조회한다.

### 유지할 계약과 동작

- 실제 주행 거리 0.1km 이상·위치 2점 이상·마지막 관측 위치에서 1km 이내의 가장 가까운 명소 하나를 인정한다. 시뮬레이션·거리 미상·짧은 기록은 제외한다.
- 스탬프 원본은 주행 기록이다. 서버는 계정의 전체 기록에서 최초 방문을 계산하며 `/journey` 최근 200건 제한을 사용하지 않는다. 기록 삭제 시 재계산하고 모바일은 다음 성공한 동기화 때 반영한다.
- 모바일은 토큰 해시별 디렉터리의 `stamps.json`에 응답을 보관한다. 전경 복귀·기록 동기화·스탬프 화면 진입·새로고침·WorkManager 주기 조회로 갱신하며, 통신 실패 시 기존 사본을 유지한다. 즉시 푸시나 스탬프 알림은 추가하지 않았다.
- 도장 그림은 차량 Canvas 선화를 그대로 이식했다. `StampPen` 도형 코드가 원본과 동일함을 비교 확인했다. 서버 `place.art`와 `place.tilt`로 명소 그림·이중 원형 테두리·기울기를 표시한다. 그림 다운로드는 없고 구버전 캐시의 누락 필드는 ID·기울기 0으로 처리한다. 향후 차량 그림을 바꾸면 모바일 이식본도 함께 확인한다.

### 이번에 실제 실행한 검증과 산출물

- 차량: Pleos SDK 포함 `:app:assembleDebug :app:testDebugUnitTest -PphoneLinkEnabled=true` 성공.
- 모바일: 그림 이식 후에도 `:app:assembleDebug :app:testDebugUnitTest` 성공.
- 서버: `test bootJar` 성공, 테스트 45개 실패 0. H2 demo 통합 테스트에서 차량 업로드 → 모바일 토큰 조회·중복 전송·인증·계정 격리·시뮬레이션/null 거리 제외·삭제 반영을 확인했다. 별도 판정 테스트도 추가했다.
- 한글 경로의 Gradle 테스트에서 클래스 로딩 오류가 발생했다. `D:\AndroidDev\drivelog-stamp-verification.init.gradle`로 출력 경로만 ASCII로 바꿔 해결했다. 차량·서버 검증 시 `--init-script D:\AndroidDev\drivelog-stamp-verification.init.gradle`를 사용했다.
- 차량 APK: `D:\AndroidDev\drivelog-stamp-verification\vehicle-app\outputs\apk\debug\app-debug.apk`.
- 모바일 APK: `D:\AndroidDev\drivelog-mobile\app\build\outputs\apk\debug\app-debug.apk`.
- 서버 JAR: `D:\AndroidDev\drivelog-stamp-verification\server-drivelog-server\libs\drivelog-server-0.0.1-SNAPSHOT.jar`.
- 상세 인계: [스탬프 연동 구현·검증·적용법](verification/STAMP_SYNC_2026-11-10.md). 모바일에도 `docs/CURRENT_STATE.md`에 요약을 남겼다.

### 남은 작업과 주의점

- 새 서버 배포, 두 앱 실제 설치, 실제 기기 UI·오프라인 재실행·WorkManager 실행·실차 GPS 판정은 미검증이다. 빌드 성공을 기기 간 최종 검증 완료로 표현하지 않는다. PostgreSQL 운영 DB 검증도 하지 않았다.
- 실제 폰은 접근 가능한 HTTPS 서버가 필요하다. 차량은 `-PphoneLinkEnabled=true`, release 주소는 차량 `-PreleaseApiBaseUrl=https://호스트/`, 모바일 `-PreleaseApiUrl=https://호스트`다.
- 기존 연결 토큰은 유지 가능하다. 연결 전 로컬 기록을 전송하려면 새 차량 버전에서 연결을 다시 진행하고 모바일의 기존 토큰으로 코드 연결한다. 기록 전송 완료 후 모바일 `내 스탬프`에서 새로고침한다.
- Docker 오류는 Virtual Machine Platform 비활성 안내를 확인하고 관리자 PowerShell의 `wsl --install`·재부팅 또는 All-users 설치를 안내한 상태다. 직접 기능을 켜거나 Docker 설치 완료를 확인하지 않았다.
- 작업 시작부터 차량 저장소에 사용자 변경과 `web/` 삭제 등이 있었다. 기존 변경을 되돌리지 않았으며 이 세션에서 커밋·푸시하지 않았다. 다음 세션도 기존 변경을 보존한다.

## 제품과 구현

- DRIVELOG, 차량 표시명은 `여정`. Pleos Connect용 Kotlin·Jetpack Compose 차량 앱이다.
- 목적지 선택 → 기본 내비 안내와 기록 시작 → 주행 중 최소 정보 → 정차 후 기록 저장·드라이브 카드·스탬프가 핵심 흐름이다.
- 기록·복구·경로 다시 보기·구간 기록·내 기록·월별 요약, 전국 17개 지역 56곳 스탬프가 구현돼 있다.
- 기본 제출 범위는 서버 없이 동작하는 차량 앱이다. 커뮤니티 코스 등 실제 연동 없는 목업은 debug 전용이다.

## 최신 검증

- `verification/RELEASE_2026-11-10.md`: 서명된 `apk/DRIVELOG_DRIVELOG_release.apk`(약 70MB), 서명 검증 통과·debuggable 아님. Pleos 에뮬레이터에서 첫 실행부터 검색·기록·저장·카드·스탬프·기록 상세까지 확인했다.
- 기본 release는 `cardWebUrl`과 `phoneLinkEnabled` 없이 빌드돼 QR과 휴대폰 연결 버튼이 없다.
- `verification/STAMPS_NATIONWIDE.md`: 11월 8일 SDK 포함 debug 빌드·단위 테스트 71개·lint 성공. lint 오류 0, 경고 68, 안내 1. 카탈로그 동기화 검사 통과.
- 에뮬레이터 검증과 실차 검증은 구분한다. 기존 검증 결과를 현재 코드에 대한 재검증으로 표현하지 않는다.

## 휴대폰 전환

- 11월 10일 스탬프 연동 추가: 차량의 기존 기록을 최초 연결 때 전송 대기열에 포함한다. 서버 `GET /api/v1/stamps`는 계정의 전체 저장 기록과 차량 공통 카탈로그로 방문을 계산하며, 모바일 홈 `내 스탬프`에서 조회하고 계정별 파일로 보관한다. 검증 범위·실행법은 `docs/verification/STAMP_SYNC_2026-11-10.md`를 따른다.
- 모바일의 도장 그림도 차량 `StampArt.kt`와 동일한 선화로 옮겼다. 서버의 `art`·`tilt`를 캐시에서 읽어 이중 테두리와 명소별 그림·기울기를 표시한다. 외부 이미지 통신은 없다.

- 11월 10일 사용자 결정으로 QR 카드 웹과 소개 페이지(`web/`)를 제거했다.
- 휴대폰은 별도 저장소 `hyundai-drivelog-mobile/drivelog-mobile`의 Android 앱이 맡는다.
- 확장 빌드 `-PphoneLinkEnabled=true`에서 차량 ↔ 서버 ↔ 휴대폰 앱을 연결한다. 6자리 코드 연결과 주행 기록 전달을 검증했다(`verification/PHONE_LINK_2026-11-10.md`).
- 검증 당시 휴대폰 앱도 같은 Pleos 에뮬레이터에 설치했다. 사진 업로드·카드 저장은 직접 누르지 않았고 API 형식만 대조했다.
- 실제 휴대폰 연동에는 접근 가능한 HTTPS 서버와 release 서버 주소 설정이 필요하다. 휴대폰 저장소는 한글 없는 경로에서 빌드했다.
- 서버 없는 QR 전달과 경로 비수집 설명은 과거 QR 방식의 설명이다. 선택적 서버 연동에서는 경로가 서버로 전달되므로 개인정보 설명도 해당 모드와 구분해야 한다.

## 이후 작업에서 유지할 기준

- 표준 차량 데이터는 AAOS Car API, 특화 기능·내비는 Pleos SDK. 길 안내는 기본 내비에 위임하고 사용자 조작으로만 요청한다.
- NaviHelper는 추천 경로 좌표를 제공하지 않는다. 거리·시간과 내비 경로 개요를 쓰며 다른 서비스로 추천 경로선을 만들지 않는다.
- 주행 제한 활성 또는 기어가 P가 아니면 기록 화면이나 잠금 안내만 렌더링한다. 애니메이션은 정차 중에만 허용한다.
- 카드 경로는 기록 GPS의 흰 선이다. 지도 매칭을 위해 경로를 외부에 보내지 않는다. 지도 출처를 유지한다.
- 최상위 Back은 `moveTaskToBack`이다. 시스템 바·독·운전 뷰를 앱이 그리지 않는다.
- 에뮬레이터 재설치 직전에 매번 `files/recording`을 확인한다. 진행 기록이 있으면 멈추고 사용자에게 묻는다. 호스트 마이크는 사용자에게 알리지 않고 켜지 않는다. 복구 기록의 보관·삭제는 사용자가 결정한다.
- release 키·비밀번호는 저장소에 넣지 않는다.

## 남은 사항

- 실차 GPS·주차 위치에 따른 스탬프 판정, Odometer·전비의 실제 값과 해상도.
- `onDestinationArrived`, 저장된 내비 즐겨찾기, 또렷한 목적지 발화 정확도와 실차 마이크.
- 실제 분할 창·다크 모드 전체 흐름·물리 글자 크기·성능 확인. 에뮬레이터의 일부 분할·CPU 측정 기록은 있으나 실차 검증은 아니다.
- 앱 카테고리와 DO 선언에 따른 주행 중 기록 화면 허용 범위 확인.
- 장소 사진의 출처·사용 권한과 일부 예시 지도 배경 정리.
- 모바일 사진 업로드·카드 저장의 실제 검증, 배포용 HTTPS 서버와 설정.
- 사업 계획의 계약·매출·MAU는 가정이며 제휴·영업 검증과 구분한다.

## 문서 우선순위와 불일치

- 최신 사용자 결정과 실제 코드·검증 결과를 우선한다. 설계/API 변경 전에는 guides 두 문서를 읽는다.
- 11월 10일 release·휴대폰 연동 기록을 과거 Phase 문서와 `review/` 평가보다 우선한다. 과거의 release 미검증·사업 문서 부재 등의 지적은 현재에도 그대로 적용되는 문제가 아니다.
- `PRODUCT_BRIEF.md`, `BUSINESS_PLAN.md`, README 일부와 과거 가이드에는 QR 웹·호스팅·서버 없는 전달·모바일 웹 설명이 남아 있다. 이를 현재 제품 결정으로 되돌려 적용하지 않는다.
- 이 요약은 문서 기반 출발점이다. 새 작업에 필요한 부분은 코드와 최신 결과로 확인하고, 상태가 달라지면 갱신한다.

## 로컬 서버 실행 확인 (2026-11-10)

- 사용자 요청으로 Docker PostgreSQL과 백엔드를 실행했다. Docker 엔진 29.9.3 응답을 확인했으며 기존 컨테이너·볼륨·이미지가 없는 상태에서 Compose로 새 DB를 생성했다.
- PostgreSQL: drivelog-local-postgres-1, postgres:17.11-bookworm, 127.0.0.1:5432, healthy. 데이터 볼륨은 drivelog-local_postgres-data다.
- 로컬 DB 자격 증명은 Git 제외된 server/.env에 생성했다. 비밀번호는 문서에 기록하지 않는다.
- 백엔드: JDK 21, local 프로파일, 기존 검증 JAR(D:/AndroidDev/drivelog-stamp-verification/server-drivelog-server/libs/drivelog-server-0.0.1-SNAPSHOT.jar), 포트 8080. /actuator/health에서 UP 및 /api/v1/health 응답을 확인했다.
- 실행 로그: D:/AndroidDev/tmp/drivelog-server/stdout.log 및 stderr.log. 이번 확인은 로컬 기동·DB 연결 확인이며 실기기·운영 배포 검증은 아니다.

## 차량·모바일 로컬 연결 확인 (2026-11-10)

- 차량을 -PphoneLinkEnabled=true로 assembleDebug하여 DRIVELOG_Pleos(emulator-5554, 사용자 10)에 설치했다. 설치 직전 files/recording이 비어 있음을 확인했으며 앱 데이터는 유지했다.
- 같은 Pleos에 기존 설치된 com.drivelog.mobile 앱에서 차량의 새 6자리 코드를 입력해 PostgreSQL 기반 로컬 서버와 연결했다. 모바일의 차량 연결됨 및 차량의 휴대폰 연결 해제 표시를 확인했다.
- 기존 차량 기록 송정 해수욕장(0.0km, 0분) 1건이 모바일 최근 드라이브에 나타났다. 새 주행 생성이나 실차 주행 검증은 하지 않았다.
- 이 확인은 동일 에뮬레이터 내 두 앱 간 연결이다. 실제 Android 휴대폰은 ADB에 연결되어 있지 않아 미검증이다. 기존 설치 모바일은 내 스탬프가 준비 중인 구버전이며 최신 스탬프 APK 적용은 별도다.

## 해운대 스탬프 주행 카드 생성 (2026-11-10)

- 사용자 요청으로 기존 demo-drive.ps1 도로 경로(광안리 → 해운대)를 Pleos 위치·기어 입력으로 재현했다. 앱 자체 시뮬레이션 모드는 사용하지 않았지만 실제 차량 주행이 아닌 에뮬레이터 검증 데이터다.
- 내비 목적지 베니키아호텔 해운대, 최종 경로 위치는 해운대 해수욕장 인근이다. 약 5.6km·4분 기록을 저장하고 차량의 서버 동기화 완료 및 모바일 최근 드라이브 반영을 확인했다.
- 차량 수집 1/56, 모바일 내 스탬프에서 해운대 해수욕장·2026.11.10 첫 방문·도장 표시를 확인했다. 요청한 결과물이므로 기록과 스탬프는 보존했다. 기어는 Park로 복귀했다.
- 모바일 APK를 데이터 유지 설치했다. 홈의 내 스탬프 보조 문구는 여전히 준비 중이지만 클릭 시 실제 스탬프 화면이 열린다. 해당 문구만으로 구버전이라고 판단했던 이전 설명을 정정한다.
- 모바일 assembleDebug 성공. 화면 증거 D:/AndroidDev/tmp/drivelog-server/haeundae-stamp.png. 사진 선택·PNG 카드 내보내기 및 실차 검증은 이번 작업 범위에 포함하지 않았다.
