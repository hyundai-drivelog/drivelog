# Pleos API 연동 가이드 (DRIVELOG 적용판)

[앱 개발·연동 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/intro)와 [SDK 사용 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/)를 2026-11-03에 읽고, 실제 AAR 시그니처(javap)와 대조해 정리했다. 설계 근거는 [PLEOS_DESIGN_PRINCIPLES.md](PLEOS_DESIGN_PRINCIPLES.md)에 있다.

## 1. API 선택 규칙

| 데이터·기능 | 사용할 API | 비고 |
|---|---|---|
| 기어 상태 | **AAOS `CarPropertyManager` `GEAR_SELECTION`** | SDK `getCurrentGearSelectionState`는 deprecated |
| 주행 규제 | **AAOS `CarUxRestrictionsManager`** | `isRequiresDistractionOptimization`, `getActiveRestrictions` |
| 클러스터 속도 | AAOS `PERF_VEHICLE_SPEED` | 현재 기능에 불필요해 미사용 |
| 주행거리 | Vehicle SDK `Odometer` | capability 확인 필수, 단위 미검증 |
| 배터리·연료 잔량 (주행 전비·연비) | **AAOS `EV_BATTERY_LEVEL`(Wh), `FUEL_LEVEL`(mL)** | Pleos `getCurrentEvEfficiency`·`getCurrentFuelEconomy`는 에뮬레이터에서 `ERROR_NOT_SUPPORTED_PROPERTY`, 의미·단위 미확인 |
| 위치 | FusedLocation SDK | `isSupported` 확인 필수 |
| 장소 출처, 경로 요청, 도착 | NaviHelper SDK | 내비 탑재 차량만 |
| 음성 안내 | TextToSpeech SDK | 오디오 포커스 선행 |
| 음성 입력 | SpeechToText SDK | `ON_DEVICE`, 오디오 포커스 선행, 탭할 때만 |
| 장소 검색 | NaviHelper `SearchPlace` | 결과를 앱이 받지 않고 내비 화면에서 고른다 |

공식 정책: 표준 데이터는 AAOS, 제조사 특화 기능은 Pleos SDK. SDK 전용 기능이 AAOS로 편입되어도 최소 2개 메이저 버전은 호환이 유지된다.

코드 위치: AAOS는 `core/vehicle/VehicleStateMonitor.kt`, Pleos SDK는 `src/pleos/.../core/platform/PleosCarPlatform.kt`. `-PpleosSdkEnabled=false` 빌드에서는 `src/noPleos`의 스텁을 쓴다.

## 2. 저장소와 의존성

```kotlin
// settings.gradle.kts
maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
// libs.versions.toml
// Vehicle 2.3.2 / FusedLocation 1.1.3 / NaviHelper 3.0.5 / TextToSpeech 3.3.1 / SpeechToText 3.3.1
```

## 3. 프로젝트와 사용 신청

- Playground의 My Project에서 프로젝트를 만들고 SDK 상태를 확인한다.
- 사용 가능: Vehicle State, NaviHelper. 준비 중: FusedLocation, TextToSpeech, SpeechToText, Vehicle Control. **문서 기준이며 실제 콘솔 상태 확인이 필요하다.**
- **CRN은 에뮬레이터 2.1.0 이후 입력이 필요 없다.**
- Client ID/Secret은 TTS·STT의 서버 연결에 쓰이며 앱에 넣지 않는다. 그래서 `Mode.ON_DEVICE`만 사용한다.
- 퍼미션은 App Market과 협의·승인된 범위에서만 쓸 수 있다.

## 4. 퍼미션

