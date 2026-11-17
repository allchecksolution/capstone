
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

Implemented reception routes are administrator-only and require the identity module's
`ReceptionAdministratorIdentity` adapter. Resolve an authenticated, currently authorized administrator to its
numeric database ID; never infer it from a username or an arbitrary token claim. Until request JWT authentication
and that adapter are connected, normal token requests remain 401 and an otherwise authenticated administrator
without the adapter receives 503. A supplied adapter that denies access returns 403.

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
| `RECEPTION_AI_ENABLED=true` | `RECEPTION_AI_BASE_URL`, `RECEPTION_AI_INTERNAL_API_KEY`, `RECEPTION_AI_CONTRACT=analysis-request-id-v1`, `RECEPTION_AI_CONNECT_TIMEOUT`, `RECEPTION_AI_STT_TIMEOUT`, `RECEPTION_AI_STRUCTURE_TIMEOUT` | Synchronous RestClient requests, internal API key, strict response/correlation checks, no redirects or application-level AI retries. |
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

Actual AWS/AI network integration, production JWT identity mapping, FCM/device delivery, and retention/cleanup
remain deployment/integration work. Local integration tests use offline S3 signing and a loopback AI HTTP server.

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
