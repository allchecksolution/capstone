
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
