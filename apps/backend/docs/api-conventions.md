
# API Conventions

## Goal

REST APIs in ALLCHECK Backend aim for **consistent resource design, clear API contracts, client compatibility, and maintainability**.
Follow HTTP standards and REST design principles to ensure that clients can use APIs in a predictable and consistent manner.

---

## Endpoint Design

- **MUST — Endpoint Structure:** Use `/api/v1/{resources}[/{id}][/{subresources}]` as the default endpoint format, and use plural nouns and lowercase kebab-case for resource names.
- **SHOULD — Resource-Oriented URLs:** Design URLs around resources and avoid unnecessary nesting. Allow the `POST /{resources}/{id}/{action}` format for business operations that are difficult to express through simple CRUD operations.

---

## API Implementation

- **MUST — API Interfaces:** Place {Resource}Api.kt interfaces in {domain}.presentation.api and declare Spring MVC mapping/binding and OpenAPI annotations on them. Controllers implement these interfaces with @RestController and request-handling logic.
- **MUST — Shared OpenAPI Configuration:** Define shared API metadata and security schemes, including JWT, in `global.config.OpenApiConfig.kt`.

---

## HTTP Semantics

- **MUST — HTTP Methods & Status Codes:** Use HTTP methods according to their standard semantics, and return appropriate success status codes (`200`, `201`, `202`, `204`, etc.) based on the outcome of the operation.
- **SHOULD — Idempotency:** For state-changing APIs where retries or duplicate requests may occur, apply mechanisms to ensure idempotency when needed.

---

## Request & Response Design

- **MUST — Explicit API Contracts:** Define API contracts using Request/Response DTOs, and do not expose Domain Entities directly to external clients.
- **MUST — Controller Responses:** Return successful REST responses as `ResponseEntity<T>`, or `ResponseEntity<Void>` when no response body is returned.
- **SHOULD — Response Separation:** Controllers construct HTTP responses; Application Services should not depend on HTTP framework types such as `ResponseEntity`.
- **MUST — DTO Documentation:** Use `@Schema` for DTO field descriptions without duplicating constraints already expressed by types and validation annotations.
- **SHOULD — Consistent Data Representation:** Use `camelCase` for JSON fields and query parameters, and maintain consistent response structures and data representations across similar APIs.
- **SHOULD — Pagination & Filtering:** Provide appropriate pagination for APIs that return large lists, and clearly define filtering and sorting conditions and page size limits.
- **MUST — Request Validation:** Validate all external request inputs, prioritizing Jakarta Bean Validation (`@Valid`, `@Validated`, and constraint annotations). Perform DTO-level format and range validation and business rule validation in their appropriate layers.

---

## Documentation & Compatibility

- **MUST — OpenAPI Documentation:** Generate API documentation from API interfaces and DTOs using springdoc, and keep documented parameters, responses, status codes, and authentication requirements consistent with the implementation.
- **MUST — Backward Compatibility:** Review backward compatibility when making API changes that affect existing clients, and handle incompatible changes through explicit versioning or migration procedures.

## Reception Integration

Reception routes require administrator authority. Controllers use `@AuthenticationPrincipal` to receive the
verified `AuthPrincipal` from Spring Security and pass its numeric account ID to reception services for ownership
checks. Unauthenticated requests return 401. Authenticated requests without administrator authority or a
supported administrator principal return 403.

The generated OpenAPI exposes creation, owner-scoped list/detail, analysis detail, confirmation, reopening,
WAV upload reservation/renewal, recording completion, and recording access. Reception HTTP errors use the shared `ErrorResponse` fields
`code`, `message`, and optional `details` (`field`, `reason`). The stored analysis resource's `error.traceId`
is execution metadata and is separate from HTTP errors. The preliminary YAML retains legacy error contracts
for unrelated unimplemented APIs. Do not infer an endpoint's availability solely from that draft.

Successful creation, confirmation, reopening, and recording completion use durable transaction-scoped
idempotency receipts. Scope includes the acting administrator, operation, numeric reception identity, and key.
Matching successful replays return the first response, except recording upload grants: their reservation is reused and the signed URL is freshly issued outside the DB transaction on every request. No signed URL is stored in a receipt. Current detail is obtained through GET. Different input
under one key returns 409 `IDEMPOTENCY_KEY_REUSED`; a concurrently held command lock returns 409
`OPERATION_IN_PROGRESS`. No production `Retry-After` duration or receipt deletion period is decided.
Completion has an additional stable source identity: new keys and old expected versions reproduce the original
acceptance after verification has already succeeded, without another S3 read or analysis attempt.