| 퍼미션 | 용도 | 검증 |
|---|---|---|
| `android.car.permission.CAR_POWERTRAIN` | 기어 (`GEAR_SELECTION`) | AAOS 표준 |
| `android.car.permission.CAR_ENERGY` | 배터리·연료 잔량 (`EV_BATTERY_LEVEL`, `FUEL_LEVEL`) | AAOS 표준. **런타임 권한**이라 기록 시작 때 위치와 함께 세션당 한 번 요청한다. 거절해도 기록은 되고 전비만 빠진다 |
| `pleos.car.permission.CAR_DRIVE` | 주행거리 (`Odometer`) | 문서 |
| `pleos.car.permission.FUSED_LOCATION` | 위치 (런타임 요청 필요) | 기존 검증 기록 |
| `android.permission.ACCESS_FINE_LOCATION` | 위치 타입 포그라운드 서비스 | Android 정책 |
| `pleos.car.permission.NAVI_ROUTE` | `get*Info()` | 문서 |
| `pleos.car.permission.NAVI_ROUTE_SEARCH` | `requestRoute()` 등 | 문서 |
| `pleos.car.permission.NAVI_CUSTOM_MAP` | `requestNavigationScreen()` | 문서 |
| `pleos.car.permission.TTS_SERVICE` | TTS | 공개 샘플에서 확인, 공식 표 확인 필요 |
| `pleos.car.permission.STT_SERVICE` | STT | 문서. 앱에 `RECORD_AUDIO` 없이 녹음 시작 확인 |

진단 전용 `pleos.car.permission.CAR_INFO`, `pleos.car.permission.CAR_ENERGY`는 debug 매니페스트에만 둔다. 쓰지 않는 퍼미션은 반려 사유다.

## 5. 주행 규제

```kotlin
manager.registerListener { restrictions ->
    val requiresDo = restrictions.isRequiresDistractionOptimization
    val active = restrictions.activeRestrictions
}
```

- DO는 `<activity>`의 `<meta-data android:name="distractionOptimized" android:value="true" />`로 선언한다 (AAOS 표준).
- 판정: UX 제한이 없고 기어가 P 또는 미보고일 때만 `parked`. 상태 미확인은 잠금으로 처리한다.
- 테스트: `adb shell cmd hmg_car_service --service drivingstate driving_regulation on|off`

## 6. NaviHelper

```kotlin
val helper = NaviHelper(context)
helper.initialize(onConnected = { helper.addListener(listener) }, onFailed = { /* 내비 없음 */ })
// 해제: removeListener(같은 인스턴스) → release()
```

| 목적 | 호출 | 결과 콜백 |
|---|---|---|
| 즐겨찾기 | `getBookmarkInfo()` | `onBookmarkInfo(List<BookmarkInfo>)`, 변경 시 `onBookMarkChanged()` |
| 최근 목적지 | `getRecentDestinationInfo()` | `onRecentDestinationInfo(List<RecentDestinationInfo>)` |
| 장소 검색 | `requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword))` | 없음. 결과는 내비 화면에 표시 |
| 목적지 받기 | `getDestinationInfo()` | `onDestinationInfo(DestinationInfo)` |
| 경로 요청 | `requestRoute(RouteInfo)` | `onRouteStarted`, `onRoutingCompleted`, `onError` |
| 안내 종료 | `cancelRoute()` | `onRouteCancelled` |
| 내비 화면 열기 | `requestNavigationScreen(NavigationScreenRequest.Foreground)` | 없음 |
| 도착 | - | `onDestinationArrived(DestinationArrivedInfo)` |
| 안내 상태 | `getRouteStateInfo()` | `onRouteStateInfo` (`naviMode`, `isRouting`) |

- `requestRoute()` 반환은 탐색 완료가 아니다. **결과는 콜백으로만 판단한다.**
- `RouteInfo`에는 내비가 준 `poiId`, `poiSubId`, `poiProvider`를 그대로 넘긴다. 그래서 장소에 이 값을 저장한다.
- 내비 탑재 여부를 확인하는 공개 방법은 없다. 서비스 존재 여부와 `onFailed`로 판단하고, 없으면 해당 기능을 숨긴다.

