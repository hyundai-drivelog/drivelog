# DRIVELOG Server

Java 21 / Spring Boot 3.5.16 / Gradle 8.14.3 / PostgreSQL 17.11 기반 로컬 데모 백엔드.
Android와 독립된 Gradle 프로젝트이며 서버 명령은 이 디렉터리에서 실행한다.

## 로컬 PostgreSQL: 기존 데이터 유지

기존 `drivelog-local-postgres-1`과 `drivelog-local_postgres-data` 볼륨을 그대로 사용한다.
계정·비밀번호를 새 값으로 변경하거나 볼륨을 초기화하지 않는다.

PowerShell 환경변수 없이 Compose를 실행하려면:
1. `.env`가 없는 경우에만 `.env.example`을 `.env`로 복사한다.
2. 개인 편집기에서 기존 DB의 `DB_USERNAME`, `DB_PASSWORD`를 동일하게 입력한다.
   기존 IntelliJ VM Options 또는 Docker Desktop의 해당 컨테이너 환경 설정에서 확인한다.
3. `DB_PORT`는 기본 5432다.
4. `docker compose config --quiet`, `docker compose up -d --wait`로 실행한다.

```powershell
if (!(Test-Path .env)) { Copy-Item .env.example .env }
# .env를 개인 편집기에서 채운 뒤:
docker compose config --quiet
docker compose up -d --wait
```

Compose는 `.env`를 자동으로 읽지만 Spring Boot는 이 파일을 자동으로 읽지 않는다.
현재 셸에 같은 이름의 환경변수가 있으면 Compose에서는 환경변수가 우선하므로 오래된 값은 먼저 확인한다.
`.env`는 Git 제외 대상이고 `.env.example`만 버전 관리한다.
기존 볼륨의 계정은 `.env`를 바꿔도 변경되지 않는다.
DB가 이미 실행 중이거나 별도 PostgreSQL을 쓰면 Compose 실행은 필요 없다.
데이터를 보존하려면 종료 시 `docker compose stop`을 사용한다. DB/볼륨 삭제 명령은 사용하지 않는다.

## 애플리케이션 실행과 비밀정보

IntelliJ에서 `server/build.gradle.kts`를 별도 Gradle 프로젝트로 연결하고 Project SDK/Gradle JVM을 JDK 21로 지정한다.
실행 클래스는 `com.drivelog.server.DrivelogServerApplication`이다.
기존 VM Options 관리 방식을 유지한다. 아래 값의 꺾쇠 자리는 기존 로컬 값으로 바꾼다.

```text
-Dspring.profiles.active=local
-DDB_URL=jdbc:postgresql://localhost:5432/drivelog
-DDB_USERNAME=<EXISTING_DB_USERNAME>
-DDB_PASSWORD=<EXISTING_DB_PASSWORD>
```

| 키 | 현재 필수 여부 / 적용 |
|---|---|
| DB_URL | 선택, local 기본 localhost:5432/drivelog |
| DB_USERNAME, DB_PASSWORD | 실제 DB 인증에 필요, JVM 시스템 프로퍼티 또는 OS 환경변수 |
| PLEOS_PROJECT_ID | 현재 불필요, 향후 Pleos 프로젝트 |
| PLEOS_SDK_CLIENT_ID, PLEOS_SDK_CLIENT_SECRET | 현재 불필요, 향후 서버 인증 |
| PLEOS_CRN | 현재 불필요, 향후 해당 환경이 요구할 때 검토 |
| GEMINI_API_KEY | 현재 불필요, 향후 Gemini 연동 |
| SERVER_PORT | 선택, 기본 8080 |
| SWAGGER_ENABLED | 선택, local 기본 true / 공통 기본 false |

향후 외부 SDK를 구현한 뒤에만 다음 옵션을 추가한다. 지금은 읽거나 필수로 요구하지 않는다.

```text
-DPLEOS_PROJECT_ID=<PROJECT_ID>
-DPLEOS_SDK_CLIENT_ID=<CLIENT_ID>
-DPLEOS_SDK_CLIENT_SECRET=<CLIENT_SECRET>
-DPLEOS_CRN=<CRN_IF_REQUIRED>
-DGEMINI_API_KEY=<API_KEY>
```

설정은 YAML만 사용한다. JVM 시스템 프로퍼티는 동일 이름의 OS 환경변수보다 우선한다.
실제 자격 증명을 소스·문서·공유 IDE 실행 구성에 저장하지 않는다.
운영 환경에서는 VM 인수 노출을 고려해 Secret Manager 또는 안전한 환경변수 주입을 사용한다.
`.env`는 로컬 Compose 전용이며 IntelliJ 애플리케이션 VM Options와 별개다.