Creation, verification/reference, and review audit timestamps use PostgreSQL microsecond precision for stable
first-response/requery equality. Visit-end validation still uses the original unrounded server time. AI visit
timestamp offsets are preserved. All original-material and signed-URL responses have `Cache-Control: no-store`.
Recording access accepts the reception and recording IDs and exposes a short-lived GET URL, expiry, method,
and verified MIME; it does not return a bucket/object key in reception detail.

External integrations and the analysis worker are disabled by default. Enable each only with its required settings:

| Enable flag | Required environment settings | Behavior |
|---|---|---|
| `S3_ENABLED=true` | `S3_REGION`, `S3_REQUEST_TIMEOUT`, `RECEPTION_RECORDING_BUCKET`, and an AWS SDK default-chain credential source | Private S3 HEAD with SHA256 and PUT/GET signing. No embedded credentials, bucket creation, ACL grants, or public URL fallback. |
| `RECEPTION_AI_ENABLED=true` | `RECEPTION_AI_BASE_URL`, `RECEPTION_AI_INTERNAL_API_KEY`, `RECEPTION_AI_CONNECT_TIMEOUT`, `RECEPTION_AI_STT_TIMEOUT`, `RECEPTION_AI_STRUCTURE_TIMEOUT` | Synchronous RestClient requests, internal API key, strict response/correlation checks, no redirects or application-level AI retries. |
| `RECEPTION_WORKER_ENABLED=true` | Both preceding integrations enabled, `RECEPTION_WORKER_CONCURRENCY`, `RECEPTION_WORKER_EXECUTION_BUDGET`, `RECEPTION_WORKER_POLL_INTERVAL` | Bounded local execution capacity backed by durable DB PENDING work and a timeout monitor. Only a committed claim may call AI. PROCESSING work is never automatically re-executed. |

Use Spring duration syntax such as `30s` or `2m`; examples are syntax only, not approved production values.
AI base URL must be an HTTPS origin without credentials, query, or fragment. Plain HTTP is accepted only on
loopback for local development/tests. Internal keys and AWS credentials belong in Infisical/runtime configuration.
Choose AI HTTP timeouts and execution budget after latency measurement, and coordinate AI download URL TTL
with when the AI service begins downloading. See BR-09 for configurable URL defaults and pending policies.

The storage adapter uses AWS SDK v2 conditional `If-None-Match: *`, a signed content length/MIME/SHA256,
and `HeadObject` with checksum mode enabled. A missing object or invalid/missing SHA256 is a 400
`FILE_UPLOAD_NOT_VERIFIED`; S3 permission/service failures are a safe 503 `RECORDING_STORAGE_UNAVAILABLE`.
New uploads require a WAV filename and one of its four documented MIME aliases. A conditional range GET of the first 12 bytes verifies the WAVE container signature; it is not a full audio decoder or duration measurement. The AI service still validates decodability. Maximum size/duration and supported codec constraints are not inferred or imposed without a decision.

Actual AWS/AI network verification, the Identity push-device adapter, Firebase credentials, and retention/cleanup remain deployment/integration work. Local integration tests use offline S3 signing and a loopback AI HTTP server.

The unchanged original filename is sufficient input for server-side contact extraction. Parsing it on Backend
keeps platform behavior consistent; parsing the random S3 key is impossible, and copying the name to S3 metadata
or asking AI to infer a number adds no necessary capability. Filenames may contain personal data and must not
be logged. Only matching phone tokens are used for automatic review input, after removing hyphens. The two
positions are not assigned a customer/business meaning without further information. A different pair or unknown
pattern leaves the required review phone for the administrator to fill. The original AI JSON never gains a phone
field. Filename-derived contact survives an STT/structure failure and does not prevent AI auto-fill of other fields.

