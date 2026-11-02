# DRIVELOG Phase 1 — 개발환경 및 검증 기록

확인일: 2026-11-02 (Windows). 이 문서는 로컬 파일 검사와 공식 문서 조사에 근거한다.
의존성 추가, 서비스 연결, 실제 차량 API 호출은 서로 다른 검증 단계다.

## 1. 기존 프로젝트와 유지한 설정

- 단일 app 모듈, namespace/applicationId: com.drivelog.app
- Android Gradle Plugin 9.1.1, Gradle Wrapper 9.3.1 (기존 SHA-256 유지)
- Kotlin: AGP 내장 KGP 2.2.10. 별도 org.jetbrains.kotlin.android 플러그인을 추가하지 않음.
- Compose Compiler 플러그인 2.2.10: Kotlin 컴파일러와 일치.
- compileSdk 36.1 / minSdk 28 / targetSdk 36 유지.
- Java 소스/대상 바이트코드 11 유지. Gradle 실행 JDK와 다른 설정이다.
- Gradle daemon: Android Studio JBR 21.0.9. 기존 daemon JVM 기준 Java 21 유지.
- JAVA_HOME의 Launcher JVM은 Zulu 17.0.14. gradlew --version에 나오는 Kotlin 2.2.21은
  Gradle 자체 Kotlin DSL 버전으로, 앱 컴파일러 버전이 아니다.
- Android SDK: local.properties의 로컬 SDK 경로 사용. 설치 플랫폼 android-36.1,
  Build Tools 36.0.0 및 36.1.0 확인.
- Compose BOM 2025.08.00 / Activity Compose 1.13.0. 해결된 Compose UI 1.9.2,
  Foundation 1.9.0, Material 3 1.3.2. BOM 외 전이 의존성에 의해 UI가 1.9.2로 선택된다.
  최신 BOM 2026.09.00은 AAR 검사에서 API 37을 요구해 실패했으므로 기존 compileSdk를
  유지하는 BOM으로 고정하고 빌드로 검증했다.
- google(), mavenCentral(), 기존 Plugin Portal/Foojay 설정 유지.
- Pleos 저장소는 ai.pleos.playground 및 하위 group에만 허용.
- 이 디렉터리는 검사 당시 Git 저장소가 아니었음. 따라서 git diff 기반 변경 검증은 불가.

