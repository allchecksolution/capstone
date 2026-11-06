# DDD 도메인 경계와 Aggregate

ALLCHECK는 5개 Bounded Context와 1개 통합 조회 모듈로 구성한다.
계층 구성과 공통 모듈 규칙은 [백엔드 아키텍처](architecture.md), 상세 업무 정책은 [업무 규칙](../../../docs/api/business-rules.md)을 따른다.

## 1. Bounded Context와 데이터 소유권

| 구분 | 도메인 / 패키지 | 소유하는 업무 및 데이터 | 연관 요구사항 |
|---|---|---|---|
| BC | 인증·접근 관리 — `identity` | 관리자 계정·자격 증명·역할·MFA, 기사 카카오 인증 식별자·계정 연결, 인증 세션·토큰 | [FR-01], [FR-13] 인증 부분 / [UC-01], [UC-08] 인증 부분 |
| BC | 상담 접수 — `reception` | 상담 건, 녹음·STT·AI 분석 결과와 처리 상태, 수동 입력, 관리자 검토·최종 확정 정보 | [FR-02], [FR-03], [FR-04] / [UC-02] |
| BC | 기사 운영 — `driver` | 기사 개인정보·사업자 정보·검증 결과, 가입 신청·심사 이력과 승인·반려·재신청·탈퇴, 전문 분야·활동 지역·장비·환경 설정, 대기·휴식, 최신 위치 | [FR-06], [FR-10], [FR-13] 회원 운영 부분, [FR-14] / [UC-06], [UC-08] 회원 운영 부분, [UC-09], [UC-14], [UC-15] |
| BC | 의뢰 수행 관리 — `operations` | 고객 의뢰, 배차 후보 선별·요청·응답·전달 결과, 배정·해제·재배차·취소 후속 결정, 기사 작업 점유, 작업 진행·재방문, 보고서 원본·수정 이력 | [FR-05], [FR-07], [FR-08], [FR-09], [FR-11], [FR-12], [FR-16] 이력 보존 / [UC-03], [UC-05], [UC-07], [UC-10], [UC-11], [UC-12], [UC-13] |
| BC | 관리자·기사 대화 — `chat` | 기사별 지속적인 대화방, 메시지, 전송 처리 결과와 대화 접근 관계 | [FR-15] / [UC-16] |
| 조회 모듈 | 현황·이력·통계 — `dashboard` | 각 BC의 정보를 조합한 의뢰·작업 현황, 이력, 기간별 통계 조회 | [FR-09], [FR-16] / [UC-04] |

## 2. 모듈 배치

각 BC는 `com.allcheck`의 직접 하위 패키지에 Spring Modulith의 최상위 Application Module로 배치한다.
`operations`는 하나의 BC를 유지하며, 의뢰·배차·작업 책임을 `request`, `dispatch`, `work` 중첩 Application Module로 구분한다.

```text
com.allcheck
├── identity
├── reception
├── driver
├── operations
│   ├── request       # 의뢰 관리
│   ├── dispatch      # 배차·배정 관리
│   └── work          # 작업 수행·보고서 관리
├── chat
├── dashboard         # 통합 조회 모듈
└── global            # 공통 기술 코드
```

`dashboard`도 최상위 Application Module로 관리하되 독립 BC로 세지 않으며, 원본 업무 데이터의 변경 책임이나 자체 업무 Aggregate를 갖지 않는다.
`global`은 BC와 Application Module에서 제외한다.

## 3. Bounded Context별 주요 Aggregate

아래는 주요 Aggregate의 초기 모델링 기준이다.

