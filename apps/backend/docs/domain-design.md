# Domain Design Conventions

Define domain boundaries around business capabilities, ownership, and invariants. The concrete contexts, aggregate names, module assignments, and business policies belong to [Business Specification](business-specification.md#project-domain-model).

## Bounded Contexts and Data Ownership

- **MUST — Explicit Ownership:** Give each business concept and authoritative state a clear owning context. Other contexts collaborate through exposed APIs or events rather than directly changing its internal data.
- **SHOULD — Business-Aligned Boundaries:** Define boundaries using business language, capabilities, lifecycle, and consistency needs. A screen, storage provider, or database table alone does not establish a bounded context.
- **SHOULD — Separate Queries from Ownership:** Query modules and read models may combine data from multiple contexts without taking ownership of source business updates.

## Aggregate Design

- **MUST — Consistency Boundaries:** Design aggregate boundaries around invariants that must remain consistent within one business operation. The aggregate root controls changes within that boundary.
- **SHOULD — Focused Aggregates:** Keep aggregates cohesive and avoid grouping unrelated lifecycles merely because data is often displayed together.
- **MUST — Cross-Aggregate References:** Reference other aggregates by identifier and follow [Persistence Conventions](persistence-conventions.md) for transactions and concurrency.

## Module Assignment

- **MUST — Ownership-Based Placement:** Place behavior and data management in the module that owns the corresponding business concept. Follow [Architecture Conventions](architecture.md) for package structure and exposed module APIs.
- **SHOULD — Justified Modules:** Introduce top-level or nested modules when business responsibility and dependency boundaries justify them. Shared infrastructure does not require a new business context by itself.
- **MUST — Project Model Documentation:** Record project-specific context names, aggregate roots, and data ownership in the business specification. Keep state transitions and integration policies there rather than duplicating them in this guide.
