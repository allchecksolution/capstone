# DDD Domain Design

ALLCHECK consists of five Bounded Contexts and one integrated query module.<br>
Detailed business policies and state transitions follow [Business Rules](business-rules.md). This document owns context boundaries, aggregate structure, and data ownership.

## 1. Bounded Contexts and Data Ownership

| Type | Domain / Package | Owned Responsibilities and Data | Related Requirements |
|---|---|---|---|
| BC | Identity and Access Management — `identity` | Administrator accounts, credentials, roles, and MFA; driver Kakao authentication identifiers and account links; authentication sessions and tokens | [FR-01], [FR-13] authentication / [UC-01], [UC-08] authentication |
| BC | Consultation Intake — `reception` | Consultation cases; recordings, STT and AI analysis results and processing statuses; manual entry; administrator review and finalized intake information | [FR-02], [FR-03], [FR-04] / [UC-02] |
| BC | Driver Management — `driver` | Driver personal and business information and verification results; registration applications and review history; approval, rejection, reapplication, and withdrawal; specialties, service areas, equipment, preferences, standby/rest status, and latest location | [FR-06], [FR-10], [FR-13] membership management, [FR-14] / [UC-06], [UC-08] membership management, [UC-09], [UC-14], [UC-15] |
| BC | Service Request Fulfillment — `operations` | Customer service requests; dispatch candidate selection, offers, responses, and delivery results; work ownership, cancellation, redispatch, and administrator follow-up decisions; work progress and revisits; original reports and revision history | [FR-05], [FR-07], [FR-08], [FR-09], [FR-11], [FR-12], [FR-16] history retention / [UC-03], [UC-05], [UC-07], [UC-10], [UC-11], [UC-12], [UC-13] |
| BC | Administrator–Driver Chat — `chat` | Ongoing conversations for each driver, messages, transmission processing results, and conversation access relationships | [FR-15] / [UC-16] |
| Query module | Status, History, and Statistics — `dashboard` | Service request and work status, history, and statistics by period, combining information from each BC | [FR-09], [FR-16] / [UC-04] |

## 2. Module Placement

Each BC is placed in a direct subpackage of `com.allcheck` as a top-level Spring Modulith Application Module.
`operations` remains one BC, with service request, dispatch, and work responsibilities organized into the nested Application Modules `request`, `dispatch`, and `work`.

```text
com.allcheck
├── identity
├── reception
├── driver
├── operations
│   ├── request       # Service request management
│   ├── dispatch      # Dispatch rounds and offers
│   └── work          # Work execution and reporting
├── chat
├── dashboard         # Integrated query module
└── global            # Shared technical code
```

`dashboard` is also managed as a top-level Application Module, but is not a separate BC and does not own source business data updates or have its own business Aggregates.
`global` is excluded from both BCs and Application Modules.

## 3. Key Aggregates by Bounded Context

The following is the initial design of the key Aggregates.

| BC / Nested Module | Aggregate Root | Owned Models and Boundaries |
|---|---|---|
| `identity` | `Account` | Administrator authentication accounts and driver authentication identities and external login links. Driver membership information and registration reviews belong to `driver`. |
| `reception` | `Reception` | Manages consultation materials and analysis results separately from intake information finalized by administrators. |
| `driver` | `Driver` | Manages driver membership information, business registration verification, application and review history, service eligibility, and dispatch preferences. |
| `driver` | `DriverLocation` | Manages each driver's latest location and update timestamp separately from profile and review information. |
| `operations.request` | `ServiceRequest` | Manages the service request lifecycle, the snapshot of finalized intake information, identifiers for the current dispatch and work, and administrator follow-up decision history linked to cancellation records or closed rounds. |
| `operations.dispatch` | `DispatchRound` | Manages dispatch attempts by round and offers, responses, and delivery results for each driver. |
| `operations.work` | `WorkOrder` | Owns the request-to-driver relationship through `requestId` and `driverId`, work progress, revisits, cancellation reason and timestamp, and previous-work links. Historical work records retain their original driver; redispatch creates a new work record. Driver workload is derived from unfinished, non-cancelled work rather than a separate aggregate. |
| `operations.work` | `WorkReport` | Manages the originally submitted report and photos, administrator revision history, and the latest effective content. |
| `chat` | `Conversation` | Manages each driver's ongoing conversation relationship independently of individual service requests. |
| `chat` | `ChatMessage` | Manages message content, senders, transmission identifiers, and processing results separately from the conversation. |

