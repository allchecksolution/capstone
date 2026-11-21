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
| BC | Identity and Access Management — `identity` | Administrator accounts, credentials, and roles; driver Kakao authentication identifiers and account links; authentication sessions and tokens; administrator and driver push-device registrations | [FR-01], [FR-13] authentication / [UC-01], [UC-08] authentication |
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

Kakao login verifies the provider token outside database transactions. Account resolution and session issuance then commit together. An `INSERT ... ON CONFLICT (social_provider, social_user_id) DO NOTHING` prevents duplicate authentication accounts without aborting the transaction on a concurrent first login. Lock and read the resolved Account before checking its current enabled state and creating a session; the conflict path never updates or reactivates an existing Account. JWT issuance failure rolls back both the new Account and session. Each successful login creates its own session without creating a Driver registration application.

Resolve the Kakao identity only from the provider's verified member number, a valid unexpired token,
and the configured application ID. Do not authenticate using a client-supplied member number or email.
Distinguish invalid or wrong-application credentials from provider unavailability; neither failure changes
existing accounts or sessions. Authentication account IDs, Kakao member numbers, and Driver IDs have
different meanings. Authentication responses describe the account; membership state is queried from driver.

### Administrator Login

Administrator login validates the password outside write transactions, then locks and rereads the Account before issuing a session. A changed credential invalidates the verification snapshot; the current role and enabled state govern issuance. Administrator and Kakao logins share session issuance and the same session lifecycle policy. Current password encoding is salted PBKDF2-HMAC-SHA256 with 600,000 iterations. The user approved operator-only CLI provisioning on 2026-11-18: create a new account with an explicit MASTER/SUB role and reject duplicate login IDs without updating or reactivating existing accounts. Provisioning does not issue sessions or expose a public signup API. The user removed the Kakao-hosted authentication-failure lockout from requirements. Administrator login also has no failure-count account lockout.

Treat login IDs and passwords as supplied, without case folding, trimming, or truncation. Unknown login
IDs, incorrect passwords, and unsupported stored credential formats produce the same authentication failure;
do not reveal which credential failed or replace existing credentials. Initial provisioning does not authorize
password resets, role changes, reactivation, or deletion; their policies remain unresolved.

### API Request Limits

The user approved per-account API request limits on 2026-11-18: up to 30 refresh requests per 60 seconds for administrators and drivers across all sessions, and up to five driver registration/resubmission requests combined per 60 seconds. Count identifiable, authorized, input-valid requests before business execution, including subsequent business failures and successful idempotent replays. Refresh first verifies an active session and enabled account, then revalidates them under the existing transaction locks before rotation. Unknown or unusable refresh tokens keep the existing authentication error contract.

Each bucket starts at its first counted request. Its expiry automatically releases the limit; blocked retries never extend it. Return 429 with the shared TOO_MANY_REQUESTS code and a positive Retry-After delay without revoking tokens or sessions. Use atomic database counters and database time across instances, committed independently of business rollback. Do not extend these limits to login, logout, read, review, withdrawal, or push APIs.

Refresh and registration limits use independent buckets, and no bucket combines different accounts.
Registration and resubmission share one bucket and are checked before NTS verification or business writes.
Refresh identifies the account from the presented active Refresh Token; invalid or unusable tokens do not
create an account bucket. Revalidation failures after counting remain counted. A limit response neither
logs the user out nor blocks otherwise permitted reads or logout; the existing valid token may be used
again after expiry of the limit window. Do not bypass the limits when counter storage is unavailable.

### Driver Registration Persistence

Generated Account, AuthSession, PushDevice, Driver, business/history/receipt/notification/delivery and WorkOrder keys use `Long` / `BIGINT` IDENTITY. Public account/driver identifiers are positive JSON integers; JWT account/session claims and FCM identifier data retain their required string representation. Installation IDs, provider member numbers, idempotency keys, hashes and delivery claims remain distinct business/technical identifiers. The user confirmed V12-V17 are unapplied; these branch migrations are updated together, preserving main V1-V11 unchanged.

`Driver` embeds its personal profile as an immutable value object and owns a separate business-information record. Profile scalar fields are stored in `drivers`; specialties, regions and equipment collections reference `driver_id`, with no independent profile identity. Personal settlement accounts and tax-invoice email belong to the profile, not the business. Each authentication account has at most one Driver. Keep full submitted snapshots and review actors, reasons, timestamps, and application revisions in append-only history; resubmission retains the same Driver and Account IDs.

Registration changes, review history, idempotency results, and notification publication intent commit together. Verify business information outside write transactions. The user confirmed on 2026-11-18 that registration and resubmission require a matching NTS business number, opening date, and representative name with CONTINUING operating status. Suspended and closed businesses cannot apply. Administrator name/business-name search uses partial matching.

An input-stage business check does not create an application or grant approval. Changing any verified
business identifier invalidates that preliminary result, and final registration/resubmission always verifies
the submitted three-field identity again rather than trusting a client verification flag. NTS matching checks
the supplied business information, not that the applicant personally is its representative. Distinguish an
explicit mismatch or suspended/closed business from an unavailable or unconfigured verification service;
neither outcome creates a registration. Optional business-name fields do not replace the three-field match.

