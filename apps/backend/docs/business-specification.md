# Business Rules

Defines the current design baseline for backend business logic supporting the driver and administrator apps. Based on the initial design, this document is kept current with business behavior and design changes decided during development.

## Document Responsibilities

| Document | Scope |
|---|---|
| [Functional Requirements](../../../docs/requirements/functional-requirements/) / [Use Cases](../../../docs/requirements/use-cases/) | Features, user flows, and exception flows |
| This document | Business states, access scopes, invariants, and atomic business change boundaries |
| [Domain Design](domain-design.md) | Domain boundaries, aggregate structure, and data ownership |
| [Security Conventions](security-conventions.md) | Authentication and authorization mechanisms, and sensitive data protection principles |
| [Persistence Conventions](persistence-conventions.md) | Transaction, idempotency, concurrency control, and event publication implementation |
| [Initial API Design](../../../docs/api/openapi.yaml) | Initial design reference for paths, request/response fields, status codes, and retry contracts |
| springdoc-generated OpenAPI / Swagger UI | Current contracts of implemented APIs, generated from backend code and documentation annotations |
| [Test Conventions](test-conventions.md) | Test design and verification methods |

Each rule uses its designated document above as the single source of truth. Other documents reference rules without redefining them.

## Rule Index

| Rule ID | Description |
|---|---|
| [BR-01](#br-01) | Business states and transitions |
| [BR-02](#br-02) | Business actors and access scopes |
| [BR-04](#br-04) | Intake confirmation and dispatch |
| [BR-06](#br-06) | Work cancellation and follow-up |
| [BR-07](#br-07) | Original reports and revision history |
| [BR-08](#br-08) | Status, history, and statistics queries |

Rule IDs support cross-document references. Do not reuse BR-03 and BR-05, which were removed through consolidation or migration.

<a id="br-01"></a>

## BR-01 Business States and Transitions

### Intake

| Field | States | Transitions and Conditions |
|---|---|---|
| `reception.reviewStatus` | `DRAFT` (review unconfirmed), `CONFIRMED` (review confirmed) | `DRAFT → CONFIRMED` after validating required intake data. Create service requests only from confirmed intake. |
| `recordingStatus`, `sttStatus`, `analysisStatus` | `PENDING` (queued), `PROCESSING` (running), `SUCCEEDED` (successful), `FAILED` (failed), `NOT_AVAILABLE` (no source material) | Manage each stage independently. The execution flow is `PENDING → PROCESSING → SUCCEEDED`; record processing failures as `FAILED`. Distinguish missing material from processing failure. |

Completed AI processing does not confirm administrator review. Administrators may confirm intake through manual entry even when recording, STT, or AI processing fails.

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

<a id="br-08"></a>

## BR-08 Status, History, and Statistics Queries

- Query features and user flows follow [FR-16](../../../docs/requirements/functional-requirements/fr-16-work-history-and-statistics-management.md) and [UC-04](../../../docs/requirements/use-cases/uc-04-dispatch-status-inquiry.md). Lists and statistics use the same query period; queries do not change source business states.
- Distinguish current service request, round, work, and driver information from historical records. Derive the need for administrator follow-up from `request.status=FOLLOW_UP_REQUIRED`.
- Distinguish an empty list, no statistics for the period, a missing selected service request, and query failure. Do not turn query failures into empty results. Response representations and status codes for implemented APIs are documented in the springdoc-generated OpenAPI.
- Report-based queries and statistics use the latest effective revisions, but count completion only once per service request. Do not count cancelled work or report revisions as additional completions.