S3 SDK beans and provider configuration are shared technical infrastructure in `global.config.S3Config`.
`global.storage.S3StorageClient` provides neutral metadata/range reads and signed PUT/GET operations.
It has no business-module dependency or WAV, ownership, or file-lifecycle rules. Each domain supplies its
bucket/key, URL lifetime, and create-only policy. `reception.infrastructure.storage.RecordingStorageClient`
retains WAV/header verification, recording TTLs, and mapping neutral storage failures to reception errors.
The former `RECEPTION_STORAGE_ENABLED`, `RECEPTION_STORAGE_REGION`, `RECEPTION_STORAGE_REQUEST_TIMEOUT`,
and `RECEPTION_STORAGE_BUCKET` settings are replaced by the common SDK settings and reception bucket above.

Terraform for private storage is under `infra/terraform/s3`; its outputs match these bindings.

Completion notifications use `global.notification.FcmClient` and shared `global.config.FcmConfig` for technical
HTTP/OAuth handling. Reception owns durable event expansion, per-installation claims and retry outcomes.
FCM uses Google Application Default Credentials (for example a runtime-mounted service-account file referenced
by GOOGLE_APPLICATION_CREDENTIALS); secrets are not stored in YAML, Terraform outputs, database rows, or Git.

To activate transport, set FCM_ENABLED=true, FCM_PROJECT_ID, FCM_CONNECT_TIMEOUT, and FCM_REQUEST_TIMEOUT.
To activate the reception worker, also provide the Identity implementation of ReceptionPushDevices and set
RECEPTION_PUSH_ENABLED=true, RECEPTION_PUSH_POLL_INTERVAL, and RECEPTION_PUSH_CLAIM_TTL. The claim TTL
must exceed the request timeout. These runtime durations remain explicit deployment choices.
Retries default to three total attempts with one-minute base backoff; Retry-After can extend the delay.
A dedicated one-thread executor keeps remote sends off the scheduler/DB transaction, with durable pending work
in the database. OAuth completion is followed by a final execution/access/binding check before the HTTP send.

The `analysisRequestId` AI envelope is confirmed. RECEPTION_AI_CONTRACT is optional and defaults to
analysis-request-id-v1; setting an incompatible contract fails startup. STT/analyze endpoints are still to be
deployed by the AI service (the current AI application only implements health).

## Operations Integration

Implemented operations routes are declared in `operations.coordination.presentation.api.OperationsApi`.
The generated `/v3/api-docs` is the implementation contract; the repository-level YAML remains a preliminary
cross-application draft. In particular, implemented mutation responses use `OperationResponse`
(`resourceId`, `requestId`, `status`, `version`, `at`, `relatedResourceId`). A matching idempotency replay
returns the first result with `Idempotency-Replayed: true`; fetch current state after commands. Registration
and follow-up decisions return the request version; acceptance, work transitions, cancellation and first report
submission return the work version; report revisions return the report version; photo commands return the
related work version. Reads and signed URL responses use `Cache-Control: no-store`.

External IDs are `req_`, `round_`, `offer_`, `work_`, `cancel_`, `report_`, `revision_`, `photo_`, and `decision_`
followed by their numeric IDs. Identity resolution is separate from these resource identifiers.
Controllers receive the verified `AuthPrincipal` through `@AuthenticationPrincipal`. The operations actor
resolver checks that the authenticated authority matches the principal's user type. Administrators use
their account ID. Drivers use the driver ID resolved from their account ID through the active-driver API.
Unsupported principals and accounts without current driver access return 403.

`OperationsDrivers` is a required dependency. The driver API applies account eligibility, standby status,
specialty, and the configured location validity period. The operations adapter matches the service area and
returns the candidate's WGS84 coordinates, measurement time, and validity period. Search distinguishes a
complete empty result from a lookup failure. Operations rechecks location freshness and filters by PostGIS
distance, existing work occupancy, and past cancellations for the same request.
`lockForAcceptance` is a short local module call joining the acceptance transaction. Its implementation must
lock authoritative profile/availability/location state, or provide equivalent version protection through commit;
it must not make an HTTP call under the operations lock. Lock order is request, operations driver guard, then
the external driver's eligibility guard. The acceptance time and expiry are checked after acquiring guards.