| BC / 중첩 모듈 | Aggregate Root | 소유하는 모델과 경계 |
|---|---|---|
| `identity` | `Account` | 관리자 인증 계정과 기사 인증 주체·외부 로그인 연결. 기사 회원정보와 가입 심사는 `driver`에 둔다. |
| `reception` | `Reception` | 상담 자료·분석 결과와 관리자 최종 확정 정보를 구분하여 관리한다. |
| `driver` | `Driver` | 기사 회원정보·사업자 검증·신청 및 심사 이력·서비스 이용 자격·배차 희망 조건을 관리한다. |
| `driver` | `DriverLocation` | 기사별 최신 위치와 갱신 시각을 프로필·심사 정보와 분리하여 관리한다. |
| `operations.request` | `ServiceRequest` | 의뢰 생명주기, 확정 접수 스냅샷, 현재 배차·수행 식별자를 관리한다. |
| `operations.dispatch` | `DispatchRound` | 회차별 배차 시도와 기사별 요청·응답·전달 결과를 관리한다. |
| `operations.dispatch` | `Assignment` | 기사와 의뢰·작업의 배정 관계, 해제, 취소 요청·사유·관리자 후속 결정을 관리한다. |
| `operations.dispatch` | `DriverWorkload` | 기사별 미완료 작업 점유를 관리한다. 기사가 설정하는 대기·휴식과 구분한다. |
| `operations.work` | `WorkOrder` | 실제 수행 단위와 진행 상태·재방문·인계 연결을 관리한다. 배정 상태의 원본은 `Assignment`에 둔다. |
| `operations.work` | `WorkReport` | 최초 보고서·사진 원본, 관리자 수정 이력, 최신 유효 내용을 관리한다. |
| `chat` | `Conversation` | 개별 의뢰와 독립적으로 지속되는 기사별 대화 관계를 관리한다. |
| `chat` | `ChatMessage` | 대화방과 별도로 메시지 내용·발신자·전송 식별자·처리 결과를 관리한다. |

[FR-01]: ../../../docs/requirements/functional-requirements/fr-01-admin-login.md
[FR-02]: ../../../docs/requirements/functional-requirements/fr-02-call-recording-collection-and-stt-transcription.md
[FR-03]: ../../../docs/requirements/functional-requirements/fr-03-ai-consultation-analysis-and-structuring.md
[FR-04]: ../../../docs/requirements/functional-requirements/fr-04-ai-analysis-result-review-and-edit.md
[FR-05]: ../../../docs/requirements/functional-requirements/fr-05-request-registration-and-dispatch-candidate-selection.md
[FR-06]: ../../../docs/requirements/functional-requirements/fr-06-driver-gps-location-collection-and-management.md
[FR-07]: ../../../docs/requirements/functional-requirements/fr-07-dispatch-request-delivery-and-notification.md
[FR-08]: ../../../docs/requirements/functional-requirements/fr-08-dispatch-request-acceptance-and-rejection.md
[FR-09]: ../../../docs/requirements/functional-requirements/fr-09-request-and-work-status-management.md
[FR-10]: ../../../docs/requirements/functional-requirements/fr-10-driver-availability-status-management.md
[FR-11]: ../../../docs/requirements/functional-requirements/fr-11-work-report-registration-and-management.md
[FR-12]: ../../../docs/requirements/functional-requirements/fr-12-dispatch-cancellation-and-redispatch-management.md
[FR-13]: ../../../docs/requirements/functional-requirements/fr-13-driver-registration-and-authentication.md
[FR-14]: ../../../docs/requirements/functional-requirements/fr-14-driver-profile-and-dispatch-criteria-management.md
[FR-15]: ../../../docs/requirements/functional-requirements/fr-15-admin-driver-one-to-one-chat.md
[FR-16]: ../../../docs/requirements/functional-requirements/fr-16-work-history-and-statistics-management.md
[UC-01]: ../../../docs/requirements/use-cases/uc-01-admin-login.md
[UC-02]: ../../../docs/requirements/use-cases/uc-02-ai-reception-review.md
[UC-03]: ../../../docs/requirements/use-cases/uc-03-dispatch-request.md
[UC-04]: ../../../docs/requirements/use-cases/uc-04-dispatch-status-inquiry.md
[UC-05]: ../../../docs/requirements/use-cases/uc-05-work-report-review.md
[UC-06]: ../../../docs/requirements/use-cases/uc-06-driver-registration-approval.md
[UC-07]: ../../../docs/requirements/use-cases/uc-07-dispatch-cancellation-review.md
[UC-08]: ../../../docs/requirements/use-cases/uc-08-driver-registration.md
[UC-09]: ../../../docs/requirements/use-cases/uc-09-driver-availability-management.md
[UC-10]: ../../../docs/requirements/use-cases/uc-10-dispatch-request-response.md
[UC-11]: ../../../docs/requirements/use-cases/uc-11-work-status-update.md
[UC-12]: ../../../docs/requirements/use-cases/uc-12-work-report-submission.md
[UC-13]: ../../../docs/requirements/use-cases/uc-13-dispatch-cancellation-request.md
[UC-14]: ../../../docs/requirements/use-cases/uc-14-driver-profile-settings.md
[UC-15]: ../../../docs/requirements/use-cases/uc-15-driver-account-withdrawal.md
[UC-16]: ../../../docs/requirements/use-cases/uc-16-admin-driver-chat.md
