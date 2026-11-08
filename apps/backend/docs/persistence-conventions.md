# Persistence Conventions

## Goal

Persistence management aims for **data consistency, clear transaction boundaries, efficient data access, and concurrency safety**.
Manage persistence using Spring Data JPA and Hibernate,
avoid unnecessary database access and complex persistence abstractions, and apply persistence and concurrency control strategies suited to business requirements.

---

## Transaction Management

- **MUST — Transaction Boundaries:** As a rule, manage transactions in Application Services, and define them as units that ensure data consistency within a single business operation.
- **MUST — Read-Only Transactions:** For Application Services that use transactions, apply `@Transactional(readOnly = true)` at the class level by default, and explicitly override it with `@Transactional` on methods that modify data.
- **SHOULD — Minimize Transaction Scope:** Limit transactions to the minimum scope needed to ensure data consistency, and exclude long-running external API calls and unnecessary computations.
- **SHOULD — Reliable Event Publication:** When a database change requires asynchronous event publication, persist the publication intent in the same transaction, for example with an outbox, and make consumers idempotent.

---

## JPA Entity Management

- **SHOULD — Entity Relationships:** Define relationships only when required by business needs, and avoid unnecessary bidirectional relationships.
- **SHOULD — Lazy Loading:** Prefer lazy loading for Entity relationships, and explicitly fetch the required data according to the purpose of the query.
- **SHOULD — Persistence Context Awareness:** Consider the persistence context lifecycle and Entity state, and do not rely on lazy loading outside transactions.

---

## Repository Management

- **SHOULD — Spring Data JPA:** Prefer Spring Data JPA features for basic CRUD operations and simple queries.
- **SHOULD — Explicit Queries:** Choose an appropriate query approach, such as JPQL, QueryDSL, or Native Query, when complex queries or performance optimization are needed.
- **SHOULD — Avoid Unnecessary Abstraction:** Do not unnecessarily split Repositories into additional interfaces or implementations without a clear need.

---

## Query Performance

- **SHOULD — Prevent N+1 Problems:** Check for N+1 problems when querying related data, and apply appropriate strategies such as Fetch Join, EntityGraph, or Batch Fetching when needed.
- **SHOULD — Efficient Data Retrieval:** Retrieve only the data needed, and consider efficient query approaches such as DTO Projection for simple queries.
- **SHOULD — Pagination:** Apply an appropriate pagination strategy to APIs that retrieve large amounts of data.
- **SHOULD — Index Awareness:** Consider indexes on columns used in query conditions, sorting, and joins, and analyze performance problems using execution plans.
- **SHOULD — Avoid Unnecessary Queries:** Avoid unnecessary database queries inside loops and duplicate queries.
- **MAY — Bulk Operations:** Consider Bulk Update/Delete when large-scale data changes are needed, while being mindful of inconsistencies with the persistence context and Entity lifecycle behavior.

---

## Concurrency Control

- **SHOULD — Appropriate Concurrency Control:** Based on the likelihood of concurrency conflicts and business requirements, choose appropriate concurrency control mechanisms such as conditional UPDATE, optimistic locking, or pessimistic locking,
and minimize scope and contention when using locks.
- **SHOULD — Conflict Handling:** When concurrency conflicts occur, apply an appropriate handling strategy, such as failure responses or limited retries, according to business requirements.
- **MAY — Isolation Levels:** Prefer the default transaction isolation level, and adjust it when needed to meet data consistency requirements.
- **MUST — Database Invariants:** Enforce domain-defined uniqueness and other persisted invariants with database constraints or atomic conditional writes, independently of idempotency-key retention. Application-level prechecks alone are insufficient under concurrency.

---

## Idempotency

- **MUST — Atomic Retry Records:** When an API uses an idempotency key, persist its scope, request fingerprint, and successful result consistently with the business mutation. Repeated matching requests reuse the successful result; reused keys with different inputs and concurrent in-flight requests must not execute the mutation again.
