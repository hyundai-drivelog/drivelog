# DRIVELOG 평가서: 5. Pleos App Market 검수·정책 담당

평가일: 2026-11-06. 프롬프트: [EVALUATION_PROMPT.md](EVALUATION_PROMPT.md)

## 1. 한 줄 총평
퍼미션 정리, 최상단 Back 처리, 주행 중 화면 제한, 목업의 debug 전용 처리는 마켓 검수 수준으로 되어 있습니다. 다만 제출물인 서명된 release APK와 `apk/` 폴더가 아직 없고, release 빌드를 실행해 본 기록도 없습니다. DO 승인과 사진 저작권도 정리되지 않아 지금 상태로는 제출 요건에서 감점이나 반려를 받을 위험이 큽니다.

## 2. 담당 관점 점수: 6.0 / 10
5~6점 '평범' 구간의 위쪽입니다. 정책 설계는 상위권이지만, 제출물이 아직 없어서 상위 10팀 경쟁권(7점 이상)으로 보지 않았습니다.

## 3. 공모전 4개 항목 (검수 관점)
- **차량특화성 7.0:** 주행 중에는 기록 화면이나 잠금 안내만 그립니다(`CarApp.kt:30-35`). UX 제한 정보가 기어보다 우선합니다(`CarViewModel.kt:132-137`). 다만 DO 선언이 받아들여질지는 아직 모릅니다.
- **창의성 6.0:** QR 주소의 `#` 뒤에 카드 데이터를 담아, 서버 없이 그 휴대폰으로만 보내는 방식(`CardShare.kt:62-86`)은 개인정보 측면에서 참신합니다.
- **기술 활용성 6.5:** 선언한 퍼미션 9개가 모두 기능과 연결됩니다(`AndroidManifest.xml:8-16`). 다만 release APK로 확인한 기록이 없습니다.
- **사업성 5.0:** 커뮤니티는 debug 전용 목업입니다(`CarApp.kt:129`). release 기준으로는 데이터를 넓힐 경로가 보이지 않습니다.

## 4. 강점
1. **최상단 Back 처리:** 맨 위 화면에서 Back을 누르면 `moveTaskToBack(true)`로 앱을 숨기고, 기록은 계속됩니다(`MainActivity.kt:31`).
2. **퍼미션 정리:** 진단용 `CAR_INFO`·`CAR_ENERGY`는 debug 매니페스트에만 있습니다(`app/src/debug/AndroidManifest.xml:4-5`). `allowBackup=false`, 평문 통신 차단도 설정되어 있습니다(`AndroidManifest.xml:23,29`).
3. **목업과 시뮬레이션 분리:** 시뮬레이션은 `BuildConfig.DEBUG`일 때만 켜집니다(`CarViewModel.kt:117`). 커뮤니티와 휴대폰 미리보기도 debug에서만 보입니다(`CarApp.kt:44,95,120,129`). 시뮬레이션 기록에는 배지가 붙습니다(`CarComponents.kt:312`).
4. **커뮤니티 공개 규칙의 코드 구현:** 경로 앞뒤 500m 자르기(`CommunityCourses.kt:29,52-55`)와 출발지를 동네 단위로 표시하는 처리(`CommunityCourses.kt:67-76`)가 코드에 있습니다. 다만 동네 이름은 8개 항목의 예시 표로만 정합니다.
5. **비밀 값 관리:** `*.jks`, `keystore.properties`가 git에서 제외되어 있습니다(`.gitignore:28-30`). `git log`에서 비밀 값이 들어간 기록은 찾지 못했습니다. 지도 출처 표기(`RouteMapCache.kt:60`)와 OpenFreeMap 전송 안내(`PlaceScreens.kt:450`)도 있습니다.

