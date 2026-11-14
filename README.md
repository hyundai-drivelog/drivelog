# DRIVELOG

**달린 길이 카드로 남는 드라이브.** 목적지 하나로 내비 안내와 주행 기록을 함께 시작하고, 정차하면 경로를 그린 드라이브 카드와 방문 스탬프를 남기는 Pleos Connect 차량 앱입니다.

**풀려는 문제.** 드라이브에서 운전한 사람에게는 남는 것이 없습니다. 운전 중에 휴대폰 기록 앱을 켤 수는 없고, 내비의 주행 기록은 연비와 운전 점수 위주입니다. DRIVELOG는 목적지를 정하는 버튼 하나로 내비 안내와 기록을 함께 시작하고, 주행 중에는 장소·시간·거리만 보이며, 정차하면 경로를 그린 드라이브 카드와 방문 스탬프를 남깁니다. 기본 차량 모드는 기록을 차량에 저장합니다. 선택적 휴대폰 연결 모드에서는 자체 서버를 거쳐 Android 앱에서 기록을 보고 카드를 꾸밉니다.

타깃, 경쟁 서비스와의 차이, 사업 계획은 [서비스 기획 요약](docs/PRODUCT_BRIEF.md)에 있습니다.

## 제출물

| 항목 | 내용 |
|---|---|
| 앱 이름 | DRIVELOG (패키지 `com.drivelog.app`, 버전 1.0) |
| 제출 APK | [`apk/DRIVELOG_DRIVELOG_release.apk`](apk/DRIVELOG_DRIVELOG_release.apk) (차량 기본 모드, release 서명, 약 70MB) |
| SHA-256 | `bf79e0ac9c20df774fb377d8d9bb8fa21dfa16e91594f7ac58172e77265fa14d` ([`.sha256`](apk/DRIVELOG_DRIVELOG_release.apk.sha256)) |
| 서명 인증서 SHA-256 | `78:4C:7B:A2:45:FA:58:62:6E:61:5C:06:89:89:88:99:1D:30:0B:5D:81:51:0C:0A:5D:8A:76:E5:DF:D1:6A:86` |
| 개발·검증 에뮬레이터 | Pleos Connect 에뮬레이터 **v2.1.0** (Android 14, API 34, x86_64) |
| 화면 규격 | **2560×1440, 160dpi** (AVD 설정값), RAM 4GB |
| 서버 | 필요 없음. 제출 APK는 서버 없이 동작합니다 |
| 선택: 로컬 연동용 | [`apk/local/`](apk/local/)의 차량 연결판·휴대폰 앱 (아래 [로컬 연동 실행](#로컬-연동-실행-선택-차량--휴대폰)) |

제출 APK는 휴대폰 연결·QR·진단·시뮬레이션·목업 화면이 없는 차량 기본 모드입니다. 휴대폰 연결은 별도 Android 앱과 자체 서버가 필요한 확장 기능이라 `apk/local/`에 따로 두었습니다.

## 테스트 환경 및 필요한 설정

1. **Pleos Connect 에뮬레이터 v2.1.0.** Android Studio의 SDK Manager → SDK Update Sites에 아래 주소를 추가하고, API 34 **Pleos Connect v2.1.0 APIs** x86_64 시스템 이미지를 받습니다. 일반 AAOS 이미지에는 Pleos 서비스가 없어 내비·위치·음성이 동작하지 않습니다.
   ```
   https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml
   ```
2. **AVD 생성.** Device Manager에서 위 이미지로 AVD를 만듭니다. 화면 2560×1440, 160dpi, RAM 4GB로 개발·검증했습니다.
3. **adb.** Android SDK Platform-Tools의 `adb`가 PATH에 있어야 합니다(기본 위치 `%LOCALAPPDATA%\Android\Sdk\platform-tools`). 아래 명령은 Windows PowerShell 기준입니다.
4. **인터넷.** 내비 검색과 지도(OpenFreeMap)에 필요합니다.

## 실행 방법 (심사용, 서버 불필요)

### 1. 설치와 실행

```powershell
# 에뮬레이터를 켠 뒤, 저장소 루트에서
adb install -r apk\DRIVELOG_DRIVELOG_release.apk
adb shell am start --user 10 -n com.drivelog.app/.MainActivity   # 또는 하단 독의 DRIVELOG 아이콘
```

- 처음 부팅한 에뮬레이터는 내비 준비가 늦습니다. 앱에서 `찾기`를 눌러도 내비가 뜨지 않거나 "내비게이션 실행이 지연되고 있습니다"가 나오면, 독의 내비 아이콘을 눌러 지도 화면이 뜰 때까지 기다린 뒤 다시 시도합니다.
- debug 빌드가 설치된 에뮬레이터에는 서명이 달라 설치가 거절됩니다. 앱을 지우면 기록도 지워지므로 별도 AVD를 쓰는 것을 권장합니다.

### 2. 첫 실행

1. 소개 4장을 넘기거나 `건너뛰기`를 누릅니다.
2. 홈의 `드라이브 시작` → 데이터 사용 안내를 끝까지 내려 `차량 기록과 지도 사용에 동의`를 누릅니다.

### 3. 주행 재현

release 빌드에는 시뮬레이션이 없으므로 에뮬레이터에 위치와 기어를 넣습니다. `scripts\demo-drive.ps1`이 광안리→해운대 실제 도로 약 5.6km(`scripts/demo-route.json`, OpenStreetMap 기반)를 대신 달립니다. 순서를 지켜야 거리가 0.0km로 남지 않습니다.

1. **Virtual Console 열기.** 기어는 에뮬레이터의 Virtual Console로 바꾸는데, 콘솔 페이지가 브라우저에 열려 있어야 적용됩니다(닫혀 있으면 `emulator_inactive` 오류).
   ```powershell
   adb forward tcp:8088 tcp:8088
   Start-Process http://127.0.0.1:8088/      # 이 탭을 주행이 끝날 때까지 열어 둡니다
   ```
2. **시작 위치로 이동.**
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\demo-drive.ps1 -Serial emulator-5554 -Prepare
   ```
   `-Serial`은 `adb devices`에 보이는 에뮬레이터 이름입니다. adb가 PATH에 없으면 `-Adb "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"`를 붙입니다.
3. **목적지와 기록 시작.** 앱의 검색창에 장소(예: 해운대해수욕장)를 입력하고 `찾기` → 내비 검색 결과에서 장소를 골라 안내를 시작 → 독의 DRIVELOG 아이콘으로 돌아와 `드라이브 시작` → 최근 검색의 장소 → `길 안내 및 기록 시작`을 누릅니다. 위치·자동차 정보 권한 창은 모두 허용합니다. 기어가 P를 벗어나면 앱이 잠기므로 반드시 주행 전에 시작합니다.
4. **주행.** 앱의 기록 화면("기록 중")을 확인한 뒤 실행합니다. D로 바꾸고 경로를 따라 이동한 뒤 P로 돌아옵니다. 약 3~4분 걸립니다.
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\demo-drive.ps1 -Serial emulator-5554
   ```
5. **저장.** P가 되면 `드라이브를 마칠까요?` 화면에 거리·시간·경로 지도가 나옵니다. `저장하고 마치기`를 누르면 드라이브 카드와 해운대 스탬프가 남고, 홈의 `내 기록`·`스탬프`에 반영됩니다.

장소 화면의 내비 거리(예: 382km)는 에뮬레이터 내비가 주입한 GPS 대신 자체 기본 위치(성남)에서 경로를 계산해서 생기는 값입니다. 앱이 기록하는 거리는 주입한 경로 기준(약 5.5km)입니다.

직접 넣을 때의 조건: 앱은 시속 250km를 넘는 위치 이동을 버리므로, 기록을 시작하기 전에 시작 위치로 옮겨 둡니다. 0.1km 미만의 기록은 카드 탭에 넣지 않습니다. 위치는 `adb emu geo fix <경도> <위도>`로 조금씩 옮깁니다.

## 로컬 연동 실행 (선택: 차량 ↔ 휴대폰)

차량 기록을 휴대폰 앱에서 보고 카드를 꾸미는 확장 기능을 PC 한 대에서 돌리는 방법입니다. 제출 APK와 별개이며, 두 에뮬레이터가 PC의 서버에 `10.0.2.2:8080`(에뮬레이터에서 본 PC 주소)으로 붙습니다. 실제 휴대폰에서는 이 APK가 서버에 닿지 않습니다.

| 파일 | 설치 대상 | 내용 |
|---|---|---|
| [`apk/local/DRIVELOG_car_local.apk`](apk/local/DRIVELOG_car_local.apk) | Pleos 에뮬레이터 | 제출 APK와 같은 코드·서명에 휴대폰 연결만 켠 빌드(`local` 빌드 타입). debug 목업·진단·시뮬레이션은 없음 |
| [`apk/local/DRIVELOG_mobile_local.apk`](apk/local/DRIVELOG_mobile_local.apk) | 일반 Android 휴대폰 에뮬레이터 | 휴대폰 앱([drivelog-mobile](https://github.com/hyundai-drivelog-mobile/drivelog-mobile)) 개발 빌드 |

체크섬은 [`apk/local/SHA256SUMS`](apk/local/SHA256SUMS)에 있습니다.

1. **서버.** JDK 21이 필요합니다(없으면 Gradle이 자동으로 받습니다). `demo` 프로필은 DB 설치 없이 H2 파일(`server/data/`)을 씁니다.
   ```powershell
   cd server
   $env:SPRING_PROFILES_ACTIVE = "demo"
   .\gradlew.bat bootRun        # "Started DrivelogServerApplication"이 나오면 준비 완료
   ```
2. **차량.** Pleos 에뮬레이터에 차량 연결판을 설치합니다. 제출 APK와 서명이 같아 기록을 유지한 채 덮어씁니다.
   ```powershell
   adb -s emulator-5554 install -r apk\local\DRIVELOG_car_local.apk
   ```
3. **휴대폰.** Device Manager에서 일반 휴대폰 AVD(예: Pixel, API 34 Google APIs x86_64)를 하나 더 만들어 켜고 휴대폰 앱을 설치합니다. 두 에뮬레이터를 함께 켜면 휴대폰 쪽에 "System UI isn't responding" 창이 뜰 수 있는데, `Wait`를 누르면 됩니다.
   ```powershell
   adb -s emulator-5556 install -r apk\local\DRIVELOG_mobile_local.apk
   ```
4. **연결.** 차량 홈 오른쪽 위 `휴대폰 연결` → 안내를 끝까지 내려 `위 내용을 확인하고 서버 전송에 동의`를 누르면 6자리 코드가 나옵니다. 이미 있는 차량 기록도 보내려면 동의 전에 `차량의 기존 기록도 전송`을 선택합니다. 휴대폰 앱의 `차량과 연결`에 코드를 넣고 `연동하기` → `위 내용을 확인하고 연결에 동의`를 누릅니다.
5. **기록 확인.** 위 [주행 재현](#3-주행-재현)으로 기록을 저장하면 휴대폰 홈의 `최근 드라이브`에 나타납니다. 바로 안 보이면 휴대폰 앱을 홈으로 보냈다가 다시 엽니다(앞으로 올 때 동기화). 기록 상세에서 경로 지도를 보고, `카드 만들기`로 제목과 표시 정보를 바꿔 이미지로 저장할 수 있습니다.

## 검증 결과 (2026-11-14)

커밋 `c8f7850`의 소스에 `local` 빌드 타입만 더한 상태에서 빌드했습니다.

| 항목 | 결과 |
|---|---|
| 차량 앱 단위 테스트 `:app:testDebugUnitTest` | 100개 통과 |
| 휴대폰 앱 단위 테스트 | 6개 통과 |
| 제출 APK 서명·manifest (`scripts\verify-submission.ps1`) | 서명 검증(v2) 통과, `debuggable` 아님, 패키지 `com.drivelog.app`, SHA-256 일치 |
| 제출 APK 권한 | debug 진단용 Pleos 권한(`CAR_INFO` 등) 없음 |
| 새 Pleos v2.1.0 AVD에서 제출 APK | 설치 → 소개·동의 → 찾기로 내비 검색 → 길 안내 및 기록 시작(권한 허용) → 주행 재현 5.5km·7분 → 저장 → 카드·해운대 스탬프, 홈 기록 1회·스탬프 1/56. 주행 중에는 장소·시간·거리만 표시. 앱 crash 없음 |
| 기본 모드 | 홈에 휴대폰 연결·커뮤니티·진단 진입 없음 |
| 로컬 연동 (서버 + Pleos AVD + Pixel API 34 AVD) | 연결 동의 → 코드 발급 → 휴대폰 입력·동의 → 연결, 차량의 기존 기록이 휴대폰에 도착, 상세 경로 지도 표시. 양쪽 앱 crash 없음 |

확인하지 못한 것: 실차, 실제 휴대폰에서의 HTTPS 서버 연결, TalkBack, 좁은 창 분할 화면. 처음 부팅한 에뮬레이터에서는 플랫폼 내비가 늦게 준비되어 첫 `찾기`가 무시됐습니다(앱은 요청을 보냈고, 내비 준비 후 정상 동작).

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

## 직접 빌드하기

서명 키와 비밀번호는 git에 넣지 않습니다. `keystore.properties.example`을 `keystore.properties`로 복사해 채웁니다. 프로젝트 경로에 한글이 있으면 단위 테스트가 클래스를 찾지 못할 수 있어, 한글 없는 경로에 체크아웃하는 것을 권장합니다.

```powershell
.\gradlew.bat :app:testDebugUnitTest                                   # 단위 테스트
.\gradlew.bat :app:packageSubmissionApk "-PsubmissionTeam=DRIVELOG"    # apk\DRIVELOG_DRIVELOG_release.apk와 .sha256
.\gradlew.bat :app:assembleLocal                                       # app\build\outputs\apk\local\app-local.apk (로컬 연동용 차량)
powershell -ExecutionPolicy Bypass -File scripts\verify-submission.ps1 -Apk apk\DRIVELOG_DRIVELOG_release.apk
```

휴대폰 앱은 별도 저장소에서 `.\gradlew.bat :app:assembleDebug`로 만듭니다(기본 서버 주소 `http://10.0.2.2:8080`). Windows에서는 한글이 없는 경로에 두어야 빌드됩니다.

개발용 debug 빌드(`:app:assembleDebug`)에서는 `여정` 제목을 길게 눌러 SDK 진단 화면을 열고 시뮬레이션을 켤 수 있습니다. 시뮬레이션 데이터는 화면과 카드에 표시되며, 커뮤니티 코스·휴대폰 미리보기 같은 목업도 debug 빌드에서만 보입니다. 서버 테스트는 `cd server; .\gradlew.bat test`입니다.


## 남은 작업

- 또렷한 목적지 발화의 인식 정확도, 저장된 즐겨찾기, `onDestinationArrived`, Odometer 값, 실제 분할 화면, CPU 사용량 검증
- 앱 카테고리와 주행 중 화면 유지 허용 범위 확인 (DO 선언은 심사 대상)
- 출처 미확인 사진 6개는 APK와 공개 저장소에서 제외했다. 다시 사용하려면 출처·사용권 증거가 필요하다
- 실제 지도는 OpenFreeMap을 쓰고 경로는 기기에서 합성한다. 수신 실패 시 도형으로 표시한다
- 실차 검증

서버 상세는 [Server README](server/README.md)를 참고한다.

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
