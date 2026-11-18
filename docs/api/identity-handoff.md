# Identity 구현 인수인계

기준: 2026-11-18, 7단계 완료·8단계 진행 중. 새 세션에서는 이 파일과 실제 Git 상태를 함께 확인한다.

## 저장소 상태

- 프로젝트: `D:\4-2\allcheckclone\capstone`
- 브랜치: `feat/ACK-30-identity-domain`, 원격 `origin`의 동일 브랜치
- 1단계 `fb9cab5`: 인증 API 계약과 세션 정책 확정
- 2단계 `6e8399f`: 계정·세션 영속성
- 3단계 `ee79bdb`: 카카오 검증 클라이언트
- 4단계 `2686956`: 카카오 로그인과 서비스 토큰 발급
- 5단계 `1d62f36`: JWT 요청 인증과 내 계정 조회
- 6단계 `92f5b96`: 토큰 갱신과 로그아웃
- 7단계 `6190ee6`: 기사 가입·심사·탈퇴와 작업·푸시 기반. origin의 동일 브랜치에 push했다.
- 8단계 현재 구현: 관리자 로그인과 공통 세션 발급을 이 인수인계와 함께 커밋·push한다. 사전 발급 방식·요청 제한 범위는 확인 대기 상태다. 실제 커밋 해시와 원격 상태는 Git으로 확인한다.
- 기존 미추적 `.idea/`는 사용자 로컬 설정이므로 커밋에서 제외한다.

## 먼저 읽을 문서

1. 루트 [AGENTS.md](../../AGENTS.md), [백엔드 AGENTS.md](../../apps/backend/AGENTS.md)
2. [Identity API 계약](identity-contract.md), [기사 가입 API 계약](driver-contract.md)
3. [Business Specification](../../apps/backend/docs/business-specification.md)의 소유권·BR-01·BR-02
4. 변경 범위에 필요한 백엔드 architecture/code/security/API/exception/persistence/test 규칙

현재 파일은 작업 상태 요약이다. 이미 합의된 정책을 다시 선택 사항으로 취급하지 않는다.

## 사용자와 확정한 사항

- 관리자·기사 앱은 분리하고 같은 백엔드·identity를 공유한다. 관리자는 사전 발급 ID/PW를 사용한다. 공개 회원가입·MFA·TOTP는 구현하지 않는다.
- 기사 카카오 SDK 로그인은 프론트에서 동작한다. 서버가 SDK Access Token을 검증하고 서버가 확인한 회원번호로 계정을 식별한다.
- 로그인 응답은 최상위 TokenPair와 `user.accountId/userType/role`이다. 가입 상태는 별도 driver API에서 조회한다.
- identity는 계정·인증 정보·세션·푸시 기기 연결을, driver는 개인정보·사업자 정보·가입·심사·탈퇴 상태를 소유한다. `driver → identity` 공개 API 방향을 유지한다.
- Access Token 기본 15분, 세션은 최초 로그인부터 기본 14일 절대 만료다. 여러 기기 세션을 허용하고 Refresh Token은 난수로 생성하여 SHA-256 해시만 저장한다.
- 갱신은 같은 세션의 Refresh Token을 교체한다. 동시 갱신은 하나만 성공하고 이전 토큰 재사용만으로 새 토큰·다른 세션을 자동 폐기하지 않는다.
- 로그아웃은 해당 세션·기기 연결만 폐기하고 Access Token도 즉시 차단한다. 계정 비활성화·탈퇴는 전체 세션·기기 연결을 폐기한다.
- 앱은 현재처럼 서비스 토큰을 메모리에만 보관한다.
- 2026-11-18 기사 OpenAPI 초안의 가입·재신청·조회·관리자 심사·탈퇴 계약을 채택했다.
- 국세청 진위확인에 사업자등록번호·개업일자·대표자명을 사용한다. 가입·재신청은 **계속사업자만 허용**한다.
- 이름·사업체명은 **부분 일치 검색**한다.
- 기사 API를 먼저 구현하고 실제 미완료 작업 조회와 승인/반려 푸시 발송 기반까지 구현한다.

MASTER/SUB 상세 업무 권한 차등, 로그인 요청 제한 임계값, 푸시 실패 자동 재전송, 데이터 보존/삭제 기간은 미확정이다. 임의로 추가하지 않는다. PostGIS·위치 배차는 이번 인증 단계 범위가 아니다.

