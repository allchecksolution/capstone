# Identity 구현 인수인계

기준: 2026-11-17, 5단계 완료 후. 새 세션에서는 이 파일과 실제 Git 상태를 함께 확인한다.

## 저장소 상태

- 프로젝트: `D:\4-2\allcheckclone\capstone`
- 브랜치: `feat/ACK-30-identity-domain`
- 1단계: `fb9cab5` — 인증 API 계약과 세션 정책 확정
- 2단계: `6e8399f` — 계정·세션 모델과 영속성 구현
- 3단계: `ee79bdb` — 카카오 토큰 검증 클라이언트 구현
- 4단계: `2686956` — 카카오 로그인과 서비스 토큰 발급 구현. origin의 동일 브랜치에 push했다.
- 5단계: JWT 요청 인증·내 계정 조회 구현. 이 인수인계 문서를 포함한 커밋이며 정확한 해시는 Git 로그로 확인한다.
- 각 단계는 `origin/feat/ACK-30-identity-domain`으로 push한다. 새 세션에서는 HEAD·원격 상태를 다시 확인한다.
- 기존 미추적 `.idea/`는 사용자 로컬 설정이므로 작업 커밋에서 제외했다.
- 이 인수인계 파일은 3단계 커밋 이후 생성했다. 다른 체크아웃·컴퓨터에서는 현재 코드와 이 파일이 함께 있는지 확인한다.

## 먼저 읽을 문서

1. 루트 [AGENTS.md](../../AGENTS.md), [백엔드 AGENTS.md](../../apps/backend/AGENTS.md)
2. [Identity API 계약](identity-contract.md) — 확정 경로·입출력, 진행 단계, 재현 명령
3. [Business Specification](../../apps/backend/docs/business-specification.md) — 도메인 소유권과 BR-02 세션 정책
4. 이후 변경 범위에 필요한 백엔드 architecture/code/security/API/exception/persistence/test 규칙만 읽는다.

현재 파일은 작업 상태 요약이다. 상세 정책의 원본은 위 계약과 명세이며, 새 세션에서 이미 합의된 정책을 다시 선택 사항으로 취급하지 않는다.

## 사용자와 확정한 사항

- 관리자·기사 앱은 분리하고 같은 백엔드·identity를 공유한다.
- 관리자 인증은 사전 발급 ID/PW다. MFA·TOTP·공개 관리자 회원가입은 구현하지 않는다.
- 기사 카카오 SDK 로그인은 프론트에서 작동하는 것을 사용자가 확인했다.
- 서버가 SDK Access Token을 카카오에 검증하고, 서버가 받은 회원번호로 내부 인증 계정을 식별한다.
- 로그인 응답은 최상위 TokenPair와 `user.accountId/userType/role`이다. 기사 가입 상태는 후속 driver API에서 조회한다.
- identity는 계정·인증 정보·세션을, driver는 개인정보·사업자 정보·가입·심사·탈퇴 상태를 소유한다. `driver → identity` 공개 API 방향을 유지한다.
- Access Token 기본 15분, 세션은 최초 로그인부터 기본 14일이다. 갱신해도 세션 절대 만료는 연장하지 않는다.
- 여러 기기 세션을 허용한다. Refresh Token은 난수로 만들고 DB에는 SHA-256 해시만 저장한다.
- 갱신 시 Refresh Token을 교체한다. 동시 갱신은 하나만 성공하고 이전 토큰 재사용만으로 새 토큰·다른 세션을 자동 폐기하지 않는다.
- 로그아웃은 해당 세션만 폐기하고 기존 Access Token도 즉시 차단한다. 계정 비활성화·탈퇴는 전체 세션을 폐기한다. 현재 DB 상태에 따른 요청 차단은 5단계에서 연결했으며, 로그아웃·계정 비활성화의 세션 폐기 조정은 후속 API에서 구현한다.
- 앱은 현재처럼 서비스 토큰을 메모리에만 보관한다.
- 이후 설명 과정에서 위치 배차에는 PostgreSQL + PostGIS가 적합하다는 의견을 제시했지만 PostGIS는 아직 설치·구현하지 않았다. 위치 구현은 현재 인증 단계의 범위가 아니다.

## 이미 구현한 것과 아직 연결하지 않은 것