| 오류 코드 | 안내 문구 |
|---|---|
| `CAUSE_LOW_BATTERY` | 배터리가 부족해 경로를 찾을 수 없어요 |
| `CAUSE_SEARCH_ROUTE_OVER_DISTANCE` | 탐색 가능한 거리를 넘었어요 |
| `CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE` | 목적지가 너무 가까워요 |
| `CAUSE_SEARCH_ROUTE`, `CAUSE_SEARCH_TRIP` | 경로를 찾지 못했어요 |

## 7. FusedLocation

```kotlin
val sdk = FusedLocation(context)
if (sdk.isSupported()) {
    sdk.initialize()
    sdk.registerFusedLocationCallback(listener) // onChanged(LocationData?), onError(Exception)
    sdk.addProximityAlert(ProximityAlertRequest(lat, lon, radiusMeters, setOf(ProximityTransition.ENTER)), alertListener)
}
```

- `confidence`의 단위가 불명확해 정확도(m)로 쓰지 않는다.
- 250km/h를 넘는 좌표 이동은 거리에서 제외한다.

## 8. Vehicle SDK

- `Vehicle(context).initialize()` 후 `getOdometer()`를 사용하고, 끝나면 `release()`.
- 값을 읽기 전에 `checkCurrentDistanceDrivenCapabilities`로 지원 여부를 확인한다.
- 에뮬레이터에서 값이 `0.0`이고 단위가 미검증이므로, **GPS 거리와 ±25% 안에서 일치할 때만** `차량 주행거리`로 표시하고 나머지는 `추정 거리`로 표시한다.

### 주행 전비·연비

- 기록 시작과 끝의 잔량 차이로 계산한다(`Efficiency.kt`, `Recorder`). 거리는 기록 거리(GPS 추정 또는 차량 주행거리)다.
- 기록 중 15초마다 잔량을 읽어 값이 바뀌는 가장 작은 폭(눈금)을 알아낸다. 쓴 양이 눈금 5개보다 적으면 반올림 오차가 커서 보이지 않는다. 1km 미만, 충전으로 늘어난 경우, 전비 1~15km/kWh·연비 2~50km/L 밖도 보이지 않는다.
- 시뮬레이션 기록에는 읽지 않는다. 복구한 부분 기록에는 없다.
- 기록 완료·상세 화면의 수치 아래 한 줄에만 보인다(`전비 · 0.50 kWh 사용`). 주행 중 화면과 카드에는 넣지 않는다.

## 9. TTS와 오디오 포커스

