# DRIVELOG — Pleos 런타임 및 차량용 MVP 검증

검증일: 2026-11-02. 이전 Phase 문서는 보존한다. 이번 변경은 Android 로컬 MVP이며 서버 API를 호출하지 않는다. `server/` 소스·설정 28개 파일의 작업 전후 SHA-256이 동일하다. 백엔드/DB/볼륨 변경 없음. 현재 디렉터리에는 `.git`이 없으며 새 저장소를 생성하지 않았다.

## A. 변경 파일

| 파일 | 역할 |
|---|---|
| `app/build.gradle.kts` | SDK 포함 여부에 따라 `src/pleos/java`, `src/noPleos/java` 선택 |
| `app/src/main/AndroidManifest.xml` | 공개 문서의 차량·위치·내비 권한 선언. Distraction Optimized 우회 선언 없음 |
| `app/src/main/java/com/drivelog/app/MainActivity.kt` | Diagnostics → 주행 → 카드 탭, 실제 AAOS UX 제한 적용 |
| `.../data/sdk/RuntimeModels.kt` | 진단 결과·출처·위치·목적지·데이터 공급자 인터페이스 |
| `app/src/pleos/java/com/drivelog/app/data/sdk/PleosRuntimeDataSource.kt` | 실제 Vehicle/FusedLocation/NaviHelper 공개 API 래퍼 |
| `app/src/noPleos/java/com/drivelog/app/data/sdk/PleosRuntimeDataSource.kt` | SDK 제외 빌드에서 명시적 Unavailable |
| `.../data/drive/DriveSession.kt` | 세션 스냅샷, GPS 누적 거리, 분리된 Mock 공급자 |
| `.../feature/diagnostics/PleosDiagnosticsScreen.kt` | 권한·실제 API 결과·시각·코드 표시 및 호출 버튼 |
| `.../feature/drive/DriveViewModel.kt` | SDK 접근과 UI 분리, 세션 상태, 모드 분리, 타이머, 진단 로그 |
| `.../feature/drive/DriveScreen.kt` | 목적지, 시작/종료, 거리·위치·요약 |
| `.../feature/drive/RouteSilhouette.kt` | 실제 수집 좌표의 경로 실루엣, 지도 SDK 아님 |
| `.../feature/card/DriveCardScreen.kt` | 사진 선택, 템플릿 2종, Bitmap 미리보기·저장 |
| `.../feature/card/DriveCardRenderer.kt` | 1200×1600 Canvas Bitmap 및 PNG/MediaStore 출력 |
| `app/src/main/res/drawable-nodpi/demo_coastal_road.png` | AI 생성 가상 데모 사진 |
| `app/src/test/java/com/drivelog/app/DriveGeometryTest.kt` | 거리/누락 좌표/실루엣 검증 3개 |

`.../`는 `app/src/main/java/com/drivelog/app/`를 뜻한다. 기존 Theme, AAOS UX 모니터, 이전 서버 데모 코드는 유지한다. 빈 Repository/UseCase를 추가하지 않았다.

## B. Pleos Emulator