터미널에서 실행할 때는 OS 환경변수를 안전하게 주입한 뒤 다음을 사용한다.
Gradle에 준 `-DDB_*`가 bootRun/test의 자식 JVM으로 자동 전달된다고 가정하지 않는다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21' # 현재 PC의 JDK 21 경로
.\gradlew.bat compileJava test bootJar
.\gradlew.bat bootRun
# 또는 직접 java -D... -jar build/libs/drivelog-server-0.0.1-SNAPSHOT.jar
```

## API / Swagger

인증과 사용자 분리가 없는 로컬 개발 전용 API다. sourceType은 요청자가 전달하는 출처이며 서버가 차량 데이터를 인증한 증거가 아니다.

| 요청 | 결과 |
|---|---|
| GET /api/v1/health | 200, 서비스 Health |
| POST /api/v1/drives | 201, DriveResponse 및 Location 헤더 |
| GET /api/v1/drives?limit=20 | 200, 최근 시작 시각 내림차순 목록, limit 1~100 |
| GET /api/v1/drives/{id} | 200 상세, 없으면 404 |
| GET /actuator/health | DB 상태를 포함한 Health, 상세 정보 비공개 |

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- springdoc 2.10.0은 Boot 3.5.x 호환 범위인 2.x 버전이다.
- local에서는 Swagger가 켜지며 `-DSWAGGER_ENABLED=false`로 끌 수 있다.
- 그 외 프로파일에서는 공통 기본값 false다. 운영 datasource/배포 설정은 별도 구성해야 한다.
- 브라우저 Swagger는 동일 서버 API를 호출하며 네이티브 Android를 이유로 CORS를 일괄 허용하지 않았다.

POST 예시 (모두 명시적인 Mock 데이터):

```json
{
  "startedAt": "2026-01-01T00:00:00Z",
  "endedAt": "2026-01-01T00:15:00Z",
  "durationSeconds": 900,
  "distanceKm": 12.450,
  "averageEfficiency": null,
  "efficiencyUnit": null,
  "sourceType": "MOCK"
}
```

시각은 UTC offset을 포함한 ISO-8601이며 최대 마이크로초 정밀도를 허용한다.
종료 >= 시작, durationSeconds는 경과한 전체 초와 일치해야 한다.
거리는 0 이상, 소수점 3자리 / 정수 9자리까지다.
효율은 null이거나 양수이며 값이 있으면 단위 km/L 또는 km/kWh를 함께 보내야 한다.
지원하지 않는 효율은 수치를 만들지 않고 값·단위 모두 null로 전달한다.
잘못된 값/JSON/enum은 400, 없는 기록은 404, DB 제약 충돌은 409의 Problem JSON으로 응답한다.
목록은 startedAt DESC, id DESC 기준이다.

## 스키마 및 아키텍처

- Flyway `V1__create_drives.sql`은 전용 `drivelog` 스키마에 `drives` 하나를 만든다.
- `flyway_schema_history`는 마이그레이션 관리용 보조 테이블이며 비즈니스 도메인이 아니다.
- 기존 public 스키마·테이블·볼륨을 초기화하지 않는다.
- `ddl-auto: validate`, `open-in-view: false`, SQL 로그 비활성 유지.
- Flyway clean 금지, 자동 baseline 비활성. 스키마 충돌은 삭제 대신 원인을 확인한다.
- 숫자는 BigDecimal / NUMERIC(12,3), 시각은 TIMESTAMPTZ, 출처는 MOCK/PLEOS.
- Controller → Service(트랜잭션) → Repository → Entity. HTTP에는 DTO만 반환한다.
- 별도 사용자·보안·외부 SDK·Redis 등의 도메인은 추가하지 않았다.

## 테스트

```powershell
.\gradlew.bat test
# DB_*를 OS 환경변수로 주입한 뒤:
.\gradlew.bat integrationTest
```

일반 테스트는 DB 없이 HTTP 계약·유효성 검사·에러 응답을 검증한다.
통합 테스트는 실제 PostgreSQL을 사용한다. Drive 테스트 데이터는 트랜잭션 rollback되며 기존 기록을 삭제하지 않는다.
시퀀스 번호는 rollback되지 않아 id에 빈 번호가 생길 수 있다. H2로 대체하지 않았다.

## Android 통신과 검증 기록

일반 Android Emulator의 호스트 PC 주소는 `10.0.2.2:8080`이며, 기기 안 localhost는 기기 자신이다.
Pleos Connect AVD의 같은 주소 접근은 별도 검증이 필요하다.
실제 차량 배포는 HTTPS를 사용하며 HTTP는 Android debug에서 10.0.2.2에만 허용한다.
Android 구현·실행 결과와 Pleos 제한은 [통합 Phase 3 기록](../docs/PHASE3.md)을 참고한다.
기존 Phase 2 결과는 VERIFICATION.md에 이력으로 보존했다.

기존 루트 및 server에 새 Git 저장소를 만들지 않았다. 현재 루트는 Git 저장소로 인식되지 않으므로
실제 추적 상태/커밋 여부는 기존 checkout을 확인해야 한다. Wrapper 파일/JAR는 버전 관리 대상이다.

공식 출처:
- [Spring Boot 3.5 요구사항](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Spring Boot 3.5 Flyway](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html)
- [springdoc 2.x 호환성](https://springdoc.org/v2/)
- [Spring 외부 설정](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)
