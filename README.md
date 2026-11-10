# DRIVELOG

**운전자에게 남는 드라이브 기록, 도착하면 찍히는 스탬프.** Pleos Connect 차량 앱입니다. 목적지를 확인하고 `길 안내 및 기록 시작`을 누르면 기록이 시작됩니다. 정차 후 저장하면 경로 카드와 조건에 맞는 방문 스탬프가 남습니다. 현재 런처·화면 브랜드는 `DRIVELOG`입니다.

**풀려는 문제.** 드라이브에서 운전한 사람에게는 남는 것이 없습니다. 운전 중에 휴대폰 기록 앱을 켤 수는 없고, 내비의 주행 기록은 연비와 운전 점수 위주입니다. 여정은 목적지를 정하는 버튼 하나로 내비 안내와 기록을 함께 시작하고, 주행 중에는 장소·시간·거리만 보이며, 정차하면 경로를 그린 드라이브 카드와 방문 스탬프를 남깁니다. 기본 차량 모드는 기록을 기기에 저장합니다. 선택적 휴대폰 연결 모드에서는 주행 경로를 자체 서버로 동기화하고 Android 앱에서 조회합니다.

타깃, 경쟁 서비스와의 차이, 사업 계획은 [서비스 기획 요약](docs/PRODUCT_BRIEF.md)에 있습니다. 제출 범위는 차량 앱(`app/`)뿐이며, `server/`는 휴대폰 연결 확장 기능용으로 남겨 둔 코드라 심사용 실행에는 필요 없습니다.

## 심사용 실행 방법

**제출 APK:** 이 체크아웃에는 서명 키와 제출 APK가 없습니다. 아래 패키징 명령으로 만든 실제 `apk/팀명_DRIVELOG_release.apk` 파일 또는 GitHub Release 링크를 제출 전에 이 절에 기재해야 합니다. 서명 없는 release 빌드와 debug APK는 제출 완료로 취급하지 않습니다.

**테스트 환경**

- Pleos Connect 에뮬레이터 2.1.0 (Android API 34). 일반 AAOS 이미지에는 Pleos 서비스가 없어 내비, 위치, 음성 기능이 동작하지 않습니다.
- 이미지 설치: Android Studio의 SDK Update Sites에 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`을 추가한 뒤, API 34 Pleos Connect 이미지로 AVD를 만듭니다. RAM은 4GB입니다.
- 내비 검색과 지도에는 인터넷 연결이 필요합니다. 서버는 필요 없습니다.

**설치와 실행**

```powershell
$submissionApk = Get-ChildItem apk -Filter '*_DRIVELOG_release.apk'
# 최종 서명 APK인지 확인한 뒤 설치합니다. 진행 기록이 있으면 설치하지 않습니다.
adb install -r $submissionApk.FullName
```

debug 빌드가 이미 설치되어 있으면 서명이 달라 설치가 거절될 수 있습니다. 앱 삭제는 기록을 지우므로 사용 중인 기기에서 자동 삭제하지 말고 별도 시험 환경을 사용합니다. 첫 기록 전에 앱의 데이터 사용 안내에 동의하고 위치 권한을 허용합니다. Pleos 권한 창 일부가 이름 없는 작업으로 표시될 수 있습니다.

**주행 재현**

release 빌드에는 시뮬레이션이 없으므로 에뮬레이터에 위치와 기어를 넣습니다. 스크립트가 이 과정을 대신합니다.

1. 에뮬레이터를 경로 시작점으로 옮깁니다. 이 단계를 건너뛰면 거리가 0.0km로 남습니다.

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\demo-drive.ps1 -Serial emulator-5556 -Prepare
   ```

