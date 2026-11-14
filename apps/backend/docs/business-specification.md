# Business Rules

Defines the project domain model, ownership, business rules, and project-specific integration policies supporting the driver and administrator apps. General engineering practices belong to convention documents; this document records the concrete project decisions.

## Document Responsibilities

| Document | Scope |
|---|---|
| [Functional Requirements](../../../docs/requirements/functional-requirements/) / [Use Cases](../../../docs/requirements/use-cases/) | Features, user flows, and exception flows |
| This document | Project domain model and data ownership; business states, access scopes, invariants, atomic changes, and project-specific integration policies |
| [Domain Design Conventions](domain-design.md) | General principles for defining context boundaries, aggregates, and data ownership |
| [Security Conventions](security-conventions.md) | General authentication, authorization, credential management, and sensitive data protection principles |
| [Persistence Conventions](persistence-conventions.md) | Transaction, idempotency, concurrency control, and event publication implementation |
| [Initial API Design](../../../docs/api/openapi.yaml) | Initial design reference for paths, request/response fields, status codes, and retry contracts |
| springdoc-generated OpenAPI / Swagger UI | Current contracts of implemented APIs, generated from backend code and documentation annotations |
| [Test Conventions](test-conventions.md) | Test design and verification methods |

Each rule uses its designated document above as the single source of truth. Other documents reference rules without redefining them.

<a id="project-domain-model"></a>

## Project Domain Model

ALLCHECK consists of five bounded contexts and one integrated query module. The project-specific ownership, module names, and initial aggregates are defined here; general design guidance follows [Domain Design Conventions](domain-design.md).

### Contexts and Data Ownership

| Type | Domain / Package | Owned Responsibilities and Data | Related Requirements |
|---|---|---|---|
| BC | Identity and Access Management — `identity` | Administrator accounts, credentials, roles, and MFA; driver Kakao authentication identifiers and account links; authentication sessions and tokens; administrator push-device registrations | [FR-01], [FR-13] authentication / [UC-01], [UC-08] authentication |
| BC | Consultation Intake — `reception` | Consultation cases; recordings, STT and AI analysis results and processing statuses; manual entry; administrator review and finalized intake information | [FR-02], [FR-03], [FR-04] / [UC-02] |
| BC | Driver Management — `driver` | Driver personal and business information and verification results; registration applications and review history; approval, rejection, reapplication, and withdrawal; specialties, service areas, equipment, preferences, standby/rest status, and latest location | [FR-06], [FR-10], [FR-13] membership management, [FR-14] / [UC-06], [UC-08] membership management, [UC-09], [UC-14], [UC-15] |
| BC | Service Request Fulfillment — `operations` | Customer service requests; dispatch candidate selection, offers, responses, and delivery results; work ownership, cancellation, redispatch, and administrator follow-up decisions; work progress and revisits; original reports and revision history | [FR-05], [FR-07], [FR-08], [FR-09], [FR-11], [FR-12], [FR-16] history retention / [UC-03], [UC-05], [UC-07], [UC-10], [UC-11], [UC-12], [UC-13] |
| BC | Administrator–Driver Chat — `chat` | Ongoing conversations for each driver, messages, transmission processing results, and conversation access relationships | [FR-15] / [UC-16] |
| Query module | Status, History, and Statistics — `dashboard` | Service request and work status, history, and statistics by period, combining information from each BC | [FR-09], [FR-16] / [UC-04] |

### Module Placement

Each BC is placed in a direct subpackage of `com.allcheck` as a top-level Spring Modulith Application Module.
`operations` remains one BC, with service request, dispatch, and work responsibilities organized into the nested Application Modules `request`, `dispatch`, and `work`.

```text
com.allcheck
├── identity
├── reception
├── driver
├── operations
│   ├── request       # Service request management
│   ├── dispatch      # Dispatch rounds and offers
│   └── work          # Work execution and reporting
├── chat
├── dashboard         # Integrated query module
└── global            # Shared technical code
```

`dashboard` is also managed as a top-level Application Module, but is not a separate BC and does not own source business data updates or have its own business Aggregates.
`global` is excluded from both BCs and Application Modules.

### Key Aggregates

The following is the initial design of the key Aggregates.

