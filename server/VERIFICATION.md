# DRIVELOG Phase 2 검증 결과

검증일: 2026-11-02, Windows 11.

## 생성·변경 범위

모든 신규 소스와 문서는 `server/` 안에 생성했다.

| 파일 | 내용 |
|---|---|
| `settings.gradle.kts` | 독립 서버 프로젝트 이름 |
| `build.gradle.kts` | Java 21, Boot 3.5.16, BOM, Web/Validation/JPA/Actuator/PostgreSQL, JUnit 5 |
| `gradlew`, `gradlew.bat` | 공식 Gradle 8.14.3 실행 스크립트 |
| `gradle/wrapper/gradle-wrapper.jar` | 공식 JAR 다운로드·SHA-256 확인 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 8.14.3 배포 ZIP SHA-256 고정 |
| `.gitignore` | 서버 빌드·캐시·개인 IDE·비밀정보·환경파일·로그 제외 |
| `compose.yaml` | PostgreSQL 단일 서비스, 외부 DB 자격 증명, localhost 포트, 볼륨, readiness 검사 |
| `src/main/java/com/drivelog/server/DrivelogServerApplication.java` | Boot 진입점 |
| `src/main/java/com/drivelog/server/domain/health/controller/HealthController.java` | GET /api/v1/health |
| `src/main/resources/application.yml` | 공통 JPA·Actuator·기본 local 프로파일 |
| `src/main/resources/application-local.yml` | DB_* 외부 설정 |
| `src/test/java/com/drivelog/server/domain/health/controller/HealthControllerTest.java` | DB 없는 MVC HTTP 계약 검사 |
| `src/test/java/com/drivelog/server/ServerPostgresIntegrationTest.java` | 실제 DB·서버 HTTP·Actuator 검사 |
| `README.md`, `VERIFICATION.md` | 실행·구조·환경변수·Android 통신·검증 문서 |

기존 루트 Gradle 설정에 server를 포함하지 않았다. 기존 Android 코드/설정 및 docs 등
작업 전 해시를 확보한 51개 파일은 SHA-256 비교에서 변경이 없었다.
Android 빌드는 이번 작업에서 다시 실행하지 않았다.

현재 경로는 `git rev-parse --show-toplevel`에서 Git 저장소로 인식되지 않았다.
새 Git 저장소와 `server/.git`은 만들지 않았다. 단일 저장소 운용을 위해서는 기존 checkout의
Git 메타데이터나 올바른 저장소 경로를 확인해야 한다. Git diff/커밋/추적 상태 검증은 불가했다.
Wrapper JAR를 포함한 Wrapper 파일은 ignore하지 않았다.

## 환경

- 실제 빌드·실행 JVM: Zulu OpenJDK 21.0.8+9-LTS.
- Spring Boot: 3.5.16. 공식 3.5 요구사항과 Maven Central 게시 버전 확인.
- 서버 Gradle: 8.14.3. Android의 9.3.1과 별개 Wrapper.
- PostgreSQL: 17.11 (Debian 17.11-1.pgdg12+2).
- Docker Engine: 28.1.1 / Compose v2.35.1-desktop.1.
- Compose 이미지: `postgres:17.11-bookworm`.
- PostgreSQL JDBC 및 JUnit 5를 포함한 라이브러리 버전은 Boot BOM으로 관리.
- 초기 JAVA_HOME은 JDK 17을 가리켰으므로 검증 프로세스 안에서만 JAVA_HOME을 JDK 21로 설정했다.
  시스템 전역 환경변수나 Android 설정은 수정하지 않았다.

## 실제 결과

| 항목 | 결과 |
|---|---|
| 공식 Wrapper JAR checksum | 일치 |
| Wrapper 실행 및 배포 ZIP checksum | 성공 |
| Gradle 의존성 해결 | 성공 |
| `compileJava` | 성공 |
| DB 없는 `test` | 1개 통과, 실패/오류/skip 0 |
| `bootJar` | 성공 |
| Compose 구문·readiness | 성공 |
| 실제 PostgreSQL `SELECT 1` | 성공 |
| 실제 PostgreSQL `integrationTest` | 1개 통과, 실패/오류/skip 0 |
| 패키징 JAR 실행 | 성공, local 프로파일·포트 8080 |
| 패키징 JAR의 GET /api/v1/health | HTTP 200, 아래 JSON |
| 패키징 JAR의 GET /actuator/health | HTTP 200, 아래 JSON |
| 새 검증 DB의 public 기본 테이블 | 0개 |

