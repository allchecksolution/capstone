# API 문서 안내

대상은 **기사 앱 ver2와 관리자 앱**이다. 접수·의뢰 등록·배차·작업·취소·재배차를 우선 명세한다. 현재 상태는 **설계 초안**이며 API 구현 완료나 전체 앱 API 명세 완료를 의미하지 않는다.

## 문서 구성과 읽는 순서

| 문서 | 목적 | 관리할 내용 |
|---|---|---|
| [화면별 API 대응표](screen-api-mapping.md) | 화면에서 필요한 API 찾기 | 앱별 화면·표시 데이터·입력값·버튼·FR/UC·API 연결·계약 작성 상태 |
| [업무 규칙](business-rules.md) | 허용 조건과 처리 결과 합의 | 개체별 상태·권한·중복·동시성·원본/이력 보존·미결정 사항·검증 시나리오 |
| [API 상세 명세](openapi.yaml) | 프론트엔드·백엔드 요청/응답 계약 | method·경로·입력 스키마·응답·오류·인증·예시 |

API 후보 중 하나만 고르는 방식이 아니다. 화면에서 실제 제공할 기능을 정하고 필요한 API를 확정한다. 여러 화면이 사용하는 같은 API는 OpenAPI에 한 번만 정의한다. 완료·오류 화면은 기존 명령의 응답을 사용하므로 화면 수만큼 API를 만들 필요는 없다.

## 현재 작성 범위

- 화면별 대응표: 기사 앱 ver2와 관리자 앱의 대상 업무 화면. 조회·파일·위치·통계 등 상세 계약이 없는 API는 **후속 명세**, 선택 기능은 **정책 결정 후**로 표시했다.
- OpenAPI: 대표 작업 **10개**, 스키마 **29개**의 초안. 전체 앱의 인증·회원가입·프로필·채팅 등은 후속 범위다.
- 업무 규칙: FR/UC에서 확정된 조건과 상태명·잠금 방식·비용 모델 등 설계 제안을 구분했다. 미결정 항목은 팀 합의가 필요하다.
- UC-04 개정 반영: 의뢰 현황·상세·기간 통계의 조회 조건, 빈 결과/조회 오류, 현재 상태와 과거 이력 구분은 BR-08을 따른다. 목록·통계·상세·회차 조회 계약은 후속 명세이며, 같은 조회 기간을 반영한다. 통계 지표·집계 기준·목록 필터의 통계 연동과 선택 기능인 내보내기는 미확정이다.
- 주요 대조 결과: 기사 구버전 취소 화면은 즉시 해제 요구사항과 충돌한다. 설계 기준은 ver2다. 관리자 앱 개정 화면의 보고서 원본·수정 이력과 배차·재배차 이력은 요구사항과 일치한다. 상세 근거는 [화면별 대응표](screen-api-mapping.md)에 있다.

## 대표 API 색인

아래 경로 앞에는 `/api/v1`을 붙인다. 모든 URI는 제안이다.

| operationId | API | 관련 업무 규칙 |
|---|---|---|
| `confirmReception` | `POST /receptions/{receptionId}/confirmation` | BR-01·02·03·04 |
| `createServiceRequest` | `POST /service-requests` | BR-02·03·04 |
| `respondToDispatchOffer` | `POST /dispatch-offers/{offerId}/response` | BR-02·03·05 |
| `transitionWork` | `POST /assignments/{assignmentId}/work-transitions` | BR-01·02·03·06 |
| `cancelAssignment` | `POST /assignments/{assignmentId}/cancellations` | BR-01·02·03·06 |
| `resolveCancellation` | `POST /dispatch-cancellations/{cancellationId}/resolution` | BR-02·03·06 |
| `submitWorkReport` | `POST /assignments/{assignmentId}/report` | BR-01·02·03·07 |
| `reviseWorkReport` | `POST /reports/{reportId}/revisions` | BR-02·03·07 |
| `getWorkReport` | `GET /reports/{reportId}` | BR-02·07 |
| `getDispatchHistory` | `GET /service-requests/{requestId}/dispatch-history` | BR-02·06·08 |

## 용어·경로 표기

| 개념 | 초안 경로/명칭 | 의미 |
|---|---|---|
| 상담 접수 | `receptions` | 녹음·STT·AI 결과와 관리자가 검토/확정한 정보 |
| 고객 의뢰 | `service-requests` | 확정 접수를 바탕으로 등록한 배차·작업 대상 |
| 기사별 배차 요청 | `dispatch-offers` | 특정 기사에게 전달한 수락·거절 대상 |
| 확정 배정 | `assignments` | 수락 결과로 확정된 기사와 의뢰의 연결 |

`receptions`는 접수와 의뢰를 구분하려고 제안한 이름이며 표준으로 강제되는 표현이 아니다. `intakes` 등으로 변경할 수 있지만 팀에서 하나를 정하면 경로·필드·대응표를 함께 변경한다. 이번 문서 재구성에서는 기존 명칭을 유지한다.

## 작성·변경 방법

1. 화면별 표시 데이터·입력·버튼·오류/빈 결과를 FR/UC 및 API와 연결한다.
2. 관련 BR 규칙에서 상태 전이·권한·중복/경합 처리·불변 조건을 확인한다.
3. OpenAPI의 API별 요청·응답·실패 예시를 작성하고 대응표의 계약 상태와 operationId를 갱신한다.
4. API 변경 시 대응표와 관련 업무 규칙도 같은 변경에서 검토한다. 규칙 변경은 요구사항 변경과 단순 설계 선택을 구분하고 결정 근거를 남긴다.

상세 요청·응답 예시는 OpenAPI를 기준으로 관리한다. Markdown에 동일 JSON 계약을 별도로 복제하지 않는다. `x-requirements`, `x-figma-node-ids`, `x-roles`, `x-business-rules`로 추적한다. 확장 필드 자체가 서버 권한/규칙을 집행하지는 않는다.

OpenAPI는 호환성을 고려해 3.1.1로 작성한 초안이다. [공식 사양](https://spec.openapis.org/oas/v3.1.1.html)을 참조하며 예시 서버 `api.example.invalid`는 실제 주소가 아니다.

## 검증 상태

UC-04 개정 반영 후 YAML 파싱, 내부 참조 100개, 대표 작업 10개의 경로 변수·operationId 유일성, 업무 규칙 연결 33개, 요청·응답 예시 75개의 JSON Schema 적합성, 로컬 문서 링크·앵커 30개를 확인했다.

실제 백엔드 동작·동시성 테스트와 OpenAPI 전체 구조 전용 검증은 아직 수행하지 않았다. 구현 검증 항목은 [업무 규칙](business-rules.md)의 핵심 검증 시나리오를 따른다.

## 근거 문서

- [기능 요구사항](../requirements/functional-requirements/)
- [유스케이스](../requirements/use-cases/)
- [비기능 요구사항](../requirements/non-functional-requirements/non-functional-requirements.md)
- [Figma](https://www.figma.com/design/PfyqT7huiwtz63yKpfJzmA/)
