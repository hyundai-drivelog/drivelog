# DRIVELOG

Pleos Connect 기반 차량용 주행 기록·드라이브 카드 앱입니다.

- `app/`: Kotlin + Jetpack Compose Android 앱
- `server/`: 독립적인 Spring Boot 백엔드
- `docs/`: 개발 및 검증 기록

## 문서

- [Pleos 설계 원칙](docs/guides/PLEOS_DESIGN_PRINCIPLES.md): 설계 기준, PRD에서 바뀐 결정, 미결 사항
- [Pleos API 연동](docs/guides/PLEOS_API_INTEGRATION.md): API 선택 규칙, 퍼미션, 연동 방법, 검증 결과

## 사용 흐름

1. 차량에서 정차 중 내비 즐겨찾기·최근·현재 목적지로 장소 3곳을 골라 목표를 만든다.
2. 장소를 선택해 기록을 시작한다. 내비 안내는 원할 때만 따로 요청한다.
3. 정차하면 기록을 끝내고 방문을 직접 확인한다.
4. 휴대폰을 연결하면 기록이 올라가고, 모바일 웹에서 사진 카드를 만들어 저장·공유한다.

## 실행

서버와 모바일 웹. PostgreSQL 없이 실행하는 `demo` 프로필이다.

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

## 남은 작업

- 저장된 즐겨찾기, `onDestinationArrived`, Odometer 값, 실제 분할 화면, CPU 사용량 검증
- 앱 카테고리와 주행 중 화면 유지 허용 범위 확인 (DO 선언은 심사 대상)
- 목표 사진은 목업에서 잘라낸 저해상도 데모 이미지이므로 교체 필요
- 지도 배경은 장식이고 경로선만 실제 데이터다. 지도 공급자 선정 필요
- 실차 검증

서버 상세는 [Server README](server/README.md), 이전 검증 기록은 [Pleos 런타임·MVP 검증](docs/PLEOS_RUNTIME_MVP.md)을 참고한다.