Successful idempotent replays reuse the stored result without repeating NTS verification; failed mutations
do not become completed idempotency results. Idempotency-result retention/deletion periods remain unresolved. Administrator
search is case-insensitive partial matching of worker or business names, with wildcard characters treated
literally. Mask settlement-account numbers to the last four characters in administrator views.

`operations.work` owns actual work records and implements the driver-owned `DriverWorkload` query contract. Assignment and withdrawal lock the authentication Account before the Driver. Database constraints allow at most one unfinished work per driver and request. Withdrawal checks real work before atomically changing membership, disabling authentication, and revoking all sessions and device bindings. Do not substitute an empty workload stub.

`identity` owns session-bound push installations for both apps. Logout removes only the current session's bindings; deactivation removes all account bindings. Driver registration events target active administrator devices, and approval/rejection events target the applicant's devices. Persist per-device delivery claims and recover abandoned claims through database leases. Revalidate current registration revision, status, account/session access, and device ownership before sending. FCM provider acceptance is not proof of device receipt. Payloads contain identifiers, with original information obtained through authorized queries. Driver's explicit-failure retry policy remains undecided.

Reception and driver share the global FCM HTTP v1 connection and transport. Driver retains its existing
generic notification title/body alongside event/driver identifiers; reception remains data-only.
After OAuth preparation, driver rechecks the delivery claim, lease, current registration and device-token
binding before HTTP transmission. Cancellation records SKIPPED when the claim is still current; stale
workers cannot overwrite another claim. Explicit provider failures remain terminal FAILED for driver;
the transport integration does not introduce a new automatic retry policy. A driver delivery lease must
exceed the shared FCM request timeout.

## Project Integration and Migration Policies

| Area | Project policy |
|---|---|
| Internal AI authentication | Backend→AI uses `X-Internal-Api-Key` separately from user JWTs. Missing/invalid keys return 401; an authenticated service without permission receives 403. Secrets are managed through Infisical. |
| Kakao authentication | Validate the SDK Access Token against Kakao and the configured numeric application ID before resolving an Account. The client returns only a verified SocialLogin and performs no database changes. Invalid credentials and provider unavailability remain distinct outcomes. |
| Sensitive business data | Do not log internal keys, presigned URLs/signatures, FCM tokens, recording contents, or STT originals. Push carries resource identifiers; original contents require an authorized query. |
| Migration versions | Begin at `V1` and allocate the next unused positive integer (`V2`, `V3`, ...). Do not use timestamp/date versions or renumber an applied migration. |
| Main and identity/driver migration integration | Preserve main's V1-V11 unchanged. The identity/driver branch's unapplied V2-V7 are allocated V12-V17 in the same dependency order: accounts/sessions, driver registration, work orders, push devices, driver deliveries, and request limits. This renumbering is valid only because these branch migrations have not been applied; it is not a procedure for upgrading an already-applied branch history. |
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
| `NO_CANDIDATES` | Successful candidate queries exhausted the configured radii without an eligible driver |
| `SEARCH_FAILED` | Candidate search could not complete because of a system/integration error |
| `DELIVERY_FAILED` | Delivery finally failed for all offers without a driver response |
| `NO_ACCEPT` | All offers ended through rejection or final delivery failure, with at least one rejection and no unanswered expiry |
| `ROUND_CLOSED` | Round closed without acceptance, for example due to expiry |

A round starts in `SEARCHING`. Finding candidates moves it to `OFFERING`; confirmed acceptance moves it to `ASSIGNED`. Record closure without a driver using the corresponding reason. Partial delivery failure alone does not close a round. Never reactivate a closed round; create a new round for each follow-up dispatch. Preserve a past round's `ASSIGNED` result even if its work is later cancelled.

Apply the following round-closure classification to the current committed results:

- A valid acceptance atomically confirms the assigned work and `ASSIGNED` round as specified in BR-04.
- Exhausting the configured search radii without eligible drivers closes the round as `NO_CANDIDATES`.
- A permanent candidate-query error, or exhaustion of the temporary-error retry policy, closes a still-searching round as `SEARCH_FAILED`. Preserve this outcome separately from a successful search with no candidates; it is an eligible closed-round target for administrator redispatch or request cancellation under BR-06.
- Final delivery failure for every offer with no driver response closes the round as `DELIVERY_FAILED`.
- When no offers remain to await and every result is rejection or final delivery failure, with at least one rejection and no unanswered expiry, close as `NO_ACCEPT`. Two rejections plus one final delivery failure therefore produce `NO_ACCEPT`.
- When no offers remain to await and at least one unanswered offer has expired, close as `ROUND_CLOSED`, including mixed rejection/final-delivery-failure/expiry results. Two rejections plus one unanswered expiry therefore produce `ROUND_CLOSED`.

Keep awaiting other valid pending offers after a partial rejection or delivery failure. Commit closure without a driver and the request's `FOLLOW_UP_REQUIRED` transition together. A round closes only once; late delivery results must not replace its recorded terminal outcome or undo a confirmed assignment.

### Driver-Specific Dispatch Offers