| BC / Nested Module | Aggregate Root | Owned Models and Boundaries |
|---|---|---|
| `identity` | `Account` | Administrator authentication accounts and driver authentication identities and external login links. Driver membership information and registration reviews belong to `driver`. |
| `reception` | `Reception` | Manages consultation materials and analysis results separately from intake information finalized by administrators. |
| `driver` | `Driver` | Manages driver membership information, business registration verification, application and review history, service eligibility, and dispatch preferences. |
| `driver` | `DriverLocation` | Manages each driver's latest location and update timestamp separately from profile and review information. |
| `operations.request` | `ServiceRequest` | Manages the service request lifecycle, the snapshot of finalized intake information, identifiers for the current dispatch and work, and administrator follow-up decision history linked to cancellation records or closed rounds. |
| `operations.dispatch` | `DispatchRound` | Manages dispatch attempts by round and offers, responses, and delivery results for each driver. |
| `operations.work` | `WorkOrder` | Owns the request-to-driver relationship through `requestId` and `driverId`, work progress, revisits, cancellation reason and timestamp, and previous-work links. Historical work records retain their original driver; redispatch creates a new work record. Driver workload is derived from unfinished, non-cancelled work rather than a separate aggregate. |
| `operations.work` | `WorkReport` | Manages the originally submitted report and photos, administrator revision history, and the latest effective content. |
| `chat` | `Conversation` | Manages each driver's ongoing conversation relationship independently of individual service requests. |
| `chat` | `ChatMessage` | Manages message content, senders, transmission identifiers, and processing results separately from the conversation. |

## Project Integration and Migration Policies

| Area | Project policy |
|---|---|
| Internal AI authentication | Backend→AI uses `X-Internal-Api-Key` separately from user JWTs. Missing/invalid keys return 401; an authenticated service without permission receives 403. Secrets are managed through Infisical. |
| Sensitive business data | Do not log internal keys, presigned URLs/signatures, FCM tokens, recording contents, or STT originals. Push carries resource identifiers; original contents require an authorized query. |
| Migration versions | Begin at `V1` and allocate the next unused positive integer (`V2`, `V3`, ...). Do not use timestamp/date versions or renumber an applied migration. |

## Rule Index