호환성 근거:
- [AGP 9.1.1: Gradle 9.3.1, JDK 최소 17, KGP 2.2.10](https://developer.android.com/build/releases/agp-9-1-0-release-notes)
- [AGP 내장 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Compose Compiler와 BOM 설정](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)
- [Activity 안정 버전](https://developer.android.com/jetpack/androidx/releases/activity)

## 2. 앱 구성

MainActivity(ComponentActivity), ui/theme/Theme.kt, feature/home/DevelopmentScreen.kt만 추가했다.
밝은 배경과 블루·민트 Material 3 색상, 스크롤 가능한 개발환경 화면, SDK별 미연결 상태,
앱 닫기 버튼을 제공한다. 시스템 바 영역은 safeDrawingPadding으로 피한다.

빈 Repository/ViewModel/UseCase, Hilt/Room/Retrofit, Mock 차량 데이터, 실제 서비스 기능은 없다.
SDK는 의존성으로 포함하되 객체 생성·서비스 초기화·위치 구독·음성 합성을 실행하지 않는다.
따라서 SDK 전용 API 권한도 직접 선언하지 않는다. 실제 연결 단계에 필요한 권한은 아래에 기록했다.

### SDK 없는 독립 빌드

```powershell
.\gradlew.bat :app:assembleDebug -PpleosSdkEnabled=false --console=plain
```

이 모드에서는 네 Pleos 의존성과 Pleos Maven 저장소를 제외하며 화면에 "미포함 · 독립 빌드 모드"를 표시한다.
기본값 true는 네 SDK를 포함하고 "의존성 포함 · 서비스 미연결"을 표시한다.
독립 빌드도 일반 Android/Compose 의존성과 SDK가 필요하므로 완전한 오프라인 빌드를 뜻하지 않는다.

## 3. AAOS Manifest 검토

- MAIN + LAUNCHER와 exported=true인 MainActivity를 선언했다.
- android.hardware.type.automotive는 required=true: AAOS 전용 앱으로 선언했다.
  FusedLocation AAR도 이를 필수로 선언하며 병합 Manifest에서도 true를 확인했다.
  폰 AVD에 adb sideload한 UI 점검은 Play 배포 적격이나 폰 공식 지원을 뜻하지 않는다.
- portrait/landscape를 필수 하드웨어로 요구하지 않는다.
- distractionOptimized=true, 운전 중 사용 허용 설정을 선언하지 않는다.
- Android Auto 전용 CAR_LAUNCHER나 com.google.android.gms.car.application을 추가하지 않았다.
- 현재 앱은 Car App Library/CarAppService 템플릿 앱이나 MediaBrowserService 앱이 아니다.
  따라서 해당 방식 전용 automotive_app_desc.xml 및 서비스 메타데이터를 형식적으로 만들지 않았다.
- 화면은 정차 중 개발 점검용이다. 주행 중 차단의 실제 동작은 Pleos AVD에서 별도 검증해야 한다.
- 일반 Android UI API와 Pleos의 Vehicle/FusedLocation/NaviHelper/Speech Service API를 구분한다.
  AAOS가 Google Play services 전체를 제공한다고 가정하지 않는다.
- android.hardware.type.automotive 선언만으로 배포 적격이나 차량 API 접근을 얻지 않는다.
- DRIVELOG의 커뮤니티/카드 화면과 TTS 브리핑의 최종 차량용 카테고리는 미확정이다.
  임의로 navigation/audio/video 카테고리를 표시하지 않았다.
  Google Play의 허용 카테고리·차량 앱 품질과 Pleos App Market/공모전 정책은 별개로 심사해야 한다.

공식 근거:
- [공모전 개발환경](https://appdevchallenge.auto-hmg.io/guide/development)
- [Android for Cars와 허용 카테고리](https://developer.android.com/training/cars)
- [AAOS 플랫폼과 feature 요구사항](https://developer.android.com/training/cars/platforms/automotive-os)
- [AAOS 정차 앱](https://developer.android.com/training/cars/parked/automotive-os)
- [차량용 배포 트랙](https://developer.android.com/training/cars/distribute)
- [운전자 주의 분산 제한](https://developer.android.com/training/cars/parked)

## 4. Pleos SDK 조사

공통 Maven 저장소:
https://nexus-playground.pleos.ai/repository/maven-releases/

네 버전의 POM HTTP 200을 로컬에서 인증 없이 확인했다.
이는 계정별 SDK 사용 승인, 서비스 접근, 모든 기능 제공을 보증하지 않는다.
Gradle 전이 의존성과 AAR 해결 및 네 SDK를 포함한 APK 빌드도 성공했다.
여기서 프로젝트 연동 완료는 의존성 해결·패키징 범위이며 서비스 초기화는 포함하지 않는다.
SDK AAR에서 가져온 바인딩 권한, 서비스 버전 메타데이터, package visibility queries와
TTS 전이 INTERNET 권한은 병합 Manifest에 남아 있다. 이 문자열을 앱 코드에 추측해 작성하지 않았다.
독립 빌드 Manifest에는 Pleos 바인딩 권한·메타데이터와 INTERNET 권한이 없음을 확인했다.

### Vehicle SDK

- 확인한 최신 한국어 설치 문서: 2.3.2.
- 좌표: ai.pleos.playground:Vehicle:2.3.2
- [설치](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup)
- [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission)
- [초기화·도메인 접근](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object)
- 문서 흐름: Vehicle(context) 생성 → initialize() → getXxx()로 도메인 획득 →
  capability/get/callback 호출 → 작업 완료 후 release().
- Odometer/DrivingMode/Brake의 대표 조회 권한: pleos.car.permission.CAR_DRIVE.
  Fuel/EvBattery: pleos.car.permission.CAR_ENERGY. 정확한 API별 @RequiresPermission 확인 필요.
- 주행시간·평균연비·누적거리 요구사항이 모두 직접 제공되는지는 아직 API별 미검증이다.
  일부는 데이터 누적/계산이 필요할 수 있으나 Phase 1에서 구현하지 않는다.
- 표준 폰 AVD가 아닌 Vehicle Service를 포함한 Pleos AVD가 필요하다.
- [샘플](https://document.pleos.ai/en/docs/pleos-only/tutorial/vehicle-sdk-sample-app) 기준
  SDK 2.3.2 / Service 2.3.22이며, 소개 문서의 Service 2.3.21과 차이가 있다.
  설치한 AVD의 Service·capability 결과로 실제 지원 여부를 확인해야 한다.
- 사용 신청 안내는 Vehicle State를 신청 없이 사용 가능, Control을 준비 중으로 설명한다.
  계정에 표시되는 실제 상태는 미확인이다.

### FusedLocation SDK

- 한국어 설치/소개/릴리즈 문서 1.1.3 확인.
- 좌표: ai.pleos.playground:FusedLocation:1.1.3
- [설치](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/project-setup)
- [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions)
- [생명주기](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/initialization-and-release)
- 객체 생성 → isSupported() → initialize() → 조회/구독 → 구독 해제 → release().
- 권한: pleos.car.permission.FUSED_LOCATION (dangerous). 실제 사용 시 런타임 승인 처리 필요.
- null 위치, onError, 차량 개인정보 보호 설정으로 인한 차단을 처리해야 한다.
- ACCESS_FINE_LOCATION/COARSE/BACKGROUND_LOCATION이나 Google Fused Location 동작으로 대체하지 않는다.
- Pleos AVD에서 검증하도록 안내되어 있으나 현재 설치/위치 수신은 미검증.
- 사용 신청 안내에는 준비 중으로 표시되어 있으므로 계정의 이용 가능 상태를 확인해야 한다.

### NaviHelper SDK

- 버전 3.0.5, 좌표 ai.pleos.playground:NaviHelper:3.0.5.
- [설치](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk)
- [권한](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/declaring-permissions)
- [초기화](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release)
- NaviHelper(applicationContext) → initialize(onConnected, onFailed) →
  연결 성공 후 리스너/API 사용 → removeListener()/release().
- 정보 조회/트립: pleos.car.permission.NAVI_ROUTE.
  경로 탐색·변경·경유지: pleos.car.permission.NAVI_ROUTE_SEARCH.
  Navigation 화면: pleos.car.permission.NAVI_CUSTOM_MAP.
  기능별 정확한 권한은 API 어노테이션으로 확인한다.
- Pleos Navigation 앱/Navi Service와 연결하므로 표준 AAOS AVD로 대체 검증하지 않는다.
- 신청 안내상 신청 없이 사용 가능. 실제 계정/에뮬레이터 내비게이션 상태는 미확인.

### TextToSpeech SDK

- 버전 3.3.1, 좌표 ai.pleos.playground:TextToSpeech:3.3.1.
- [설치](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/importing-sdk)
- [권한](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/declaring-permissions)
- [초기화](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/initialization-and-release)
- [모드·서비스·등록 조건](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/TextToSpeech/intro)
- ai.pleos.playground.tts.TextToSpeech가 진입점이며 android.speech.tts.TextToSpeech와 별개다.
- 객체 생성 → initialize() → 이벤트 등록 → 필요 시 locale/앱 등록 →
  서버 모드에서는 연결 성공 후 speak() → stop()/리스너 해제/release().
- 권한: pleos.car.permission.TTS_SERVICE.
- HYBRID/SERVER의 서버 합성에는 My Project client ID/secret 기반 앱 등록이 필요하다.
  ON_DEVICE 전용은 소개 문서상 네트워크·앱 등록이 불필요하다.
- SDK 사용 신청 문서의 "준비 중" 표기와 상세 SDK 가이드의 설치 가능 상태는 구분해야 한다.
  계정 상태/사용 권한/실제 Speech Service 제공 여부는 미확인이다.
- 실제 음성 출력, 언어 지원, 서버 연결은 미검증.

### 문서 버전과 계정 접근 주의

- [한국어 2026-10-19 릴리즈](https://document.pleos.ai/docs/pleos-only/release-note/sdk-261015):
  Vehicle 2.3.2, FusedLocation 1.1.3, NaviHelper 3.0.5, TTS/STT 3.3.1.
- 영어 릴리즈/Quick Start 일부는 Vehicle 2.3.1/FusedLocation 1.1.2를 안내한다.
  한국어 최신 설치 가이드와 실제 게시된 POM을 기준으로 버전을 고정했다.
- [프로젝트 생성·SDK 사용 신청](https://document.pleos.ai/docs/pleos-only/create-my-project):
  회원 가입 → My Project 생성 → SDK별 현재 상태 확인 → 필요한 항목만 신청/승인.
  계정 로그인·프로젝트 생성·약관 동의·신청은 이 작업에서 수행하지 않았다.
- STT는 이번 네 SDK 연동 대상에 포함하지 않았다. 마이크 권한도 추가하지 않았다.

## 5. Windows Pleos Connect AVD 설치

검사 당시 Pleos/일반 Automotive 시스템 이미지와 AVD는 없었다.
기존 AVD는 Pixel_3a_2(폰)뿐이며 이것은 차량 Service 검증 환경이 아니다.
Windows WHPX 가속은 emulator -accel-check에서 installed and usable로 확인했다.

공식 절차:
1. Android Studio > Tools > SDK Manager > SDK Update Sites > Add.
2. Name: Pleos Connect System Image
3. Windows/Linux URL:
   https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml
4. SDK Platforms에서 Show Package Details를 켜고 API 34 Pleos Connect 이미지를 설치한다.
5. Android Studio 재시작 후 Tools > Device Manager > Create device > Automotive >
   Pleos Connect v2.1.0을 선택한다.
6. Advanced Settings에서 Cold boot와 RAM 4GB를 설정한다.
7. API 34의 Pleos Connect v2.1.0 APIs 이미지를 선택해 AVD를 생성·실행한다.
8. Virtual Console/마이크 등은 필요한 기능의 공식 설정에 따라 구성한다.
9. DRIVELOG의 app 실행 구성을 선택하고 설치·실행한 다음 SDK별 Service 버전과
   연결 콜백, capability/permission 결과를 점검한다.

Windows에서는 BIOS/UEFI 가상화와 Windows Hypervisor Platform 조건을 확인한다.
설치 중 다운로드·라이선스/계정 화면이 표시되면 사용자가 직접 처리해야 한다.
API 34 기기 실행과 compileSdk 36.1은 별개이며 minSdk 28 조건을 충족한다.

근거:
- [Pleos AVD 설치](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- [Windows 에뮬레이터 하드웨어 가속](https://developer.android.com/studio/run/emulator-acceleration)

## 6. 검증 결과

최종 검증 결과와 로그는 verification/RESULTS.md에 기록한다.
Android Studio GUI Sync 실행 여부와 Gradle CLI 의존성 해결 결과는 구분한다.
실제 Pleos 차량 API 호출 검증은 전 SDK 미완료다.

## 7. 다음 단계

1. Android Studio에서 Gradle Sync 후 기본 app 실행 구성 확인.
2. Pleos 계정 프로젝트와 SDK별 사용 상태 확인, API 34 Pleos AVD 설치.
3. SDK별 최소 연결/해제 및 실패 처리를 실제 AVD에서 검증.
4. 필요한 차량 조회 API·권한·수집 가능 주기·백그라운드 정책을 기능별로 확정.
5. 정차/주행 상태별 UX와 Pleos 카테고리·배포 요구사항을 확인한 뒤 실제 기능 개발.