| Field | States | Conditions |
|---|---|---|
| `offer.status` | `PENDING`, `ACCEPTED`, `REJECTED`, `EXPIRED`, `RESPONSE_CLOSED` | Transition from pending to accepted, rejected, expired, or closed. Once another driver's acceptance is confirmed, close the remaining pending offers as `RESPONSE_CLOSED`. |
| `offer.deliveryStatus` | `PENDING`, `PROVIDER_ACCEPTED`, `FAILED` | Transition from pending delivery to provider acceptance or delivery failure. Manage independently of driver response state. |

Fix the target driver, dispatch round, and expiry time at offer creation. `PROVIDER_ACCEPTED` does not mean actual receipt or viewing. Record actual receipt and viewing in `receivedAt` and `viewedAt`; leave them null without confirmation signals.

Create one logical offer per round and driver, shared by all of that driver's active registered installations. All installations use the same offer identity and fixed expiry; the first valid committed acceptance or rejection is the driver's response for every installation. Device count does not create additional offers, assignments, or response windows.

Keep per-installation delivery outcomes separately and derive the offer's delivery status: any installation accepted by the push provider makes it `PROVIDER_ACCEPTED`, which other installation failures must not downgrade. With no provider acceptance, keep it `PENDING` while any delivery can still be attempted, and mark it `FAILED` only when no active installation exists or all targeted deliveries have finally failed. Actual receipt/viewing remains independent of provider acceptance. Round closure continues to use the driver-offer results rather than treating each installation as a separate candidate.

Use **1 minute from offer creation** as the initial, configurable response window. Delivery retries, provider acceptance, receipt, and viewing do not extend the fixed expiry. Reject acceptance at or after `expiresAt`, even if an expiry worker has not persisted the terminal offer state yet. Expose the server-fixed expiry for the app countdown; delivery latency consumes part of the response window.

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
- Entering `REVISIT_REQUIRED` requires a nonblank reason. The planned revisit timestamp is optional, so an unknown schedule does not block recording the need for a revisit. The currently responsible driver records or changes the planned timestamp after coordinating with the customer; no administrator approval is required.
- Revisit reasons and planned timestamps belong to the work and are exposed to its authorized driver and all currently authorized administrators. Keep them separate from the immutable confirmed intake's original visit window. Preserve earlier revisit records when the same work requires a further visit; administrator queries do not grant permission to schedule or change the driver's revisit.
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