[FR-01]: ../../../docs/requirements/functional-requirements/fr-01-admin-login.md
[FR-02]: ../../../docs/requirements/functional-requirements/fr-02-call-recording-collection-and-stt-transcription.md
[FR-03]: ../../../docs/requirements/functional-requirements/fr-03-ai-consultation-analysis-and-structuring.md
[FR-04]: ../../../docs/requirements/functional-requirements/fr-04-ai-analysis-result-review-and-edit.md
[FR-05]: ../../../docs/requirements/functional-requirements/fr-05-request-registration-and-dispatch-candidate-selection.md
[FR-06]: ../../../docs/requirements/functional-requirements/fr-06-driver-gps-location-collection-and-management.md
[FR-07]: ../../../docs/requirements/functional-requirements/fr-07-dispatch-request-delivery-and-notification.md
[FR-08]: ../../../docs/requirements/functional-requirements/fr-08-dispatch-request-acceptance-and-rejection.md
[FR-09]: ../../../docs/requirements/functional-requirements/fr-09-request-and-work-status-management.md
[FR-10]: ../../../docs/requirements/functional-requirements/fr-10-driver-availability-status-management.md
[FR-11]: ../../../docs/requirements/functional-requirements/fr-11-work-report-registration-and-management.md
[FR-12]: ../../../docs/requirements/functional-requirements/fr-12-dispatch-cancellation-and-redispatch-management.md
[FR-13]: ../../../docs/requirements/functional-requirements/fr-13-driver-registration-and-authentication.md
[FR-14]: ../../../docs/requirements/functional-requirements/fr-14-driver-profile-and-dispatch-criteria-management.md
[FR-15]: ../../../docs/requirements/functional-requirements/fr-15-admin-driver-one-to-one-chat.md
[FR-16]: ../../../docs/requirements/functional-requirements/fr-16-work-history-and-statistics-management.md
[UC-01]: ../../../docs/requirements/use-cases/uc-01-admin-login.md
[UC-02]: ../../../docs/requirements/use-cases/uc-02-ai-reception-review.md
[UC-03]: ../../../docs/requirements/use-cases/uc-03-dispatch-request.md
[UC-04]: ../../../docs/requirements/use-cases/uc-04-dispatch-status-inquiry.md
[UC-05]: ../../../docs/requirements/use-cases/uc-05-work-report-review.md
[UC-06]: ../../../docs/requirements/use-cases/uc-06-driver-registration-approval.md
[UC-07]: ../../../docs/requirements/use-cases/uc-07-dispatch-cancellation-review.md
[UC-08]: ../../../docs/requirements/use-cases/uc-08-driver-registration.md
[UC-09]: ../../../docs/requirements/use-cases/uc-09-driver-availability-management.md
[UC-10]: ../../../docs/requirements/use-cases/uc-10-dispatch-request-response.md
[UC-11]: ../../../docs/requirements/use-cases/uc-11-work-status-update.md
[UC-12]: ../../../docs/requirements/use-cases/uc-12-work-report-submission.md
[UC-13]: ../../../docs/requirements/use-cases/uc-13-dispatch-cancellation-request.md
[UC-14]: ../../../docs/requirements/use-cases/uc-14-driver-profile-settings.md
[UC-15]: ../../../docs/requirements/use-cases/uc-15-driver-account-withdrawal.md
[UC-16]: ../../../docs/requirements/use-cases/uc-16-admin-driver-chat.md
