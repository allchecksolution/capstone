# Identity 인증 API 계약

상태: **1단계 확정 — 2026-11-16 사용자 승인. API 구조와 세션 정책을 아래 계약으로 구현한다.**

이 문서는 identity 최초 구현의 확정된 경로·입출력 계약과 세션 정책 참조를 기록한다.
구현 완료를 의미하지 않는다. 구현된 계약은 Spring API 인터페이스·DTO에서 생성하는 OpenAPI로 확인한다.
기존 [전체 OpenAPI 초안](openapi.yaml) 및 [프론트 어댑터 계약](../../apps/frontend/API-CONTRACT.md)과의 차이는 아래에 기록한다.

## 확정된 범위

- 관리자 앱과 기사 앱은 분리하고 하나의 백엔드·identity를 공유한다.
- 관리자는 사전 등록한 ID/PW로 로그인한다. 공개 관리자 회원가입, MFA, TOTP 등록·검증은 제공하지 않는다.
- 기사 앱은 기존 카카오 SDK 로그인을 유지한다. 서버는 카카오 Access Token을 검증한 후 서비스 토큰을 발급한다.
- 카카오 회원번호는 서버가 카카오 응답에서 확인한다. 프론트가 보내는 회원번호·이메일로 인증하지 않는다.
- 계정·외부 로그인 연결·세션은 identity가, 기사 신청·승인·반려·탈퇴 상태는 driver가 소유한다.
- 카카오 토큰과 ALLCHECK 토큰은 별개의 자격 증명이다.

## 확정된 API 계약

아래 경로·필드·응답 분리 방식으로 구현한다. 프론트 어댑터는 서버 구현과 연결하는 단계에서 변경한다.
실제 요청 URL에는 `/api/v1`을 포함한다. `auth` 아래에 계정·세션·토큰 리소스를 배치한다.

| 메서드 | 경로 | 인증 수단 | 성공 응답 |
|---|---|---|---|
| POST | `/api/v1/auth/sessions/kakao` | 본문의 `kakaoAccessToken` | 200, TokenPair |
| POST | `/api/v1/auth/sessions/admin` | 본문의 `loginId`, `password` | 200, TokenPair |
| POST | `/api/v1/auth/tokens/refresh` | 본문의 `refreshToken` | 200, 새 TokenPair |
| POST | `/api/v1/auth/sessions/logout` | 본문의 `refreshToken` | 204, 본문 없음 |
| GET | `/api/v1/auth/accounts/me` | 서비스 Access Token의 Bearer 헤더 | 200, AuthUser |

로그인·갱신·로그아웃은 기존 Access Token을 요구하지 않는다.
해당 요청에 만료된 Access Token이 함께 들어와도 요청 인증 필터가 본문 자격 증명 검증을 막지 않도록 한다.
클라이언트는 이 요청들에 Bearer 헤더를 보내지 않는 것을 원칙으로 한다.

### 요청

```json
{"kakaoAccessToken": "kakao-token"}
```

```json
{"loginId": "admin01", "password": "password"}
```

갱신·로그아웃은 동일한 입력 형식을 사용한다.

```json
{"refreshToken": "allcheck-refresh-token"}
```

### TokenPair와 AuthUser

```json
{
  "accessToken": "allcheck-access-token",
  "refreshToken": "allcheck-refresh-token",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshExpiresIn": 1209600,
  "user": {
    "accountId": "internal-account-id",
    "userType": "DRIVER",
    "role": null
  }
}
```

- 예시 만료 시간은 확정된 기본 설정을 적용한 최초 로그인 시점의 값이다.
- `accountId`는 identity의 내부 계정 ID다. 카카오 회원번호 및 `driverId`와 구분한다.
- `userType`은 `ADMIN` 또는 `DRIVER`다. 요청 본문에서 사용자 유형·역할을 받지 않는다.
- 관리자 `role`은 기존 명세의 `MASTER` 또는 `SUB`, 기사 `role`은 null이다.
- 마스터/부 관리자별 업무 권한 차등은 기존 명세상 미확정이다. 이번 인증 구현에서는 역할을 보존하되 미확정 업무 권한을 새로 부여하지 않는다.
- `/auth/accounts/me`는 위 `user` 객체만 반환하며 기사 개인정보·가입 상태를 반환하지 않는다.

### 카카오 검증

