# Test Conventions

## Goal

Tests in ALLCHECK Backend aim for **reliable business behavior, regression prevention, and independent, stable execution**.

Write tests with the characteristics of Kotlin and Spring Boot in mind, and verify externally observable behavior and business invariants rather than implementation details.

---

## Test Design

- **SHOULD — Given-When-Then:** Clearly distinguish the Given (setup), When (execution), and Then (verification) phases of a test. Use `// given`, `// when`, and `// then` comments by default in Kotlin test code.
- **MUST — Behavior-Oriented Tests:** Verify results for given inputs, state changes, external contracts, and business invariants rather than implementation details.
- **SHOULD — Risk-Based Coverage:** Prioritize verification of core business rules, boundary conditions, and major failure scenarios, and avoid duplicate tests.
- **SHOULD — Meaningful Assertions:** Clearly verify expected results and state rather than merely checking whether execution succeeds.

---

## Test Isolation & Reliability

- **MUST — External Dependency Isolation:** In unit tests, ensure independence by replacing or controlling dependencies outside the test scope, such as external APIs, messaging, and time, using Mock, Fake, Stub, or similar techniques. Use those dependencies in integration tests intended to verify actual integrations.
- **MUST — Deterministic Tests:** Control nondeterministic factors such as time, random numbers, and asynchronous processing so that the same conditions produce consistent results.
- **SHOULD — Minimize Mocking:** Do not indiscriminately replace the core logic under test or objects whose actual collaboration matters with Mocks.
- **SHOULD — Controlled Test Data:** Use Fixtures, Factories, or similar tools to prepare only the data needed, while avoiding excessive abstraction.

---

## Kotlin Testing Guidelines

