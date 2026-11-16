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
| BC | Identity and Access Management — `identity` | Administrator accounts, credentials, and roles; driver Kakao authentication identifiers and account links; authentication sessions and tokens; administrator push-device registrations | [FR-01], [FR-13] authentication / [UC-01], [UC-08] authentication |
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
| `identity` | `AuthSession` | One independent login session, its current Refresh Token hash, absolute expiry, refresh time, and revocation. References `Account` by ID; sessions are not an Account-owned collection. |
| `reception` | `Reception` | Manages consultation materials and analysis results separately from intake information finalized by administrators. |
| `driver` | `Driver` | Manages driver membership information, business registration verification, application and review history, service eligibility, and dispatch preferences. |
| `driver` | `DriverLocation` | Manages each driver's latest location and update timestamp separately from profile and review information. |
| `operations.request` | `ServiceRequest` | Manages the service request lifecycle, the snapshot of finalized intake information, identifiers for the current dispatch and work, and administrator follow-up decision history linked to cancellation records or closed rounds. |
| `operations.dispatch` | `DispatchRound` | Manages dispatch attempts by round and offers, responses, and delivery results for each driver. |
| `operations.work` | `WorkOrder` | Owns the request-to-driver relationship through `requestId` and `driverId`, work progress, revisits, cancellation reason and timestamp, and previous-work links. Historical work records retain their original driver; redispatch creates a new work record. Driver workload is derived from unfinished, non-cancelled work rather than a separate aggregate. |
| `operations.work` | `WorkReport` | Manages the originally submitted report and photos, administrator revision history, and the latest effective content. |
| `chat` | `Conversation` | Manages each driver's ongoing conversation relationship independently of individual service requests. |
| `chat` | `ChatMessage` | Manages message content, senders, transmission identifiers, and processing results separately from the conversation. |

### Identity Persistence

`Account` owns either an embedded Kakao `SocialLogin` or an embedded `AdminCredential` including the administrator role. Store them in `identity_accounts`, with a database check ensuring the credential shape matches `userType`. Keep the social-provider/member-number pair and administrator login ID unique. Account deactivation retains its credentials and uniqueness constraints, preventing a withdrawn identity from being recreated as a new account.

`AuthSession` is stored in `identity_auth_sessions` with an account-ID foreign key, a unique SHA-256 Refresh Token digest, and an account-ID index. Both aggregates use optimistic versions. A stale session update must not overwrite a committed rotation or revocation; application services translate conflicts according to the refresh API contract. Neither aggregate stores a raw Refresh Token or a Kakao token. Authentication state and session state must be coordinated by application-service transactions; disabling an Account object alone does not persist session revocations.

## Project Integration and Migration Policies

