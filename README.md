# DRIVELOG

Pleos Connect 기반 차량용 주행 기록·드라이브 카드 앱입니다.

- `app/`: Kotlin + Jetpack Compose Android 앱
- `server/`: 독립적인 Spring Boot 백엔드
- `docs/`: 개발 및 검증 기록

## 문서

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

## 남은 작업

- 실제 음성의 인식 결과, 저장된 즐겨찾기, `onDestinationArrived`, Odometer 값, 실제 분할 화면, CPU 사용량 검증
- 앱 카테고리와 주행 중 화면 유지 허용 범위 확인 (DO 선언은 심사 대상)
- 목표 사진은 목업에서 잘라낸 저해상도 데모 이미지이므로 교체 필요
- 드라이브 카드 지도는 OpenFreeMap을 쓴다. 홈과 목적지 확인 화면의 지도 배경은 아직 예시다
- 실차 검증

서버 상세는 [Server README](server/README.md), 이전 검증 기록은 [Pleos 런타임·MVP 검증](docs/PLEOS_RUNTIME_MVP.md)을 참고한다.
