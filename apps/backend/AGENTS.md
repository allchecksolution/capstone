# ALLCHECK Backend

Applies to `apps/backend/` and descendants; more specific `AGENTS.md` instructions take precedence within their scope.

Backend API for ALLCHECK's administrator and driver dispatch and work-reporting workflows. Stack: Kotlin, Java 21, Spring Boot/MVC, Spring Data JPA with PostgreSQL, Flyway, and Spring Security; Gradle Kotlin DSL, JUnit 5, and ktlint.

## Rule Levels

These definitions apply to all backend convention documents:

- **MUST:** Always follow the rule. If an exception is needed, clearly state the justification.
- **SHOULD:** Follow the rule unless there is a specific reason not to.
- **MAY:** Apply the rule selectively depending on the situation.

## Conventions: read by task

Identify the task's scope and consult only the relevant documents below.
For substantive Kotlin code changes, include `code-conventions.md` alongside task-specific conventions.
Read additional documents only when responsibilities overlap.
Do not load all conventions by default or reread documents already available in the current context.
The linked documents are authoritative; consult the relevant source when checking specific rules.


| Document                                                      | Read when the task involves                                                                    |
|---------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| [architecture.md](docs/architecture.md)                       | Architectural design, DDD, module boundaries, package structure, or inter-module dependencies. |
| [code-conventions.md](docs/code-conventions.md)               | Kotlin style, naming, readability, or general coding practices.                                |
| [exception-conventions.md](docs/exception-conventions.md)     | Exceptions, error codes, or exception handling.                                                |
| [persistence-conventions.md](docs/persistence-conventions.md) | JPA, repositories, entity persistence, transactions, or data access.                           |
| [flyway-conventions.md](docs/flyway-conventions.md)           | Database schema migrations or Flyway.                                                          |
| [test-conventions.md](docs/test-conventions.md)               | Unit, integration, security, persistence, or architecture tests.                               |
| [api-conventions.md](docs/api-conventions.md)                 | REST APIs, controllers, requests, responses, or API contracts.                                 |
| [security-conventions.md](docs/security-conventions.md)       | Authentication, authorization, or security implementation.                                     |
| [logging-conventions.md](docs/logging-conventions.md)         | Application logging, log levels, or sensitive information in logs.                             |

## Development and verification

Run Gradle commands from `apps/backend/` using the included wrapper and Java 21. Choose the smallest verification scope that covers the change:

| Command                                                | Use                                                                                                                                          |
|--------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| `./gradlew ktlintCheck`                                | Kotlin formatting checks, configured by [.editorconfig](.editorconfig).                                                                      |
| `./gradlew test --tests '<fully.qualified.TestClass>'` | Affected tests; replace the placeholder with the actual test class.                                                                          |
| `./gradlew test`                                       | Broader backend coverage when the change warrants it.                                                                                        |
| `git diff --check` / `git diff --cached --check`       | Check whitespace errors in unstaged and staged changes; for documentation-only changes, also verify links and skip builds/application tests. |

Confirm available plugins and dependencies in [build.gradle.kts](build.gradle.kts) before using technologies named in conventions; a mention alone does not imply installation. For database-dependent checks, consult the [local PostgreSQL configuration](../../infra/compose/compose.local.yml) and [local application configuration](src/main/resources/application-local.yml).