Register an initial application that passes business registration verification as `PENDING_APPROVAL`. Registration and resubmission accept only continuing businesses. Account-specific access scopes follow [BR-02](#br-02).

Rejection requires a nonblank reason. Resubmission retains the same Driver and Account IDs, advances
the application revision, and preserves earlier submitted snapshots and review actors/reasons/times.
Review and membership changes must reject a stale version instead of overwriting a newer decision.
Approval alone does not set availability to READY or create a work assignment. An active driver without a saved dispatch state starts as UNAVAILABLE.

Withdrawal requires an active membership, explicit confirmation, and no unfinished work, including revisit
or report-submission waiting. Commit WITHDRAWN, authentication-account deactivation, and revocation of all
sessions/device bindings together. Preserve the Kakao link and registration/review/work history. Assignment
and withdrawal cannot both succeed against incompatible membership/workload state. A withdrawn account
cannot regain access by logging in again or creating a replacement authentication identity.

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

Calculate location usability at query or candidate-selection time. Use only `VALID` locations for distance-based candidate selection. Record location permission denial and collection errors as separate causes. Invalid location alone does not block existing work progress or report submission. Keep only the latest location report. A newer issue clears coordinates, an older or duplicate report leaves the current state intact, and a report dated in the future is rejected.

Use **5 minutes** as the initial, configurable location validity period. A location whose last update is exactly 5 minutes old is still valid; an older location is stale. Missing coordinates or an unavailable update timestamp must not qualify a driver for distance-based dispatch. Apply the same validity policy when revalidating candidate eligibility at offer acceptance.

<a id="br-02"></a>

## BR-02 Business Actors and Access Scopes

Authentication and authorization mechanisms follow [Security Conventions](security-conventions.md). Apply BR-01 account and work state conditions to the operation-specific access scopes below.

### Administrator Authentication

Administrators authenticate using pre-provisioned IDs and passwords. Do not provide public administrator registration, MFA, or TOTP enrollment/verification. Successful ID/password authentication issues application credentials; operation-specific authorization remains required. The user confirmed that `MASTER` and `SUB` have identical business permissions. Preserve their role values for classification, and apply the same resource-access, business-state, and history-preservation rules to both. Neither role alone grants operating-system access to the provisioning CLI.

### Authentication Sessions

The following initial policies were confirmed on 2026-11-16. This section owns session behavior;
implemented paths and payloads are defined by the generated OpenAPI, with the initial API design retained
as a reference in [openapi.yaml](../../../docs/api/openapi.yaml).

| Concern | Policy |
|---|---|
| Access Token | JWT; default lifetime 15 minutes, configurable |
| Refresh Token | Cryptographically random; persist only its hash |
| Session lifetime | Default 14 days from login; refresh never extends the session's absolute expiry |
| Multiple devices | Allow independent sessions; a new login does not revoke existing sessions |
| Refresh | Issue a new Access/Refresh Token pair and invalidate the consumed Refresh Token atomically |
| Access Tokens after refresh | Previously issued Access Tokens retain their own expiry while the session stays active |
| Concurrent refresh and token replay | Only one refresh using the same token succeeds; reject other uses with 401. Replay alone does not revoke the replacement token or other sessions |
| Logout | Revoke only the session identified by the presented valid Refresh Token; immediately reject that session's previously issued Access Tokens |
| Logout with an invalid, expired, or revoked token | Return 204 without affecting other sessions |
| Account deactivation and driver withdrawal | Revoke all account sessions and prevent both existing Access Token use and refresh |

Refresh rotation and token issuance either commit together or preserve the previously valid Refresh Token.
If logout commits first in a refresh/logout race, refresh is rejected. If rotation commits first, logout with
the consumed token has no effect on the replacement token or other sessions. Login, refresh, and logout
validate their presented body credentials independently of an expired Access Token attached to the request.

Authenticate every protected request against the current account and session state stored on the server. Do not rely solely on token claims or process-local memory. Identity owns authentication state; driver owns membership state. Driver withdrawal disables authentication and revokes all sessions through identity's public module API. Identity does not depend on driver internals; the app queries driver registration state separately after login. An authenticated identity does not itself grant access to driver business features.

### Driver Account Access

| Account State | Permitted Access |
|---|---|
| `PENDING_APPROVAL` | Own registration application and approval status, and logout. Restrict normal dispatch, work, and chat features. |
| `REJECTED` | Own rejection reason, reapplication, and logout. Restrict normal service access until activation. |
| `ACTIVATED` | Normal service use within the operation-specific access scopes and state conditions below |
| `WITHDRAWN` | No login or normal service access |

### Push Device Ownership

Administrator and driver installations bind to the authenticated account and its current session, including
pending/rejected applicants. An active installation cannot be taken over or removed by another account;
its prior owner must release the binding before transfer. A registration token has only one active binding.
Resolve current account/session/device access for each send, and conditionally invalidate only the rejected
token binding so a delayed provider failure cannot revoke a replacement token. Revocation removes the
stored token; logout affects only bindings of that session, while withdrawal/deactivation affects all bindings.

### Operation-Specific Access

| Actor / Resource | Permitted Access |
|---|---|
| Administrator operations | Allow intake confirmation, service request registration, follow-up decisions, report revisions, and operational status queries to authorized administrators. |
| Operations administration | In the MVP, all currently authorized administrators may query all service requests, dispatch rounds/offers, work, reports, and their histories, and make BR-06 exception follow-up decisions regardless of which administrator registered the request. Shared operations access does not allow editing registered intake snapshots, direct cancellation from `OPEN`/`ACCEPTED`, access to another administrator's reception originals, or registration from another administrator's reception. |
| Reception administration | In the MVP, an administrator may list, read, edit, confirm, revoke confirmation, and create a service request only from receptions they created. Resolve the reception owner from the authenticated administrator at creation, and enforce ownership on every operation and file/material access, including duplicate-request responses. Do not grant access to other administrators' receptions. |
| Reception existence disclosure | When resolving a reception for an authenticated administrator, a nonexistent reception and a reception owned by another administrator both return 404 with the same RECEPTION_NOT_FOUND code/message. Perform the owner-scoped lookup before resource-specific state, version, material access, or business validation. Authentication and role failures retain their normal 401/403 semantics. |
| Dispatch offers | Drivers may view and respond only to offers addressed to them. Viewing closed offers does not require new-acceptance eligibility; response conditions follow BR-04. |
| Site address for offer recipients | Offer list/detail responses disclose only the site's administrative area through si/gun/gu and eup/myeon/dong, plus straight-line distance. Do not include street names, building numbers, unit details, or exact site coordinates in offer payloads. After acceptance, the full confirmed address is available through authorized work queries. Work type, symptoms, visit window, and reviewed notes retain their existing offer visibility. Customer phone access follows the separate rule below. |
| Work and first report submission | Allow work progress, cancellation, and first report submission only to the driver matching `driverId` on the service request's current work. Permitted states follow BR-01. |
| Driver work history | An authorized driver may read their own completed/cancelled work details and cancellation reason, plus the originals, latest effective content, revision history, and attachments of reports submitted for their own work. Historical access is read-only and does not allow resumed progress, another first report submission, or access to another driver's later work after redispatch. The current driver's separate handover access remains as specified below. |
| Customer contact phone for drivers | Disclose the confirmed customer contact phone only after acceptance to the driver authorized as responsible for the service request's current unfinished work. Candidate offer/list responses omit the phone. Completed/cancelled work history, a previous assignment, or handover read permission does not grant phone access; withhold it from these historical queries even when the completed work remains linked as the request's current work. |
| Handover records | The new driver may read previous work records linked to the same service request. Do not grant permission to modify previous work or reports. |
| Recordings and work photos | Consultation recording access is limited to the administrator owning the reception. Work photos follow the related service request/work access permissions. When attaching files, verify upload ownership and the corresponding intake/work linkage. |
| STT originals and analysis attempts | Only the administrator owning the related reception may read its original transcript and analysis records. AI services do not obtain application-user access or direct Backend database access. |
| Push devices | Administrators and drivers, including pending/rejected applicants, may register and remove only their own installation bindings. Analysis notifications target the original recording uploader's currently registered devices. Driver registration notifications target administrators, and review notifications target the applicant. Revalidate current resource access before sending. |

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
- The MVP does not provide editing of registered service-request intake fields, including address, visit schedule, work type, symptoms, contact phone, and notes. If those details need to change, cancel the existing request under the request-cancellation rules and create a new reception and service request. Keep the existing request's snapshot and history unchanged; cancellation does not reopen its source reception. Do not add direct administrator request cancellation from `OPEN` or `ACCEPTED`.
- The administrator reviews, corrects, and confirms intake, then registers the request to start automatic dispatch. After driver acceptance, the customer and assigned driver coordinate normal service directly. Retain administrator exception follow-up under BR-06 after a round fails to secure a driver or assigned work is cancelled: the administrator decides to redispatch or cancel the remaining request from `FOLLOW_UP_REQUIRED`. This exception workflow does not grant direct intervention in an ongoing dispatch or work.
- `ReceptionOperations` is the public module boundary for the confirmed snapshot and request linkage. Registration must join the request-creation transaction (MANDATORY propagation), so a failed request insert or conflicting reopen cannot leave only one side committed. The snapshot exposes reviewed fields, including notes, without granting drivers access to reception materials.
- Provide optional `notes` for customer requests and additional information, such as contact-before-arrival and parking instructions. Preserve the AI original separately and let the administrator review, add, and correct the effective notes. Include the reviewed notes and exact visit schedule in the service request snapshot. Drivers may read the notes from their own dispatch offer before acceptance and from their authorized work after acceptance, following BR-02. Source reception ownership does not grant drivers access to reception APIs.
- Treat `notes` as a normal administrator-review field: expose original extracted notes under `aiOriginal` and the latest editable notes under `finalData`. Populate initial notes under the same first-result rules as the other fields, allow manual additions when AI omitted notes, and save changes without overwriting `aiOriginal.notes`.
- Administrator detail reads must distinguish the stored STT original, original recording access, immutable original AI-extracted JSON, and the effective administrator-editable data. Editing must never rewrite any of the three original materials. Provide recording access through the existing authorized private-file access contract; do not expose a public bucket URL. Return missing materials as absent, and retain stage-specific progress/failure information. Original transcript read failures follow the API failure contract rather than becoming empty text.
- The detail exposes the reserved `recordingFileId` so an interrupted upload can obtain a renewed URL and complete verification. Only `recordingStatus=SUCCEEDED` makes an original available for playback. The administrator app requests a short-lived Presigned GET URL from the recording access API, which returns `url`, `expiresAt`, `method`, and `contentType`. It then reads the audio from that URL and requests a new URL after expiry. S3 bucket/key lookup belongs to Backend; a separate object-key field is not needed in the app contract. Check reception ownership and recording linkage on every URL issuance.
- Successful service request creation does not mean candidates were found or a driver received an offer.

### Candidate Search and Delivery

- Registering a service request starts automatic candidate search and offer delivery; the MVP does not require an administrator to select candidates or confirm a separate send command. Administrators make the existing follow-up decisions when a round ends without a driver or assigned work is cancelled.
- Use push dispatch to send offers simultaneously to every eligible driver within the search radius, without a top-N candidate limit. Candidates must meet BR-01 account, availability, and workload conditions, plus specialty, service area, and valid location requirements. Compare registered service areas to the geocoded site by administrative name segments after normalizing province and metropolitan names. A selected region includes its districts or neighborhoods. The first valid committed acceptance assigns the work and closes the other pending offers as specified below.
- For every redispatch round, exclude any driver with a recorded work cancellation for the same service request. Apply this exclusion at candidate selection and acceptance revalidation. A previous offer rejection, unanswered expiry, or delivery failure alone does not exclude a driver from later rounds if all current eligibility conditions are met. The exclusion is specific to that service request and does not prevent candidacy for other requests.
- Calculate search-radius inclusion with PostGIS using the surface distance between the site's and driver's WGS84 coordinates (the business's straight-line distance). Candidate filtering does not require road-network distance or driving-time calculations.
- Search at 10 km first, then expand to 30 km and finally 50 km only when the preceding radius has no eligible candidates. Start offering to every eligible driver at the first radius that yields candidates, and do not continue widening that round while awaiting their responses. Keep these radii configurable with 10/30/50 km as the initial policy values; specialty, service-area, account, availability, workload, and location requirements still apply at every radius.
- If the initial search area yields no candidates, expand it according to the configured dispatch policy. Keep `SEARCHING` while expanding and record search radius and iteration separately. Close as `NO_CANDIDATES` if no candidates are found before the termination conditions are met.
- On a temporary candidate-query failure, repeat the same radius/conditions up to **3 total attempts**, including the first, with **5 seconds after the first failure and 10 seconds after the second failure** before retrying. Persist the attempt count and retry due time so restart does not reset the budget. A failed or incomplete query is not an empty candidate result and must not advance the search radius or create offers.
- If those attempts fail, or the error is permanent, atomically close the still-`SEARCHING` round as `SEARCH_FAILED`, set the request to `FOLLOW_UP_REQUIRED`, and append its administrator notification intent. Retain the failure and search history. Stop that round's search; a late worker cannot reopen it or create offers after closure. Redispatch requires the normal administrator decision and a new round rather than an automatic new round. This policy concerns candidate lookup after request creation; geocoding failures remain subject to the registration prerequisite below.
- Distinguish no candidates, total delivery failure, and partial delivery failure. With partial failure, keep waiting for responses to valid offers and record delivery results per driver.
- Send each offer to every active registered installation of the target driver. Device registration, token ownership, and conditional invalidation belong to another domain's integration contract; operations owns logical offers, per-installation dispatch delivery records, and aggregation. Identity supplies the current account-to-installation binding. Resolve the driver ID to its account ID and revalidate the binding and its version before sending or invalidating a token.
- For dispatch push, retry temporary delivery failures up to **3 total attempts per installation**, including the first attempt. Wait **5 seconds after the first failure** and **10 seconds after the second failure** before retrying; honor a longer provider Retry-After without extending offer expiry. Stop retries when the offer expires, is no longer pending, or its round is assigned or closed. Do not retry successful deliveries or permanent failures such as invalid/unregistered tokens. Keep attempts and retry scheduling durable and configurable; reception analysis-completion push retains its separate retry policy.
- Creating a service request or redispatch round commits its durable search intent with the business transition, and candidate search executes asynchronously after commit. Scheduler intervals, concurrency, and integration timeouts are runtime settings separate from search-radius, query-retry, and offer-response policies.

### Site Coordinates and Geocoding Provider

- Backend owns resolution of the confirmed customer address into a dispatch location. Preserve the confirmed reception snapshot and store the resolved location separately on the service request for subsequent searches and redispatch. This does not introduce a shared customer-address database or grant access to reception originals.
- Resolve an unambiguous site location before creating a service request. If geocoding finds no usable result, cannot resolve multiple candidates, or is unavailable, do not create the request, its first round/offers, or its reception registration linkage. The failed registration does not change the reception's confirmed data, review status, or version. Do not choose an arbitrary candidate or start dispatch without coordinates.
- An administrator corrects an unresolved or ambiguous address through the existing owned-reception reopening, editing, and confirmation flow before trying registration again. A temporary provider failure permits another registration attempt against the unchanged confirmed intake. Registration retries must not create duplicate requests, and commit-time checks still revalidate current ownership, confirmed snapshot/version, and registration consistency after geocoding.
- Select **SGIS WGS84 geocoding** as the operations development integration target. Its public contract accepts an address and returns EPSG:4326 coordinates; authentication uses a service ID/secret to obtain an expiring access token. Keep the provider behind an operations-owned integration boundary and use controlled responses for local/test verification.
- The provider choice is a technical decision, not confirmation of unrestricted production use. As reviewed on **2026-11-19**, the published SGIS API terms contain no explicit geocoding-result storage prohibition, but require prior consent for business activity. Confirm permission for this project's address-to-coordinate storage and dispatch use before commercial activation; an API key alone does not establish that permission.
- The SGIS introduction says daily usage is unlimited, while its API terms still specify 50,000 calls per key per day. Do not promise unlimited capacity; reconcile the applicable limit with the provider before production activation. Runtime credentials, successful address-quality checks, and any required permissions remain integration prerequisites.
- MOIS address-search and coordinate-lookup APIs were evaluated as an alternative. They require two lookup stages, separate approvals/keys for search and coordinates, and coordinate-system handling. The public catalogue marks the service free with unrestricted licensing, but the current portal terms also restrict commercial use without explicit approval, and the coordinate application restricts applications from overseas IPs. Do not treat the catalogue label alone as clearance of every operational condition.

Provider review sources:

- [SGIS WGS84 geocoding contract](https://sgis.mods.go.kr/developer/html/newOpenApi/api/dataApi/addressBoundary.html)
- [SGIS authentication and token use](https://sgis.mods.go.kr/developer/html/newOpenApi/api/dataApi/authAndUseApi.html)
- [SGIS API terms](https://sgis.mods.go.kr/developer/html/newOpenApi/app/rules.html)
- [SGIS introduction and usage guidance](https://sgis.mods.go.kr/developer/html/newOpenApi/api/intro.html)
- [MOIS coordinate API catalogue](https://www.data.go.kr/data/15056663/openapi.do)
- [MOIS coordinate API guide](https://business.juso.go.kr/jst/jstCoordApiSearch)
- [MOIS API application](https://business.juso.go.kr/jst/jstAddressApiApplicationWrite)
- [Address Information Portal terms](https://www.juso.go.kr/Terms)

### Acceptance and Rejection

- Acceptance requires the driver's own offer to be `PENDING` and unexpired, the service request to be `OPEN`, and the round to be `OFFERING`. Revalidate candidate eligibility at acceptance time.
- Commit work creation, service request `ACCEPTED`, round `ASSIGNED`, offer `ACCEPTED`, and closure of the remaining pending offers together; do not commit a partial result. Concurrent acceptance of the same request and one driver's concurrent acceptance of different requests must respect BR-01 per-request work and per-driver workload limits.
- Rejection closes only the driver's own valid pending offer. Do not require `READY` or an unoccupied workload merely to reject an offer.
- Atomicity and concurrency control implementation for concurrent acceptance follows [Persistence Conventions](persistence-conventions.md#concurrency-control).

<a id="br-06"></a>

## BR-06 Work Cancellation and Follow-Up

- Cancellation reason codes are `SCHEDULE_CONFLICT`, `EQUIPMENT_UNAVAILABLE`, `TRAVEL_ISSUE`, `CUSTOMER_UNREACHABLE`, and `OTHER`.
- Every cancellation requires one reason code and a nonblank detailed reason of **1 to 1,000 Unicode code points**, including for non-`OTHER` choices. Reject a missing/unknown code, missing/blank detail, or an over-limit detail without creating a cancellation, changing work/request state, or publishing a follow-up notification. Preserve the accepted reason in the immutable cancellation record for administrator decisions and authorized history reads.
- Commit cancellation receipt, work `CANCELLED`, and service request `FOLLOW_UP_REQUIRED` together. Create only one cancellation record per work record and preserve its reason and timestamp.
- Administrator follow-up decides whether to redispatch or cancel the remaining customer service request. There is no administrator approval or rejection process for work cancellation.
- Confirm a follow-up decision only once per target cancellation or closed round. Commit either the redispatch decision, service request `OPEN`, and new round creation together, or the request-cancellation decision and service request `CANCELLED` together. The two decisions must not both succeed.
- Determine pending follow-up from `request.status=FOLLOW_UP_REQUIRED`; do not add a separate `cancellation.resolutionStatus`. Preserve cancellation records and administrator decision history independently of state fields. Link each decision to its target cancellation or closed round, decision content, administrator, decision time, and created round.
- If a redispatch round fails to secure a driver, return the service request to `FOLLOW_UP_REQUIRED`. Do not re-execute the existing decision; record a new follow-up decision against the latest closed round.
- Preserve round, work, driver, cancellation, and decision history from initial dispatch through redispatch, separately from current information. Never overwrite historical work or decisions with new dispatch data.

### Administrator Follow-Up Notifications

- Notify every currently authorized administrator's active registered installations when a round ends without an assigned driver (`NO_CANDIDATES`, `SEARCH_FAILED`, `DELIVERY_FAILED`, `NO_ACCEPT`, or `ROUND_CLOSED`), or when the assigned driver cancels work. These transitions to `FOLLOW_UP_REQUIRED` are the administrator-push triggers in the MVP; normal registration, acceptance, travel, arrival, work progress, and successful completion remain available through queries without administrator push.
- Commit one logical notification intent with the corresponding follow-up transition, then deliver after commit. Track per-installation results and deduplicate each logical event and installation independently. A notification failure must not undo cancellation or round closure, change the service request outcome, or cause redispatch automatically.
- Revalidate the recipient's current administrator authorization and installation binding, and verify that the notification still refers to the current unresolved follow-up target before sending. Notifications contain event/resource identifiers; the app queries current authoritative state on receipt or re-entry rather than treating push as the source of business data.
- Use Identity's active administrator and device binding APIs for recipient lookup and token ownership. Operations owns follow-up events and their deliveries. Configure bounded delivery retries separately from the driver's one-minute offer response window; administrator notifications do not create or extend a driver offer.

<a id="br-07"></a>

## BR-07 Original Reports and Revision History

- The MVP provides final report submission only, with no server-side report draft, partial-save, or draft-restoration API. Validate the complete report and verified required photos at submission; missing inputs or a failed/unsubmitted report must not complete work or the service request. Photo upload reservations and completed-upload metadata remain separate from report submission and do not constitute a saved report draft.
- Preserve the first submitted original and before/after work photos; administrator revisions must not overwrite them.
- In the MVP, every currently authorized administrator may correct only diagnosis (`diagnosis`), work description (`workDescription`), and total cost (`totalCost`) on a submitted report, regardless of which administrator registered the service request. Do not permit replacing or deleting submitted photos, changing the responsible driver, or changing original submission metadata through report revision.
- Require a revision reason and append administrator revisions as separate history containing that reason, changed fields, before/after values, editor, and timestamp. Use the latest effective content without a separate approval or rejection process. The driver's successful first submission completes work and the service request under BR-01 without administrator approval; later corrections do not reopen either state.
- Drivers and administrators use the same single work cost `totalCost` as an integer in KRW. Report revisions are not new first submissions or repeated completion of work or service requests.
- Access, retention, and deletion of originals, revision history, and attachments follow BR-02.
- Work photos are uploaded directly to private S3 with a phase of BEFORE or AFTER only; during-work photos are outside the MVP. A submitted report requires **3 to 5 BEFORE photos and 3 to 5 AFTER photos**, inclusive, for 6 to 10 attached photos in total. Reject during-work uploads and report fields. Validate completed upload, phase, ownership, work linkage, per-phase counts, and duplicate IDs within and across the report's photo arrays at submission. Preserve all submitted original photos.
- Allow only JPEG (`image/jpeg`) and PNG (`image/png`) work photos, with a per-file size of 1 to **10 MB (10,000,000 bytes)** inclusive. Validate declared MIME and size when reserving an upload, and verify the stored object's actual format, size, and checksum at upload completion; declared metadata alone does not establish a verified photo. The actual format must match the declared MIME before the photo may be attached to a report.

<a id="br-08"></a>

## BR-08 Status, History, and Statistics Queries

- Query features and user flows follow [FR-16](../../../docs/requirements/functional-requirements/fr-16-work-history-and-statistics-management.md) and [UC-04](../../../docs/requirements/use-cases/uc-04-dispatch-status-inquiry.md). Lists and statistics use the same query period; queries do not change source business states.
- Distinguish current service request, round, work, and driver information from historical records. Derive the need for administrator follow-up from `request.status=FOLLOW_UP_REQUIRED`.
- The MVP operations frontend integration uses REST queries together with the driver-offer and administrator follow-up push policies in BR-04 and BR-06. Use **5-second polling while an in-progress screen is active** as the initial client integration baseline, and refresh current state on screen entry/re-entry, push receipt, and after operations such as acceptance or cancellation. Operations implementation supplies the query APIs and notification events; it does not introduce SSE or WebSocket state subscriptions.
- Polling frequency and push arrival do not determine authoritative state or extend offer expiry. Commands always validate current persisted state, eligibility, ownership, and versions, even when the displayed state came from an earlier query. Push is a cue to query current state rather than a replacement for a successful authorized read.
- Operations implementation includes administrator-facing, per-service-request dispatch history and round-detail query APIs. These source-domain queries remain in scope independently of the dashboard's combined lists and statistics. All currently authorized administrators may query them under BR-02.
- Preserve and expose the full chain from the first dispatch through each redispatch: round identity/order, start/end and outcome; accepted offer and assigned work/driver identity and acceptance/assignment time; work cancellation identity, reason code/detail, time, and pre-cancellation state; and administrator follow-up decision, actor/time, and linked new round or final request cancellation. Keep the original driver and previous-work links on historical work. Do not overwrite this chain when the current round or driver changes, or rewrite an earlier `ASSIGNED` round after its work is cancelled.
- The per-request history response must include cancellation reasons alongside the corresponding past work so the frontend can display why the assignment ended without a separate request per cancellation. Round details expose each targeted driver's offer, delivery/response result, and relevant timestamps, including rounds that never secured a driver. Preserve the distinction between provider acceptance, actual receipt, and viewing under BR-04. Current assignment and historical records must be distinguishable in responses.
- Driver history queries support the read scope in BR-02, including own-work cancellation details and links to authorized report/photo reads. Expose assignment time, actual completion/cancellation time, and the confirmed visit window as distinct data; a scheduled visit time does not establish when the work actually ended.
- Driver history month filtering uses the first successful report's stored submission timestamp for `WORK_DONE`, and the stored cancellation timestamp for `CANCELLED`. Interpret the selected month in `Asia/Seoul`, including its first instant and excluding the next month's first instant. Earlier assignment or scheduled visit dates do not exclude work that ends in the selected month. Administrator report revisions and duplicate successful submissions do not change the original completion timestamp or move the work into another month.
- Driver personal monthly statistics use each event's own stored timestamp within the same `Asia/Seoul` month boundaries: assignments use the accepted work's `assignedAt`, completions use the first successful report's submission timestamp, and cancellations use the work cancellation timestamp. Assignment counts represent accepted work creation, not offer delivery. The cancellation card counts cancelled work, not an administrator's later cancellation of the service request. A prior-month assignment may therefore contribute to the selected month's completion/cancellation count.
- The driver's work-type completion distribution includes only work completed in the selected month, grouped by the five confirmed intake work types, with that month's total completed work as its denominator. The completion/cancellation counts use the same work sets as the corresponding monthly history rows. Retries, revisits within the same work, report revisions, and notification deliveries do not create additional assignment or completion counts. These personal metrics are scoped to the authenticated driver and retain the existing operations source-data and dashboard query-module boundaries.
- Distinguish an empty list, no statistics for the period, a missing selected service request, and query failure. Do not turn query failures into empty results. Response representations and status codes for implemented APIs are documented in the springdoc-generated OpenAPI.
- Report-based queries and statistics use the latest effective revisions, but count completion only once per service request. Do not count cancelled work or report revisions as additional completions.
- For administrator lists and statistics, apply the same filters to service requests registered within the selected dates, inclusive in `Asia/Seoul`. Lists can omit either date. Statistics require both. Match driver and work status against the same current or historical work record, and count each matching request once. The list shows the current round, work, and driver reference alongside the current request status. Use dispatch history to resolve a historical match.
- Search the formatted request ID, confirmed site address, and symptoms by literal case-insensitive substring. Match region by administrative-area prefix, using the same aliases as dispatch. Read source facts at query time through the operations query contract.
- Count total, open, completed, and finally cancelled service requests, and divide completed and cancelled counts by the total for their rates. Historical filters select requests and each selected request contributes its current outcome. Work cancellation during redispatch is preserved in work history. Use the latest report cost for completed requests and average across their reports, rounded to whole KRW. Return observed seven-day buckets anchored on the selected start date and counts and shares for the five confirmed work types. Return `hasData=false` and `metrics=null` when no requests match.

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

- The Identity module owns administrator/device registration. Reception checks the administrator's active
  status, resolves current device bindings, and conditionally invalidates a rejected binding through
  `PushDeviceOperations`. The push worker requires a configured FCM project and ADC credentials.
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