- `TextToSpeech(context, Mode.ON_DEVICE)` → `initialize(listener)` → `speak(text, flush)`.
- 말하기 전에 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`을 요청하고, `onDone/onError/onStop`에서 반납한다.
- 선행 묵음은 150~200ms가 기준이다. SDK가 PCM에 접근할 수 없어 가이드의 3순위인 재생 지연(150ms)을 쓴다.

2026-11-11 주행 종료 음성 중복 제보 후: 내비 안내 중과 종료/도착 후 4초는 앱 TTS·효과음을 생략하며, 자동 저장 결과와 내비 종료 문구는 화면으로 안내한다. TTS focus request는 `setWillPauseWhenDucked(true)`와 손실 리스너를 사용한다. 손실 시 SDK `stop()`·예약 콜백 취소·포커스 반납을 수행하며 자동 재개하지 않는다. 내비 상태는 `getRouteStateInfo()`/`onRouteStateInfo`의 `isRouting`으로 앱 복귀 때도 확인한다. 상세 설치·재현 상태는 [음성 중복 수정 검증](../verification/NAVIGATION_AUDIO_2026-11-11.md)을 따른다.

2026-11-11 사용자 요청으로 상태 효과음을 추가했다. `SoundEffects`가 sonification 포커스를 짧게 사용하고 반납하며, 포커스를 잃으면 중단한다. `CarPlatform.playCue`는 효과음 후 필요한 기존 TTS를 호출한다. 효과음 비활성·샘플 미준비·재생 실패에서는 기존 TTS 경로로 진행한다. 효과음 설정은 TTS를 끄지 않는다. 일반 확인음은 정차·앱 전경에서만 요청하며, TTS 진행 중과 음성 인식 중에는 별도 효과음을 생략한다. 상세 검증 한계는 [사운드 설계](../design/SOUND_FEEDBACK_2026-11-11.md)를 따른다.

## 10. Back 키

```kotlin
onBackPressedDispatcher.addCallback(this) {
    if (!model.goBack()) moveTaskToBack(true) // finish() 금지
}
```

## 11. Sync와 ADAS

- 사용자가 받은 소개 자료에는 있지만 **현재 SDK 사용 가이드 목록(5종)에 없고** 공개 Nexus에도 없다 (`ai.pleos.sync:syncSDK`, `Adas` 모두 404).
- 다만 Pleos 이미지에는 `ai.pleos.playground.service.sync`, `service.adas` 서비스가 설치되어 있다. 클라이언트 SDK를 받는 경로는 `partnership@pleos.ai`에 문의가 필요하다.
- PRD에 ADAS가 필요한 기능은 없다.
- 동기화는 자체 서버 + `JourneyStore` outbox와 별도 Android 앱으로 구현했다. 연결별 저장·대기열과 명시적 전송 동의를 사용한다. 차량은 전체 기록 `GET /api/v1/journey/vehicle`, 모바일은 최근 200건 `GET /api/v1/journey`를 사용한다. 구서버의 전용 API 404에서는 로컬을 유지하고 실패를 표시한다.
- 아티팩트를 받으면 차량 쪽은 `JourneyStore`만 교체하면 된다.

## 12. 에뮬레이터 검증

- 시스템 이미지: `system-images/android-34/pleos_car_emulator/x86_64`. **`Pleos_Connect_v2.1.0` 이름의 기존 AVD는 일반 AAOS(android-35)를 가리키며 Pleos 서비스가 없다.**
- AAOS 사용자는 10이다 (`am get-current-user`). 멀티 디스플레이라 `screencap`에 `-d <id>`가 필요하다.
- 실행 AVD: `DRIVELOG_Pleos` (`emulator -avd DRIVELOG_Pleos -port 5556 -no-snapshot`).
- Virtual Console: `adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`. Motion 도메인에서 기어를 바꾼다. 자동화할 때는 `POST /browser/session?client=<id>`를 1초 간격으로 보내 세션을 유지하고 `PUT /vehicle/gear?gear=Drive|Park`를 호출한다.
- `driving_regulation` 명령의 `hmg_car_service`는 에뮬레이터에 없어 실차에서만 쓸 수 있는 것으로 보인다.
- 위치 주입: `adb emu geo fix <경도> <위도>`.
- 2026-11-02 런타임 검증에서 본 목적지 좌표 불일치는 이번에 재현되지 않았다. 지도 타일 미표시는 여전하다.
- 한글 경로에서 Gradle 테스트가 클래스를 못 찾으면 build 디렉터리를 ASCII 경로로 옮긴다.

## 13. 실제 동작에서 확인한 사실 (2026-11-03, Pleos 에뮬레이터 2.1.0)

**실차가 아니다.** 위치는 `geo fix`로, 기어는 Virtual Console로 주입했다.

| 항목 | 결과 |
|---|---|
| `GEAR_SELECTION` (AAOS) | 통과. P/D 변경이 앱에 즉시 반영됨 |
| `requestRoute` | 통과. 실제 경로 생성, **내비가 자동으로 앞 화면에 나옴** |
| `getDestinationInfo` | 통과. 이름과 주소를 내비 기준으로 돌려줌 (예: 사하구 다대동) |
| `getRecentDestinationInfo` | 동작. 경로를 3번 요청해도 1건만 반환됨. 누적 조건 미확인 |
| `getBookmarkInfo` | 콜백 수신. 깨끗한 에뮬레이터에서는 이름 없는 빈 항목만 와서 걸러냄. **저장된 즐겨찾기로는 미검증** |
| FusedLocation 연속 위치, 근접 알림 | 통과. 진입 시 "목적지 근처" 표시 |
| `onDestinationArrived` | 미검증 (실제 안내 완주 필요) |
| TTS `ON_DEVICE` | 통과. `Locale.KOREAN`은 `unsupported locale: ko-`로 실패하므로 **`Locale.KOREA` 필수** |
| Odometer | 호출 코드만 검증. 기록은 모두 `GPS_ESTIMATED`로 저장됨 |
| 주행 중 잠금, 정차 시 종료 화면, 강제 종료 후 복구, 오프라인 재전송 | 통과 |
| 메모리 | 약 107MB (기준 400MB). CPU는 아래 2026-11-06 측정 참고 |
| `SearchPlace` | 통과. 검색 결과가 내비에 표시됨. 안내 중에는 처음에 자동완성과 키보드가 함께 나올 수 있음 |
| 검색 후 장소 받기 | 통과. 결과에서 목적지를 정하면 안내가 바로 시작되고, 앱이 앞으로 돌아올 때 `onDestinationInfo`로 이름·좌표를 받음 |
| 앱이 뒤에 있을 때 콜백 | 내비가 앞에 있는 동안 전달되지 않고 복귀 시 몰아서 옴. **콜백 즉시 수신을 전제로 하지 않는다** |
| `cancelRoute` | 통과. `onRouteCancelled` 수신 |
| STT `ON_DEVICE` | 실제 소리의 인식 통과(2026-11-05). 에뮬레이터는 호스트 마이크가 켜져 있어야 한다(`adb emu avd hostmicon`). 꺼지면 녹음은 되지만 무음이다(peak rms 약 5, 켜면 약 5,400). `onReady`까지 재부팅 직후 약 9초, 이후 1초 미만. 듣기 제한 시간은 `onReady`부터 센다. 인식 결과는 검색창에만 넣고 `찾기`를 눌러야 내비 검색을 연다. **또렷한 목적지 발화의 정확도와 실차 마이크는 미검증** |
| PiP | `android.software.picture_in_picture`가 `false`. Pleos 문서에도 언급 없음 |
| 2분할 (내비와 나란히) | 창 바 조작으로 통과. 검색 결과가 내비 창에 뜨고 앱은 그대로 보이며, 안내가 시작되면 앱에 바로 반영됨. 앱이 분할을 요청하는 방법은 문서에 없음 |
| 2분할일 때 콜백 | `onDestinationChanged` 없이 `onRouteStarted`만 관찰됨. 앱이 복귀하지 않으므로 이때 `getDestinationInfo()`를 호출한다 |
| `cancelRoute` 후 화면 | 내비가 앞으로 나옴 |
| 안내 종료와 기록 | 내비의 `안내 종료`는 `onRouteCancelled`로 오고, 내비가 앞에 있어도 바로 전달됨. 주행 중에는 기록을 유지하고 P에서 저장 (D→종료→P 통과) |
| `onRouteEnded` | **안내 종료 신호가 아니다.** 새 경로를 시작하면 `onRouteStarted` 직후 함께 온다. 믿으면 기록이 시작하자마자 끝나므로 쓰지 않는다 |
| 새 경로 요청 시 신호 전달 | 내비가 앞에 있는 동안 전달되지 않고 앱 복귀 시 함께 옴 (`started`, `ended`) |
| 주행 중 잠금 | Virtual Console로 D 전환 시 홈 대신 `주행 중이에요` 표시. 세션을 1초 간격으로 유지해야 기어가 바뀜 |
| 내비 경로선 | NaviHelper 3.0.5에 경로 좌표 API 없음(AAR 확인). `TripInfo`도 경유지·충전소 지점만 제공. 카드의 선은 앱이 기록한 GPS 경로 |
| 카드 지도 | MapLibre 13.7.1 `MapSnapshotter` + OpenFreeMap 타일에 앱 자체 레이어(`routeMapStyle`: 육지·바다·도로만, 건물과 글자 없음). 온라인에서 생성, 저장 후 오프라인 표시, 네트워크 없으면 지도 없는 카드로 대체 후 다음 기회에 재시도 통과. `RouteMapCache.kt` |
| 지도 일괄 생성 | 기록마다 `MapSnapshotter`를 새로 만들면 5장째에 `Vulkan buffer allocation failed` 후 `std::bad_alloc`으로 앱이 죽었다. 앱 전체에서 하나만 만들어 재사용한다. 스타일을 바꾸면 `route-maps-v3`의 버전을 올려 다시 그린다 |
| 휴대폰 화면 미리보기 | 실제 연동 없는 목업(`PHONE_PREVIEW_*`, 목업 07·08). debug 빌드이고 `phoneLinkEnabled=false`일 때만 기록 화면에 `휴대폰으로 보내기`가 나온다. 서버·토큰·기록을 건드리지 않고 시뮬레이션으로 표시한다 |
| 경로 모양 그림 | `RouteGlyph`. 기록 좌표만으로 목록에 그려 지도 요청과 통신이 없다. 애니메이션 없음 |
| 커뮤니티 코스 미리보기 | 실제 커뮤니티 없는 목업(`COMMUNITY`, `COURSE_*`, `CommunityCourses.kt`). debug 빌드에서만 보인다. 예시 코스는 앱에 들어 있고, 내 추천은 메모리에만 두어 앱을 끄면 사라진다. 공개 미리보기는 경로 앞뒤 500m를 자르고 출발지는 동네 이름만 쓴다(작은 표 기반 예시). 안내 버튼은 기존 장소 확인 화면으로 이어지고 목적지까지만 안내한다 |
| 화면용 실제 지도 | 기록 종료 확인·복구 화면(`LiveRouteMap`). 온라인이면 테마에 맞는 지도, 오프라인이면 `지도 배경은 예시예요`. 화면을 빠르게 다시 열면 `Map is currently rendering an image`로 거절될 수 있어 1초 뒤 한 번 재시도한다. 복귀 10회 반복 통과, 앱 메모리 약 200MB. **주행 전환 시 요청 취소는 실제로 시험하지 않았다** |
| 지도 라이브러리 캐시 | 앱 저장소에 `mbgl-offline.db`가 생긴다. 기록을 삭제하면 `clearAmbientCache`와 `packDatabase`로 지도 조각 캐시 전체를 비운다. 다른 기록의 카드 이미지는 남는다 |
| 목록 좌표 | 같은 장소라도 목록마다 좌표와 주소 표기가 조금 다르다. 목록 중복 제거에 이름도 쓴다 |
| 재현 스크립트 (2026-11-06, debug 빌드) | `scripts/demo-drive.ps1`로 D 전환, 위치 주입, P 복귀 통과. 3.9km 기록, 기록 중 충돌 없음. 시작 위치를 먼저 옮기지 않으면 첫 좌표가 순간이동으로 걸러지고 뒤따른 좌표도 모두 버려져 0.0km가 된다(`-Prepare`) |
| 카드 경로 (2026-11-06, debug 빌드) | 실제 도로 좌표(`scripts/demo-route.json`) 5.6km를 주입해 5.5km 기록. 지도 위 도로를 따라 그려지고 1~5km 표시 확인. 이후 속도 색을 빼서 캐시는 `route-maps-v5`. **실제 차량 GPS로는 미검증** |
| 내비 경로선 (2026-11-06) | `navi-data` 3.0.4 공개 타입에 경로 좌표 없음. `RoutePointInfo`는 출발·경유·도착 지점뿐. `NavigationScreenRequest.OverviewRoute`로 내비의 경로 개요를 열고, `RouteStartInfo.distance/duration`을 표시한다. 통과(2026-11-06): 앱의 `13 km · 약 18분`이 내비 개요의 `18분 · 13km`와 일치해 distance는 m, duration은 초다. 에뮬레이터 내비는 `geo fix`와 다른 자기 위치에서 경로를 계산하므로 장소 화면의 `현재 위치`와 경로 출발점이 다를 수 있다 |
| 구간 기록, 경로 재생, QR 카드 (2026-11-06, debug 빌드) | 5.5km 시험 주행에서 구간 6개 표시, `경로 다시 보기`로 지도 위 재생 확인. 화면의 QR을 ZXing으로 다시 읽어 367자 주소를 얻었고, 그 주소로 `web/card/index.html`이 같은 경로의 카드를 그렸다(Chrome headless). **실제 휴대폰 카메라 인식과 공개 호스팅은 미검증** |
| 시험 중 시스템 재시작 (2026-11-06) | 내비가 앞에 있는 주행 중 `android.fg`가 68초 멈춰 Watchdog이 system_server를 재시작했다. 앱 충돌 기록은 없고 복구 화면이 정상 동작했다 |
| 주행 전비 (2026-11-08, debug 빌드) | `EV_BATTERY_LEVEL`·`INFO_EV_BATTERY_CAPACITY`(77.4kWh)·`EV_BATTERY_INSTANTANEOUS_CHARGE_RATE`·`FUEL_LEVEL`·`PERF_ODOMETER`가 HAL에 등록돼 있다. 주행해도 잔량은 줄지 않고 순간 전력은 0이며, `FUEL_LEVEL`은 값이 없다. Virtual Console `PUT /vehicle/ev-battery?field=battery_level&value=<kWh>`(세션 유지 필요)로 잔량을 바꿀 수 있지만 **1kWh 단위로 반올림된다**. `demo-drive.ps1 -KmPerKwh`로 5.5km 주행에 77.4→77.0kWh가 되어 처음 계산은 13.8km/kWh(의도 6.5)로 틀렸다. 그래서 눈금 5개 기준을 넣었다. 다시 시험(77.0kWh에서 시작, `-KmPerKwh 1.2`)에서 15초 측정이 눈금 1000Wh를 알아내고 77→72kWh(눈금 5개)로 `1.1 km/kWh · 5.00 kWh 사용`을 저장·표시했다. 에뮬레이터의 5.6km 경로로는 현실적인 전비가 눈금 5개를 넘지 못해 보이지 않는다. 읽기·권한 요청·저장·표시는 통과, **실차의 잔량 해상도와 값은 미검증** |
| 시험 중 시스템 재시작 (2026-11-08) | 두 번째 전비 시험 주행 중 Watchdog이 `GnssNative.native_stop_nmea_message_collection`에서 74초 멈춘 system_server를 재시작했다. 아래 2026-11-03 기록과 같은 GNSS 정지다. 앱은 복구 화면으로 돌아왔다 |
| CPU, 메모리 (2026-11-06) | 주행 중 앱 전체 0.45%(4코어 기준, 한 코어의 1.82%), 정차 중 0.20%. PSS 약 78MB, RSS 약 159MB. 내비 경로 안내 중 앱을 앞에 두고 잰 값이며 **에뮬레이터 수치다. 실차 기준 충족을 뜻하지 않는다** |

디스크가 가득 차면 에뮬레이터가 멈춘다. `DRIVELOG_Pleos`만 7GB 이상 쓰므로 여유 공간을 15GB 이상 둔다. 2026-11-03 시험 중 시스템 프로세스가 위치 서비스(GNSS)에서 멈춰 Watchdog으로 한 번 재시작됐고, 원인은 확인하지 못했다. 앱 크래시 기록은 없었다.

Pleos 내비는 에뮬레이터에서 실제 지도 데이터로 검색한다. 그래서 인터넷 연결이 필요할 수 있으며, 오프라인 동작은 미검증이다.

`wm size 853x1344`는 Pleos 이미지에서 시스템 화면을 겹치게 하고 앱을 0×0으로 만든다(2026-11-06). 좁은 창 확인에 쓰지 않는다.
