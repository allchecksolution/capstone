# 관리자 ID/PW 인증과 사전 계정 발급

2026-11-18 identity 8단계 진행 문서. 관리자 로그인은 구현했으며 **사전 계정 발급 방식과 요청 제한 범위는 사용자 확인 대기 중**이다. 8단계 전체 완료를 의미하지 않는다.

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

## 확인 대기 사항

- 사전 발급: 운영자 전용 CLI에서 loginId·비밀번호·MASTER/SUB로 신규 생성하고 중복 ID는 덮어쓰지 않는 방식을 제안했다. 사용자 답변 전에는 해당 절차를 구현하지 않는다.
- 로그인·갱신 요청 제한: 임계값·운영 방식이 미정이다. 이번 단계에 포함할지 후속으로 분리할지 사용자에게 질문했다. 임의 제한값·계정 잠금 정책은 추가하지 않는다.
- MASTER/SUB의 상세 업무 권한 차등은 기존 합의대로 후속 과제다.

실제 운영 관리자 계정은 아직 생성하지 않았다. 테스트는 격리 DB에서 생성한 계정만 사용·정리한다. 프론트 어댑터 연결도 후속 작업이다.

## 검증

2026-11-18 Java 21·Gradle 9.8.2·격리 PostgreSQL 18에서 `--offline ktlintCheck test` **389개 통과, 실패·오류·건너뜀 0**. 관리자 로그인 27개와 비밀번호 해시 8개를 추가했다. 실제 HTTP/Spring Security·PostgreSQL을 사용하며 해시·세션·JWT·갱신·로그아웃·동시성·롤백·생성 OpenAPI와 기존 기사 흐름의 회귀를 확인했다. 환경은 [인수인계](identity-handoff.md#검증-및-환경)를 따른다. 사전 발급 및 요청 제한 결정에 의존하는 검증은 아직 포함하지 않는다.
