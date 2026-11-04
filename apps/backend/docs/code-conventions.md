# Code Conventions

## Goal

Kotlin code in ALLCHECK Backend prioritizes readability, maintainability, and encapsulation.

The following rules are **recommended guidelines** and should not be applied mechanically in every situation.
Reasonable exceptions are allowed when following a rule would make the code more complex or conflict with common Kotlin and Spring/JPA usage.

---

## Object-Oriented Design

- **SHOULD — One Method, One Responsibility:** Write methods with a single, clear responsibility.
- **SHOULD — Small Classes:** Keep classes small and focused on cohesive responsibilities, while avoiding splits made solely to reduce size.
- **SHOULD — Pack All Primitives & Strings:** Consider wrapping primitive values and strings in value objects when they represent meaningful domain concepts or have validation rules.
- **SHOULD — Pack Collections:** Consider introducing a first-class collection when the collection itself has domain rules or behavior.
- **SHOULD — No Setter:** Avoid indiscriminate state exposure and modification, and prefer meaningful domain methods. Allow accessors needed for serialization, query models, JPA, and similar purposes.
- **MAY — Instance Variables ≤ 3:** Consider separating responsibilities when a class has excessive state. Do not treat 3 fields as an absolute limit.

---

## Methods & Control Flow

- **SHOULD — One Method, One Indent:** Reduce nesting and use method extraction, guard clauses, and similar techniques. Do not enforce a single indentation level as an absolute rule.
- **SHOULD — No Else & Switch:** Consider early return first for simple branches. However, allow clear and readable branching expressions such as Kotlin's `when` expression.
- **SHOULD — Method Parameters ≤ 3:** When there are many parameters, consider grouping related values or separating responsibilities. Avoid creating unnecessary objects solely to meet a parameter count.
- **MAY — One Line, One Dot:** Reduce long call chains and excessive navigation. Allow natural chaining such as Kotlin safe calls, collection operations, and DSLs.

---

## Kotlin Language Guidelines

- **SHOULD — Data Class Usage:** Use `data class` for DTOs and value objects, but use it cautiously for JPA Entities, considering equality and persistence management issues.
- **SHOULD — Null Safety:** Use Nullable types, safe calls (`?.`), the Elvis operator (`?:`), and similar features, and avoid unnecessary use of `!!`.
- **SHOULD — Explicit Nullability:** Clearly express through types and validation whether a value may be absent or must be present.

---

## Data Safety

- **SHOULD — No Hardcoding:** Manage constants and configuration values that are likely to change or whose meaning is unclear in appropriate locations, such as constants, enums, or configuration. Do not unconditionally extract self-explanatory literals.
- **SHOULD — Immutable Collection Exposure:** Do not directly expose internal mutable collections. Use defensive copies or read-only views when needed. Note that read-only types such as Kotlin's `List` do not guarantee the immutability of the underlying collection.

---


## Code Style & Formatting

- **MUST — Official Kotlin Style:** Follow JetBrains' official Kotlin Coding Conventions as the default style guide.
- **MUST — Consistent Formatting:** Follow the project's `.editorconfig` and ktlint rules for indentation, spacing, line breaks, braces, and import formatting.
- **MUST — Automated Style Verification:** Kotlin code must pass ktlint checks, and style violations must be resolved before committing or merging a PR.

---

## Application Guidelines

- When rules conflict, prioritize **correctness, domain invariants, readability, and maintainability**.
- Do not create unnecessary wrappers, classes, methods, or abstractions just to satisfy a rule.
- Consider consistency with existing code and the project architecture, and avoid sweeping mechanical refactoring.
- Do not change code unnecessarily solely because it violates a SHOULD/MAY rule.
