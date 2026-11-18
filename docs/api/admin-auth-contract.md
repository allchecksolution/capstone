# 관리자 ID/PW 인증과 사전 계정 발급

2026-11-18 identity 8단계 계약. 관리자 로그인과 사용자 승인에 따른 **운영자 전용 사전 발급 CLI**를 구현했다. 로그인·갱신 요청 제한의 구체적인 정책은 확인 대기 중이며 현재 제한기를 구현했다는 의미는 아니다.

## 로그인 계약

`POST /api/v1/auth/sessions/admin`은 사전 등록된 관리자만 인증한다. 입력과 토큰 응답은 [identity 확정 계약](identity-contract.md)을 유지한다. 공개 회원가입·MFA·TOTP·역할 변경 API는 제공하지 않는다.

```json
{"loginId": "admin01", "password": "operator-supplied-password"}
```

- loginId는 공백 아닌 최대 100자, password는 공백 아닌 최대 200자다. 계정 ID·역할·미정의 필드를 클라이언트가 추가하면 400이다.
- 아이디·비밀번호의 대소문자·공백을 자동 변환하지 않는다. 비밀번호 전체를 검증하며 긴 입력을 잘라 인증하지 않는다.
- 성공은 200 TokenPair다. userType=ADMIN, role은 현재 DB의 MASTER/SUB다. 역할별 미확정 업무 권한 차등은 추가하지 않는다.
- 헤더는 `Cache-Control: no-store`, `Pragma: no-cache`다. HTTP 세션·로그인 쿠키를 만들지 않는다.
- 이 POST는 Bearer 헤더를 요구하지 않는다. 만료·잘못된 Authorization 헤더가 있어도 본문의 ID/PW로 처리한다. 다른 경로·메서드는 함께 공개하지 않는다.

| 상황 | 응답 |
|---|---|
| 누락·null·공백·길이 초과·미정의 입력 | 400 공통 ErrorResponse |
| 없는 아이디·잘못된 비밀번호·지원하지 않는 저장 해시 | 401 INVALID_CREDENTIALS, 동일한 안내 |
| 올바른 자격 증명의 비활성 계정 | 403 ACCOUNT_DISABLED |
| 세션 저장·JWT 서명 실패 | 트랜잭션 롤백, 공통 서버 오류 |

## 비밀번호 저장

Spring Security의 Pbkdf2PasswordEncoder를 사용하여 PBKDF2-HMAC-SHA256·600,000회 반복·16바이트 난수 salt·256비트 결과를 저장한다. 저장 형식은 `{pbkdf2-sha256-600000}` 뒤에 salt와 결과를 결합한 96자리 hex다. 매 인코딩의 salt가 다르다. 기존 password_hash(255자) 컬럼에 들어가므로 V2와 적용된 마이그레이션을 변경하지 않는다.

현재 의존성으로 구현할 수 있고 200자 유니코드 입력을 자르지 않는 방식을 선택했다. 알고리즘과 비용 근거는 [Spring Security Password Storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html), [OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)를 참고했다. 미지원 형식·평문·noop 저장값은 인증하지 않으며 자동으로 계정을 재발급하거나 해시를 덮어쓰지 않는다.

없는 계정·지원하지 않는 해시도 더미 해시로 같은 비용의 검증을 수행한다. 입력 비밀번호·저장 해시는 응답·로그에 포함하지 않는다. 인증 요청 DTO와 내부 자격 증명 스냅샷은 자동 toString에 비밀이 포함되지 않도록 일반 클래스를 사용한다.

## 세션·트랜잭션

1. 읽기 트랜잭션에서 관리자 자격 증명 스냅샷을 조회한다.
2. DB 트랜잭션 밖에서 비밀번호 해시를 검증한다. 호출자의 쓰기 트랜잭션이 있어도 중단한다.
3. 계정을 쓰기 잠금으로 다시 읽고 유형·loginId·저장 해시가 검증한 값과 같은지 확인한다. 중간 변경이 있으면 401로 거부한다.
4. 현재 활성 여부를 확인하고 공통 SessionIssuanceService로 세션 저장·JWT 발급을 원자적으로 처리한다. 역할은 잠금 후 현재 값을 사용한다.

카카오 로그인도 같은 세션 발급기를 사용한다. 기본 Access Token 15분·최초 로그인부터 14일 절대 만료·다중 기기·Refresh Token 해시 저장·회전·로그아웃 즉시 차단 정책을 유지한다. 동시 로그인은 각각 독립 세션을 만들고 비활성화와 같은 계정 잠금 순서로 경쟁을 조정한다.

## 운영자 전용 관리자 계정 발급 CLI

사용자는 2026-11-18 관리자 사전 계정을 운영자 전용 CLI로 발급하기로 확정했다. 이번 범위는 **신규 발급**이다. loginId·비밀번호·MASTER/SUB 역할을 명시하고 중복 아이디는 기존 계정의 활성 여부와 관계없이 거부한다. 비밀번호·역할·세션·비활성 상태를 덮어쓰지 않는다. 계정 수정·삭제·재활성화는 별도 정책이 필요한 후속 기능이다.

