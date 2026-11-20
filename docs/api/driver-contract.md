# 기사 가입·심사·탈퇴 API 계약

2026-11-18 identity 7단계 구현 계약. 사용자가 [전체 OpenAPI 초안](openapi.yaml)의 기사 API와 국세청 진위확인·계속사업자 제한·부분 검색·실제 작업 조회 및 푸시 구현을 승인했다. 현재 필드별 계약은 실행 중인 서버의 `/v3/api-docs`와 API 인터페이스·DTO에서 확인한다.

가입 입력 중 확인을 위해 `POST /api/v1/drivers/business-verifications`를 추가했다. 기사 인증 후 사업자등록번호·개업일자·대표자명으로 계속사업자 여부를 확인하되 신청은 만들지 않는다. 최종 가입·재신청에서 반드시 다시 검증한다. 설정과 실제 검증은 [국세청 연결 안내](nts-business-verification.md)를 따른다.

## 경로와 접근

모든 경로는 `/api/v1`로 시작하고 Bearer Access Token을 요구한다. identity는 인증 계정·세션·기기 연결을, driver는 개인정보·사업자 정보·가입 상태·신청/심사 이력·가입 알림을 소유한다. 로그인만으로 가입 신청을 생성하지 않는다.

| 메서드 | 경로 | 권한 | 결과 |
|---|---|---|---|
| GET | `/drivers/me/registration` | DRIVER | 200 본인 신청·검증 결과·현재 반려 사유, 이력 없음은 404 |
| POST | `/drivers/me/registration` | DRIVER | 201 신규 신청, 기존 신청이 있으면 409 |
| POST | `/drivers/me/registration/resubmission` | DRIVER | 200 기존 driverId로 재신청, REJECTED만 허용 |
| GET | `/drivers` | ADMIN | 200 목록·페이지 정보, 빈 결과도 200 |
| GET | `/drivers/{driverId}` | ADMIN | 200 상세·반려 이력, 정산계좌 번호는 끝 4자리만 표시 |
| POST | `/drivers/{driverId}/approval` | ADMIN | 200 승인 또는 반려, PENDING_APPROVAL만 허용 |
| POST | `/drivers/me/withdrawal` | ACTIVATED DRIVER | 200 탈퇴, 미완료 작업이 있으면 409 |
| PUT | `/push-devices/{installationId}` | ADMIN 또는 DRIVER | 200 본인 현재 세션과 기기 연결·토큰 갱신 |
| DELETE | `/push-devices/{installationId}` | ADMIN 또는 DRIVER | 204 본인 기기 연결 해제 |

사용자 확정 정책에 따라 MASTER/SUB의 심사 권한과 다른 관리자 업무 권한은 동일하다. 두 역할에 같은 리소스 접근·업무 상태 검사를 적용한다. 승인 전에도 기사는 본인 가입 상태 조회·재신청 조건 확인·푸시 기기 등록·해제·인증 API를 사용할 수 있다.

미가입·승인 대기·반려 기사는 기사 개인정보 변경·위치·대기 상태·배차·작업·채팅 경로의 일반 업무 접근을 403으로 차단한다. 필터는 driver 공개 API의 현재 DB 판정만 사용한다. 활성 기사의 개별 리소스 소유권과 업무 상태 검사는 각 업무 API에서 수행해야 한다. 아직 없는 업무 API는 정상적인 404이며 가짜 성공 응답을 만들지 않는다.

## 신청과 심사

