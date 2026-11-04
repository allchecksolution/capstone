# Exception Conventions

## Goal

Exception handling in ALLCHECK Backend aims for **clear expression of error causes, separation of responsibilities by layer, and consistent API responses**.

The default flow is **define domain-specific ErrorCodes → throw a BusinessException → convert it to an HTTP response in GlobalExceptionHandler**.

Design exceptions with their causes, recoverability, and handling responsibilities in mind, and avoid unnecessary exception handling and abstractions.

---

## Exception Design

- **MUST — Meaningful Exceptions:** Exceptions must clearly express the cause of failure, and business failures are represented by `BusinessException` with an appropriate `ErrorCode`.
- **MUST — Domain Error Codes:** Domain-specific business errors must be defined and managed in an `ErrorCode` within the corresponding domain. Manage only errors shared across multiple domains in `GlobalErrorCode`.
- **MUST — Unique Error Codes:** Error codes exposed to clients must be unique across the entire API and must not duplicate the meaning of existing codes.
- **MUST — No Silent Exceptions:** Do not ignore or hide exceptions, or broadly catch and swallow `Exception` or `Throwable`.
- **SHOULD — Preserve Exception Cause:** When translating an exception, pass the original exception as the `cause` if tracing the cause is needed.
- **SHOULD — Exceptions for Exceptional Cases:** Do not indiscriminately use exceptions to represent normal business flows. If failure is an expected outcome, also consider expressing it through the return type.

### Exception Structure

```text
com.allcheck
├── {domain}/
│   └── exception/
│       └── {Domain}ErrorCode.kt
│
└── global/
    └── exception/
        ├── ErrorCode.kt
        ├── GlobalErrorCode.kt
        ├── BusinessException.kt
        ├── ErrorResponse.kt
        └── GlobalExceptionHandler.kt
```

- Manage each domain's business errors in `{Domain}ErrorCode`.
- Use `BusinessException` across all domains.
- Create separate domain-specific exception classes only when there is a clear need.
- Handle shared exception processing and HTTP response conversion in `global.exception`.

---

## Exception Handling

- **MUST — Handle at Appropriate Boundaries:** Handle exceptions in a layer that can recover from them or translate them meaningfully, and propagate exceptions that cannot be handled to a higher layer.
  - Application Service as Primary Boundary: As a rule, throw use-case-level business exceptions in Application Services.
  - Domain Invariant Enforcement: Validate invariants that a domain object must guarantee directly within that object.
  - Infrastructure Exception Translation: Translate technical failures into appropriate exceptions only when their meaning can be interpreted.
- **SHOULD — Specific Exception Handling:** Handle exception types as specifically as possible.
- **SHOULD — Minimize Try-Catch Scope:** Use `try-catch` only when separate handling is needed, and keep its scope minimal.
- **SHOULD — Preserve Abstraction Boundaries:** Prevent implementation details of lower layers from being unnecessarily exposed to higher layers.
- **SHOULD — Avoid Redundant Wrapping:** Do not wrap exceptions unnecessarily when neither their meaning nor their handling changes.

---

## API Exception Handling

- **MUST — Consistent Error Responses:** Standardize API error responses using the shared `ErrorResponse` format.
- **MUST — Appropriate HTTP Status:** Return an HTTP status code appropriate to the nature of the error, and do not wrap errors in successful responses.
- **MUST — No Internal Information Exposure:** Do not expose stack traces, internal class names, SQL statements, sensitive information, or similar details in client responses.
- **MUST — Separate Exception Responsibilities:** The business layer throws exceptions, and the web layer is responsible for generating HTTP error responses.
- **SHOULD — Global Exception Handling:** Centralize shared HTTP exception handling using `@RestControllerAdvice`.
- **SHOULD — Explicit Error Codes:** Clients identify errors by their unique `ErrorCode` rather than error messages.
- **SHOULD — Validation Error Handling:** Handle request data validation and deserialization failures with consistent 400 responses. Distinguish internal contract violations from request errors.
- **SHOULD — Unexpected Exception Fallback:** Handle unexpected exceptions with a shared 500 response that does not expose internal information.

---

## Exception Logging

- **MUST — No Sensitive Information:** Do not log sensitive information such as passwords, authentication tokens, personal information, or raw request content.
- **SHOULD — Log at Appropriate Boundaries:** Log exceptions at the boundary that actually has responsibility for handling them.
- **SHOULD — Avoid Duplicate Logging:** Do not repeatedly log the same exception across multiple layers.
- **SHOULD — Appropriate Log Levels:** Use appropriate log levels, distinguishing expected business errors from system failures.
- **SHOULD — Preserve Diagnostic Context:** Log the error codes, request identifiers, and exception causes needed for problem analysis.

---

## Kotlin Exception Guidelines

- **SHOULD — Explicit Null Handling:** Handle Nullable query results explicitly using `?: throw BusinessException(...)` or similar constructs.
- **SHOULD — Avoid Non-Null Assertions:** Avoid unnecessary use of `!!`, and prefer safe access or explicit validation.
- **SHOULD — Require and Check:** Use `require` and `check` to validate internal contracts and invariants, and use `BusinessException` for business errors to be conveyed to clients.
- **MAY — Result-Based Handling:** Use `Result` or `runCatching` when it is appropriate to represent failure as a value. However, avoid overusing them merely to shorten syntax.
- **SHOULD — Coroutine Cancellation:** When using coroutines, do not translate `CancellationException` into an ordinary business exception or swallow it.
