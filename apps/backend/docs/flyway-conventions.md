
# Flyway Migration Conventions

## Goal

Database migrations aim for **consistent schema change history, deployment stability, and guaranteed data integrity**.

Version database changes through Flyway and apply the same migrations reproducibly across environments.

---

## Migration Organization

- **MUST — Migration Naming:** Use the `V{version}__{description}.sql` format, and write descriptions using lowercase English letters and underscores (`_`) to convey the purpose of the change.
- **MUST — Unique Migration Version:** Each Versioned Migration uses a unique version, managed with version conflicts and application order in mind.
- **MUST — Centralized Migration Files:** Manage migration files in the Spring Boot module's `src/main/resources/db/migration/` directory and include them in Git.

---

## Migration Lifecycle

- **MUST — Immutable Applied Migrations:** Do not modify, delete, or rename Versioned Migrations applied to shared or deployed environments; write additional changes as new migrations.
- **MUST — Flyway as Schema Source of Truth:** Manage schema changes through Flyway, and prohibit arbitrary manual changes to shared or production databases and Hibernate's automatic schema changes (`create`, `create-drop`, `update`). Include corresponding Entity mapping updates in the same PR as schema changes.

---

## Migration Safety

- **MUST — Safe Schema Changes:** When changing the schema, review existing data, integrity constraints, and application compatibility, and apply destructive changes only after checking the possibility of data loss.
- **SHOULD — Backward-Compatible Changes:** When old and new application versions may coexist, first consider phased schema changes such as Expand-Contract.
- **SHOULD — Safe Data Migration:** For large-scale data changes, review execution time, locking, transaction support, the possibility of partial application, and recovery plans.
- **MUST — Production Safety:** Do not run destructive reset commands such as `flyway clean` on production or shared databases.
- **MUST — Migration Failure Handling:** When a migration fails, check the database state and cause before recovery, and do not use `repair` to bypass validation errors.

---

## Migration Review

- **MUST — Change Review:** Review migration order, schema compatibility, constraints, and data impact before deployment.
- **SHOULD — History Validation:** Use Flyway validation to check applied migration versions and checksums before applying pending migrations.
