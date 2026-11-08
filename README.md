# DRIVELOG

Pleos Connect 기반 차량용 주행 기록·드라이브 카드 앱입니다. 차량 화면에는 `여정`으로 표시됩니다.

**풀려는 문제.** 드라이브에서 운전한 사람에게는 남는 것이 없습니다. 운전 중에 휴대폰 기록 앱을 켤 수는 없고, 내비의 주행 기록은 연비와 운전 점수 위주입니다. 여정은 목적지를 정하는 버튼 하나로 내비 안내와 기록을 함께 시작하고, 주행 중에는 장소·시간·거리만 보이며, 정차하면 경로를 그린 드라이브 카드를 만들어 QR로 휴대폰에 넘깁니다. 경로는 서버에 저장하지 않습니다.

타깃, 경쟁 서비스와의 차이, 사업 계획은 [서비스 기획 요약](docs/PRODUCT_BRIEF.md)에 있습니다. 제출 범위는 차량 앱(`app/`)뿐이며, `server/`는 휴대폰 연결 확장 기능용으로 남겨 둔 코드라 심사용 실행에는 필요 없습니다.

## 심사용 실행 방법

**APK 위치:** `apk/<팀명>_DRIVELOG_release.apk`

**테스트 환경**

- Pleos Connect 에뮬레이터 2.1.0 (Android API 34). 일반 AAOS 이미지에는 Pleos 서비스가 없어 내비, 위치, 음성 기능이 동작하지 않습니다.
- 이미지 설치: Android Studio의 SDK Update Sites에 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`을 추가한 뒤, API 34 Pleos Connect 이미지로 AVD를 만듭니다. RAM은 4GB입니다.
- 내비 검색과 지도에는 인터넷 연결이 필요합니다. 서버는 필요 없습니다.

**설치와 실행**

```powershell
adb install -r apk\<팀명>_DRIVELOG_release.apk
```

debug 빌드가 이미 설치되어 있으면 서명이 달라 설치가 거절됩니다. 먼저 삭제해야 하며, 이때 앱의 기록도 함께 지워집니다. 위치 권한 요청이 나오면 허용합니다.

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

**휴대폰으로 카드 보내기(QR)**

`web/card/index.html`을 HTTPS 정적 호스팅(GitHub Pages 등)에 올리고, 그 주소로 빌드하면 기록 화면의 `휴대폰으로 보내기`가 QR 코드를 보여 줍니다. 휴대폰 카메라로 찍으면 카드가 열리고 이미지로 저장할 수 있습니다. 서버는 필요 없습니다.

소개 페이지는 `web/landing/index.html`입니다. 빌드 없이 같은 방식으로 정적 호스팅에 올리면 됩니다. 화면 사진(`web/landing/img/`)은 Pleos 에뮬레이터에서 찍은 실제 앱 화면이며, 앱 화면이 바뀌면 다시 찍어 교체합니다.

```powershell
.\gradlew.bat :app:packageSubmissionApk -PsubmissionTeam=<팀명> -PcardWebUrl=https://<호스트>/card/
```

`cardWebUrl` 없이 빌드하면 QR 버튼이 나오지 않습니다.

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

제출 범위는 차량 앱뿐이며 서버 없이 동작한다. 휴대폰 연결과 모바일 웹 사진 추가는 확장 기능으로 코드에 남아 있고, `-PphoneLinkEnabled=true`로 빌드하면 켜진다. 다만 모바일 웹은 아직 목표 기준으로 되어 있어 새 흐름에 맞게 고쳐야 한다.

## 실행

서버와 모바일 웹은 `phoneLinkEnabled=true`일 때만 필요하다. PostgreSQL 없이 실행하는 `demo` 프로필이다.

```powershell
cd server
$env:SPRING_PROFILES_ACTIVE="demo"
$env:DRIVELOG_WEB_URL="http://<PC의 IP>:8080/m/"   # 차량 연결 화면에 표시할 주소
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
.\gradlew.bat :app:packageSubmissionApk -PsubmissionTeam=<팀명>
```

## 남은 작업

- 또렷한 목적지 발화의 인식 정확도, 저장된 즐겨찾기, `onDestinationArrived`, Odometer 값, 실제 분할 화면, CPU 사용량 검증
- 앱 카테고리와 주행 중 화면 유지 허용 범위 확인 (DO 선언은 심사 대상)
- 앱에 들어간 장소 사진의 출처와 사용 권한 확인, 필요하면 교체
- 드라이브 카드 지도는 OpenFreeMap을 쓴다. 홈과 목적지 확인 화면의 지도 배경은 아직 예시다
- 실차 검증

서버 상세는 [Server README](server/README.md), 이전 검증 기록은 [Pleos 런타임·MVP 검증](docs/PLEOS_RUNTIME_MVP.md)을 참고한다.