- `identity/ModuleMetadata.kt`: 명시적 Spring Modulith 모듈 등록
- `identity/domain/entity/Account.kt`: 카카오 연결 또는 관리자 자격 증명, 활성 여부와 비활성 시각, 낙관적 버전
- `identity/domain/entity/AuthSession.kt`: 계정 ID 참조, 현재 Refresh Token 해시, 절대 만료·갱신·폐기 시각, 낙관적 버전
- `identity/infrastructure/repository`: 계정·세션의 Spring Data JPA 저장소
- `V2__create_identity_accounts_and_sessions.sql`: identity_accounts·identity_auth_sessions 및 유니크·외래키·상태 제약. V1은 수정하지 않았다.
- `identity/infrastructure/kakao/KakaoClient.kt`: 고정된 카카오 토큰 정보 API 호출, 토큰·앱 ID·만료 검증 후 `SocialLogin` 반환. DB 변경이나 서비스 토큰 발급은 하지 않는다.
- `KakaoClientConfig/KakaoProperties`: 연결 2초·응답 대기 5초 기본 설정, 리다이렉트 금지
- `IdentityErrorCode`: 카카오 인증 실패 401과 외부 장애 503 구분. HTTP 400의 Kakao `code=-1`도 일시 장애로 분류한다.
- 실제 SDK 토큰을 이용한 서버 연결 검증은 아직 수행하지 않았다. 실제 `KAKAO_APP_ID` 값은 이 대화에서 제공받지 않았다.
- `identity/presentation/AuthSessionController.kt`와 `api/AuthSessionApi.kt`: POST `/api/v1/auth/sessions/kakao`, 입력 검증·토큰 쌍 응답·no-store 헤더·생성 OpenAPI를 구현했다.
- `KakaoLoginService`: 외부 카카오 호출은 NOT_SUPPORTED로 DB 트랜잭션 밖에서 수행한다.
- `KakaoSessionService`: 계정 upsert·잠금 조회·활성 여부 확인·세션 저장·JWT 발급을 한 트랜잭션으로 처리한다. 서명 실패 시 계정·세션도 롤백한다.
- `RefreshTokenProvider`: 256비트 SecureRandom 난수, Base64URL 원문 토큰, SHA-256 hex 저장용 해시를 구현했다.
- `global/security/jwt/JwtTokenProvider.kt`: sub·sid·iss·aud·iat·exp·token_use=access를 발급·검증한다. 기본 issuer는 `allcheck`, audience는 `allcheck-api`이며 환경 변수로 설정할 수 있다.
- `JwtAuthenticationFilter`와 SecurityConfig 연결: JWT를 검증하고 현재 DB 계정·세션을 확인한 후 Spring Security에 등록한다. 카카오 로그인 POST는 필터에서 제외한다.
- `IdentityOperations`와 `AuthenticatedAccount`, `UserType`, `AdminRole`: identity 모듈 루트의 공개 API·계약 타입이다. global.security는 내부 Entity·Repository를 직접 참조하지 않는다.
- `SessionAuthenticationService`: 계정과 세션을 한 쿼리로 조회하고 활성 여부·연결·폐기·생성·절대 만료 시각을 확인한다. 조회 결과를 메모리나 HTTP 세션에 보관하지 않는다.
- `AuthPrincipal`: 계정 ID·세션 ID·현재 유형·관리자 역할을 보존한다. Spring Security에는 ROLE_ADMIN/ROLE_DRIVER만 연결하며 미확정 업무 권한은 추가하지 않는다.
- `AuthAccountApi/AuthAccountController`: GET `/api/v1/auth/accounts/me`로 AuthUser만 반환하고 no-store를 적용한다.
- `CustomAuthenticationEntryPoint`: 인증 누락·만료·무효 JWT 및 사용 불가 계정·세션을 공통 ErrorResponse/401과 Bearer challenge로 처리한다. 권한 부족은 기존 핸들러의 403을 유지한다.
- 갱신·로그아웃 API는 아직 구현하지 않았다. 세션 폐기·계정 비활성화 이후 기존 Access Token을 차단하는 요청 인증 경로는 검증했다.

## 다음 작업: 6단계 갱신·로그아웃과 동시성 검증