## 5. 약점과 위험
1. **[치명] 제출물 부재:** `apk/` 폴더가 없고, 로컬에 `keystore.properties`도 없어 서명된 release APK를 만든 적이 없습니다. README의 APK 위치도 `<팀명>` 자리표시자 그대로입니다(README '심사용 실행 방법').
2. **[높음] release 빌드의 실행 기록 없음:** `docs/verification/RESULTS.md`와 `PLEOS_API_INTEGRATION.md` 13절의 검증은 모두 debug 빌드입니다. release 관련 기록은 이전 단계의 서명 없는 assembleRelease뿐입니다(`docs/PHASE3.md:180`). 심사위원은 release APK만 실행합니다.
3. **[높음] DO 선언과 카테고리 미확정:** `distractionOptimized=true`를 선언했습니다(`AndroidManifest.xml:48`). 그러나 Lifestyle과 Mobility Services 중 어느 카테고리인지, 주행 중 화면 유지가 허용되는지는 미결로 남아 있습니다(`PLEOS_DESIGN_PRINCIPLES.md` 4절·11절-1). 선언과 실제 기능이 다르면 즉시 반려된다고 문서 스스로 적고 있습니다.
4. **[높음] 사진 저작권과 표시 없는 비실제 사진:** `place_*.png`, `place_gwangalli_poster.jpg`의 출처 기록이 없습니다. `demo_coastal_road`는 AI 생성으로 기록되어 있으나(`PLEOS_RUNTIME_MVP.md` G절), 그때는 png였고 11-06에 jpg로 교체된 뒤의 출처는 확인되지 않습니다. 또 광안리 기록 카드는 번들된 사진을 '나의 드라이브 … 남긴 하루' 위에 표시 없이 깔아 사용자의 사진처럼 보이게 합니다(`PlaceScreens.kt:562,600-607`).
5. **[보통] release에 진단 화면이 남음:** 홈 제목을 길게 누르면 release에서도 영어 Material UI의 'SDK Diagnostics'가 열립니다(`CarApp.kt:126`, `PleosDiagnosticsScreen.kt:70-92`). 거기서 내비 화면 요청과 위치 조회 버튼을 쓸 수 있어, 숨은 개발 기능으로 지적받을 수 있습니다.
6. **[보통] QR로 보내는 데이터 범위:** QR 주소에는 자르지 않은 전체 경로(출발지 포함)와 시작 시각이 들어갑니다(`CardShare.kt:66-86`). 차 안의 누구나 QR을 찍을 수 있고, 화면 문구는 "휴대폰으로만"이라는 점만 강조합니다(`CardShare.kt:121`).
7. **[낮음] 기록 접근과 지도 요청 안내:** 휴대폰 연동이 꺼진 release에는 운전자 전환이나 일괄 삭제 수단이 없어, 차를 함께 쓰는 사람 누구나 모든 기록을 봅니다. 또 OpenFreeMap 전송 안내는 '내 기록' 화면에만 있는데, 장소 확인 화면은 그보다 먼저 지도를 요청합니다(`PLEOS_DESIGN_PRINCIPLES.md` 9절).

## 6. 마감(11-15) 전 할 일 (우선순위 순)
1. **서명된 release APK 만들기:** 키를 만들고 `packageSubmissionApk -PsubmissionTeam=… -PcardWebUrl=…`로 빌드합니다. `apk/`를 커밋하고 README의 자리표시자를 채웁니다. 효과: 제출 요건 충족과 가산점(치명 해소). 2시간.
2. **release APK로 전체 흐름 시험:** Pleos 에뮬레이터에서 권한 → 기록 → 카드 → QR까지 돌리고 `docs/verification/`에 남깁니다. 설치 전에는 `files/recording`이 비어 있는지 확인해야 합니다. 효과: 실행 불가로 인한 탈락 방지. 3시간.
3. **사진 정리:** 출처가 불명확한 사진은 직접 찍었거나 라이선스가 확인된 사진으로 바꾸고, 출처 목록을 만듭니다. 번들 사진에는 `예시 사진` 표시를 붙입니다. 효과: 저작권·오인 위험 제거. 3시간.
4. **진단 화면 진입을 debug 전용으로 제한:** `{ model.go(Screen.DIAGNOSTICS) }`를 `BuildConfig.DEBUG`로 감쌉니다. 0.5시간.
5. **QR 화면 문구 보강:** "출발지를 포함한 전체 경로가 담긴다"는 점을 화면에 적거나, QR 데이터에도 출발지 쪽 경로를 잘라 넣습니다. 1~2시간.
6. **DO 근거 정리와 문의:** README에 DO 근거(주행 중 렌더링 범위)와 카테고리 가정을 적고, `partnership@pleos.ai`에 질문을 보낸 기록을 남깁니다. 1시간.

## 7. 2차 PT 예상 질문과 모범 답변의 방향
1. **"DO를 선언했는데 카테고리는 무엇이고, 주행 중에 무엇을 보여 주는가?"**
   기록 화면은 장소·분 단위 시간·거리만 보인다는 점과 `routeFor`의 강제 구조를 시연합니다. 승인이 나지 않으면 DO를 빼도 기록 자체는 서비스로 계속된다는 대안을 제시합니다.
2. **"QR에 집 위치가 담기지 않는가? 서버가 없다는 것을 어떻게 증명하는가?"**
   URL의 `#` 뒤는 서버로 전송되지 않는다는 점, 카드 페이지는 외부 요청이 0건이라는 점(`web/card/index.html:28`)을 보입니다. 출발 구간 자르기 옵션도 함께 제시합니다.
3. **"앱 안의 사진과 지도는 상업적으로 써도 되는가?"**
   사진별 출처와 라이선스 표, OpenFreeMap·OSM 표기 위치를 보여 주고, 마켓에 올릴 때 지도 공급자를 바꿀 계획을 답합니다.

## 8. 확인하지 못한 것과 이유
- **release APK의 실제 동작과 서명 상태:** 빌드와 설치가 금지되어 있고, 결과물도 존재하지 않습니다.
- **장소 사진의 실제 출처:** 저장소에 출처 기록이 없습니다.
- **Pleos 마켓의 DO·카테고리 승인 기준:** 공개된 문서만으로는 알 수 없어 미결로 남아 있습니다.
- **주행으로 바뀔 때 지도 요청이 취소되는지:** 문서 스스로 시험하지 않았다고 밝히고 있습니다(13절).
- **OpenFreeMap 이용 약관의 상업적 사용 조건:** 외부 문서를 조회하지 않았습니다.