## 구현 상태

### identity 1~6단계와 7단계 연동

- Account·AuthSession, V2·JPA·Repository, 카카오 로그인·JWT·갱신·로그아웃·내 계정 조회를 구현했다. 현재 계정·세션 상태를 요청마다 DB로 확인한다.
- 카카오 검증은 트랜잭션 밖에서 수행한다. 계정 upsert·계정 잠금·세션 저장·JWT 발급은 한 트랜잭션이다. 로그인만으로 Driver를 생성하지 않는다.
- JWT HS256·sub/sid/iss/aud/iat/exp/token_use=access를 검증하고 AuthPrincipal에 현재 유형·역할을 보존한다. JWT 권한 Claims로 업무 권한을 부여하지 않는다.
- 카카오 로그인·갱신·로그아웃 POST는 본문 자격 증명으로 처리한다. 내 계정은 AuthUser만 반환한다. 갱신·로그아웃은 계정 → 세션 순서로 잠근다.
- IdentityAccountOperations는 공개 계정 잠금과 기사 계정 비활성화·전체 세션 폐기를 제공한다. 탈퇴 호출자의 트랜잭션에 참여하며 관리자 계정은 변경하지 않는다.
- PushDeviceOperations와 `/api/v1/push-devices/{installationId}`는 ADMIN/DRIVER의 본인 세션·기기 연결을 관리한다. 토큰은 응답·로그에 노출하지 않는다.

### 7단계 기사 API·작업·푸시

- DriverApi는 가입·재신청·본인 상태·관리자 목록/상세·승인/반려·탈퇴를 제공한다. 개인정보·사업자 정보는 별도 테이블이며 이전 신청 전체 스냅샷과 심사 이력을 보존한다.
- NtsBusinessClient는 진위확인과 운영 상태를 구분한다. 계속사업자만 신청을 허용한다. 키 미설정·장애는 503, 불일치·휴업·폐업은 400이다.
- 계정 → driver 잠금·expectedVersion·DB advisory lock으로 동시 변경과 멱등성을 조정한다. 상태 변경·이력·발송 의도·멱등성 결과를 함께 커밋한다.
- 보안 필터는 driver 공개 API의 현재 DB 판정을 사용해 미가입·승인 대기·반려 기사의 일반 업무 접근을 차단한다. 개별 리소스 권한은 각 업무 API에서 검증해야 한다.
- operations.work는 실제 work_orders와 WorkOperations.recordAssignment를 제공하고 driver 소유 DriverWorkload SPI를 구현한다. WORK_DONE/CANCELLED 외 모든 상태가 미완료다. 배정과 탈퇴는 같은 계정·기사 잠금을 사용한다.
- 배차 전체·작업 진행/보고서 HTTP API는 아직 없다. 배차 호출자는 READY·분야·지역·위치·의뢰 상태를 검증해야 한다. 상세의 availability는 아직 null이다.
- DB outbox와 기기별 전송은 각각 중복 방지한다. FCM 호출은 커밋 후 수행하며 전송 전에 현재 신청·기기 소유권·세션을 재확인한다. 여러 작업자와 중단 전송 임대 복구를 DB로 조정한다.
- PROVIDER_ACCEPTED는 제공자 접수이며 단말 수신 보장이 아니다. 명시적 실패는 FAILED다. 중단 복구 시 제공자 중복 전송 가능성이 있어 앱은 eventId로 중복 처리하고 최신 상태를 조회한다.
- V3~V6을 추가했으며 V1·V2는 수정하지 않았다. V1 관리자 분석 테이블·AI 알림 연결은 기사 발송기와 별개다.

## 8단계 진행과 다음 작업

관리자 ID/PW 로그인은 AuthSessionApi의 `/admin` POST로 구현했다. 해시는 PBKDF2-HMAC-SHA256·600,000회·16바이트 salt다. 외부 트랜잭션을 중단하고 검증한 뒤 계정을 잠가 자격 증명·현재 활성 상태를 다시 확인한다. 공통 SessionIssuanceService를 카카오와 공유하여 기존 세션 정책을 유지한다. 상세는 [관리자 인증 계약](admin-auth-contract.md)을 따른다.