| Area | Project policy |
|---|---|
| Internal AI authentication | Backend→AI uses `X-Internal-Api-Key` separately from user JWTs. Missing/invalid keys return 401; an authenticated service without permission receives 403. Secrets are managed through Infisical. |
| Kakao authentication | Validate the SDK Access Token against Kakao and the configured numeric application ID before resolving an Account. The client returns only a verified SocialLogin and performs no database changes. Provider failure classification and configuration follow the [Identity API Contract](../../../docs/api/identity-contract.md#카카오-검증-클라이언트). |
| Sensitive business data | Do not log internal keys, presigned URLs/signatures, FCM tokens, recording contents, or STT originals. Push carries resource identifiers; original contents require an authorized query. |
| Migration versions | Begin at `V1` and allocate the next unused positive integer (`V2`, `V3`, ...). Do not use timestamp/date versions or renumber an applied migration. |
| Internal and external reception IDs | Generated primary keys and their internal references use Long/BIGINT with IDENTITY by default, following Persistence Conventions. At API/AI boundaries, format the numeric IDs as rec_{id}, file_rec_{id}, anl_{id}, and file_stt_{id}. AI treats these strings as opaque and returns them unchanged. Keep authenticated administrator resolution separate from external ID parsing; a parsed ID does not establish ownership. |
| Existing string-key schema | V7 changes the unused V1-V6 key columns and their foreign-key references together, and adds a numeric device PK while preserving installation_id as a unique business identifier. It locks and checks all affected tables before changing types. If any existing rows are found, stop without deleting or inventing an ID mapping; a populated environment needs a separately reviewed migration. V1-V6 remain unchanged. |

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
| `reception.reviewStatus` | `DRAFT` (review unconfirmed), `CONFIRMED` (review confirmed) | `DRAFT → CONFIRMED` after processing has ended and required intake data is valid. Before service request creation, an administrator may revoke confirmation (`CONFIRMED → DRAFT`), edit, and confirm again. Create service requests only from confirmed intake. After service request creation, prohibit reception edits and confirmation revocation. |
| `recordingStatus`, `sttStatus`, `analysisStatus` | `PENDING` (queued), `PROCESSING` (running), `SUCCEEDED` (successful), `FAILED` (failed), `NOT_AVAILABLE` (no source material) | Manage each stage independently. The execution flow is `PENDING → PROCESSING → SUCCEEDED`; record processing failures as `FAILED`. Distinguish missing material from processing failure. |

Completed AI processing does not confirm administrator review. Administrators may confirm intake through manual entry even when recording, STT, or AI processing fails.
The AI-result review screen is read-only while analysis is pending/processing. A terminal processing failure enables the agreed manual fallback; it does not authorize early confirmation while analysis remains active.

STT execution and durable transcript storage in the database are separate stages. The transcript storage state uses the same processing states and is exposed separately in analysis queries. Analysis attempt states and stale-result handling follow BR-09.

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

### Administrator Authentication

Administrators authenticate using pre-provisioned IDs and passwords. Do not provide public administrator registration, MFA, or TOTP enrollment/verification. Successful ID/password authentication issues application credentials; operation-specific authorization remains required. Administrator `MASTER`/`SUB` role distinctions are retained, but their detailed permission differences remain undecided.

### Authentication Sessions

The following initial policies were confirmed on 2026-11-16. API paths, payloads, and frontend migration are defined in the [Identity API Contract](../../../docs/api/identity-contract.md).

| Concern | Policy |
|---|---|
| Access Token | JWT; default lifetime 15 minutes, configurable |
| Refresh Token | Cryptographically random; persist only its hash |
| Session lifetime | Default 14 days from login; refresh never extends the session's absolute expiry |
| Multiple devices | Allow independent sessions; a new login does not revoke existing sessions |
| Refresh | Issue a new Access/Refresh Token pair and invalidate the consumed Refresh Token atomically |
| Concurrent refresh and token replay | Only one refresh using the same token succeeds; reject other uses with 401. Replay alone does not revoke the replacement token or other sessions |
| Logout | Revoke only the session identified by the presented valid Refresh Token; immediately reject that session's previously issued Access Tokens |
| Logout with an invalid, expired, or revoked token | Return 204 without affecting other sessions |
| Account deactivation and driver withdrawal | Revoke all account sessions and prevent both existing Access Token use and refresh |

Authenticate every protected request against the current account and session state stored on the server. Do not rely solely on token claims or process-local memory. Identity owns authentication state; driver owns membership state. Driver withdrawal disables authentication and revokes all sessions through identity's public module API. Identity does not depend on driver internals; the app queries driver registration state separately after login. An authenticated identity does not itself grant access to driver business features.

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
| Reception administration | In the MVP, an administrator may list, read, edit, confirm, revoke confirmation, and create a service request only from receptions they created. Resolve the reception owner from the authenticated administrator at creation, and enforce ownership on every operation and file/material access, including duplicate-request responses. Do not grant access to other administrators' receptions. |
| Reception existence disclosure | When resolving a reception for an authenticated administrator, a nonexistent reception and a reception owned by another administrator both return 404 with the same RECEPTION_NOT_FOUND code/message. Perform the owner-scoped lookup before resource-specific state, version, material access, or business validation. Authentication and role failures retain their normal 401/403 semantics. |
| Dispatch offers | Drivers may view and respond only to offers addressed to them. Viewing closed offers does not require new-acceptance eligibility; response conditions follow BR-04. |
| Work and first report submission | Allow work progress, cancellation, and first report submission only to the driver matching `driverId` on the service request's current work. Permitted states follow BR-01. |
| Customer contact phone for drivers | Disclose the confirmed customer contact phone only after acceptance to the driver authorized as responsible for that service request. Candidate offer/list responses omit the phone. A previous assignment or historical read permission alone does not grant phone access; withhold it when the caller is not the currently authorized responsible driver. |
| Handover records | The new driver may read previous work records linked to the same service request. Do not grant permission to modify previous work or reports. |
| Recordings and work photos | Consultation recording access is limited to the administrator owning the reception. Work photos follow the related service request/work access permissions. When attaching files, verify upload ownership and the corresponding intake/work linkage. |
| STT originals and analysis attempts | Only the administrator owning the related reception may read its original transcript and analysis records. AI services do not obtain application-user access or direct Backend database access. |
| Administrator push devices | Administrators may register and remove only their own installation bindings. Analysis notifications target the original recording uploader's currently registered devices, with current resource access revalidated before sending. |

Administrator access to retained records after driver withdrawal, and personal data retention and deletion, follow [UC-15](../../../docs/requirements/use-cases/uc-15-driver-account-withdrawal.md).

<a id="br-04"></a>

## BR-04 Intake Confirmation and Dispatch

### Intake Confirmation and Service Request Creation

- Separate original AI analysis from administrator-finalized intake data. The administrator edits locally and submits the complete final intake data with the review-confirmation command. In the MVP, there is no separate administrator draft-save operation. Validate the submitted data and atomically save it, set CONFIRMED, record confirmedAt, and increment the reception version once. Validation or version/state failure must leave the prior data and review state unchanged.
- For an AI-assisted reception, allow review editing only after analysis has ended. While its analysis attempt is PENDING/PROCESSING, the result screen is read-only and Backend rejects confirmation with REVIEW_NOT_READY, even if the submitted fields are complete. After success, review the populated values; after a terminal collection/STT/storage/structuring failure, use manual completion. Keep the existing manual-entry path for cases where no AI pipeline can be started. Do not model a workflow where an administrator confirms before an active AI analysis ends.
- A reserved recording waiting for verified upload and initial analysis acceptance is not a manual-entry reception. Reject confirmation with REVIEW_NOT_READY while that recording is reserved but no analysis attempt has been accepted. A missing attempt status must not bypass the read-only workflow.
- The review application service resolves the current attempt from persisted reception linkage and uses its overall status, not client input or the structuring-stage status. Missing or mismatched current-attempt records are internal consistency failures and must not enable manual confirmation. Save review changes in a single transaction; translate a conflicting JPA write to VERSION_CONFLICT without automatic retries.
- Require work type, symptoms, address, precise desired visit start and end timestamps, and a customer contact phone number for confirmation. Customer name is not a required confirmation field. Missing contact information or either visit boundary blocks confirmation even when AI analysis succeeds.
- Work types are BLOCKAGE (막힘), EXTERIOR_LEAK (외벽 누수), INTERIOR_LEAK (내벽 누수), BOILER (보일러), and WATERPROOFING (방수). Use the same five values for AI extraction, administrator review, service request snapshots/filters, and driver specialties. Existing identifiers remain unchanged.
- Represent the visit schedule only through `desiredVisitFrom` and `desiredVisitTo`; do not add a free-text visit field or a derived visit-preference text to the MVP contract. If AI cannot supply exact timestamps, the administrator fills the missing timestamps before confirmation. Validate the submitted final range and require the end to be later than the start. Additional customer instructions remain in optional `notes` and cannot substitute for required timestamps.
- At review confirmation, require the visit end to be strictly later than Backend's current time. The start may be in the past if the requested window has not ended. An end equal to or earlier than the current time fails validation without saving or confirming. Use server time rather than device time. This is a confirmation-time validation and does not invalidate historical detail reads after the visit window passes.
- Do not repeat the future-end check at later service request creation. A validly confirmed reception may be registered after its visit window has elapsed, using the confirmed snapshot unchanged. Still validate ownership, CONFIRMED state, version, and registration consistency. Do not revoke confirmation or require new review solely because time passed before registration. If the administrator voluntarily reopens and confirms again, apply the confirmation-time check at that new confirmation.
- The AI extraction still excludes the contact phone. The app sends the unchanged original recording filename in `originalFilename`; Backend extracts a review phone from `rec_<label>_<phone1>_<phone2>@<date>_<time>.wav` when both syntactically valid phone values match after removing hyphens. Preserve this source separately from caller metadata and AI JSON, and prefill missing `finalData.contactPhone`. Unknown patterns or different numbers remain empty for manual confirmation; do not guess which position denotes the customer. An administrator-reviewed phone always takes precedence. Do not derive referenceTime or callStartedAt from the filename.
- On the first analysis result's valid application, automatically populate the editable intake draft from the AI extraction when no administrator-saved intake data exists. Opening the detail screen displays these saved initial values; reading the screen does not perform a mutation or confirm review. Preserve the separate AI original and do not overwrite administrator-saved data. The MVP has no reanalysis flow; corrections use administrator editing or manual entry.
- Applying the first valid result stores the reception's AI original and increments its business version once, while review remains DRAFT. With no independent draft-save operation, existing nonempty finalData is preserved as previously saved intake data; only an empty draft is populated from AI. Do not derive the confirmed customer contact from callerPhone or the AI payload. An empty/partial AI result is still incomplete intake, even when missingFields is empty.
- For administrator intake edits in the MVP, retain the immutable original AI extraction and only the latest effective editable data. Do not append per-save before/after revision history. This policy does not remove the analysis execution records or reliable publication records required by BR-09.
- Do not persist administrator input that clears or omits a required field. The confirmation request carries the complete final field set rather than partial changes; absent optional notes mean there are no effective notes. Optional notes can be cleared, while all required fields must remain valid. The initial AI-populated DRAFT may still be incomplete until the administrator submits valid final data.
- Editing a confirmed reception requires revoking confirmation first and confirming again after changes. Permit revocation only before a service request has been created; intake must be confirmed again before service request creation. Resolve concurrent revocation and service request creation against the same authoritative reception state so both cannot succeed against the previous confirmation. After service request creation, reception data remains immutable.
- Preserve a snapshot of finalized intake data when creating a service request.
- `ReceptionOperations` is the public module boundary for the confirmed snapshot and request linkage. Registration must join the request-creation transaction (MANDATORY propagation), so a failed request insert or conflicting reopen cannot leave only one side committed. The snapshot exposes reviewed fields, including notes, without granting drivers access to reception materials.
- Provide optional `notes` for customer requests and additional information, such as contact-before-arrival and parking instructions. Preserve the AI original separately and let the administrator review, add, and correct the effective notes. Include the reviewed notes and exact visit schedule in the service request snapshot. Drivers may read the notes from their own dispatch offer before acceptance and from their authorized work after acceptance, following BR-02. Source reception ownership does not grant drivers access to reception APIs.
- Treat `notes` as a normal administrator-review field: expose original extracted notes under `aiOriginal` and the latest editable notes under `finalData`. Populate initial notes under the same first-result rules as the other fields, allow manual additions when AI omitted notes, and save changes without overwriting `aiOriginal.notes`.
- Administrator detail reads must distinguish the stored STT original, original recording access, immutable original AI-extracted JSON, and the effective administrator-editable data. Editing must never rewrite any of the three original materials. Provide recording access through the existing authorized private-file access contract; do not expose a public bucket URL. Return missing materials as absent, and retain stage-specific progress/failure information. Original transcript read failures follow the API failure contract rather than becoming empty text.
- The detail exposes the reserved `recordingFileId` so an interrupted upload can obtain a renewed URL and complete verification. Only `recordingStatus=SUCCEEDED` makes an original available for playback. The administrator app requests a short-lived Presigned GET URL from the recording access API, which returns `url`, `expiresAt`, `method`, and `contentType`. It then reads the audio from that URL and requests a new URL after expiry. S3 bucket/key lookup belongs to Backend; a separate object-key field is not needed in the app contract. Check reception ownership and recording linkage on every URL issuance.
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
- Reserve only on an owned, unregistered DRAFT reception with the matching expected version. Persist the reserved file ID, the incremented reception version, and immutable recording metadata in the same transaction. A reserved ID is visible before completion but does not mean the original has been verified or analysis has started. URL renewal reuses this reservation without incrementing the reception version.
- The administrator app uploads to private S3 through a Presigned PUT URL. Backend validates the actual object before treating the reserved recording as a verified original and accepting its first analysis automatically. No separate app request starts the first analysis.
- Commit recording linkage, the first fixed recording-received/reference timestamp, the new reception version, analysis attempt, and durable execution intent together. Duplicate completion notifications reproduce the first acceptance and do not create another attempt, including after idempotency-key expiry.
- Return HTTP 202 to the app after durable acceptance. Backend executes the work in the background and calls AI STT and structuring APIs sequentially through synchronous RestClient requests. AI jobs, callbacks, and automatic AI/provider retries are excluded.
- Use one new analysisRequestId per execution flow. Send the same fixed receptionVersion and recording identity to both AI stages. Backend owns duplicate, version, stale-result, and one-time application checks.
- The AI transcribe API returns the exact STT text in its response `transcript`; the analyze API receives that text in its request `transcript`. For a nonblank result, Backend preserves the unchanged original string in the database and commits its analysis linkage and storage status before calling structuring. Do not trim, summarize, replace, or normalize line breaks in the stored original. Separate STT execution failure, database transcript storage failure, and structuring failure. A later stage's failure must not remove an original already stored successfully.
- Accept an STT result only from the current claim owner while the attempt is PROCESSING at STT. A valid nonblank response moves STT to SUCCEEDED and transcript storage to PROCESSING at TRANSCRIPT_STORAGE. Commit this checkpoint before the separate original-storage transaction so a storage rollback does not erase the fact that transcription succeeded. The checkpoint does not store or normalize the transcript text; create the canonical original from the same unchanged response string. Duplicate, wrong-owner, wrong-stage, and terminal responses must not advance or rewrite the attempt.
- Commit the canonical transcript and its attempt linkage/storage SUCCEEDED status together, verifying its reception, recording, and producing attempt identities. After this commit, STRUCTURE/PENDING is ready for a separate structuring start. If original storage fails, roll back its insert/linkage, then record FAILED at TRANSCRIPT_STORAGE with sttStatus=SUCCEEDED, transcriptStorageStatus=FAILED, analysisStatus=NOT_AVAILABLE, and TRANSCRIPT_STORAGE_FAILED in a separate transaction together with the failure-notification intent. Do not call AI again to recover the original automatically.
- With IDENTITY-generated transcript IDs, check storage eligibility before inserting, then attach the generated ID and commit both changes atomically. If the attachment is rejected or the transaction loses a concurrent write, roll back the inserted original as well. Any terminal failure that must remain after that rollback is recorded in a separate transaction. Never commit an unaccepted transcript row merely to obtain an ID.
- Start structuring only for the current claim owner after successful STT and transcript storage. Commit analysisStatus=PROCESSING before the external call. A valid response stores the attempt's original JSON and unchanged validated missingFields, marks the structuring stage SUCCEEDED, and moves to RESULT_APPLICATION while overall status remains PROCESSING. Stage success alone does not enable review or mean reception application succeeded. Keep AI missing-field cross-validation separate from Backend's confirmation validation; do not reconstruct or silently correct the provider's list.
- Atomically save the reception's valid first-result application, the attempt's SUCCEEDED/COMPLETED/resultApplied=true state, and the success-notification intent. Match reception ownership, captured version, current attempt, recording, and canonical transcript linkage, and require DRAFT/unregistered/no previously applied AI original. A mismatch closes only the attempt as DISCARDED with STALE_ANALYSIS_RESULT and preserves all reception values. Structuring failure or AI HTTP timeout closes the active STRUCTURE stage as FAILED with AI_STRUCTURING_FAILED or AI_TIMEOUT, preserving the stored transcript and prior successful stages. Failure/discard publication still follows current-attempt/access checks and the normal notification policy.
- An empty or whitespace-only STT result is a failed transcription with `STT_NO_CONTENT`. Close the active attempt as FAILED at the STT stage with sttStatus=FAILED, transcriptStorageStatus=NOT_AVAILABLE, and analysisStatus=NOT_AVAILABLE. Do not create a canonical transcript artifact or call structuring for this result, and do not retry AI automatically. Keep the verified recording available, publish the failure under the normal notification policy, and allow manual intake completion. The blank check does not change the content of nonblank originals.
- The database is the canonical store for STT originals, original AI JSON, and the latest effective administrator data. Keep recordings in private S3 and do not create a second canonical STT copy in S3. The existing AI wire name `transcriptFileId` may continue identifying the persisted original as an opaque ID; it does not require an S3 object.
- S3 recording writes and database writes are not one atomic transaction. Use stable recording IDs/object keys to identify partial writes and record unlinked objects for reconciliation under the retention policy. Transcript content, its analysis linkage, and its stored status belong to one database transaction; call the external AI service after that commit. A storage failure must not trigger another AI call automatically.
- Manage stage progress independently of the reception's optimistic version. Increment the reception version on recording reservation, first completion, and applied final results. Capture the post-acceptance version for both AI requests.
- Apply results only when the attempt is still current, its captured version and source linkage match, and review remains DRAFT. Keep stale-result and terminal-state protection as server safeguards; the normal MVP flow does not permit administrator confirmation while analysis is active. Local screen edits do not mutate server state. Mark invalidated results DISCARDED and retain existing originals/finalData. Resolve the reception's progress display from retained valid material so invalidated work does not remain pending.
- Terminal attempt states are immutable. Timeout means FAILED with an unknown remote outcome; it does not prove AI stopped. Interrupted in-flight work is not automatically rerun. A durable pending intent may still receive its first execution after restart.
- Claim only an initial FULL attempt in PENDING. Commit the transition to PROCESSING, the STT stage/in-progress state, worker identity, start time, and claim deadline before external AI I/O. An in-memory successful claim alone is insufficient: only the transaction that commits may call AI. Use the separate JPA persistence version for conflicting claims without changing the captured receptionVersion. A PROCESSING or terminal attempt is not claimable again, even after its deadline. Deadline values and worker orchestration remain separate configuration/integration work; claiming does not imply AI has already completed a download or transcription.
- The claim deadline is the fixed whole-analysis execution cutoff, separate from each HTTP call's timeout. At or after that cutoff, close an eligible PROCESSING attempt as FAILED with ANALYSIS_TIMEOUT even if the late response arrives before a timeout monitor has persisted failure. Preserve earlier stage successes and any stored transcript; fail the unfinished stage and mark unavailable downstream stages appropriately. Terminal attempts are never rewritten by later responses or expiry checks. This does not cancel remote execution or permit an automatic AI retry. Choose STT/structuring HTTP timeouts and the whole-flow budget after representative latency measurements; no production duration is decided yet.
- In the MVP, perform only the initial automatically accepted analysis. Do not implement a public reanalysis endpoint, another FULL execution, or STRUCTURE_ONLY execution. After failed, discarded, or inaccurate analysis, use administrator editing/manual entry and the same review/confirmation flow. Retrying an incomplete upload and replaying upload completion are still supported and must not create a second analysis. Existing draft contracts and V1 mode columns for reanalysis are reserved references outside the MVP.
- Relative dates use the first verified recording-delivery timestamp, expressed in Asia/Seoul with its matching offset. Keep it across upload retries and duplicate completion notifications. Actual call time interpretation is a later verification task.
- Commit final success/failure and push publication intent together, then send FCM notifications to all currently registered devices of the original recording uploader. Check access again before sending; deduplicate the logical event and per-device delivery independently of AI execution.
- The analysis transaction appends one durable completion event per terminal attempt. Expand it atomically into one delivery per currently registered installation, then claim each delivery in a committed transaction before remote send. Recheck current reception ownership/current attempt and device binding before each attempt and again after OAuth preparation. An event in PENDING/EXPANDED does not prove a push was sent. Notification availability never changes the analysis outcome or prevents authorized detail refreshes.
- A current DISCARDED attempt publishes a failure notification explaining non-application; the app always resolves the current server state.
- Keep notification failures separate from analysis outcomes. Provider acceptance, device receipt, and user viewing are distinct. Push contains event/reception/attempt IDs; the app retrieves authoritative current results and also refreshes on re-entry. Old or duplicate notifications cannot replace newer results.
- Recording Presigned URL lifetimes default to 15 minutes for upload, 10 minutes for administrator playback, and 10 minutes for AI download. Keep each configurable through `RECEPTION_RECORDING_UPLOAD_URL_TTL`, `RECEPTION_RECORDING_PLAYBACK_URL_TTL`, and `RECEPTION_RECORDING_AI_DOWNLOAD_URL_TTL`. Effective validity may be shorter when signing credentials expire or S3 policy imposes a shorter limit; playback seeking/reconnection after expiry requires renewed authorized access. S3 integration must use these settings when issuing URLs.
- These lifetimes are project defaults. AWS documents the request-time expiration check, credential limits, and maximum validity in [Presigned URL behavior](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html).
- New administrator recording uploads accept WAV only, with `.wav` extension (case-insensitive) and `audio/wav`, `audio/x-wav`, `audio/wave`, or `audio/vnd.wave`. Verify the stored object's WAVE container header as well as MIME, size, and SHA256. Do not infer a specific codec, sample rate, maximum size, or duration from the extension. Size/duration limits, HTTP deadlines, and retention/deletion schedules remain explicit TODOs. API details belong to [Backend OpenAPI](../../../docs/api/openapi.yaml) and [AI OpenAPI](../../ai/docs/openapi.yaml).
- Recording upload grants are an explicit exception to exact HTTP response replay: reuse the reserved file/object/metadata and idempotency fingerprint, but issue a fresh signed URL on every initial or renewal request. Never persist signed URLs in the database. Revalidate current ownership and PENDING upload status before signing; a completed recording returns FILE_ALREADY_UPLOADED. A different request body under the same key remains IDEMPOTENCY_KEY_REUSED. A signing failure may occur after durable reservation; retry the same key or use the existing file renewal endpoint without creating another recording or analysis.
- The confirmed Backend-to-AI wire contract is the current AI OpenAPI `analysisRequestId` envelope, including unchanged reception/version/recording and transcript identities. The earlier `jobId` example is not the selected contract. Reject mismatches instead of silently aliasing identifiers. Real AI server deployment and latency verification remain integration work.

STT storage decision: use database text storage for the immutable original and provide it through the authorized reception detail API. STT Presigned GET URLs and the previous S3 transcript-file access flow are outside the MVP. V4 aligns the S3-based V1 transcript table without rewriting V1-V3 or changing recording/analysis source foreign keys. The user confirmed no STT originals have been stored; no content import is required. If unexpected legacy transcript rows exist, migration must fail rather than invent content or delete originals.

New transcript rows require the unchanged nonblank text, its reception/recording/producing-attempt identities, and a storage timestamp. The current AI contract limits the original to 100,000 Unicode code points; never truncate it to fit. Keep one original per producing attempt and reject duplicate ID/producer inserts without replacing existing content. S3 bucket/key remain null. Existing internal `status=UPLOADED` and `uploaded_at` columns represent database storage completion/time for these rows, independently of the analysis attempt's STT/storage progress states. Retained byte length and SHA-256 metadata describe the exact UTF-8 content; they do not require an S3 copy.

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

### Reception Completion Push

- The Identity module owns administrator/device registration. `ReceptionPushDevices` supplies active installations,
  resolves the current owned token at send time, and conditionally invalidates only the rejected token.
  Reception does not read or mutate Identity's device table directly. An adapter and configured FCM project/ADC
  credentials are required before enabling the push worker; no default user-ID/token inference is provided.
- Send only the four string fields eventId, eventType, receptionId, and analysisRequestId through FCM HTTP v1.
  Record PROVIDER_ACCEPTED separately from device receipt/viewing, and never put originals, contact details,
  signed URLs, device tokens, or OAuth credentials in delivery/event payload storage or logs.
- Default to three total attempts. Retry temporary HTTP 429/5xx, transport/unknown outcomes, and temporary
  authentication availability with a one-minute base delay and short exponential backoff (one then two minutes
  for the default). Honor a longer Retry-After in seconds or HTTP-date form. Max attempts and base delay are
  configurable through RECEPTION_PUSH_MAX_ATTEMPTS and RECEPTION_PUSH_RETRY_DELAY. No in-memory retry loop.
- Keep retry due times/counts in the database. An interrupted/expired claim records unknown remote outcome
  and schedules a bounded retry. An old worker cannot overwrite a newer claim or terminal result.
  Event/installation uniqueness and optimistic versions prevent duplicate expansion and simultaneous sends.
- Retries after unknown remote outcomes provide at-least-once attempts, not an exactly-once FCM guarantee.
  Reuse the stable eventId for app deduplication and authoritative result refresh. Never rerun AI for push errors.
- An unregistered token is a permanent failure with conditional Identity invalidation. Other permanent provider
  errors stop that delivery. A revoked/rebound installation or stale reception event is SKIPPED; a token rotated
  during OAuth preparation is not sent and the next bounded attempt resolves the latest token.

References: [FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api),
[FCM error/retry guidance](https://firebase.google.com/docs/cloud-messaging/error-codes).
