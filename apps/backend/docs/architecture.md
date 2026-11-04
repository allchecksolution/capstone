# Architecture

## Goal

The ALLCHECK Backend codebase adopts **a Modular Monolith with a domain-oriented package structure
and a 4-layer Layered Architecture that apply the core design principles of DDD (Domain-Driven Design)**.
Business domains are organized into cohesive application modules, with clearly separated layer responsibilities within each module.

---

### Core Design Principles

1. **Domain-Oriented Packaging**
    - Organize packages around business domains rather than technical layers.
    - Place related features and code within the same domain package.

2. **Separation of Concerns**
    - Separate the responsibilities of Presentation, Application, Domain, and Infrastructure.
    - Each layer contains only the logic that falls within its responsibilities.

3. **Domain-Driven Design**
    - Encapsulate business rules and state change logic in domain models whenever possible.
    - Domain models manage their own state.
    - Introduce DDD concepts selectively as needed instead of applying all DDD patterns uniformly.

4. **Pragmatic Architecture**
    - Minimize unnecessary abstractions and conversion code between layers.
    - Do not create Port, Adapter, or UseCase interfaces without a clear need.
    - Consider additional abstractions only when complexity increases or technical separation is required.

---

### Modular Architecture

- **MUST — Modular Monolith:** Structure the backend as a modular monolith, organizing business functionality into cohesive domain-oriented modules within a single Spring Boot application.
- **MUST — Module Boundaries:** Define clear responsibilities and boundaries for each module, and prevent direct access to other modules' internal implementations.
- **MUST — Module Dependencies:** Allow inter-module dependencies only through explicitly exposed APIs, and prevent circular dependencies between modules.
- **SHOULD — Module Communication:** Use explicit module APIs for direct collaboration, and prefer application events for loosely coupled business reactions when appropriate.
- **SHOULD — Module Documentation:** Generate and maintain application module diagrams and documentation using Spring Modulith's `Documenter`.

---

### Dependency Direction

Code dependencies between layers follow these directions.

- Presentation → Application → Domain
- Application → Infrastructure
- Infrastructure → Domain
- Application → Presentation is allowed only for returning Response DTOs.

---

### Package Structure

```text
com.allcheck
├── BackendApplication.kt
│
├── {domain}/                         # Spring Modulith Application Module
│   ├── {Domain}Operations.kt         # Public Module API (when needed)
│   ├── ModuleMetadata.kt             # Required @ApplicationModule & @PackageInfo
│   │
│   ├── presentation/
│   │   ├── {Domain}Controller.kt
│   │   └── dto/
│   │       ├── request/
│   │       │   └── {UseCase}Request.kt
│   │       └── response/
│   │           └── {UseCase}Response.kt
│   │
│   ├── application/
│   │   ├── {UseCase}Service.kt
│   │   └── ...
│   │
│   ├── domain/
│   │   ├── entity/
│   │   │   └── {AggregateRoot}.kt
│   │   ├── service/
│   │   │   └── {Domain}Service.kt
│   │   └── vo/
│   │       └── {ValueObject}.kt
│   │
│   └── infrastructure/
│       ├── repository/
│       │   └── {AggregateRoot}Repository.kt
│       └── ...
│
└── global/                           # Shared technical code, not an Application Module
    ├── config/
    ├── security/
    ├── exception/
    └── ...
```

### Package Rules

- Implement new features in the corresponding business domain package.
- Before creating a new top-level domain package, check whether it belongs within an existing domain boundary.
- Create a package only when code for that layer is actually needed.
- Expose inter-module APIs through the module's root package, and use Spring Modulith Named Interfaces when additional packages need to be exposed.
- Keep internal implementations within module subpackages and prevent other modules from depending on them.
- Keep shared technical configuration and cross-cutting concerns in the `global` package, outside business application modules. Do not place domain-specific business logic in `global`, and prevent dependencies on business module internals.
- Explicitly register business application modules using Spring Modulith's `@ApplicationModule`, and exclude the `global` package from application module detection.
- Even when shared concepts are found across domains, do not extract them into a common module without sufficient justification.
- Expose only explicitly public contract types through module APIs, and do not expose internal Domain Entities or implementation-specific types in method parameters or return values.

---

### Layer Responsibilities

#### 1. Presentation Layer

- Controllers do not directly reference Repositories or Domain Entities.

#### 2. Application Layer

- The Application Layer calls Domain Entities and Domain Services, and may directly access Repositories and external API Clients.
- Do not duplicate business rules that domain objects can handle.
- Response DTOs from the Presentation Layer may be returned directly. However, introduce UseCaseResult when clear separation between layers is required.
- Do not return Domain Entities directly to Controllers.
- As a rule, manage transaction boundaries in Application Services.

#### 3. Domain Layer

- Encapsulate business rules and state changes in domain models, and maintain domain invariants.
- Change Entity state through meaningful domain methods.
- Create a Domain Service only when business logic that does not belong to a specific Entity is needed.
- Do not depend on other application technologies or layers, except for JPA mapping dependencies.
- Use a unified JPA Entity and Domain Entity model.
- JPA mapping annotations are allowed in the Domain Layer.
- Do not create separate Persistence Entities or Mappers without a clear need.

#### 4. Infrastructure Layer

- Handle Repositories and integration with external systems.
- Repositories focus on data access and persistence, and do not implement business use cases.
- Prevent data structures from external systems from unnecessarily propagating into internal domain models.

---

### Inter-Domain Dependencies

- Clearly define data ownership and business responsibilities for each domain.
- Introduce Domain Events only when there is a clear business need for event-driven collaboration.