- 신청은 `profile`과 `business`를 받는다. 정산계좌·세금계산서 이메일은 기사 개인 정보다. 사업자 정보는 별도 테이블로 저장한다.
- 사업자등록번호 10자리·개업일자·대표자명으로 [국세청 진위확인](identity-contract.md#국세청-진위확인-클라이언트)을 수행한다. 계속사업자만 신규·재신청을 허용한다. 불일치·휴업·폐업은 400, 외부 장애·키 미설정은 503이며 실패한 신청은 저장하지 않는다.
- 최초 신청은 `PENDING_APPROVAL`, version=1이다. 승인 시 `ACTIVATED`, 반려 시 `REJECTED`가 된다. 반려는 공백 아닌 `rejectionReason`을 요구한다.
- 재신청은 `expectedVersion`과 수정한 전체 `registration`을 받는다. 같은 driverId와 accountId를 유지하고 현재 신청 회차를 올린다. 이전 제출의 전체 스냅샷과 심사 사유·처리자·시각을 이력으로 보존한다.
- 심사·재신청·탈퇴는 `expectedVersion`으로 현재 버전을 확인한다. 버전 불일치는 409 `VERSION_CONFLICT`이며 현재 상태를 다시 조회한다.
- 목록은 `accountStatus`로 필터하고 `q`로 이름 또는 사업체명의 일부를 찾는다. 대소문자를 구분하지 않으며 `%`, `_`, `!`는 검색어 문자 그대로 취급한다. page는 1부터, 기본 size는 20, 최대 100이다. 최신 제출 시각과 driverId 순으로 정렬한다.
- 상세의 `availability`는 아직 설정 기능을 구현하지 않아 null이다. 가입 승인으로 대기 상태를 임의 설정하지 않는다.

## 멱등성과 트랜잭션

가입·재신청 POST는 기사 계정별로 합산하여 첫 요청부터 60초 동안 최대 5회를 허용한다. 업무 실패와 완료 결과의 멱등 재전송도 집계하며, 초과 시 국세청 호출과 상태 변경 전에 `TOO_MANY_REQUESTS`/429 및 `Retry-After`를 반환한다. 구간 종료 후 자동 해제한다. 다른 기사 POST에는 이 제한을 확대하지 않는다. 상세는 [API 요청 제한](request-limits.md)을 따른다.

기사 POST 변경 API는 1~200자의 `Idempotency-Key`를 요구한다. actor 계정·HTTP 메서드/실제 경로·키를 묶어 처리하고 키는 SHA-256으로 저장한다. 타입으로 검증한 본문을 직렬화한 지문을 비교한다.

같은 키·본문의 완료 요청은 최초 성공 본문과 상태를 재현하며 `Idempotency-Replayed: true`를 반환한다. 최초 성공은 false다. 다른 본문에 키를 재사용하면 409 `IDEMPOTENCY_KEY_REUSED`, 같은 키가 DB 트랜잭션에서 처리 중이면 409 `OPERATION_IN_PROGRESS`다. 실패 요청은 완료 기록을 남기지 않는다. 만료·삭제 주기는 미확정이므로 임의 TTL이나 자동 삭제를 추가하지 않았다. 응답은 `Cache-Control: no-store`다.

신청/상태 변경·이력·알림 발송 의도·멱등성 결과를 같은 트랜잭션에 저장한다. 국세청 조회와 FCM 네트워크 호출은 DB 변경 트랜잭션 밖에서 수행한다. 재시도는 저장된 결과와 상태 충돌을 우선 확인하여 불필요한 국세청 호출을 피한다.

## 실제 작업 조회와 탈퇴

`operations.work`가 `work_orders`를 소유하고 driver가 정의한 `DriverWorkload` 조회 계약을 구현한다. driver가 작업 내부 Entity·Repository에 의존하지 않는다. `WORK_DONE`, `CANCELLED` 외의 모든 작업은 미완료이며 재방문 대기·보고서 미제출도 포함한다.

`WorkOperations.recordAssignment`는 실제 작업을 생성하는 내부 공개 API다. 계정 → 활성 driver 잠금 순서를 탈퇴와 공유하며 의뢰별 DB advisory lock과 기사/의뢰별 미완료 작업 유니크 인덱스로 중복 배정을 막는다. 호출자의 트랜잭션에 참여한다. 배차 호출자는 별도로 READY·전문 분야·활동 지역·위치·배차 제안 자격과 의뢰 상태를 검증해야 한다.

탈퇴는 `expectedVersion`과 `confirmed: true`를 요구한다. 미완료 작업은 409 `WITHDRAWAL_BLOCKED_ACTIVE_WORK`다. 성공하면 WITHDRAWN 전이·전체 세션 폐기·identity 계정 비활성화·전체 기기 연결 해제를 원자적으로 처리한다. 카카오 연결과 가입/심사·작업 이력을 보존하며 탈퇴 후 JWT·갱신·재로그인은 차단한다. 성공 응답을 잃어도 비활성 계정은 인증이 차단되므로 탈퇴 API 재호출은 401이 될 수 있다.

작업 진행·완료·보고서·배차 HTTP API 전체를 이번 단계에 구현한 것은 아니다. 실제 작업 저장·조회·경합 방지 기반까지 구현했다.

## 푸시 기기와 발송

기기는 `installationId`(1~64자), platform(ANDROID/IOS), `pushToken`(공백·제어 문자가 없는 1~4000자 FCM 등록 토큰)을 사용한다. 계정·세션은 JWT에서 결정한다. 토큰은 전송을 위해 DB에 저장하고 응답·로그에는 노출하지 않는다. 토큰 해시를 유니크하게 유지한다. 동일 연결 반복 등록은 같은 결과를 반환한다.

활성 상태인 다른 계정의 설치 ID를 가져오면 409, 다른 계정의 연결 해제는 403이다. 이전 소유자가 연결을 해제한 뒤 새 계정으로 등록한다. 로그아웃은 해당 세션의 토큰을 제거하고 탈퇴는 계정 전체 토큰을 제거한다. 세션 만료·계정 비활성 기기도 발송 대상에서 제외한다. 이전 토큰에 대한 FCM 오류로 갱신된 토큰을 폐기하지 않는다.

가입·재신청은 현재 관리자 기기, 승인·반려는 해당 기사 기기를 대상으로 한다. DB outbox의 논리 이벤트와 기기별 전송을 각각 중복 방지한다. 여러 작업자는 `FOR UPDATE SKIP LOCKED`로 분리하며 PROCESSING 임대가 만료되면 중단된 전송을 재획득한다. 새로운 claimId 이후 예전 작업자의 완료 기록은 무시한다. 발송 전에 현재 신청 회차·상태·계정·세션·기기 소유권을 다시 확인한다.

FCM에 전달하는 데이터는 eventId·eventType·driverId·revision뿐이며 알림 문구는 일반 안내다. 개인정보·계좌·반려 사유는 앱의 권한 있는 조회로 받는다. 제공자가 메시지 ID를 반환하면 PROVIDER_ACCEPTED, 오류는 FAILED로 기록한다. 이는 단말 수신 보장이 아니다. UNREGISTERED는 해당 토큰 연결을 폐기한다. 명시적 실패의 자동 재전송 횟수·간격은 미확정이므로 추가하지 않았다. 제공자 접수 직후 서버가 중단되면 복구 시 중복 전송될 수 있으므로 앱은 eventId로 중복 처리하고 최신 가입 상태를 조회한다.

| 환경 변수 | 의미 | 기본값 |
|---|---|---|
| `FCM_ENABLED` | 공통 FCM HTTP v1 연결·기사 주기 작업 활성화 | false |
| `FCM_PROJECT_ID` | Firebase 프로젝트 ID, 활성화 시 필수 | 없음 |
| `FCM_CONNECT_TIMEOUT` | 공통 FCM HTTP 연결 타임아웃, 활성화 시 양수 필수 | 없음 |
| `FCM_REQUEST_TIMEOUT` | 공통 FCM HTTP 요청 타임아웃, 활성화 시 양수 필수 | 없음 |
| `GOOGLE_APPLICATION_CREDENTIALS` | ADC에서 읽을 서비스 계정 파일 경로. 운영에서는 환경의 ADC도 사용 가능 | 환경 ADC |
| `FCM_DELIVERY_LEASE` | 중단 전송 복구를 위한 임대 시간 | PT1M |
| `FCM_POLL_INTERVAL` | DB 큐 조회 간격 | PT5S |

reception과 driver는 `global.config.FcmConfig` 및 `global.notification.FcmClient`의 HTTP v1 전송과 ADC 인증을 공유한다. `notification.fcm`에서 프로젝트·연결·요청 타임아웃을 설정하며, 기사 큐의 임대·조회 주기는 `driver.push`에 둔다. 임대 시간은 공통 요청 타임아웃보다 길어야 한다. driver는 기존 제목·본문을 notification으로, 식별자를 data로 함께 전송하고 reception은 기존 data-only 동작을 유지한다. OAuth 준비 후 HTTP 전송 직전에도 현재 claimId·임대·신청 상태·기기 토큰을 재확인한다. 확인 실패는 현재 claim에 한해 SKIPPED로 기록하고 예전 작업자는 새 claim을 변경할 수 없다. FCM이 비활성이면 큐를 성공 처리하지 않고 그대로 보존한다. 활성화 시 유효한 프로젝트·자격 증명과 타임아웃을 준비한다. 도메인별 알림·배송 기록과 재시도 정책은 공유하지 않으며 기존 마이그레이션은 수정하지 않았다.

## 검증과 남은 연결

실제 PostgreSQL에서 HTTP·Spring Security·Flyway/Hibernate·상태 전이·멱등성·롤백·동시 가입·작업 배정/탈퇴 경합·기기 소유권·로그아웃·FCM 실패/임대 복구를 검증한다. 외부 국세청·FCM 호출은 테스트 대역으로 격리하고, 루프백 HTTP로 공통 FCM 메시지·일반 알림 보존·오류 분류·발송 직전 취소를 확인한다. 전체 실행 결과는 [인수인계](identity-handoff.md#검증-및-환경)에 기록한다.

실제 국세청 키와 Firebase 자격 증명·실기기 토큰이 제공되지 않아 실서비스 발송은 미검증이다. 관리자 ID/PW 로그인과 운영자 CLI 신규 발급은 8단계에서 구현했다. 프론트의 새 인증/driver 계약 연결과 배차·작업 진행·위치·채팅 API는 후속 작업이다. 자동 재전송과 개인정보 보존/삭제 기간은 임의 확정하지 않는다. MASTER/SUB는 동일한 업무 권한을 가진다.