사용자에게 사전 계정 발급을 운영자 CLI로 할지, 로그인·갱신 요청 제한을 후속으로 분리할지 질문했다. 사용자는 현재 구현의 커밋·push와 두 용어의 설명을 요청했다. **두 정책의 선택은 아직 확정되지 않았으며 그 결정에 의존하는 구현을 진행하거나 8단계 전체 완료로 표시하지 않는다.** 기존 MASTER/SUB 권한 차등은 추가하지 않는다.

프론트 어댑터의 새 인증/driver 계약 연결, 실제 카카오 SDK 토큰·국세청 키·Firebase 자격 증명/실기기 토큰 검증도 남아 있다. 실제 키를 제공받지 않아 외부 실서비스 호출은 수행하지 않았다.

## 검증 및 환경

- 8단계 현재 전체 검증: 2026-11-18 Java 21·Gradle 9.8.2·격리 PostgreSQL 18, `--offline ktlintCheck test`, **389개 통과 / 실패·오류·건너뜀 0**. 관리자 로그인 27개·해시 8개를 추가했고 카카오/세션 테스트의 이전 미구현 경로 기대를 현재 계약으로 갱신했다. 사전 발급·요청 제한 결정 대기 상태이므로 8단계 전체 완료는 아니다.

- 최종 전체 검증: 2026-11-18, Java 21·Gradle 9.8.2·격리 PostgreSQL 18, `--offline ktlintCheck test`, 전체 테스트 **355개 통과 / 실패·오류·건너뜀 0**. 생성 OpenAPI 헤더와 YAML 파싱·로컬 문서 링크 34개도 확인했다.
- 실제 HTTP/Spring Security·DB 상태 전이·동시 가입·버전/멱등성·공동 롤백·배정/탈퇴 경합·기기 소유권·로그아웃·푸시 대상/실패/중단 복구·모듈 경계를 검증한다. 국세청·FCM 네트워크는 격리한다.
- 6단계 전체 258개, 7단계 초기 identity 공개 API 추가 후 전체 266개가 통과했다. 최종 검증은 그 이후 변경을 포함한다.
- 실제 외부 자격 증명 연결과 Compose PostgreSQL 17 검증은 아직이다.
- Docker가 실행되지 않아 로컬 PostgreSQL 18 테스트 전용 클러스터를 사용했다. 새 세션에서 실행 상태를 가정하지 않는다.
- 클러스터: `C:/Users/Public/Documents/ESTsoft/CreatorTemp/identity-postgres-20261116`, 포트 25432, DB `allcheck_identity_test`, 사용자/암호 `identity_test`. 운영 환경이 아니다.
- Java 21: `C:/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot`, 캐시: `C:/Users/user/.gradle`. 기본 JAVA_HOME이 17이므로 실행 시 지정한다.
- Gradle은 apps/backend에서 실행한다. `IDENTITY_TEST_DATABASE_URL/USER/PASSWORD`와 기본 컨텍스트용 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`를 위 격리 DB에 설정하고 `gradlew.bat --offline ktlintCheck test`로 재현한다. 캐시 접근 거부 시 정해진 escalation 절차를 사용한다.
- `KAKAO_APP_ID`는 숫자 앱 ID다. NTS 설정은 [identity 계약](identity-contract.md#국세청-진위확인-클라이언트), Firebase ADC·FCM 설정은 [기사 계약](driver-contract.md#푸시-기기와-발송)을 따른다.
- 7단계 검증 후 PostgreSQL을 종료했다. 8단계 착수 시 같은 테스트 서버가 이미 실행 중이어서 새로 시작하지 않고 기존 서버를 사용했다. 이 실행은 이번 작업에서 시작한 것이 아니므로 임의 종료하지 않는다.

## 새 세션 시작 문구

```text
AGENTS.md와 apps/backend/AGENTS.md를 따르고 docs/api/identity-handoff.md,
docs/api/identity-contract.md, docs/api/driver-contract.md를 읽어 현재 상태를 복원해줘.
docs/api/admin-auth-contract.md도 읽고 8단계의 실제 diff와 사용자 답변 여부를 확인해줘.
확정된 정책을 유지하며 관리자 인증과 사전 계정 발급을 이어서 진행하자.
```