- **SHOULD — Readable Test Names:** Use backtick (`` ` ``) function names to express test conditions and expected behavior in natural language.
- **SHOULD — Nullability Cases:** Test null and non-null scenarios when nullability affects business behavior or error handling.
- **SHOULD — Exception Assertions:** When verifying exceptions, check the specific exception type and, when needed, meaningful attributes such as `ErrorCode`.
- **SHOULD — Coroutine Testing:** Use coroutine testing tools or similar tools to deterministically verify completion, failure, and cancellation behavior of `suspend` functions and asynchronous logic.

---

## Spring & Layer Testing

- **SHOULD — Domain Unit Tests:** Verify the business rules and invariants of Entities and Domain Services without a Spring context whenever possible.
- **SHOULD — Application Service Tests:** Verify the success and failure flows of use cases, and use integration tests when transaction or persistence behavior is central to the test.
- **SHOULD — Focused Spring Tests:** Prefer the smallest test scope that meets the verification goal. Use focused tests such as `@WebMvcTest` or `@DataJpaTest`, and use `@SpringBootTest` only when full-context verification is necessary.
- **SHOULD — API Contract Tests:** Verify HTTP status codes, response structures, input validation, authentication, authorization, and error codes for key APIs.
- **SHOULD — Test Authentication Setup:** In tests where authentication and authorization are not under verification, use Spring Security's testing support to set up the required authentication context and focus on verifying core business behavior.
- **MUST — Security Test Coverage:** In tests where authentication and authorization are under verification, retain the security configuration and verify key security scenarios such as unauthenticated access, insufficient permissions, and resource access restrictions.
- **SHOULD — Module Integration Testing:** Use Spring Modulith's `@ApplicationModuleTest` to verify module behavior and interactions when module-level integration testing is appropriate.
- **SHOULD — Module Boundary Tests:** Use architecture tests to verify the [module boundary rules](architecture.md#modular-architecture) and [Entity reference rules](persistence-conventions.md#jpa-entity-management).

---

## Persistence Testing

- **MUST — Transaction Tests:** For critical transactional use cases, verify data consistency across successful execution and failure scenarios.
- **SHOULD — Repository Tests:** Test complex queries, Entity mappings and relationships, and database constraints against a schema created by Flyway.
- **SHOULD — Production-Like Database:** Run tests that depend on constraints, locking, queries, and transaction behavior against the same database engine as production whenever possible.
- **SHOULD — Transaction Boundary Verification:** When verifying commits and rollbacks, ensure that the test's own automatic rollback through `@Transactional` does not obscure actual behavior.
- **SHOULD — Concurrency Tests:** For concurrency-sensitive use cases, test concurrent execution in independent transactions against the same database, and verify that duplicate processing and data inconsistencies are prevented without relying on shared local locks.
- **SHOULD — Query Verification:** For performance-critical queries, check the actual SQL generated and the number of query executions.

---

## Test Maintenance

- **MUST — Regression Tests:** When fixing a confirmed defect, add a test that reproduces the issue whenever possible to prevent the same issue from recurring.
- **SHOULD — Stable Test Suite:** Resolve the causes of intermittently failing tests, and do not hide problems through simple retries or unconditional disabling.

---

## Running Reception Persistence Tests

`ReceptionRepositoryTest`, `ReceptionRecordingRepositoryTest`, `ReceptionTranscriptRepositoryTest`, and `ReceptionAnalysisRepositoryTest` use PostgreSQL 17 from the [local Compose configuration](../../../infra/compose/compose.local.yml).
Start it with `npm run db:up` from the repository root, then run from `apps/backend/`:

```sh
./gradlew test --tests 'com.allcheck.reception.infrastructure.repository.*'
```

The test profile uses the local database connection by default. Set `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME`,
and `TEST_DATABASE_PASSWORD` to use another PostgreSQL test database.
Each run creates UUID-named schemas, applies the real Flyway migrations, validates JPA mappings, and removes only those schemas afterward.
The existing application schema and its tables are not reset or migrated by this test.
The tests cover JSONB round trips, owner-scoped queries and counts, database constraints, concurrent writes from independent transactions,
atomic recording reservation linkage and metadata persistence/rollback,
and V1-to-V2 / V2-to-V3 upgrades that preserve existing recording metadata and first completion timestamps.
Transcript tests cover exact text/line-break/Unicode preservation, source foreign keys, duplicate-insert protection,
and V3-to-V4 migration with existing recording/analysis records. An additional isolated schema verifies that unexpected legacy S3 transcript rows stop V4 without losing existing data.
Work-type coverage verifies all five types in AI-original/final-data JSONB round trips and confirmation/reopen persistence,
rejects unsupported types, and checks V4-to-V5 expansion while preserving existing confirmed intake data.
Analysis tests cover persisted pending intents, fixed verified-recording context, one initial attempt per recording even after failure,
ordered pending queries, and a single successful execution-claim commit across independent transactions.
V5-to-V6 migration preserves existing processing state, worker identity, claim deadlines, and captured reception versions.
STT progression tests cover blank-text failure, exact deadline boundaries, claim ownership, source linkage, and terminal-state immutability.
Database tests verify atomic transcript/linkage storage, rollback with a preserved STT-success checkpoint,
and concurrent transcript storage versus timeout without leaving a losing transcript row or dangling linkage.
Structuring tests verify phase/owner/deadline guards, immutable AI results and missing-field lists, initial auto-fill without review confirmation,
saved administrator-value protection, and stale-result rejection. Database coverage includes atomic reception/attempt completion,
rollback, one-time concurrent application, and administrator confirmation/reopening that preserves the original AI JSON.
The current entities and internal references use numeric IDs; domain-only fixtures simulate already assigned IDs,
while repository and service tests use actual IDENTITY-generated keys. Recording reservation and transcript attachment
insert first to obtain the ID and commit their linkage in the same transaction. Tests verify concurrent generated-ID uniqueness,
rollback without ID reuse, V6-to-V7 conversion of empty schemas, numeric reference types and restored foreign keys,
and safe V7 refusal when existing string-key data needs an explicit mapping. Existing V1-V6 upgrade tests retain their original schema versions.

`ReceptionServiceTest` imports the real review service into a focused JPA context and uses a controlled server clock.
It runs calls outside the test rollback transaction to verify committed confirmation/reopen results, failed-call preservation,
owner-scoped 404 responses, persisted overall-analysis readiness, and conflicting service calls from independent transactions.
It also creates and removes only its own UUID-named schema. Run it with:

```sh
./gradlew test --tests com.allcheck.reception.application.review.ReceptionServiceTest
```

`ReceptionPipelineTransactionsTest` verifies the recording acceptance, STT checkpoint, canonical DB transcript,
structuring, result application, and durable completion-event transactions together. It also covers owner-scoped
material queries and atomic request registration through `ReceptionOperations`. The request module must call
registration inside its own transaction; request insertion and reception linkage either commit together or both
roll back. Registration and reopening cannot both win against one confirmed version. Elapsed visit windows do
not prevent registration of an already confirmed snapshot.

`ReceptionCommandServiceTest` verifies database transaction-scoped advisory locking, successful command receipts,
unchanged first-response replay, payload mismatch rejection, rollback, and current ownership checks on replay.
`ReceptionApiIntegrationTest` exercises real HTTP serialization, authorization, validation, generated OpenAPI,
and review flows against PostgreSQL with an administrator `AuthPrincipal` supplied through Spring Security's
MockMvc test support.

Full application and security tests also use owned UUID-named schemas. The `test` profile does not enable local
Swagger automatically; tests that verify the documentation endpoints enable it explicitly. Run all backend
coverage with `./gradlew ktlintCheck test`; it must not migrate or clear the local application's public schema.

`RecordingStorageClientTest` signs real SDK requests offline and checks conditional PUT headers, distinct URL
lifetimes, HEAD checksums, error translation, and disabled-storage behavior. `ReceptionApiIntegrationTest` also
verifies failed object verification leaves no analysis, successful and concurrent completion converge on one
acceptance, later replays skip S3, and playback access enforces ownership without exposing object keys.

`AiClientTest` uses a loopback HTTP server to verify internal authentication, exact STT preservation, timestamp
offsets, correlation, strict JSON types/unknown/null/duplicate fields, synchronous status, disabled redirects,
HTTP timeout, and no application-level retries. Its keys and AWS signing credentials are test-only fixtures.
`ReceptionAnalysisExecutionTest` runs real PostgreSQL transactions and loopback AI calls through the entire
orchestrator. It covers successful auto-fill/publication intent, blank STT, transcript-insert rollback and separate
failure commit, structure failure preserving originals, late responses, and interrupted PROCESSING expiry without
another AI call. These tests do not contact an actual AI provider, AWS, or FCM or select production runtime settings.

WAV and filename-contact coverage verifies identical versus ambiguous phone tokens, WAV MIME aliases and
container headers, false-extension rejection, source contact persistence, AI auto-fill alongside a source phone,
manual correction precedence, and preservation after failed analysis. Recording HTTP tests verify actual fresh
signing on every same-key/renewal request, exactly one reservation, no signed URL in command receipts, and
refusal to issue another upload grant after completion. All filenames/phone numbers in fixtures are synthetic.

Shared S3 tests verify that image uploads and other buckets use the same SDK configuration without recording
format rules, and that create-only versus replace signing is chosen by the caller. `S3ConfigTest` verifies
independence from reception feature flags. Architecture coverage rejects global-to-business-module dependencies.
Offline AWS test fixtures live in `support.S3TestFixtures`, so generic tests do not depend on reception tests.

`FcmClientTest` uses loopback HTTP to verify OAuth headers, safe provider error classification, Retry-After,
redirect refusal, and execution cancellation after credential preparation. `ReceptionPushIntegrationTest` uses
its own PostgreSQL schema to verify atomic expansion, per-device ownership and token rotation/logout, bounded
retries without AI reruns, unknown-outcome recovery, old-worker rejection, and independent concurrent claims.
All full-context schemas explicitly disable production integrations/workers even if the development shell enables
them. Tests inject only fake device bindings and loopback FCM transport; no actual notification is sent.

`DriverPushSenderTest` verifies identifier-only data, preservation of the existing generic notification,
provider result/error translation, and cancellation using the shared HTTP transport against loopback FCM.
Driver integration tests cover logout, token rotation, and lease expiry at the final send checkpoint,
plus rejection of stale claims. `DriverPushConfigTest` verifies the common FCM activation flag and that
delivery leases exceed the shared HTTP timeout. Driver and reception retain separate delivery policies.
`DriverRegistrationIntegrationTest` owns an isolated UUID-named schema through `PostgresTestSchema`,
so its global queue polling cannot consume another test context's deliveries or device bindings.