`apps/backend`에서 Java 21로 빌드하고 실행한다.

```powershell
.\gradlew.bat bootJar
java -jar build/libs/app.jar provision-admin --help
java -jar build/libs/app.jar provision-admin --login-id=admin01 --role=MASTER
```

대화형 콘솔에서 비밀번호를 숨김 입력한다. 비밀번호가 명령줄 인자나 셸 기록에 남지 않도록 `--password=...` 인자는 받지 않는다. 자동 실행 환경은 비밀번호 파일 경로를 사용한다.

```powershell
java -jar build/libs/app.jar provision-admin --login-id=admin02 --role=SUB --password-file=C:/secure/admin-password.txt
```

파일은 BOM 없는 UTF-8 원문이다. 전체 내용을 비밀번호로 사용하며 공백·끝 줄바꿈을 자동 제거하지 않는다. 비밀번호는 공백 아닌 최대 200자이고 loginId는 공백 아닌 최대 100자다. 역할에는 기본값이 없으며 MASTER 또는 SUB를 직접 지정한다. 콘솔이 없는 환경에서는 `--password-file`이 필요하다.

| 환경 변수 | 용도 |
|---|---|
| `SPRING_DATASOURCE_URL` | 발급할 계정을 저장하는 PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | 해당 DB에 계정 생성 권한이 있는 접속 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | DB 접속 암호. 기존 운영 비밀 관리 방식으로 공급 |

CLI는 내부 admin-provisioning 프로필의 독립적인 비웹 컨텍스트를 사용하고 작업 후 종료한다. JWT 서명키·카카오·국세청·Firebase 설정은 필요하지 않다. 앱과 같은 Flyway/Hibernate 스키마를 사용하며 기존 V2 마이그레이션을 수정하지 않았다. 비밀번호 해싱은 쓰기 트랜잭션 밖에서, 계정 생성은 DB 유니크 제약과 `ON CONFLICT DO NOTHING`으로 처리한다. 여러 운영자가 같은 아이디를 동시에 발급해도 한 계정만 생성한다. 발급 자체는 로그인 세션을 만들지 않는다.

성공 출력은 `ADMIN_CREATED accountId=... role=...`이고 비밀번호·해시는 출력하지 않는다. 종료 코드는 성공/도움말 0, 중복 아이디·DB/설정 오류 1, 인자·비밀번호 입력 오류 2다. 실패 출력에도 원본 예외·입력 비밀번호를 포함하지 않는다.

## 요청 제한 문서 검색과 남은 결정

2026-11-18 저장소의 Markdown과 숨김 경로를 검색했다. 관련 문서는 있지만 적용 대상·집계 기준·허용 횟수·시간·운영 위치를 확정한 **구현 지침은 찾지 못했다**.

| 기존 문서 | 현재 내용 |
|---|---|
| [Identity API 계약](identity-contract.md#오류-계약) | 요청 제한은 필요하지만 임계값과 운영 환경 적용 방식은 별도 확정 |
| [Security Conventions](../../apps/backend/docs/security-conventions.md#api--web-security) | 로그인·토큰 재발급 API의 rate limiting 적용 권고 |
| 이 문서의 이전 버전 | 요청 제한의 임계값·운영 방식 미정 |

따라서 임의의 제한값·계정 잠금 정책을 추가하지 않는다. 다음 항목을 사용자와 결정한 뒤 구현한다.

- 관리자 로그인·카카오 로그인·갱신 중 적용할 경로, 모든 요청 또는 인증 실패만 집계할지
- IP·입력 아이디·인증 계정·세션 중 집계 단위와 조합
- 허용 횟수·집계 시간·제한 해제/재시도 기준
- 백엔드와 게이트웨이 중 적용 위치, 여러 서버의 공유 저장소와 신뢰할 프록시/IP 전달 방식

MASTER/SUB의 상세 업무 권한 차등, 비밀번호 재설정·역할 변경·계정 비활성화/삭제의 관리 범위와 기존 세션 처리도 후속 결정 사항이다. CLI 신규 발급을 승인한 것으로 이 변경 권한과 정책까지 확정된 것으로 취급하지 않는다.

실제 운영 관리자 계정은 아직 생성하지 않았다. 테스트는 격리 DB에서 생성한 계정만 사용·정리한다. 프론트 어댑터 연결도 후속 작업이다.

## 검증

2026-11-18 Java 21·Gradle 9.8.2·격리 PostgreSQL 18에서 `--offline ktlintCheck test` **420개 통과, 실패·오류·건너뜀 0**. CLI 추가분은 발급 통합 7개·입력/오류 24개다. 패키징한 실제 JAR로 JWT 서명키 없이 격리 DB에 SUB 관리자를 생성하고, 중복 요청이 기존 역할을 유지하며 종료 코드 1을 반환하는 것도 확인했다. 사용한 테스트 계정과 임시 비밀번호 파일은 정리했다. 환경은 [인수인계](identity-handoff.md#검증-및-환경)를 따른다. 요청 제한은 정책 미확정으로 검증 대상에 포함하지 않는다.