| Rule ID | Description |
|---|---|
| [BR-01](#br-01) | Business states and transitions |
| [BR-02](#br-02) | Business actors and access scopes |
| [BR-04](#br-04) | Intake confirmation and dispatch |
| [BR-06](#br-06) | Work cancellation and follow-up |
| [BR-07](#br-07) | Original reports and revision history |
| [BR-08](#br-08) | Status, history, and statistics queries |
| [BR-09](#br-09) | Recording ingestion, analysis attempts, S3 originals, and completion push |

Rule IDs support cross-document references. Do not reuse BR-03 and BR-05, which were removed through consolidation or migration.

<a id="br-01"></a>

## BR-01 Business States and Transitions

### Intake

| Field | States | Transitions and Conditions |
|---|---|---|
| `reception.reviewStatus` | `DRAFT` (review unconfirmed), `CONFIRMED` (review confirmed) | `DRAFT → CONFIRMED` after validating required intake data. Create service requests only from confirmed intake. |
| `recordingStatus`, `sttStatus`, `analysisStatus` | `PENDING` (queued), `PROCESSING` (running), `SUCCEEDED` (successful), `FAILED` (failed), `NOT_AVAILABLE` (no source material) | Manage each stage independently. The execution flow is `PENDING → PROCESSING → SUCCEEDED`; record processing failures as `FAILED`. Distinguish missing material from processing failure. |

Completed AI processing does not confirm administrator review. Administrators may confirm intake through manual entry even when recording, STT, or AI processing fails.

STT execution and durable transcript storage are separate stages. The transcript storage state uses the same processing states and is exposed separately in analysis queries. Analysis attempt states and stale-result handling follow BR-09.

### Service Request `request.status`

A service request represents the full lifecycle of a customer-requested service. Work states track detailed progress such as travel, arrival, on-site work, and revisits.

| State | Meaning |
|---|---|
| `OPEN` | Dispatch pending or in progress to confirm a driver |
| `ACCEPTED` | Driver confirmed; includes waiting to start, on-site work, and waiting for a revisit |
| `FOLLOW_UP_REQUIRED` | Administrator follow-up required after work cancellation or failure to secure a driver |
| `COMPLETED` | Service request completed when the current driver's first report submission is successfully stored |
| `CANCELLED` | Cancellation of the service request confirmed by an administrator |

| Event | Transition |
|---|---|
| Service request creation | Create as `OPEN` |
| Valid dispatch offer accepted and work created | `OPEN → ACCEPTED` |
| Assigned work cancelled | `ACCEPTED → FOLLOW_UP_REQUIRED` |
| Dispatch round closed without a driver | `OPEN → FOLLOW_UP_REQUIRED` |
| Administrator decides to redispatch and a new round is created | `FOLLOW_UP_REQUIRED → OPEN` |
| Administrator decides to cancel the service request | `FOLLOW_UP_REQUIRED → CANCELLED` |
| Current driver's first report submission succeeds | `ACCEPTED → COMPLETED` |

`COMPLETED` and `CANCELLED` are terminal states. A driver change or revisit alone does not complete a service request.

### Dispatch Round `round.status`

| State | Meaning |
|---|---|
| `SEARCHING` | Searching for candidate drivers |
| `OFFERING` | Delivering driver-specific dispatch offers and awaiting responses |
| `ASSIGNED` | Driver confirmed |
| `NO_CANDIDATES` | No suitable candidates found before the search termination conditions were met |
| `DELIVERY_FAILED` | Delivery failed for all offers |
| `NO_ACCEPT` | All target drivers rejected their offers |
| `ROUND_CLOSED` | Round closed without acceptance, for example due to expiry |

A round starts in `SEARCHING`. Finding candidates moves it to `OFFERING`; confirmed acceptance moves it to `ASSIGNED`. Record closure without a driver using the corresponding reason. Partial delivery failure alone does not close a round. Never reactivate a closed round; create a new round for each follow-up dispatch. Preserve a past round's `ASSIGNED` result even if its work is later cancelled.

### Driver-Specific Dispatch Offers

| Field | States | Conditions |
|---|---|---|
| `offer.status` | `PENDING`, `ACCEPTED`, `REJECTED`, `EXPIRED`, `RESPONSE_CLOSED` | Transition from pending to accepted, rejected, expired, or closed. Once another driver's acceptance is confirmed, close the remaining pending offers as `RESPONSE_CLOSED`. |
| `offer.deliveryStatus` | `PENDING`, `PROVIDER_ACCEPTED`, `FAILED` | Transition from pending delivery to provider acceptance or delivery failure. Manage independently of driver response state. |

Fix the target driver, dispatch round, and expiry time at offer creation. `PROVIDER_ACCEPTED` does not mean actual receipt or viewing. Record actual receipt and viewing in `receivedAt` and `viewedAt`; leave them null without confirmation signals.

### Work `work.status`

Each work record has a `requestId` and an assigned `driverId`. A service request may retain multiple historical work records, but at most one may be neither completed nor cancelled. Work represents the assignment relationship; do not create a separate `assignment` entity or assignment status.

| State | Meaning |
|---|---|
| `NOT_STARTED` | Driver confirmed; work has not started |
| `EN_ROUTE` | Travelling to the site |
| `ARRIVED` | Arrived at the site |
| `IN_PROGRESS` | On-site work in progress, including work awaiting report submission |
| `REVISIT_REQUIRED` | Additional visit by the same driver required |
| `WORK_DONE` | Work completed through successful first report submission |
| `CANCELLED` | Work ended through cancellation or a driver change |

- Normal progress: `NOT_STARTED → EN_ROUTE → ARRIVED → IN_PROGRESS`.
- Revisit: `IN_PROGRESS → REVISIT_REQUIRED → EN_ROUTE → ARRIVED → IN_PROGRESS`. The same driver keeps the same work ID.
- Report submission and completion: Only the current driver may submit the first report for work in `IN_PROGRESS`. Create only one first report per work record; successful submission commits work `WORK_DONE` and service request `COMPLETED` together. Opening the report screen, leaving it unsubmitted, or a save failure does not complete work.
- Work cancellation: Any unfinished state (`NOT_STARTED`, `EN_ROUTE`, `ARRIVED`, `IN_PROGRESS`, `REVISIT_REQUIRED`) may transition to `CANCELLED`. Preserve the reason and pre-cancellation state in history.
- `WORK_DONE` and `CANCELLED` are terminal states; prohibit new cancellations, first report submissions, and resumed work progress.
- When work transitions, cancellation, and first report submission compete, validate subsequent changes against the first committed work state and current assignment relationship.

When the driver changes, end the existing work as `CANCELLED` and create new work as `NOT_STARTED` upon acceptance of the follow-up dispatch. Keep the original work's `driverId` unchanged and link the new work to the previous work ID. Exclude cancelled work from completion counts.

### Driver Account `driver.accountStatus`

| State | Meaning | Transition |
|---|---|---|
| `PENDING_APPROVAL` | Registration awaiting approval | Administrator approval leads to `ACTIVATED`; rejection leads to `REJECTED` |
| `ACTIVATED` | Active account | `WITHDRAWN` when withdrawal conditions are met |
| `REJECTED` | Registration rejected | `PENDING_APPROVAL` upon resubmission after business registration verification |
| `WITHDRAWN` | Account withdrawn | Terminal state |

Register an initial application that passes business registration verification as `PENDING_APPROVAL`. Account-specific access scopes follow [BR-02](#br-02).

### Driver Availability and Workload

`driver.availability` records the driver's willingness to receive new dispatch offers.

| State | Meaning |
|---|---|
| `READY` | Available for new dispatch; on standby |
| `UNAVAILABLE` | Unavailable for new dispatch; resting |

Determine workload from the driver's `work` records. All states except `WORK_DONE` and `CANCELLED` count as occupied, including waiting for a revisit or report submission. Each driver may occupy at most one work record at a time. Do not add an `IN_WORK` state or an independent workload ledger.

Candidate selection and acceptance require `ACTIVATED`, `READY`, and no occupied work. Setting `READY` alone neither clears workload nor activates the account. After completion or cancellation, a driver may accept another offer if the remaining dispatch conditions are met. Availability is not a prerequisite for progressing existing work or submitting its report.

### Location Usability `driver.locationUsability`

| State | Criteria |
|---|---|
| `MISSING` | No location data |
| `STALE` | Configured validity period exceeded since the last update, or update time unavailable |
| `VALID` | Location and last update time exist and are within the validity period |

Calculate location usability at query or candidate-selection time. Use only `VALID` locations for distance-based candidate selection. Record location permission denial and collection errors as separate causes. Invalid location alone does not block existing work progress or report submission.

<a id="br-02"></a>

## BR-02 Business Actors and Access Scopes

Authentication and authorization mechanisms follow [Security Conventions](security-conventions.md). Apply BR-01 account and work state conditions to the operation-specific access scopes below.

### Driver Account Access

| Account State | Permitted Access |
|---|---|
| `PENDING_APPROVAL` | Own registration application and approval status, and logout. Restrict normal dispatch, work, and chat features. |
| `REJECTED` | Own rejection reason, reapplication, and logout. Restrict normal service access until activation. |
| `ACTIVATED` | Normal service use within the operation-specific access scopes and state conditions below |
| `WITHDRAWN` | No login or normal service access |

### Operation-Specific Access

| Actor / Resource | Permitted Access |
|---|---|
| Administrator operations | Allow intake confirmation, service request registration, follow-up decisions, report revisions, and operational status queries to authorized administrators. |
| Dispatch offers | Drivers may view and respond only to offers addressed to them. Viewing closed offers does not require new-acceptance eligibility; response conditions follow BR-04. |
| Work and first report submission | Allow work progress, cancellation, and first report submission only to the driver matching `driverId` on the service request's current work. Permitted states follow BR-01. |
| Handover records | The new driver may read previous work records linked to the same service request. Do not grant permission to modify previous work or reports. |
| Recordings and work photos | Viewing is limited by related intake/service request/work access permissions. When attaching files, verify upload ownership and the corresponding intake/work linkage. |
| STT originals and analysis attempts | Authorized administrators may read the related reception's original transcript and attempt history. AI services do not obtain application-user access or direct Backend database access. |
| Administrator push devices | Administrators may register and remove only their own installation bindings. Analysis notifications target the original recording uploader's currently registered devices, with current resource access revalidated before sending. |

Administrator access to retained records after driver withdrawal, and personal data retention and deletion, follow [UC-15](../../../docs/requirements/use-cases/uc-15-driver-account-withdrawal.md).

<a id="br-04"></a>

## BR-04 Intake Confirmation and Dispatch

### Intake Confirmation and Service Request Creation

- Separate original AI analysis from administrator-finalized intake data. Confirmation validates the latest saved intake data; the visit end time must be later than the start time.
- Preserve a snapshot of finalized intake data when creating a service request.
- Successful service request creation does not mean candidates were found or a driver received an offer.

### Candidate Search and Delivery

- Use push dispatch to send offers to selected drivers. Candidates must meet BR-01 account, availability, and workload conditions, plus specialty, service area, and valid location requirements.
- If the initial search area yields no candidates, expand it according to the configured dispatch policy. Keep `SEARCHING` while expanding and record search radius and iteration separately. Close as `NO_CANDIDATES` if no candidates are found before the termination conditions are met.
- Distinguish no candidates, total delivery failure, and partial delivery failure. With partial failure, keep waiting for responses to valid offers and record delivery results per driver.

### Acceptance and Rejection

- Acceptance requires the driver's own offer to be `PENDING` and unexpired, the service request to be `OPEN`, and the round to be `OFFERING`. Revalidate candidate eligibility at acceptance time.
- Commit work creation, service request `ACCEPTED`, round `ASSIGNED`, offer `ACCEPTED`, and closure of the remaining pending offers together; do not commit a partial result. Concurrent acceptance of the same request and one driver's concurrent acceptance of different requests must respect BR-01 per-request work and per-driver workload limits.
- Rejection closes only the driver's own valid pending offer. Do not require `READY` or an unoccupied workload merely to reject an offer.
- Atomicity and concurrency control implementation for concurrent acceptance follows [Persistence Conventions](persistence-conventions.md#concurrency-control).

<a id="br-06"></a>

## BR-06 Work Cancellation and Follow-Up

- Commit cancellation receipt, work `CANCELLED`, and service request `FOLLOW_UP_REQUIRED` together. Create only one cancellation record per work record and preserve its reason and timestamp.
- Administrator follow-up decides whether to redispatch or cancel the remaining customer service request. There is no administrator approval or rejection process for work cancellation.
- Confirm a follow-up decision only once per target cancellation or closed round. Commit either the redispatch decision, service request `OPEN`, and new round creation together, or the request-cancellation decision and service request `CANCELLED` together. The two decisions must not both succeed.
- Determine pending follow-up from `request.status=FOLLOW_UP_REQUIRED`; do not add a separate `cancellation.resolutionStatus`. Preserve cancellation records and administrator decision history independently of state fields. Link each decision to its target cancellation or closed round, decision content, administrator, decision time, and created round.
- If a redispatch round fails to secure a driver, return the service request to `FOLLOW_UP_REQUIRED`. Do not re-execute the existing decision; record a new follow-up decision against the latest closed round.
- Preserve round, work, driver, cancellation, and decision history from initial dispatch through redispatch, separately from current information. Never overwrite historical work or decisions with new dispatch data.

<a id="br-07"></a>

## BR-07 Original Reports and Revision History

- Preserve the first submitted original and before/after work photos; administrator revisions must not overwrite them.
- Append administrator revisions as separate history containing changed fields, before/after values, editor, and timestamp. Use the latest effective content without a separate approval or rejection process.
- Drivers and administrators use the same single work cost `totalCost` as an integer in KRW. Report revisions are not new first submissions or repeated completion of work or service requests.
- Access, retention, and deletion of originals, revision history, and attachments follow BR-02.
- Work photos are uploaded directly to private S3 with a phase of BEFORE, DURING, or AFTER. During photos are optional; before/after requirements remain. Validate completed upload, phase, ownership, work linkage, and duplicate IDs across the report's photo arrays at submission. Preserve all submitted original photos.

<a id="br-08"></a>

## BR-08 Status, History, and Statistics Queries

- Query features and user flows follow [FR-16](../../../docs/requirements/functional-requirements/fr-16-work-history-and-statistics-management.md) and [UC-04](../../../docs/requirements/use-cases/uc-04-dispatch-status-inquiry.md). Lists and statistics use the same query period; queries do not change source business states.
- Distinguish current service request, round, work, and driver information from historical records. Derive the need for administrator follow-up from `request.status=FOLLOW_UP_REQUIRED`.
- Distinguish an empty list, no statistics for the period, a missing selected service request, and query failure. Do not turn query failures into empty results. Response representations and status codes for implemented APIs are documented in the springdoc-generated OpenAPI.
- Report-based queries and statistics use the latest effective revisions, but count completion only once per service request. Do not count cancelled work or report revisions as additional completions.

<a id="br-09"></a>

## BR-09 Recording Ingestion, Analysis, and Completion Push

- Reserve at most one recording per reception in the MVP. Retrying an incomplete upload retains its file ID, immutable metadata, and object key. Completed recordings cannot be replaced or overwritten.
- The administrator app uploads to private S3 through a Presigned PUT URL. Backend validates the actual object before linking the recording and accepting its first analysis automatically. No separate app request starts the first analysis.
- Commit recording linkage, the first fixed recording-received/reference timestamp, the new reception version, analysis attempt, and durable execution intent together. Duplicate completion notifications reproduce the first acceptance and do not create another attempt, including after idempotency-key expiry.
- Return HTTP 202 to the app after durable acceptance. Backend executes the work in the background and calls AI STT and structuring APIs sequentially through synchronous RestClient requests. AI jobs, callbacks, and automatic AI/provider retries are excluded.
- Use one new analysisRequestId per execution flow. Send the same fixed receptionVersion and recording identity to both AI stages. Backend owns duplicate, version, stale-result, and one-time application checks.
- Backend saves the exact STT text as a new UTF-8 original in S3 and persists the file metadata and attempt linkage before requesting structuring. Separate STT execution failure, transcript storage failure, and structuring failure. Preserve previously valid originals/results on a new attempt's failure.
- S3 and database writes are not one atomic transaction. Use stable artifact IDs/object keys to identify partial writes and record unlinked objects for reconciliation under the retention policy. A storage failure must not trigger another AI call automatically.
- Manage stage progress independently of the reception's optimistic version. Increment the reception version on recording reservation, first completion, explicit reanalysis acceptance, and applied final results. Capture the post-acceptance version for both AI requests.
- Apply results only when the attempt is still current, its captured version and source linkage match, and review remains DRAFT. Administrator edits or confirmation invalidate in-flight results; mark the attempt DISCARDED and retain existing originals/finalData. Resolve the reception's progress display from retained valid material so invalidated work does not remain pending.
- Terminal attempt states are immutable. Timeout means FAILED with an unknown remote outcome; it does not prove AI stopped. Interrupted in-flight work is not automatically rerun. A durable pending intent may still receive its first execution after restart.
- Only an explicit administrator request can reanalyze a DRAFT reception. Reject reanalysis of CONFIRMED receptions and a second active attempt. FULL creates a new STT artifact; STRUCTURE_ONLY reuses a validated original belonging to the same reception and recording.
- Relative dates use the first verified recording-delivery timestamp, expressed in Asia/Seoul with its matching offset. Keep it across retries/reanalysis. Actual call time interpretation is a later verification task.
- Commit final success/failure and push publication intent together, then send FCM notifications to all currently registered devices of the original recording uploader. Check access again before sending; deduplicate the logical event and per-device delivery independently of AI execution.
- A current DISCARDED attempt publishes a failure notification explaining non-application. Skip unsent events superseded by a newer current attempt; the app always resolves the current server state.
- Keep notification failures separate from analysis outcomes. Provider acceptance, device receipt, and user viewing are distinct. Push contains event/reception/attempt IDs; the app retrieves authoritative current results and also refreshes on re-entry. Old or duplicate notifications cannot replace newer results.
- File size/type/length limits, URL lifetimes, HTTP deadlines, retention/deletion schedules, and push retransmission settings remain explicit TODOs. API details belong to [Backend OpenAPI](../../../docs/api/openapi.yaml) and [AI OpenAPI](../../ai/docs/openapi.yaml).

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
