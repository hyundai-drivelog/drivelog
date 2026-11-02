# Phase 1 검증 결과 — 2026-11-02

## 완료 범위

최소 Compose/Material 3 Activity, Theme, 차량용 Manifest, 네 Pleos SDK 의존성 통합,
SDK 제외 독립 빌드, APK 생성까지 완료했다. 실제 Pleos Service 연결은 전 SDK 미검증이다.

| 검증 | 결과 | 증거 |
|---|---|---|
| Gradle 설정 평가 및 의존성 해결 | 성공 | dependencies-sdk.log |
| Android Studio GUI Gradle Sync | 실행하지 않음 | IDE에서 Sync Now 수행 필요 |
| SDK 포함 assembleDebug | 성공 | assemble-sdk.log, final-build-lint.log |
| SDK 제외 assembleDebug | 성공 | assemble-independent.log |
| Kotlin 컴파일 / AAR 메타데이터 검사 / Manifest 병합 | 최종 빌드 성공 | final-build-lint.log |
| lintDebug | 성공, 0 errors / 14 warnings | app/build/reports/lint-results-debug.txt |
| SDK 포함/제외 Manifest 검사 | 완료 | merged-with-sdk.xml, merged-independent.xml |
| Pleos/일반 AAOS Emulator | 설치되어 있지 않아 미실행 | 설치 안내는 ../PHASE1.md |
| 기존 폰 AVD 부팅 및 APK 설치 | 성공 | Pixel_3a_2, adb install: Success |
| MainActivity 실행 | am start -W Status: ok, COLD | 아래 런타임 한계 참고 |
| 앱 화면 렌더링 | 확인 | phone-screen.png, phone-screen.xml |
| 실제 차량·위치·내비·TTS API 호출 | 전 SDK 미실행 | 화면은 서비스 미연결 표시 |

## 생성 APK

- 기본 SDK 포함: app/build/outputs/apk/debug/app-debug.apk
- SDK 포함 보관본: build/phase1/drivelog-with-sdk-debug.apk
- SDK 제외 보관본: build/phase1/drivelog-independent-debug.apk
- 기본 APK 크기: 52,943,057 bytes.
- 독립 APK 크기: 48,885,819 bytes.
- 기본 APK SHA-256:
  2C8D30AB725E5D8519CB998526372EA6D9517761FC91E5EACDABF10856158995
- 모두 개발용 debug 서명이다. 배포용 서명·마켓 제출은 이번 범위에 포함하지 않는다.

## 실행한 핵심 명령

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:assembleDebug -PpleosSdkEnabled=false --console=plain
.\gradlew.bat :app:assembleDebug :app:lintDebug --console=plain
```

마지막 빌드는 기본 SDK 포함 모드다. 독립 모드로 빌드하면 app/build/outputs의 동일 APK 경로를
덮어쓰므로 비교용 APK는 build/phase1에 따로 보관했다.

## 병합 Manifest 확인

두 모드 모두 automotive=true / portrait=false / landscape=false,
minSdk 28 / targetSdk 36, exported MainActivity와 MAIN/LAUNCHER를 확인했다.
distractionOptimized나 Android Auto 전용 메타데이터는 선언하지 않았다.

SDK 포함 모드의 바인딩 권한·버전 메타데이터·서비스 queries와 INTERNET은
다운로드한 SDK AAR의 Manifest에서 들어왔다. 앱에서 임의 생성한 권한이 아니다.
독립 모드에는 Pleos 관련 항목과 INTERNET이 없다.
AndroidX의 DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION은 두 모드 모두 존재한다.

## 런타임 점검의 한계

기존 Pixel_3a_2는 API 37.1, x86_64, 16KB 페이지의 폰 이미지다.
Pleos Connect API 34 이미지나 표준 AAOS 이미지가 아니다.
Windows WHPX 가속은 사용 가능했고, adb 설치와 Activity 시작, 화면 렌더링을 확인했다.

첫 화면 점검에서 16KB 호환성 경고가 표시되었다.
경고에 libjnidispatch.so, libandroidx.graphics.path.so, libuacore.so가 나열되었고,
최종 lint는 특히 전이 의존성 net.java.dev.jna:jna:5.13.0의
x86_64/libjnidispatch.so 비정렬을 보고했다.
경고는 닫고 실제 화면을 확인했으나, 이것은 16KB 네이티브 호환성 통과가 아니다.

부팅 직후 System UI isn't responding 대화상자도 발생했다.
System UI의 Wait를 선택한 뒤 호환성 경고의 OK를 눌러 화면을 확인했다.
앱 자체 ANR로 판단할 근거는 없으며, 안정적인 장시간 실행 검증은 하지 않았다.
검증을 위해 시작한 headless 폰 에뮬레이터는 점검 후 종료했다.

16KB 네이티브 라이브러리는 공급자 지원 버전·공식 배포 조건을 확인해야 한다.
검증 없이 전이 의존성을 강제 교체하거나 바이너리를 수정하거나
경고를 숨기는 설정을 추가하지 않았다.
[Android 16KB 안내](https://developer.android.com/guide/practices/page-sizes)

## Lint 경고

0 errors / 14 warnings:
- 기존 AGP/Core 및 Kotlin/Compose에 대한 최신 버전 안내
- JNA x86_64 16KB 정렬 경고 (중복 보고 포함)
- 템플릿 colors.xml의 미사용 색상 리소스

기존 도구 호환성 유지를 위해 최신 버전 안내를 이유로 일괄 업그레이드하지 않았다.
최초 최신 Compose BOM 시도는 API 37 요구사항 때문에 실패했고,
기존 compileSdk 36.1을 유지하는 BOM 2025.08.00으로 조정한 최종 빌드는 통과했다.
최종 소스나 Manifest에 빌드 오류를 무시하는 플래그, lint baseline, lint 오류 억제를 추가하지 않았다.

## SDK 상태

| SDK | 버전 | 단계 |
|---|---|---|
| Vehicle | 2.3.2 | 공식 문서 확인 / 프로젝트 연동 완료(의존성) |
| FusedLocation | 1.1.3 | 공식 문서 확인 / 프로젝트 연동 완료(의존성) / 계정 제공 상태 확인 필요 |
| NaviHelper | 3.0.5 | 공식 문서 확인 / 프로젝트 연동 완료(의존성) |
| TextToSpeech | 3.3.1 | 공식 문서 확인 / 프로젝트 연동 완료(의존성) / 서비스·서버 모드 자격 증명 확인 필요 |

모두 "실제 API 호출 검증 완료" 단계가 아니다.
공식 설치·권한·초기화·AVD·계정 문서 링크는 ../PHASE1.md에 정리했다.
