# Exception Conventions

## Goal

Express business failures through domain `ErrorCode` values and the shared `BusinessException`, and convert them into HTTP responses at the web boundary.

Extend exception handling based on actual use cases and verified requirements, avoiding speculative abstractions.

---

## Exception Design

- **MUST — Meaningful Exceptions:** Exceptions must clearly express the cause of failure, and business failures are represented by `BusinessException` with an appropriate `ErrorCode`.
- **MUST — Domain Error Codes:** Define business errors in `{domain}/exception/{Domain}ErrorCode.kt`. Reserve `GlobalErrorCode` for errors shared across domains.
- **MUST — Unique Error Codes:** Error codes exposed to clients must be unique across the entire API and must not duplicate the meaning of existing codes.
- **MUST — No Silent Exceptions:** Do not ignore or hide exceptions, or broadly catch and swallow `Exception` or `Throwable`.
- **SHOULD — Preserve Exception Cause:** When translating an exception, preserve the original `cause` when needed for diagnosis; apply the response and logging policies before exposing its information.
- **SHOULD — Exceptions for Exceptional Cases:** Do not indiscriminately use exceptions to represent normal business flows. If failure is an expected outcome, also consider expressing it through the return type.
- **SHOULD — Avoid Unnecessary Exception Types:** Create specialized exception classes only when distinct handling or recovery is needed.
- **SHOULD — Explicit Null Handling:** Handle nullable query results explicitly using `?: throw BusinessException(...)` or similar constructs.
- **SHOULD — Require and Check:** Use `require` and `check` for internal contracts and invariants, and `BusinessException` for business errors conveyed to clients.
- **SHOULD — Coroutine Cancellation:** When using coroutines, do not translate `CancellationException` into an ordinary business exception or swallow it.

---

## Exception Handling

- **MUST — Handle at Appropriate Boundaries:** Handle exceptions in a layer that can recover from them or translate them meaningfully, and propagate exceptions that cannot be handled to a higher layer.
    - **Application Service:** As a rule, throw use-case-level business exceptions in Application Services.
    - **Domain Invariant Enforcement:** Validate invariants that a domain object must guarantee directly within that object.
    - **Infrastructure Exception Translation:** Translate technical failures only when their meaning can be interpreted and the translation adds value.
    - **Web Boundary:** Convert propagated exceptions into HTTP error responses through shared exception handling.
- **MUST — Centralized Business Exception Handling:** Handle domain business exceptions consistently in GlobalExceptionHandler using BusinessException and ErrorCode. Avoid introducing unnecessary domain-specific exception handlers.
- **SHOULD — Specific Exception Handling:** Catch specific exception types only where distinct recovery or translation is needed, and keep the `try-catch` scope minimal.
- **SHOULD — Preserve Abstraction Boundaries:** Prevent implementation details of lower layers from being unnecessarily exposed to higher layers.
- **SHOULD — Avoid Redundant Wrapping:** Do not wrap exceptions unnecessarily when neither their meaning nor their handling changes.

---

## API Exception Handling

- **MUST — Consistent Error Responses:** Standardize API error responses using the shared `ErrorResponse` format at supported HTTP error handling boundaries.
- **MUST — Appropriate HTTP Status:** Return an appropriate error status; do not wrap errors in successful responses.
- **MUST — No Internal Information Exposure:** Do not expose stack traces, internal class names, SQL statements, sensitive information, or similar details in client responses.
- **MUST — Preserve Framework HTTP Semantics:** Preserve the HTTP status and required response headers of Spring MVC exceptions, even when the response uses a fallback `GlobalErrorCode` whose declared status differs.
- **SHOULD — Explicit Error Codes:** Clients should identify errors by their unique `ErrorCode` rather than error messages.
- **SHOULD — Unexpected Exception Fallback:** Handle unexpected MVC exceptions with a shared 500 response without exposing internal information.
- **SHOULD — Validation Failures:** Return 400 for request validation and deserialization failures. Treat return-value constraint violations as internal contract failures, not client input errors.
- **SHOULD — Validation Details:** Include all relevant validation errors when safe and meaningful, without raw input values or internal binding information. Do not guarantee their order unless explicitly defined by the API contract.

---

### Security Exception Boundary

- When implementing Security error responses, define `SecurityErrorCode` implementing the shared `ErrorCode` and use the common `ErrorResponse` structure.
- Implement response generation in `security.handler`: `AuthenticationEntryPoint` for authentication failures and unauthenticated access (401), and `AccessDeniedHandler` for authenticated users with insufficient permissions (403). Do not convert these failures into generic 500 responses in `GlobalExceptionHandler`.
- MVC advice does not cover all Security filter-chain failures. Follow [Test Conventions](test-conventions.md) for Security integration coverage.

---

## Exception Logging

- **MUST — No Sensitive Information:** Do not log unprotected secrets, personal information, raw request content, or untrusted exception and cause messages. Exclude unsafe dynamic field identifiers, such as user-supplied Map keys, from validation logs.
- **SHOULD — Log at Handling Boundaries:** Log at the boundary responsible for handling the exception, avoiding duplicate logs across layers.
- **SHOULD — Safe Diagnostic Context:** Prefer error codes and necessary validation metadata over raw messages. Retain safe request identifiers, exception types, and permitted stack information; record unexpected server failures at ERROR level.

---

## Exception Evolution & Testing

- **MUST — Preserve Existing Contracts:** Apply [API Conventions](api-conventions.md) compatibility requirements to public error codes, statuses, response structures, and required headers.
- **SHOULD — Incremental Improvement:** Evolve shared exception handling based on concrete domain requirements and verified defects, and protect behavior changes with regression tests.