1. 서버가 `/v1/user/access_token_info`를 호출한다.
2. 토큰 유효성·남은 유효시간과 설정된 카카오 앱 ID(`app_id`)의 일치를 확인한다.
3. 서버가 받은 카카오 회원번호로 계정을 조회하거나 최초 인증 계정을 생성한다.
4. 동일 카카오 회원의 동시 로그인에도 DB 유니크 제약으로 인증 계정 중복을 방지한다.
5. 비활성화된 계정에는 세션을 발급하지 않는다.

카카오 HTTP 호출은 DB 변경 트랜잭션 밖에서 수행한다.
카카오 토큰·서비스 토큰·비밀번호는 로그에 기록하지 않는다.
회원번호만 필요하면 사용자 프로필 API는 추가 호출하지 않는다.

### 기사 가입 상태 조회와 모듈 경계

로그인 응답에는 인증 정보만 넣고, 앱은 후속 driver API로 본인 가입 상태를 조회한다.
기존 초안의 `GET /drivers/me/registration`에 `/api/v1`을 적용해 사용하며, 구체적인 driver 구현은 후속 단계에서 진행한다.

- 신청 이력이 없으면 driver 조회의 404를 신규 기사 가입 화면으로 연결한다. 임의의 서버 오류를 미가입으로 취급하지 않는다.
- 승인 대기·반려·활성은 driver의 현재 상태와 반려 사유로 화면을 결정한다.
- 로그인만으로 기사 `Driver`나 가입 신청을 자동 생성하지 않는다.
- `driver → identity` 공개 API 방향으로 협력한다. identity는 driver 내부 엔티티·Repository를 조회하지 않는다.
- driver 탈퇴 시 인증 계정 비활성화·전체 세션 폐기를 함께 반영한다. 후속 driver 구현에서 트랜잭션과 동시성 검증이 필요하다.
- 사업자 검증·가입 신청·관리자 승인·업무 API의 최신 상태·소유권 검사는 후속 driver/업무 단계에서 구현한다.

인증 정보 확인 API가 성공했다고 배차·작업·채팅 권한이 부여되는 것은 아니다.

## 확정된 세션 정책