2. 약 10초 뒤 홈에서 장소를 찾아 내비에서 목적지로 정하고, 확인 화면에서 `길 안내 및 기록 시작`을 누릅니다. 기어가 P에서 벗어나면 앱이 잠기므로 먼저 시작해야 합니다.
3. 앱으로 돌아와 기록 화면을 확인한 뒤 주행을 재현합니다. D로 바꾸고, 광안리에서 해운대까지 실제 도로 약 5.7km(`scripts/demo-route.json`, OpenStreetMap 기반)를 속도를 바꿔 가며 이동한 뒤 P로 돌아옵니다. 약 3분 걸립니다.

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\demo-drive.ps1 -Serial emulator-5556
   ```

4. 기록을 끝내면 드라이브 카드를 볼 수 있습니다. 카드는 `내 기록`에도 남습니다.

직접 넣을 때의 조건: 앱은 시속 250km를 넘는 위치 이동을 버리므로, 기록을 시작하기 전에 시작 위치로 옮겨 둡니다. 0.1km 미만의 기록은 카드 탭에 넣지 않습니다. 위치는 `adb emu geo fix <경도> <위도>`로 조금씩 옮깁니다. 기어는 `adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`의 Virtual Console에서 바꾸며, 브라우저를 열어 두어야 적용됩니다.

기록 상세에서는 구간 기록과 `경로 다시 보기`를 볼 수 있습니다. 커뮤니티 코스는 실제 연동이 없는 목업이라 debug 빌드에서만 보입니다.

**휴대폰 앱**

휴대폰 쪽은 별도 저장소의 Android 앱 [drivelog-mobile](https://github.com/hyundai-drivelog-mobile/drivelog-mobile)이 맡습니다. 차량 홈의 `휴대폰 연결`에서 받은 6자리 코드로 연결하고, 서버(`server/`)를 거쳐 기록을 받아 카드를 만듭니다. 차량 앱은 `-PphoneLinkEnabled=true`로 빌드해야 연결 버튼이 보이며, 제출용 기본 빌드에는 없습니다. 연결 코드 → 연동 → 주행 기록 전달까지 에뮬레이터에서 확인한 절차와 결과는 [docs/verification/PHONE_LINK_2026-11-10.md](docs/verification/PHONE_LINK_2026-11-10.md)에 있습니다. 휴대폰 저장소는 Windows에서 한글이 없는 경로에 두어야 빌드됩니다.

예전의 QR 카드 웹 페이지(`web/card/`)와 소개 페이지(`web/landing/`)는 이 앱으로 대체해 저장소에서 뺐습니다(2026-11-10). 차량 앱의 QR 기능은 `cardWebUrl`을 주지 않으면 나오지 않으므로 기본 빌드에서는 보이지 않습니다.

## 구성

- `app/`: Kotlin + Jetpack Compose Android 앱
- `server/`: 독립적인 Spring Boot 백엔드
- `docs/`: 개발 및 검증 기록

## 문서

- [전국 스탬프 카탈로그](docs/STAMP_CATALOG.md): 17개 지역·56곳의 명소, 좌표 출처와 목록 갱신 방법
- [디자인 개선 및 검증](docs/DESIGN_DEVELOPMENT.md): 5개 에이전트 개선 내용, 화면 캡처와 검증 범위
- [Pleos 설계 원칙](docs/guides/PLEOS_DESIGN_PRINCIPLES.md): 설계 기준, PRD에서 바뀐 결정, 미결 사항
- [Pleos API 연동](docs/guides/PLEOS_API_INTEGRATION.md): API 선택 규칙, 퍼미션, 연동 방법, 검증 결과

## 사용 흐름

1. 홈에서 장소를 말하거나 입력해 찾거나, 최근 검색·저장한 장소에서 고른다. 검색은 기본 내비가 맡는다.
2. 목적지 확인 화면에서 `길 안내 및 기록 시작`을 누르면 내비 안내와 기록이 함께 시작된다.
3. 정차하면 기록을 끝낸다.
4. `내 기록`에서 이전 드라이브와 카드를 따로 본다.

제출 범위는 서버 없이 동작하는 차량 앱이다. 휴대폰 연결은 별도 Android 앱과 자체 서버를 사용하는 선택적 확장이다. 연결 전에 서버 전송에 동의해야 하며 기존 차량 기록 전송은 기본 선택되지 않는다. 연결별 기록·대기열을 분리하고 연결 해제 시 이전 연결 기록을 현재 목록에서 숨긴다. 기록을 지우거나 새 계정으로 자동 이전하지 않는다.

## 실행

서버는 `phoneLinkEnabled=true`의 Android 앱 연결에 필요하다. 아래 `demo`는 개발용 H2 프로필이며 운영 검증과 구분한다.

```powershell
cd server
$env:SPRING_PROFILES_ACTIVE="demo"
.\gradlew.bat bootRun
```

차량 앱. 실제 Pleos 이미지(`android-34/pleos_car_emulator`)로 만든 AVD에서 실행한다. 프로젝트 경로에 한글이 있어 테스트가 클래스를 찾지 못하면 build 디렉터리를 ASCII 경로로 옮긴다.

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

- 디버그 빌드에서 `여정` 제목을 길게 누르면 SDK 진단 화면이 열리고, 여기서 시뮬레이션을 켤 수 있다. 시뮬레이션 데이터는 화면과 카드에 표시된다.
- 테스트: `.\gradlew.bat :app:testDebugUnitTest`, `cd server; .\gradlew.bat test`

제출용 APK. 서명 키와 비밀번호는 git에 넣지 않는다. `keystore.properties.example`을 `keystore.properties`로 복사해 채운 뒤 실행하면 `apk/`에 공모전 형식의 파일이 만들어진다.

```powershell
$teamName = Read-Host '실제 제출 팀명'
.\gradlew.bat :app:packageSubmissionApk "-PsubmissionTeam=$teamName"
```

## 남은 작업

- 또렷한 목적지 발화의 인식 정확도, 저장된 즐겨찾기, `onDestinationArrived`, Odometer 값, 실제 분할 화면, CPU 사용량 검증
- 앱 카테고리와 주행 중 화면 유지 허용 범위 확인 (DO 선언은 심사 대상)
- 출처 미확인 사진 6개는 `docs/assets-unverified/`로 옮겨 APK에서 제외했다. 다시 사용하려면 출처·사용권 증거가 필요하다
- 실제 지도는 OpenFreeMap을 쓰고 경로는 기기에서 합성한다. 수신 실패 시 도형으로 표시한다
- 실차 검증

서버 상세는 [Server README](server/README.md), 이전 검증 기록은 [Pleos 런타임·MVP 검증](docs/PLEOS_RUNTIME_MVP.md)을 참고한다.

## 평가 후 수정과 데이터 안내

- [평가 조치·검증 현황](docs/verification/EVALUATION_FIXES_2026-11-10.md)
- [개인정보 및 데이터 안내](docs/PRIVACY.md): 앱 홈에서도 열람 가능
- [제출·검증 체크리스트](docs/SUBMISSION_CHECKLIST.md)
- [사용자·구매 검증 계획](docs/research/VALIDATION_PLAN.md)
- [여행 묶음·대표 카드·내 기록 개편](docs/product/TRIP_EXPERIENCE.md)
- [지역 여행 기록 시범 상품안](docs/business/REGIONAL_TRAVEL_PILOT.md)

차량 동기화는 인증된 전체 기록 API `GET /api/v1/journey/vehicle`을 사용한다. 모바일의 `GET /api/v1/journey` 최근 200건을 차량 전체 목록으로 사용하지 않는다. 서버를 먼저 업데이트해야 하며 구서버에서는 실패를 알리고 로컬을 보존한다. 기존 연결 버전에서 업데이트하면 전송 동의를 다시 받아야 한다.

## 여행으로 모아보기

`내 기록 → 여행 → 여행 만들기`에서 이름과 주행을 선택한다. 개별 카드의 `기록 보기 → 여행에 담기`로 기존 여행에 추가할 수도 있다. 여행 상세의 대표 카드와 타임라인에서 함께 돌아보고, 편집에서 이름·포함 주행·대표 장소를 바꾼다. 여행 묶음을 해제해도 원본 주행과 스탬프는 보존한다. 여행 이름·묶음은 차량의 연결별 로컬 공간에 저장하며 서버·휴대폰 여행 동기화는 후속 범위다.