```json
{"service":"drivelog-server","status":"UP"}
```

```json
{"status":"UP"}
```

JPA Entity가 없는 상태에서 `ddl-auto: validate`로 실행했다. Hibernate가 애플리케이션 테이블을
생성하지 않았음을 실제 PostgreSQL의 information_schema 조회로 확인했다.
Actuator DB 검사도 활성화된 상태이며 상세 정보는 HTTP에 공개하지 않는다.

첫 빌드는 DB 환경변수를 제공하지 않고 `compileJava test bootJar`를 완료했다.
실제 PostgreSQL을 사용하는 통합 테스트는 별도 task로 실행했다.
같은 소스의 후속 빌드에서는 Gradle의 UP-TO-DATE 결과가 사용되었으며 실제 테스트 실행 결과는
아래 XML에 남아 있다. Mockito 테스트 인프라 사용 시 JVM CDS 경고가 발생했으나 테스트 실패는 없었다.

## 산출물과 로컬 증거

- 실행 JAR: `build/libs/drivelog-server-0.0.1-SNAPSHOT.jar`.
- 초기 컴파일·DB 없는 테스트: `build/verification/build.log`.
- 최종 Gradle 명령: `build/verification/final-build-and-integration.log`.
- 일반 테스트 XML: `build/test-results/test/TEST-com.drivelog.server.domain.health.controller.HealthControllerTest.xml`.
- 통합 테스트 XML: `build/test-results/integrationTest/TEST-com.drivelog.server.ServerPostgresIntegrationTest.xml`.
- JAR 실행 stdout/stderr: `build/verification/application.stdout.log`, `application.stderr.log`.
- PostgreSQL 버전·테이블 수·두 HTTP 응답: `build/verification/runtime-result.json`.

이 로컬 증거와 임시 검증 스크립트는 `build/`에만 있으며 Git 제외 대상이다.
JAR에는 비밀번호나 Pleos/Gemini 자격 증명을 포함하지 않았다.

## 검증 자원 정리와 재실행

DB 자격 증명은 임시 프로세스 환경에서 생성하고 파일·문서에 값을 저장하지 않았다.
검증 전용 고유 Compose 프로젝트와 볼륨을 만들었으며 종료 시 해당 자원만 제거했다.
다른 Docker 프로젝트/볼륨은 변경하거나 정리하지 않았다.
패키징 JAR 검증 프로세스도 종료했다. 서버와 개발 DB를 상시 실행 상태로 남기지 않았다.

사용자는 README의 절차대로 자신의 DB_USERNAME/DB_PASSWORD를 외부에서 주입하고
선택적으로 `docker compose up -d --wait`, 이후 `bootRun` 또는 `java -jar`를 실행하면 된다.
검증용 임시 DB 자격 증명을 재사용할 필요가 없다.

## 남은 범위

- 실제 단일 Git 저장소 확인·버전 관리: 현재 저장소 메타데이터가 없어 미검증.
- IntelliJ 실행 구성은 생성하거나 인증정보를 저장하지 않았다. README에 설정 절차를 제공했다.
- Android Retrofit·앱 HTTP 설정은 구현하지 않았다.
- 일반 Android Emulator의 10.0.2.2와 Pleos AVD 연결은 이번 단계에서 실제 통신하지 않았다.
- 실제 차량 HTTPS·도메인·인증서·배포·인증 시스템·Pleos/Gemini 연동은 미구현이다.
- 다음 도메인을 추가하기 전에 API 계약·Entity·스키마 마이그레이션 방식을 확정한다.

설정 키별 필수 여부, VM Options, 기존 PostgreSQL 사용법과 공식 출처는 README.md에 정리했다.