1. 확정된 POST `/api/v1/auth/tokens/refresh`, POST `/api/v1/auth/sessions/logout`의 API 인터페이스·DTO·컨트롤러와 서비스를 구현한다.
2. 해당 경로만 추가로 공개하고 JWT 필터에서 제외한다. 만료된 Authorization 헤더가 있어도 본문의 Refresh Token을 검증한다.
3. 갱신은 해시로 현재 토큰을 확인하고 한 트랜잭션에서 토큰 쌍을 교체한다. 세션 절대 만료를 연장하지 않는다.
4. 같은 토큰의 동시 갱신은 하나만 성공하고 나머지는 401이다. 이전 토큰 재사용만으로 교체된 토큰이나 다른 세션을 폐기하지 않는다.
5. 로그아웃은 유효한 Refresh Token의 해당 세션만 폐기한다. 무효·만료·폐기 토큰은 다른 세션에 영향 없이 204를 반환한다.
6. 실제 DB 독립 트랜잭션에서 갱신 충돌·롤백과 로그아웃 후 기존 Access Token의 즉시 차단을 검증한다. 5단계 요청 인증 경로를 함께 사용한다.

기사 가입·사업자 검증·승인·탈퇴는 7단계, 관리자 로그인은 8단계다. 프론트 어댑터의 새 HTTP 계약·driver 상태 조회 연결과 실제 카카오 SDK 토큰 연결 검증도 아직 남아 있다.

마스터/부 관리자별 업무 권한 차등과 로그인 요청 제한 임계값은 아직 미정이다. 역할을 보존하되 업무 권한을 임의로 추가하지 않는다.

## 검증 및 환경

- 마지막 전체 검증: Java 21·Gradle 9.8.2, `--offline ktlintCheck test`, **219개 통과 / 실패·오류·건너뜀 0**.
- 4단계 커밋 분리 시 Git 인덱스의 스냅샷을 별도 폴더로 복원해 `ktlintCheck test` **181개 통과 / 실패·오류·건너뜀 0**을 다시 확인했다. 5단계 파일 없이 독립적으로 검증했다.
- 5단계 요청 인증 통합 테스트 37개와 global → identity 내부 참조 금지 아키텍처 테스트 1개를 추가했다. 실제 DB와 Security 필터를 사용하며 카카오 외부 검증만 테스트 대역이다. 생성 OpenAPI, 관리자 역할 보존과 JWT 권한 무시, 세션 폐기·계정 비활성화 이후 차단도 확인했다.
- 4단계 로그인 HTTP·트랜잭션 통합 테스트 21개, JWT 테스트 23개, Refresh Token 생성 테스트 2개를 포함한다. 외부 카카오 검증만 테스트 대역이며 DB 저장·동시성·롤백과 Spring Security 경로는 실제 구현을 사용했다.
- 신규 카카오 테스트 55개는 실제 카카오 API나 자격 증명 없이 가짜 HTTP 응답·전송 예외로 검증했다.
- DB 검증은 격리된 PostgreSQL 18에서 수행했다. Compose의 PostgreSQL 17 검증은 아직이다.
- Docker 엔진이 당시 실행되지 않아 로컬 PostgreSQL 18 실행 파일로 별도 클러스터를 만들었다. 사용 후 서버를 종료했다. 새 세션에서 DB 실행 상태를 가정하지 않는다.
- 이전 임시 클러스터: `C:/Users/Public/Documents/ESTsoft/CreatorTemp/identity-postgres-20261116`, 포트 25432, DB `allcheck_identity_test`, 테스트 전용 사용자 `identity_test`. 클러스터는 로컬 검증용이며 운영 환경이 아니다.
- 재현 명령과 테스트 DB 환경 변수는 [Identity API 계약](identity-contract.md)의 검증 절차를 따른다.
- 현재 PC Java 21 경로는 `C:/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot`이다. 당시 기본 JAVA_HOME이 17이어서 Gradle 실행 시 21을 지정했다.
- 5단계 검증은 의존성이 준비된 `C:/Users/user/.gradle` 캐시와 `--offline`을 사용했다. `C:/.gradle`은 의존성을 다시 내려받아야 했다. 샌드박스에서 접근이 거부되면 정해진 escalation 절차를 사용한다.
- `KAKAO_APP_ID`는 숫자 앱 ID이며 Native App Key나 REST API Key가 아니다. 미설정 상태에서는 카카오 인증을 외부 호출 없이 503으로 차단한다.

## 새 세션 시작 문구

```text
이 저장소의 AGENTS.md와 apps/backend/AGENTS.md를 따르고,
docs/api/identity-handoff.md 및 docs/api/identity-contract.md를 읽어 현재 상태를 복원해줘.
feat/ACK-30-identity-domain 브랜치의 실제 코드와 커밋 상태로 5단계까지 구현된 것을 확인하고,
확정된 정책을 유지하며 6단계 Refresh/Logout API와 동시성 검증을 구현하자.
먼저 Git 상태와 기존 코드를 확인하고 이어서 진행해줘.
```
