# Logging Conventions

## Goal

Logging in ALLCHECK Backend aims to ensure **system behavior traceability, root cause analysis, and operational visibility**.

Use Spring Boot and SLF4J to maintain consistent logging, record only the information necessary for troubleshooting, and minimize unnecessary log generation.

---

## Logging Principles

- **MUST — Standard Logging:** Record application logs through SLF4J.
- **SHOULD — Meaningful Logging:** Log operationally meaningful information, such as key business events
and system state changes, while avoiding unnecessary repetitive logs.
- **SHOULD — Appropriate Log Levels:** Use `ERROR`, `WARN`, `INFO`, `DEBUG`, and `TRACE` according to event severity and operational purpose.
- **SHOULD — Parameterized Logging:** Use SLF4J's `{}` placeholders instead of string concatenation or unnecessary computations.

---

## Log Structure & Traceability

- **SHOULD — Request Correlation:** Use request identifiers (Request IDs) or Trace IDs to trace the processing flow of the same request across services.
- **SHOULD — Structured Log Context:** When needed for log analysis, record Trace IDs, event types, resource identifiers,
processing times, and similar contextual information using consistent field names, without including sensitive information.
