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