정책 원본은 [BR-02 Authentication Sessions](../../apps/backend/docs/business-specification.md#authentication-sessions)에 기록한다.
Access Token 기본 15분, 세션 절대 수명 기본 14일, 여러 기기 로그인 허용, Refresh Token 회전 및 로그아웃 즉시 차단 정책을 적용한다.
앱은 현재처럼 서비스 토큰을 메모리에만 보관하며, 앱 재시작 시 다시 로그인한다.

서비스 JWT에는 내부 계정 ID(`sub`), 세션 ID(`sid`), 발급자(`iss`), 수신 대상(`aud`), 발급·만료 시각과 토큰 용도를 둔다.
계정 유형·관리자 역할·세션의 현재 상태는 서버 저장 상태에서 확인한다.
매 보호 요청에서 계정·세션의 사용 가능 여부를 확인하므로, 세션 정책은 특정 서버 인스턴스의 메모리에 의존하지 않는다.
`refreshExpiresIn`은 응답 시점부터 세션 절대 만료 시각까지의 남은 초다.

## 오류 계약

기존 백엔드 `ErrorResponse` 형식을 사용하고, 카카오 원본 오류나 입력된 자격 증명을 응답에 포함하지 않는다.

| 상황 | HTTP 상태 |
|---|---|
| 잘못된 입력 형식·누락 | 400 |
| 관리자 ID/PW 불일치 | 401, 어느 필드가 틀렸는지 구분하지 않음 |
| 무효·만료 카카오 토큰 또는 다른 앱의 토큰 | 401 |
| 무효·만료·이미 사용한 Refresh Token | 401 |
| 보호 API의 만료·변조 토큰 또는 폐기된 세션 | 401 |
| 유효한 자격 증명에 연결된 비활성 계정으로 로그인·갱신 | 403 |
| 인증됐으나 해당 작업 권한 없음 | 403 |
| 카카오 장애·타임아웃 | 503, 인증 실패와 구분 |

로그인·갱신 API의 요청 제한은 필요하지만, 임계값과 운영 환경 적용 방식은 별도 확정한다.

## 기존 계약과 프론트 연결 변경

| 현재 프론트 operation | 변경할 HTTP 요청 | 어댑터 변경 |
|---|---|---|
| `auth/kakao` | POST `/auth/sessions/kakao` | `accessToken` 입력을 `kakaoAccessToken`으로 매핑 |
| `auth/admin-login` | POST `/auth/sessions/admin` | `username`을 `loginId`로 매핑 |
| `auth/refresh` | POST `/auth/tokens/refresh` | 신규 Refresh Token도 반드시 저장 |
| `auth/logout` | POST `/auth/sessions/logout` | 메모리의 Refresh Token을 본문에 추가 |

- `API_BASE_URL`은 `/api/v1`을 포함하게 설정한다. 업무 operation은 이번 단계에서 변경하지 않는다.
- 전체 `workflow/state`, 기사 프로필, 가입 상태를 identity 응답으로 만들어내지 않는다.
- 카카오 로그인 뒤 driver 조회와 UI 상태 변환을 어댑터·로그인 흐름에 연결해야 한다. 기존의 `account: null`을 그대로 적용하면 모든 사용자가 가입 화면으로 이동하므로 함께 수정한다.
- 전체 OpenAPI 초안의 인증 경로·스키마는 이 계약으로 갱신한다. 기존 로그인 응답의 `accountStatus`·`fullAccess`·중첩 `tokens`는 제거하고 최상위 TokenPair를 반환한다.
- 미구현 업무 API나 driver 가입 상태를 가짜 데이터로 연결하지 않는다.

## 단계별 완료 기준

1. **완료:** 이 계약의 API 형식·기사 상태 조회 분리와 세션 정책을 확정했다.
2. **완료:** Account·외부 로그인 연결·AuthSession 모델, Repository, V2 Flyway 마이그레이션을 구현했다.
3. **완료:** 카카오 검증 클라이언트와 정상·만료·타 앱·장애·설정 검증 테스트를 구현했다.
4. 카카오 로그인과 토큰 발급을 구현하고 중복 계정 생성 경쟁을 검증한다.
5. JWT 요청 인증과 `/auth/accounts/me`를 연결해 실제 앱 인증을 확인한다.
6. 갱신·로그아웃·동시 갱신·폐기 세션 차단을 검증한다.
7. driver 가입·승인·반려·탈퇴 및 업무 권한을 연결한다.
8. 관리자 ID/PW 인증과 사전 계정 발급을 연결한다. 역할별 업무 권한 차등·요청 제한의 미확정 정책은 해당 작업 전에 확인한다.

## 영속성 구현과 검증

- `identity`를 Spring Modulith 모듈로 등록하고, `Account`와 `AuthSession`을 별도 Aggregate로 관리한다.
- `Account`의 카카오 연결·관리자 자격 증명은 Embeddable이며 `identity_accounts`에 함께 저장한다. `AuthSession`은 `identity_auth_sessions`에 저장한다. 세부 소유권·제약은 [Identity Persistence](../../apps/backend/docs/business-specification.md#identity-persistence)를 따른다.
- 스키마는 `V2__create_identity_accounts_and_sessions.sql`로 추가하며 V1은 수정하지 않는다.
- 이 단계에는 로그인 API, 토큰 난수 생성·해싱, 계정 비활성화와 전체 세션 폐기를 조정하는 서비스가 아직 포함되지 않는다. 이후 단계에서 연결한다.

도메인·모듈 경계·스타일 검사는 Java 21 환경에서 `apps/backend`를 기준으로 실행한다.

```powershell
.\gradlew.bat ktlintCheck test --tests 'com.allcheck.identity.domain.*' --tests 'com.allcheck.ModularityTests'
```

영속성 테스트에는 **다른 데이터가 없는 별도 PostgreSQL 테스트 DB**를 사용한다. 로컬 Compose PostgreSQL을 사용하는 경우 서버를 실행한 뒤 테스트 DB를 한 번 생성한다.

```powershell
createdb -h 127.0.0.1 -p 15432 -U allcheck allcheck_identity_test
$env:IDENTITY_TEST_DATABASE_URL = 'jdbc:postgresql://localhost:15432/allcheck_identity_test'
.\gradlew.bat test --tests 'com.allcheck.identity.infrastructure.IdentityPersistenceTest'
```

접속 계정이 로컬 기본값과 다르면 `IDENTITY_TEST_DATABASE_USER`, `IDENTITY_TEST_DATABASE_PASSWORD`도 설정한다.
이 테스트는 Flyway로 생성한 스키마를 Hibernate `validate`로 확인하고, 실제 DB의 유니크·외래키·계정 유형 제약과 독립 트랜잭션의 동시 갱신 충돌을 검증한다. 테스트 자체는 Flyway clean이나 기존 데이터 전체 삭제를 수행하지 않는다.

2026-11-17 검증 결과: Java 21·Gradle 9.8.2와 분리된 임시 PostgreSQL 18에서 `ktlintCheck test`를 실행해 전체 92개 테스트가 통과했다. Flyway V1·V2 적용, Hibernate 스키마 검증, 동시 갱신의 단일 커밋을 확인했다. 로컬 Compose의 PostgreSQL 17에서는 아직 실행하지 않았다.

## 카카오 검증 클라이언트

`identity.infrastructure.kakao.KakaoClient.verify(accessToken)`은 고정된 `https://kapi.kakao.com/v1/user/access_token_info`에 GET 요청을 보낸다. 토큰은 Authorization Bearer 헤더에만 넣으며 URL·로그·DB에 기록하지 않는다. HTTP 리다이렉트를 따르지 않고 연결·응답 대기 타임아웃을 적용한다.

응답의 `id`, `app_id`, `expires_in`을 정수로 확인한다. 양수 회원번호와 설정된 앱 ID의 일치, 남은 유효시간을 검증한 후 `SocialLogin`만 반환한다. 문자열·소수·누락 필드를 임의 변환해서 인증하지 않는다. 사용자 프로필 API 호출, 계정 조회·생성 및 서비스 토큰 발급은 이 클라이언트의 책임에 포함하지 않는다.

설정은 `identity.kakao` 아래에서 바인딩한다.

| 환경 변수 | 의미 | 기본값 |
|---|---|---|
| `KAKAO_APP_ID` | Kakao Developers에 등록한 앱의 숫자 ID. Native App Key·REST API Key와 다름 | 없음 |
| `KAKAO_CONNECT_TIMEOUT` | 카카오 연결 대기 시간 | `PT2S` |
| `KAKAO_READ_TIMEOUT` | 카카오 응답 대기 시간 | `PT5S` |

앱 ID가 미설정이면 백엔드는 시작할 수 있지만 카카오 인증은 외부 요청 없이 503으로 차단한다. 0·음수·문자열 앱 ID와 0·음수 타임아웃은 설정 오류로 시작을 중단한다. 실제 SDK 연결 검증 전에 본인 앱의 `KAKAO_APP_ID`를 환경에 설정해야 한다. 테스트용 숫자 ID를 운영 설정의 기본값으로 사용하지 않는다.

카카오의 [액세스 토큰 정보 조회 문서](https://developers.kakao.com/docs/ko/kakaologin/rest-api#req-access-token-info)에 따라 다음처럼 실패를 분류한다.

| 카카오 응답 또는 상황 | 서비스 오류 코드 | HTTP 상태 |
|---|---|---|
| HTTP 401, HTTP 400의 `code=-401` 또는 `code=-2` | `KAKAO_AUTH_FAILED` | 401 |
| 성공 응답이지만 토큰 만료 또는 다른 앱 ID | `KAKAO_AUTH_FAILED` | 401 |
| HTTP 400의 `code=-1` 또는 알 수 없는 오류 | `KAKAO_SERVICE_UNAVAILABLE` | 503 |
| 연결 실패·타임아웃·5xx·429·리다이렉트·비정상 성공 응답 | `KAKAO_SERVICE_UNAVAILABLE` | 503 |
| 서버 앱 ID 미설정 | `KAKAO_SERVICE_UNAVAILABLE` | 503 |

공통 `BusinessException`·`ErrorResponse`를 사용한다. 외부 오류 메시지·응답 본문은 공개 메시지로 전달하지 않으며, 공통 예외 처리기는 원본 cause 대신 서비스 오류 코드만 로그에 기록한다. 카카오 오류가 발생했다고 기존 계정이나 세션을 변경하지 않는다.

아래 테스트는 실제 카카오 API·자격 증명·DB 없이 가짜 HTTP 응답으로 요청 형식과 오류 분류를 검증한다. 네트워크 타임아웃은 가짜 전송 예외로 검증하며 실제 카카오 연결 속도·가용성을 측정하는 테스트는 아니다.

```powershell
.\gradlew.bat ktlintCheck test --tests 'com.allcheck.identity.infrastructure.kakao.*' --tests 'com.allcheck.ModularityTests'
```

2026-11-17 검증 결과: 카카오 클라이언트·설정 테스트 55개가 통과했다. Java 21과 분리된 임시 PostgreSQL 18에서 `ktlintCheck test`를 실행해 기존 테스트를 포함한 전체 147개가 통과했으며 실패·오류·건너뜀은 없었다. 실제 카카오 SDK 토큰을 이용한 연결 검증은 로그인 API를 연결하는 4단계 이후에 수행한다.