- [공식 설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)는 Emulator 2.1.0 / API 34, Windows x86_64 이미지 저장소를 안내한다.
- [Playground](https://developers.pleos.ai/playground), [공모전 개발환경](https://appdevchallenge.auto-hmg.io/guide/development)도 확인했다.
- 공식 저장소: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
- 공식 ZIP 다운로드·SHA-1 일치: `b4f858d5bcc6c71506d7edfc872bcb9e5a11acaa` (4,087,751,964 bytes).
- ZIP 내부 태그 `android-automotive`, revision 1과 원격 XML 경로 `pleos_car_emulator`, revision 2가 불일치한다. 최초 AVD 생성 시 패키지 경로 오류가 발생했다. 새로 설치한 이미지에 한해 내부 메타데이터와 일치하는 `system-images/android-34/android-automotive/x86_64` 경로로 정리하고 AVD를 생성했다. 기존 이미지·AVD 삭제 없음.
- SDK: `C:/Users/user/AppData/Local/Android/Sdk`. CLI tools 23도 공식 Google ZIP의 SHA-1 검증 후 설치했다.
- AVD: `DRIVELOG_Pleos_2_1`, Pleos Connect 하드웨어 프로파일, RAM 4096MB. headless/software GPU에서 부팅 약 62초, ADB `emulator-5556`, API 34, `sys.boot_completed=1` 확인.
- AAOS 현재 사용자 **10**. 앱 설치·실행 및 OEM 화면 내 표시 성공. `run-as ... --user 10`이 필요하다. 기본 사용자 0의 파일 경로로는 결과가 보이지 않는다.
- AVD 검증 후 `avdmanager move avd`로 `C:/Users/user/.android/avd/DRIVELOG_Pleos_2_1.avd`에 보존했다. 프로젝트 `build/` 정리로 AVD가 삭제되지 않는다.
- 이동 후 재부팅도 약 51초에 완료(`sys.boot_completed=1`), ADB 재연결 확인. 최종 상태는 Pleos AVD headless 실행, 일반 Pixel AVD 종료다.
- 첫 `am start -W`는 시간 초과했으나 앱 화면은 실제 표시됐다. 이후 호출과 카드 조작을 실제 화면으로 확인했다.
- 호스트 RAM 16GB에서 두 에뮬레이터 동시 실행 시 메모리 부족이 발생해 순차 검증했다. OEM 부팅 로그에 다른 시스템 서비스의 crash가 있었으나 해당 crash를 DRIVELOG 성공/실패로 혼동하지 않았다.

Windows에서 재설치할 경우 Android Studio → Tools → SDK Manager → SDK Update Sites에 위 공식 URL 추가 → SDK Platforms → Show Package Details → API 34 Pleos image → 설치 → IDE 재시작 → Device Manager → Automotive → Pleos 프로파일과 공식 이미지 선택. 같은 태그 불일치가 재현되면 기존 이미지 전체를 삭제하지 말고 공식 배포 패키지 정합성부터 확인한다.

```powershell
& "$env:LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd DRIVELOG_Pleos_2_1 -port 5556 -gpu swiftshader -no-snapshot
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s emulator-5556 shell am start --user 10 -n com.drivelog.app/.MainActivity
```

GUI 부팅은 사용자 필요 시 위 명령으로 실행한다. 자동 검증은 `-no-window -no-audio -no-boot-anim`으로 수행했다.
현재 headless 인스턴스가 실행 중이면 GUI 실행 전에 `adb -s emulator-5556 emu kill`로 해당 인스턴스만 종료한다. AVD/사용자 데이터 삭제 옵션은 사용하지 않는다.

## C. Vehicle 실제 호출

SDK 2.3.2 / 이미지 내 서비스 2.3.22. 다운로드·컴파일과 별도로 에뮬레이터의 실제 서비스 callback을 검증했다. 이 값은 실제 도로 주행 측정값이 아닌 **Pleos 에뮬레이터 차량 값**이다.

| 항목 | 결과 | 근거 |
|---|---|---|
| initialize | SUCCESS | 실제 주행거리·SOC callback 응답 |
| 차량 기본 정보 / VIN | UNAVAILABLE | capability/조회 대기 시간 초과. 식별값 로그 없음 |
| 드라이브 모드 | UNSUPPORTED | `ERROR_NOT_SUPPORTED_PROPERTY`; 차량 이동/정차 상태와 별개 |
| 주행거리 | SUCCESS | `getCurrentDistanceDriven` 원시값 `0.0`. 단위·현재 트립 기준 미검증 |
| 현재 속도 | UNSUPPORTED | 검증한 공개 접근자 없음. 크루즈 목표 속도로 대체하지 않음 |
| 배터리 SOC | SUCCESS | 원시값 `100` |
| 연료 잔량 | UNSUPPORTED | `ERROR_NOT_SUPPORTED_PROPERTY` |
| 현재 트립 연비 / 전비 | UNSUPPORTED | `ERROR_NOT_SUPPORTED_PROPERTY` |

UI의 실제 모드 주행거리는 Vehicle 원시값을 추정 변환하지 않고 수집 GPS 점 사이 거리로 표시한다. 평균 연비·전비는 실제 세션 기준과 단위가 검증되기 전까지 null/미지원이다.

## D. NaviHelper 실제 호출

SDK 3.0.5 / 서비스 3.0.4. 공개 라이브러리의 `NaviHelper`, `RouteInfo`, `NavigationScreenRequest.Foreground`, listener 시그니처를 확인했다.

| 기능 | 결과 |
|---|---|
| initialize | SUCCESS: `onConnected` |
| capability | UNSUPPORTED: 별도 확인된 capability 조회 API 없음. 권한과 실제 응답으로 판단 |
| 현재 경로/안내 상태 | SUCCESS: `SAFE_GUIDING` 및 `ROUTE_GUIDING`, `routing=false`, `terms=true` |
| 목적지·경로 요청 | **부분 검증**: 공식 42dot 좌표 요청 후 내비 앱에 42dot / 50m / 1분 안내가 표시됨. 목적지 정보 callback 수신. 그러나 요청 좌표 일치 검증 실패, `onRoutingCompleted` 미수신/30초 시간 초과 → `navi.route=UNAVAILABLE` |
| 별도 Foreground 요청 | SUCCESS: 메서드 반환 후 OEM 내비 화면 표시를 스크린샷으로 확인 |
| 지도 타일 | **미검증/문제 있음**: 안내 오버레이는 보이지만 지도 배경이 흰색. 네트워크·지도 서비스·에뮬레이터 설정 추가 조사 필요 |
| 도착·목적지 변경·재탐색 listener | 공개 API로 수신 코드 구현. 실제 이벤트 발생/수신은 미검증 |

내비는 별도 앱 화면이다. DRIVELOG 안에 지도/내비 엔진을 임베딩하지 않았다. `navi.foreground=SUCCESS`는 요청 반환과 별도 화면 표시를 뜻하며 경로 요청 전체 성공과 다르다.

구체적인 불일치: 요청 `37.48544722, 127.03636666`, 반환 `42dot / 37.41289678, 127.09383464`. POI 식별자에 따른 정규화/이전 위치 등의 가능성은 있으나 원인은 확인되지 않았다. 좌표 검증을 제거해서 성공으로 바꾸지 않았다.

## E. FusedLocation 실제 호출

SDK 1.1.3 / 서비스 1.0.0.

- `isSupported=true` 확인.
- Manifest 권한 선언만으로는 부족했다. Diagnostics에서 `FUSED_LOCATION` 권한 요청 후 OEM 허용 대화상자를 통해 실제 허용했다.
- 최근 위치: SUCCESS. 초기 좌표 `39.237255, -123.15003166666666`, confidence `5.0`, SDK timestamp 수신. 마지막 알려진 위치이므로 항상 최신 GPS라고 주장하지 않는다.
- 연속 callback: SUCCESS. 실제 SDK에서 약 1~2초 간격으로 반복 수신했다. 요청 주기 보장·장시간 안정성은 미검증.
- `adb emu geo fix 127.03636 37.48544`로 **에뮬레이터 위치를 주입**했고 SDK가 서울 좌표를 반환했다. 실제 차량 GPS 이동 검증이 아니다.
- `confidence`의 단위가 확인되지 않아 정확도 m로 변환하지 않는다. 정확도 m는 null.
- 좌표 급이동 시 거리 급증을 발견해 250km/h를 초과하는 점 간 이동을 제외한다. 이는 데모용 이상치 방어이며 센서 융합/정밀 주행거리 측정 구현이 아니다. GPS 위치를 큰 폭으로 옮긴 후 새 세션을 시작한다.

## F. 주행·사진 카드 데모

- MOCK / PLEOS 분리. 모드 변경 시 이전 좌표·요약·출력 경로 초기화. 실제 SDK 연결 실패 시 가상 좌표를 PLEOS로 섞지 않는다.
- 시작 → monotonic 세션 시간 → 좌표 누적 → 종료 스냅샷 → 요약 → 카드 흐름.
- Mock 실기 검증: 35초, 36개 좌표, 2.15km. 화면과 PNG에 MOCK 명시.
- Pleos 실제 위치 callback의 메모리 누적 확인. 원시 Vehicle 거리를 세션 거리로 오인하지 않는다.
- 최신 APK의 실제 SDK 세션도 종료·카드 저장 성공: 29초 / 23개 좌표 / 약 0.01km. 에뮬레이터에 주입한 시험 위치이며 실제 도로 주행이 아니다.
- 일반 Android Pixel_3a_2 / API 36에서도 SDK 포함 APK 실행 → Mock 시작 → 4초/5개 좌표/0.37km 종료 → 카드 미리보기 → PNG 저장을 실제 조작했다. MediaStore 1200×1600 / `is_pending=0` 및 UI 완료 표시 확인. 16KB 호환 모드였다는 제한은 아래에 기록한다.
- 번들 데모 사진/로컬 이미지 1장, 저널·포토 2종, 날짜/거리/시간/경로 실루엣/출처 표시.
- Pleos에서 템플릿 2종 PNG 생성. MediaStore 사용자 10 조회로 **각 1200×1600** 및 `Pictures/DRIVELOG/` 등록 확인. 내부 app files/cards에도 PNG 보존.
- API 29+는 MediaStore 저장, API 28은 앱 내부 PNG만 저장한다. 외부 갤러리 쓰기 권한을 임의 추가하지 않았다.
- 앱 프로세스 종료 후 세션 복원, DB 저장, 백그라운드 장시간 기록은 범위 밖이다. 세션은 현재 메모리 기반이다.
- 표준 AAOS `CarUxRestrictionsManager` 사용. 제한 활성/상태 미확인 시 편집·개발 버튼 잠금. 녹화 중 탭·목적지·사진 편집 비활성화. DO 메타데이터나 주행 중 실행 권한을 임의 선언하지 않았다. 실차 운행 중 정책 검증은 별도 필요하다.

## G. Mock 및 사진 출처

가상 GPS·14.2km/L는 Mock 모드에만 사용한다. 실제 모드의 연비/전비를 채우지 않는다. Mock 목적지 선택은 실제 경로 탐색 결과가 아니다. 여행 사진은 실제 사용자 여행 증거가 아닌 가상 풍경이며 카드 하단에 AI-GENERATED DEMO PHOTO가 표시된다.

이미지 생성은 `imagegen` 스킬과 built-in image tool을 사용했다. 에셋: `app/src/main/res/drawable-nodpi/demo_coastal_road.png`. 별도 목업 원본은 제공되지 않아 요청문에 명시된 화이트/블랙/세로형 사진 중심 방향을 따랐다.

생성 프롬프트:

> Create a single photorealistic editorial travel photograph as a bundled DEMO background for the DRIVELOG Android app. Portrait 3:4 composition. A quiet winding asphalt road through lush Korean coastal mountains, blue sea in the distance, soft late afternoon natural light, restrained colors, realistic landscape photography. No cars, people, text, logos, UI, graphics, watermark, or numbers. This is a fictional demo travel scene, not evidence of a real user's journey. The photo will be cropped by app code inside a vertical photo card, so keep the main landscape composition broadly centered.

## H. 검증 결과 및 차단 사항

- SDK 포함 `assembleDebug`: 성공.
- `assembleDebug -PpleosSdkEnabled=false`: 성공. SDK 없이도 Mock 앱을 빌드할 수 있다.
- `testDebugUnitTest`: 4개 통과(새 지오메트리 3개 + 기존 1개).
- `lintDebug`: 오류 0개, 경고 30개. 경고를 오류 없이 숨기거나 모두 해결됐다고 보고하지 않는다.
- 최종 SDK 포함 APK: `app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `9C0C0E77FE5F8E11796487ECA87296B22F839F33B923A63E991E3839D7507ED5`.
- 실제 앱 SDK 예외는 상태로 표시하며 기본 앱이 크래시하지 않도록 처리했다.
- 일반 Android API 36 / 16KB 페이지 에뮬레이터는 SDK 포함 APK의 네이티브 라이브러리(`libjnidispatch.so`, `libuacore.so`, `libandroidx.graphics.path.so`)에 대해 16KB 정렬 경고를 표시했다. OK 후 호환 모드로 앱이 실행됐다. 16KB 완전 호환 검증은 통과하지 않았다. Pleos API 34 실행 성공과 구분하며, 공급자 바이너리/공식 호환 버전 확인이 필요하다. 경고를 비활성화하는 Manifest 설정을 추가하지 않았다.
- Android 앱에 Client Secret/Gemini key를 추가하지 않았다. 기존 Spring Boot VM Options는 Android에 전달되지 않는다. 이번 에뮬레이터 호출에는 해당 비밀값을 사용하지 않았다.
- 로그: 앱 내부 `files/sdk-diagnostics.jsonl`, Logcat tag `DriveLogSDK`. VIN/CRN/비밀값 대신 진단 코드·시각·필요한 시험 좌표만 기록. 파일 크기 상한 적용.
- 미완료: 물리 차량, 평균 효율 단위/트립 매칭, Vehicle 기본 정보 시간 초과, 내비 목적지 좌표 불일치/완료 이벤트/지도 타일, 실제 도착·변경 이벤트, 장시간·백그라운드 기록, 로컬 사진 picker의 다양한 제조사 동작.

## I. 다음 순서

1. Pleos Virtual Console의 차량·GPS 값 변경으로 데이터 항목별 지원 여부 재검증. 실제 차량 시험과 구분.
2. Navi 서비스/SDK 버전 호환성, 반환 목적지 좌표와 지도 네트워크 상태를 공식 지원 채널에 확인.
3. 심사 시연은 정차 상태에서 Mock → 종료 → 카드 PNG 흐름으로 준비하고 실제 SDK Diagnostics를 별도로 제시.
4. 실차 접근 후 지원 신호의 단위·타임스탬프·권한·주행 UX 검증, 그 이후 세션 영속화/서버 동기화를 별도 단계에서 진행.

공식 참고: [Vehicle 초기화](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object), [Vehicle 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission), [FusedLocation](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/initialization-and-release), [NaviHelper](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release), [AAOS 정차 앱 UX 제한](https://developer.android.com/training/cars/parked/automotive-os), [Android 16KB 호환성](https://developer.android.com/guide/practices/page-sizes).

검증 산출물: [진단 원본 로그](verification/pleos-runtime/diagnostics.jsonl), [Pleos 카드 화면](verification/pleos-runtime/pleos-card-preview.png), [실제 SDK 세션 요약](verification/pleos-runtime/pleos-real-summary.png), [내비 화면과 빈 지도 배경](verification/pleos-runtime/pleos-navigation.png), [실제 출력 Mock PNG](verification/pleos-runtime/mock-card.png), [일반 Android 카드 저장 완료](verification/pleos-runtime/phone-card.png).