The nested request, dispatch, and work modules own their aggregates. The coordination module composes their
public APIs; shared parent contracts have no dependency back into the child implementations. Database partial
unique indexes protect active rounds, accepted offers, and unfinished driver/request work independently of
command receipts. Durable tasks use database claims, lease tokens, bounded attempts and due times. Expired
claims are recovered; a stale result cannot update a new claim or reopen a terminal round. Push transport can
have an unknown outcome and therefore be delivered more than once; the logical offer/follow-up event remains
unique and clients refresh authoritative state on receipt. Active progress screens initially poll every five seconds.

All administrators may query `/service-requests`, its detail, `/dispatch-rounds` and `/dispatch-history`,
`/dispatch-cancellations`, and `/reports`. History includes inline cancellation reasons and redispatch decisions.
Drivers use `/drivers/me/dispatch-offers`, `/drivers/me/works?scope=CURRENT|HISTORY`, and
`/drivers/me/work-statistics?month=YYYY-MM`. `HISTORY&month=YYYY-MM` uses Asia/Seoul completion/cancellation
month; `CURRENT` with a month is invalid. Work and report details allow an owner's historical reads and a
current driver's previous-work handover chain. Previous drivers cannot read another driver's subsequent work.
Offer responses expose only the administrative area. Driver customer-phone access ends when their current
unfinished work ends, including when the request retains its completed-work link.

Photo reservations are `/works/{workId}/photos/upload-requests`, with renewal at
`/works/{workId}/photos/{photoId}/upload-requests`. Same-key reservations are reused and freshly signed;
renewal changes no business state and needs no command receipt. Completion verifies S3 HEAD MIME/length/SHA256
and a conditional range read of the JPEG/PNG signature. This is format identification, not full image decoding.
Only verified BEFORE/AFTER photos of the same work/driver may be submitted, three to five per phase.
Photo access uses the related work permissions; responses never expose bucket/object keys. No server report
draft, DURING photo, administrator approval, or direct ongoing-request edit/cancellation API is provided.

Runtime activation requires the following, in addition to the existing JWT and shared S3/FCM configuration:

| Feature | Configuration and prerequisites |
|---|---|
| Database | PostgreSQL 17 with PostGIS. V12 installs the extension in public if absent; provision it beforehand when the migration role lacks extension permission. Unmapped legacy photo references stop V12 without deleting data or guessing new work IDs. Local Compose uses the official PostGIS image with amd64 emulation on Apple Silicon. Existing data volumes are not reset. |
| Geocoding | `OPERATIONS_SGIS_ENABLED=true`, `OPERATIONS_SGIS_SERVICE_ID`, `OPERATIONS_SGIS_SECRET`, `OPERATIONS_SGIS_CONNECT_TIMEOUT`, `OPERATIONS_SGIS_REQUEST_TIMEOUT`, and the provider's required usage permission. A single complete building-level match is converted from EPSG:5179 to WGS84 in PostGIS; missing/ambiguous results do not create a request or alter reception confirmation. |
| Photos | `S3_ENABLED=true`, the shared S3 settings/credentials, and private `OPERATIONS_PHOTO_BUCKET` with PUT/HEAD/GET and checksum permissions. Upload/access TTLs default to 15m/10m and are configurable with `OPERATIONS_PHOTO_UPLOAD_URL_TTL` / `OPERATIONS_PHOTO_ACCESS_URL_TTL`. No bucket or public ACL is created by application startup. |
| Worker | `OPERATIONS_WORKER_ENABLED=true` requires a push device integration and an enabled FCM connection. The driver adapter is required by the application. `OPERATIONS_WORKER_LEASE` defaults to 60s and `OPERATIONS_WORKER_POLL_INTERVAL` to 1s. Configure integration timeouts within the lease and measure production capacity. |
| Dispatch | `OPERATIONS_OFFER_LIFETIME` defaults to 1m. Search and driver-device transient retries use three total attempts with 5s/10s delays. A longer provider Retry-After is honored only within offer expiry. |
| Administrator push | `OPERATIONS_ADMIN_PUSH_MAX_ATTEMPTS` defaults to 3; `OPERATIONS_ADMIN_PUSH_RETRY_DELAY` to 1m. Device resolution revalidates current authorization and binding; only the current unresolved follow-up target may be sent. |

Keys and credentials belong in runtime secret configuration. Live SGIS/S3/FCM verification, retention/cleanup,
and cross-application deployment remain integration work. Automated coverage uses isolated PostgreSQL/PostGIS
schemas, offline S3 signing, and loopback SGIS/FCM.
