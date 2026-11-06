# AI API v1.2 설계 초안

Jira: **ACK-23**. 구현 전 계약·테이블 설계 검토용 문서다.

상태: **팀 검토용 초안, /health 외 미구현**. DB 적용·FastAPI 기능 구현은 수행하지 않았다.

## 기준과 파일

- PR #7 HEAD: `341a01597af2d697cd53906fb27ed8c4b7e44072`
- [기준 OpenAPI](https://github.com/allchecksolution/capstone/blob/341a01597af2d697cd53906fb27ed8c4b7e44072/docs/api/openapi.yaml)
- [AI OpenAPI 3.1.1](ai-api-v1.2.yaml)
- [Backend 웹훅 수신 OpenAPI 3.1.1](backend-callback-v1.2.yaml)
- [SQL 초안](../../backend/src/main/resources/db/migration/V2026110601__create_ai_job_tables.sql.draft)
- [검증 결과](validation-v1.2.md)

두 OpenAPI는 각각 독립된 servers를 사용한다. AI는 `http://127.0.0.1:8000`, Backend 콜백은 `http://127.0.0.1:8080`을 로컬 origin 예시로 사용하며 `/api/v1`을 붙이지 않는다. 배포 서버 주소는 TODO다. 기존 Backend OpenAPI·Swagger 설정은 변경하지 않는다. 명세 파일을 Swagger UI로 열어 확인할 수 있으나 현재 실행 서버에 내부 API가 자동 추가되는 것은 아니다.

## API 흐름

### 확정된 책임 분리

| 영역 | Backend 책임 | FastAPI 책임 |
|---|---|---|
| 기존 PostgreSQL 전체 운영 | DB 운영, Flyway 버전·적용 및 AI 전용 테이블 접근 권한 관리 | 승인된 AI 전용 테이블만 접근. Backend 소유 테이블 직접 접근 금지 |
| 녹음 | 파일 저장 후 바이너리를 multipart/form-data로 FastAPI에 전달 | 전달받은 녹음으로 STT 실행 |
| 업무 데이터 | 접수 데이터, 정식 STT 원문, GPT 분석 결과 및 전체 처리 흐름 관리 | STT·GPT 실행 결과를 계약으로 반환 |
| AI 실행 상태 | 접수의 업무 처리 상태와 후속 흐름 관리 | AI 작업 상태 및 웹훅 발송 상태 관리 |

AI 작업 테이블의 transcript는 작업 조회·결과 전달을 위한 AI 측 사본이며 Backend의 정식 STT 원문을 대체하지 않는다. 결과의 Backend 저장 확인, 사본의 보관·삭제 시점은 TODO다. HTTP 204 웹훅 응답만으로 Backend의 정식 원문 저장 완료를 가정하지 않는다.

1. Backend가 녹음 파일을 저장한 뒤 작업자가 jobId를 발급하고 파일 바이너리와 메타데이터를 multipart로 전송한다.
2. AI는 작업·중복 방지 정보를 내구성 있게 저장하고 처리 예약 후 HTTP 202를 반환한다.
3. 작업자는 전사 결과를 저장하고 같은 DB 트랜잭션에서 웹훅 outbox를 만든다.
4. HTTP 전송은 DB 트랜잭션 밖에서 수행한다. 성공·실패 종료 이벤트를 모두 보낸다.
5. Backend는 콜백을 멱등하게 접수하고 작업 조회 API로 원문·실패·receptionVersion을 확인한다.
6. 성공한 STT에 대해 Backend 작업자가 analyze를 동기 호출한다.
7. Backend가 aiOriginal과 상태를 저장한다. 관리자의 finalData 수정·검토 확정·의뢰 생성은 기존 Backend 흐름을 따른다.

| API | 요청 | 성공 응답 |
|---|---|---|
| GET /health | 없음, 인증 없음 | 200 HealthResponse |
| POST /internal/v1/transcribe | Idempotency-Key + TranscribeRequest multipart | 202 TranscribeAccepted |
| GET /internal/v1/jobs/{jobId} | jobId path | 200 SttJobView |
| POST /internal/v1/analyze | AnalyzeRequest JSON | 200 AnalyzeSucceeded |
| POST /internal/v1/ai/jobs/{jobId}/callback | jobId path + SttCallback JSON | 204, 본문 없음(기본 제안) |

요청·응답 필드와 제한의 단일 정의는 YAML이다. JSON 필드와 multipart 파트는 camelCase다. jobId·receptionId·recordingFileId는 불투명 문자열 1~64자, receptionVersion은 정수 1 이상이다. analyze의 jobId는 연결된 STT 작업 ID로 제안하며 재분석 식별 정책은 TODO다.

## 기존 접수 계약과 호환성

Id, DateTime, WorkType, ReceptionDraftFields, Error를 기준 PR에서 구조 변경 없이 두 문서에 복사했다. 공유 스키마 변경 시 같은 비교 검증을 다시 수행한다.

- WorkType: EXTERIOR_LEAK / INTERIOR_LEAK / BLOCKAGE.
- ReceptionDraftFields는 선택 필드 6개이며 추가 필드와 각 필드의 null을 허용하지 않는다.
- symptoms는 1~2000자, address는 1~300자, notes는 최대 2000자다. notes의 빈 문자열은 기존 스키마상 허용된다.
- desiredVisitFrom/To와 referenceTime은 시간대가 포함된 RFC 3339다. 방문 종료는 시작 이후여야 한다. 순서와 timeZone 일관성은 구현에서 검증한다.
- transcript는 최대 100000자다. 기존 PR처럼 minLength는 추가하지 않았다. 무음/빈 원문과 모델 입력 한도 정책은 TODO다.
- analyze 성공 시 aiOriginal은 객체이며 미확인 값은 null 대신 생략한다. 모든 값을 모르면 빈 객체를 반환하는 기본안이다. missingFields는 확정 필수 필드 workType/symptoms/address/desiredVisitFrom/desiredVisitTo 중 생략된 값만 나열하며 notes는 제외한다.
- Backend ReceptionDetail의 transcript와 aiOriginal은 키가 필수이며 결과가 없으면 null이다. 신규 job 응답의 생략 규칙을 Backend 공개 응답에 그대로 적용하지 않는다.
- Backend finalData는 관리자가 저장한 값이며 AI가 덮어쓰지 않는다. 재분석·늦은 결과에 대한 Backend 낙관적 버전 검증과 확정 후 처리 정책은 TODO다.

## 비동기 상태·필드 생략

SttJobStatus는 PENDING / PROCESSING / SUCCEEDED / FAILED다. Backend ProcessingStatus의 NOT_AVAILABLE은 존재하지 않거나 시작 불가능한 처리 단계를 표현하므로 STT 작업 Enum에 추가하지 않는다. 존재하는 작업 상태 4개는 Backend sttStatus에 동일 이름으로 매핑한다. NOT_AVAILABLE의 구체적인 매핑 시점은 팀 합의가 필요하다.

- PENDING/PROCESSING: transcript와 error를 생략하며 null로 대체하지 않는다.
- SUCCEEDED: transcript 필수, error 금지.
- FAILED: Error 객체 필수, transcript 금지.
- 실패한 작업 조회는 리소스 조회 성공이므로 HTTP 200이다. 조회 실패나 요청 오류를 200으로 감싸지 않는다.
- 전사 202는 작업 완료가 아니다. 동일 키/동일 입력 재시도는 최초 접수 응답을 재현하고 최신 상태는 GET으로 확인한다.

## 멱등성·인증·오류

**TODO: 웹훅 인증 방식과 내부 API 인증 정책은 미확정이다.** YAML의 `X-Internal-Api-Key` 및 security 요구는 계약 검토용 후보 표시이며 최종 채택을 의미하지 않는다. 실제 키는 포함하지 않는다. /health는 기존 공개 상태 확인을 유지한다. 인증 후보는 관리자/기사 JWT와 구분하며 Backend→AI, AI→Backend의 방향별 키·TLS·교체·권한·로그 마스킹은 승인 전 확정하지 않는다.

Idempotency-Key는 필수 문자열 1~200자다. 기존 requestId 명칭은 사용하지 않아 고객 의뢰 ID와의 혼동을 피한다. DB는 사용자 요청대로 전역 UNIQUE로 제안했다. 기존 Backend의 인증 주체+method+경로 범위와 다르므로 다중 서비스 주체를 지원하기 전에 키 이름공간 또는 복합키를 합의한다. 유효기간과 개인정보 삭제 후 중복 방지 정책도 TODO다.

request_hash는 lowercase SHA-256 hex 64자로 제안한다. 파일 바이트와 jobId/receptionId/receptionVersion/recordingFileId 등 의미 있는 입력을 포함하고 multipart boundary는 제외해야 한다. 정확한 canonicalization·파일 해시 결합 방식은 구현 전 TODO다. 같은 키 다른 의미 입력과 충돌한 jobId는 409로 제안한다.

콜백은 성공과 실패를 모두 알리고, 재시도 중 동일한 `X-Webhook-Delivery-Id` UUID와 본문을 유지한다. 수신 측 영속 중복 확인과 후속 조회 작업의 내구성 있는 예약 후 204를 반환하는 기본안이다. 전송 성공과 STT 성공은 다른 상태다. 콜백에 receptionVersion이 없으므로 Backend는 후속 조회에서 확인한다. 경로와 본문의 jobId는 같아야 한다. 204/200 선택과 수신 inbox 저장소는 TODO다.

공통 Error는 code/message/traceId 필수이며 resourceId/currentState/fieldErrors만 선택이다. FastAPI 기본 detail 오류 형식을 그대로 사용하지 않는다. 입력 오류는 PR #7 기준 422로 정의했다. Backend 일반 예외 지침의 400 권고와의 최종 통일은 TODO다. 추가 오류 코드와 500은 내부 API 초안 제안이며 기존 코드와 전역 중복 여부를 검토한다. 원문·키·서명 URL·제공자 내부 오류를 message/로그에 노출하지 않는다.

job 테이블은 error_code/error_message만 보존한다. 조회 Error.traceId를 현재 조회 요청에서 생성할지 원래 실패 trace를 별도 보존할지 TODO이며 역사 추적을 보장한다고 주장하지 않는다.

## DB 초안

기존 PostgreSQL 17만 사용한다. DB 전체 운영·Flyway 적용과 AI 전용 테이블 접근 권한은 Backend가 관리한다. FastAPI는 승인된 ai_stt_jobs/ai_webhook_deliveries만 접근하고 Backend 소유 테이블은 직접 조회·수정하지 않는다. AI 전용 DB 서버·스키마 자동 생성은 추가하지 않는다. 구체적인 DB 계정명·schema/search_path·최소 권한 설정은 TODO다.

| 테이블 | 컬럼·제약 제안 |
|---|---|
| ai_stt_jobs | 요청한 14개 컬럼. job_id PK, idempotency_key UNIQUE, 필수 ID/상태/버전/해시/횟수/생성·수정 시각 NOT NULL. transcript·오류·완료 시각 NULL 허용. 버전 >=1, 횟수 >=0, 원문 <=100000자, 상태별 결과 일관성 CHECK |
| ai_webhook_deliveries | 요청한 10개 컬럼. delivery_id UUID PK, job_id FK(RESTRICT), UNIQUE(job_id,event_type). 종료 이벤트 및 배달 상태 CHECK, 횟수 >=0, HTTP 상태 100~599. 재시도·최근 응답·오류·배달 완료 시각 NULL 허용 |

웹훅 event_type은 STT_SUCCEEDED/STT_FAILED, 배달 status는 PENDING/PROCESSING/DELIVERED/FAILED를 **기본 제안**으로 둔다. job의 terminal 상태는 불변이라는 전제이며, 작업 재시작·재분석·반복 이벤트가 필요하면 이벤트 식별/유일성 정책부터 변경해야 한다. 이 코드와 terminal 불변 정책은 TODO 승인 대상이다.

인덱스는 접수+버전 조회, PENDING 작업 선점, PROCESSING 복구 조회, 대기 웹훅의 COALESCE(next_retry_at,created_at) 실행 시각 조회를 지원한다. FK 인덱스는 UNIQUE(job_id,event_type)가 선두 job_id를 제공하므로 중복 생성하지 않는다. 실제 실행계획·부하 검증은 미수행이다.

completed_at/delivered_at은 완료 전 NULL이다. created_at/updated_at은 기본 현재 시각, updated_at 갱신은 애플리케이션 책임이다. timestamptz는 원래 IANA 시간대 이름을 보존하지 않으므로 재현에 필요한 timeZone/referenceTime 보존 위치는 TODO다. DB varchar 길이와 Error DTO의 무제한 code/message는 동일한 제한이 아니며 저장용 code 64자 정책을 합의해야 한다.

request_hash 형식과 상태별 CHECK는 검토용 제약 제안이다. ID와 멱등성 키는 공통 API처럼 최소 1자이며 PostgreSQL varchar 길이는 문자 수 기준이다. 외부 reception/recording 테이블의 실제 PK가 아직 없으므로 FK를 임의로 만들지 않았다.

## Flyway 적용 전 필수 TODO

1. 기준 시점 로컬·원격 main·PR #7에는 기존 SQL migration이 없었다. `V2026110601`은 충돌 없는 초안 번호이지 운영 DB 이력에 대해 예약한 버전이 아니다. 팀원 미공개 작업·flyway_schema_history는 확인하지 않았으며 승격 직전 재확인한다.
2. 중앙 위치 규칙에 따라 Backend migration 디렉터리에 두되 `.sql.draft`로 저장했다. 현재 기본 `.sql` 검색에는 포함되지 않는다. 승인 없이 이름을 바꾸거나 SQL을 실행하지 않는다.
3. DB 전체 운영·Flyway 관리 및 AI 전용 테이블 접근 권한은 Backend 책임으로 확정됐다. 기존 Backend Flyway를 유일한 schema 변경 경로로 사용한다. 구체적인 schema/search_path·계정명·허용 권한과 삭제/보존 정책은 TODO다.
4. 운영·공유 DB에 적용하지 말고 별도 승인된 테스트 DB에서 신규 적용 및 이전 버전 업그레이드를 검증한 뒤 반영한다. 이번 작업에서는 DB 테스트 서버도 만들지 않았다.
5. 네트워크 요청은 DB 트랜잭션 밖으로 분리하고, 작업 상태 확정+outbox 삽입은 원자적으로 수행하는 제안이다. **STT 비동기 작업자 구현과 서버 재시작 후 복구 방식은 TODO**이며 retry backoff/최대 횟수/lease·heartbeat·PROCESSING 복구·최종 실패 재전송은 아직 정하지 않았다.
6. 웹훅 수신 inbox·인증 정책·STT 결과의 Backend 저장 확인·AI 사본 보관/삭제 정책은 TODO다. 녹음 저장과 정식 업무 데이터 관리가 Backend 책임이라는 합의와 구분한다.

## 추가 합의 TODO

- STT 제공자, GPT 모델·토큰 제한, 요청 제한·동시성·HTTP 타임아웃, 파일 MIME·크기·길이
- 상대 날짜·불명확 작업 유형·여러 작업 유형·무음·빈 원문의 처리
- 세부 인증/권한, 키 교체, 재시도 및 이벤트/작업의 보존 기간
- 추출 결과와 missingFields의 교차 검증, 날짜 순서 및 timezone 검증
- 같은 jobId의 분석 재호출과 비용/모델 버전 추적, stale 결과 처리
- Swagger UI 실제 렌더링·Mock 호출 및 이후 Backend/AI 통합 검증

기존 Backend API 명세·실제 API·DB·Compose·루트 설정은 변경하지 않는다. 이 문서 작성은 해당 기능의 구현 완료를 의미하지 않는다.
