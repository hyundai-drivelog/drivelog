// One reviewed catalogue feeds both the vehicle and optional server card renderer.
// Run with --check to detect stale generated files.
import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const root=new URL('../',import.meta.url);
const read=p=>fs.readFile(new URL(p,root),'utf8');
const catalogue=JSON.parse(await read('app/stamps/catalog.json'));
const regions=catalogue.regions, places=regions.flatMap(r=>r.places);
const artwork=await read('app/src/main/java/com/drivelog/app/feature/car/StampArt.kt');
assert.equal(regions.length,17);
assert.equal(new Set(regions.map(r=>r.id)).size,regions.length);
assert.equal(new Set(places.map(p=>p.id)).size,places.length,'Duplicate stamp IDs');
assert.equal(new Set(places.map(p=>p.name)).size,places.length,'Duplicate stamp names');
for(const r of regions){assert.ok(r.places.length>=3,r.id);for(const p of r.places){
 assert.ok(p.latitude>=33&&p.latitude<=39&&p.longitude>=124&&p.longitude<=132,p.id+' coordinate outside Korea');
 assert.ok(p.art&&p.motto&&p.area,p.id+' missing metadata');
 assert.ok(artwork.includes('"'+p.art+'"'),p.id+' missing artwork');
 assert.ok(p.sourceUrl?.startsWith('https://')||r.id==='busan',p.id+' missing coordinate source');
}}
const quote=s=>JSON.stringify(s).replaceAll('$','\\$');
const number=n=>Number.isInteger(n)?n+'.0':String(n);
const kotlin=`// Generated from app/stamps/catalog.json by scripts/sync-stamps.mjs. Do not edit by hand.
package com.drivelog.app.feature.car

val StampRegions = listOf(
${regions.map(r=>`    StampRegion(${quote(r.id)}, ${quote(r.name)}, listOf(\n${r.places.map(p=>`        StampPlace(${quote(p.id)}, ${quote(p.name)}, ${quote(p.area)}, ${number(p.latitude)}, ${number(p.longitude)}, ${quote(p.motto)}, ${p.tilt}f, ${quote(p.art)}),`).join('\n')}\n    )),`).join('\n')}
)

/** Flat index for on-device nearest-arrival matching. */
val StampPlaces = StampRegions.flatMap { it.places }
`;
const tuples=places.map(p=>`    ${JSON.stringify([p.name,p.latitude,p.longitude,p.tilt])},`).join('\n');
const serverPath='server/src/main/resources/static/m/card.js';
const server=await read(serverPath);
const pattern=/  \/\/ (?:The car's stamp places|Generated from app\/stamps\/catalog.json)[\s\S]*?  const STAMPS = \[[\s\S]*?\n  \];/;
assert.ok(pattern.test(server),'Server stamp block missing');
const nextServer=server.replace(pattern,`  // Generated from app/stamps/catalog.json by scripts/sync-stamps.mjs.\n  const STAMPS = [\n${tuples}\n  ];`);
const documentation=`# 전국 관광명소 스탬프

확인일: ${catalogue.checkedAt}. 전국 ${regions.length}개 여행 지역, ${places.length}곳. 기존 부산 8곳을 유지하고 다른 지역에는 3곳씩 추가했다. 이 지역 구분은 여행용 분류이며 행정구역 개편을 추적하는 주소 데이터가 아니다.

## 사용 방식

스탬프 탭에서 전국 지역 목록을 열고 지역을 고르면 명소 모음이 나온다. 전체 지역 버튼이나 하드웨어 뒤로 가기로 지역 목록에 돌아온다. 기존 부산 ID·좌표와 방문 기록은 유지한다. 새 목록은 기존 주행 기록에도 적용되므로 예전에 방문한 명소도 기록의 종료 위치가 맞으면 표시된다.

실제 주행 0.1km 이상, 위치 점 2개 이상, 마지막 위치가 대표 지점 1km 이내일 때 가장 가까운 명소 하나를 판정한다. 시뮬레이션은 제외한다. 기록을 지우면 해당 방문도 다시 계산된다. 모든 판정은 기기 안에서 하며 주행 중 스탬프 목록·알림은 표시하지 않는다.

## 목록과 좌표 출처

대표 관광지의 범위는 [문화체육관광부·한국관광공사의 관광명소 소개](https://www.korea.kr/news/policyNewsView.do?newsId=148938769)를 참고했다. 아래는 DRIVELOG가 고른 목록으로 한국관광 100선 전체 목록이나 공식 제휴 팩을 뜻하지 않는다. 좌표는 Wikimedia 공개 데이터와 한국학중앙연구원 공간정보, 공개 관광·문화시설 데이터의 재게시 자료를 대조했다. 개별 출처는 각 명소 링크와 원본 JSON에 기록했다. 새 설명은 직접 작성했고 로고·사진을 복제하지 않았으며 스탬프는 장소 특성별 직접 그린 선화 모티프를 사용한다.

| 지역 | 관광명소 |
|---|---|
${regions.map(r=>'| '+r.name+' | '+r.places.map(p=>p.sourceUrl?'['+p.name+']('+p.sourceUrl+')':p.name+' (기존 좌표)').join(' · ')+' |').join('\n')}

좌표는 WGS84 대표 지점이며 주차장·차량 진입구를 보증하지 않는다. 배가 필요한 남이섬은 차량 도착 위치가 섬 안에 닿지 않는 문제로 제외하고 춘천 소양강스카이워크로 대체했다. 베어트리파크의 Wikimedia 좌표는 국립세종수목원 근처로 잘못 연결돼 있어 전동면 신송로 주소의 공개 문화시설 좌표로 교정했다. 국립세종수목원과 세종호수공원처럼 1km 범위가 겹치는 경우 가장 가까운 명소 하나만 인정한다. GPS 오차와 실제 주차 위치에 따른 도착 판정은 실차 검증이 필요하다.

## 유지보수와 확인

원본: [app/stamps/catalog.json](../app/stamps/catalog.json). 좌표·ID·그림·지역은 이 파일에서 수정한다. 기존 ID는 방문 호환성을 위해 바꾸지 않는다. 이름을 바꾸면 과거 QR 링크의 스탬프 이름까지 바뀌지는 않는다.

- 생성: \`node scripts/sync-stamps.mjs\`
- 동기화·중복·국내 좌표 범위·그림 누락 검사: \`node scripts/sync-stamps.mjs --check\`
- 차량 빌드·도착 판정 테스트: \`.\\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug\`

생성 대상은 차량의 StampCatalog.kt와 확장용 서버 모바일 카드의 STAMPS다. QR 정적 페이지는 차량에서 명소 이름·기울기를 받으므로 별도 목록이 없다. app/stamps/catalog.json은 위치를 수집하는 원격 API가 아니라 개발 시 사용하는 카탈로그 원본이다.
`;
for(const [path,content] of [['app/src/main/java/com/drivelog/app/feature/car/StampCatalog.kt',kotlin],[serverPath,nextServer],['docs/STAMP_CATALOG.md',documentation]]){
 if(process.argv.includes('--check'))assert.equal((await read(path)).replaceAll('\r\n','\n'),content.replaceAll('\r\n','\n'),'Stale catalogue: '+path);
 else await fs.writeFile(new URL(path,root),content);
}
console.log(`${regions.length} regions / ${places.length} stamps; vehicle and server ${process.argv.includes('--check')?'in sync':'generated'}.`);
