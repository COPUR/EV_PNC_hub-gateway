# ADR-0018: eMSP hub adapter framework (Hubject and GIREVE as future integration points)

**Status:** proposed · **Date:** 2026-10-27 · **Revision:** 0.1 · Accepted by the product owner on the Kanban card for this ADR.

## Context

On 2026-10-27 the product owner decided that the first emails to Hubject and GIREVE (TR-13, TR-14) are not sent. The hubs stay on the roadmap as integration points for the Europe expansion, so the platform needs a framework in which a hub is an adapter that can be built, tested and kept current without a contract, an account or any contact with the hub. "Reverse engineer" is read narrowly: derive protocols, flows and contracts from published specifications, public SDKs and public implementation guides. No live hub system is probed, scraped or called.

Already decided and kept: ADR-0001 (translate at the edge into one canonical OCPI 2.3.0-shaped model, hub rules as data), ADR-0002 (Hubject as an online EMP), ADR-0003 (one Plug&Charge port, two ecosystem adapters), ADR-0004 (ECHX is OCPI only, phase 5), ADR-0005 (asymmetric parallel hubs), ADR-0006 (guest pricing), the Kafka backend stack ADR-0008 to ADR-0015 (Kotlin, Spring Boot, JDK 25), and the research in `EV_PNC_hub-contracts` (pinned OICP 2.3 and OPCP files, profiles, 122 requirements).

Hubject speaks OICP 2.3 (not OCPI) and Plug&Charge over OPCP 1.0.0. GIREVE speaks OCPI 2.2.1 with its own deviations and Plug&Charge over PNCP 1.2.0. The platform core speaks OCPI 2.3.0.

## Decision

1. **Ports and adapters.** `hub-core` defines what the platform needs from a hub and what a hub needs from the platform; each hub is one module that implements it. The platform never sees OICP or a GIREVE deviation.
   - Driven by the platform (inbound ports it implements): `AuthorizationPolicy` (price threshold, operator lists, consent), `CdrSink` (payments, invoicing).
   - Offered by an adapter (outbound): `RemoteSessionPort` (start, stop), plus adapter-specific inbound entry points that a web layer binds to the hub's URLs.
   - Declared by an adapter: `HubCapability` set. The registry reports the matrix, so a gap shows in a health endpoint and not in a driver's failed session. An adapter claims only what it built.
2. **Anti-corruption layer per hub.** Hubject: `HubjectMapping` (identification variants, CDR) and an OICP wire model validated against the pinned contract. GIREVE and later ECHX: the shared `Ocpi221Compat` layer in `hub-core`, which converts OCPI JSON between the canonical 2.3.0 shape and 2.2.1 as a hub speaks it. Facts with no canonical home are not dropped: they travel under `extensions.<hub>` (CDR, session, token) or as explicit side data (location, tariff, credentials).
3. **Hub rules are data.** Timeouts, rate limits, party ids, unsupported commands, retry schedules and the decision to StatusCode table are read from profiles in `EV_PNC_hub-contracts` through typed views. The gateway vendors the profiles per module and CI compares them with the commit in `contracts.lock`. A change in a hub's rules is a data change with a diff to review.
4. **Seams for everything with a side effect.** `HubTransport` (HTTP, mTLS), `Clock`, the policy executor, `IdempotencyStore`, `CdrSink`. Adapters build requests as data and never open a socket, so every flow runs in a unit test and nothing can reach a live system by accident.
5. **Failure semantics are decided once.** `FailureClass` (retryable, non-retryable, configuration, unknown state) is the only vocabulary adapters throw. Examples: StatusCode 017 is a configuration error and is not retried; a remote start that times out is an unknown state, not a failure; HTTP 429 and GIREVE's 425 are retryable; OCPI 2xxx is non-retryable, 3xxx retryable.
6. **Time budgets are enforced, not hoped for.** The Hubject authorize answer must leave within the 10 s window (profile) minus a safety margin; `GuardedPolicy` abandons a slow policy and the adapter answers NotAuthorized with the configured fallback (021), because Hubject treats a silent EMP as NotAuthorized anyway.
7. **Answer first, never lose a CDR.** CDR intake acknowledges positively as soon as the record is stored or parked, deduplicates by (hub, session id) and replays parked records. Hubject never resends a failed CDR.
8. **Characterization before change.** The pinned vendor contract is the legacy surface we cannot edit. Tests compare our wire model and status-code catalogue with the pinned files, and `ConversionTable.verifyCovered` fails (also at adapter start-up) when the vendored conversion table has a rule the code does not handle. A contract bump that moves anything fails in review.
9. **Framework conformance test.** `HubAdapterContract` (test fixtures of `hub-core`) is subclassed by every adapter; a new hub joins by passing it first. See `docs/adding-a-hub.md`.
10. **Runtime wiring is a separate step.** The modules are plain Kotlin libraries (kotlinx.serialization, SnakeYAML). The Spring Boot application, the mTLS `HubTransport`, persistence and Kafka publishing are cards, not part of this ADR (ADR-0014: golden-path template, not a shared library).

## Decisions inside the decision

| Topic | Choice | Why |
|---|---|---|
| `tax_included` when a 2.2.1 tariff is silent | emit `N/A` and record side data `tax_included=defaulted` | The conversion table asks for UNKNOWN, which the canonical enum (YES, NO, N/A) lacks. `N/A` is the closest legal value; ocpi-model should add UNKNOWN (follow-up), then the fallback is one config value. |
| Remote start `SessionID` | not sent | The pinned OpenAPI lists it as required; the OICP EMP services text says an EMP must not fill it (Hubject assigns it). We follow the text and pin the conflict in a test. |
| Remote stop path | `/providers/{ProviderID}/…` | The pinned file names the parameter `externalID`; the leading integration guide fills it with the ProviderID. |
| Hubject QR and remote identification token type | `APP_USER` with the EvcoID as uid and contract id | Inference; the policy engine treats them as app-held contracts. Plug&Charge maps to `EMAID`. |
| Authorize refusal codes | table in `hubject-authorize-statuscode.mapping.yaml`, confidence inference | OICP does not say which refusal uses which code (only 102 is labelled "sent by EMP"). Q-ERR-1 is not asked now. |
| GIREVE authorize budget | 8 s, configuration | GIREVE's wait is not published; the number is an assumption. |
| JDK | build and CI on JDK 21 | ADR-0012 asks for JDK 25. Gradle 8.14 runs on Java up to 24; moving to JDK 25 means Gradle 9.1 or later. Bytecode target is 21 and runs on 25. |

## Reading ADR-0005 and the gate now

ADR-0005 stays as written. Its engineering order (shared layer, then OICP adapter, then PNCP client) is what this framework follows. Its commercial track (Hubject talks, GIREVE PNCP pre-production, contracts after the 2026-12-07 gate) depended on the hub replies that will not be requested. Proposed reading, for the product owner to confirm on the gate card: without hub evidence K1-H, K2, K5 (hub side), K6 and K7 stay "unknown" and decision rule 5 applies at the gate, so the pilot runs hub-less (option E) and the hubs wait for the Europe phase. The impact analysis is in `kanban/hub-emails-dropped-impact.md` (project files).

## Consequences

- The Hubject QA end-to-end protocol, the live test and every `@sandbox` scenario stay blocked. They need a contract and a Provider ID; nothing in this framework can replace them. What the framework gives is that the day a contract exists, the adapter is already tested against the public contract and only certification remains.
- Behaviour that the public documents leave open stays open and visible: profile entries tagged `unknown` and `@pending-confirmation` scenarios keep their inferred expectations. When a hub is contacted later, the answers change data, not code.
- PNCP (GIREVE) cannot be built beyond the guide: the OpenAPI export is not public. OPCP (Hubject) can be built from the public OpenAPI files; both belong to later cards.
- Parked CDRs and idempotency keys are in memory in this step. Durable storage and the Kafka outbox follow ADR-0008 and ADR-0011.

## Delivered here and cards still open

| Card | State |
|---|---|
| H-02 profile loader and OCPI 2.3.0 to 2.2.1 compatibility layer | delivered in `hub-core` (token, session, CDR, tariff, location, credentials, START_SESSION; routing headers; coverage check) |
| H-05 Hubject authorize with policy hook and StatusCode catalogue | delivered in `hub-hubject` |
| H-06 remote start/stop, CDR receiver, notifications and reconciliation | remote start/stop and CDR receiver delivered; notifications and `GetChargeDetailRecords` reconciliation open |
| H-07 EVSE data pull, status mapping, throttler | throttler delivered; pulls open |
| GIREVE OCPI adapter (H-14, phase 5) | authorize, CDR intake, START/STOP_SESSION and token PUT delivered ahead of time; registration (FromIOP), locations, tariffs, sessions open |
| H-03, H-04, H-16 mock hubs | open in `EV_PNC_simulator`; these adapters are testable without them |
| H-08, H-11, H-12 OPCP and PNCP clients | open |
| mTLS `HubTransport`, Spring Boot application, persistence, Kafka publishing | open |
| ocpi-model: UNKNOWN in `TaxIncluded`, `extensions` on Location, EVSE, Connector and Tariff | open follow-up on `EV_PNC_ocpi-model` |
| Move to JDK 25 | needs Gradle 9.1 or later |

Sources: `EV_PNC_hub-contracts` specs and profiles at the commit in `contracts.lock`; OICP 2.3 EMP OpenAPI pinned at oicp-emp-2.3-api-doc 9a773b3; OCPI 2.3.0 (COPUR/ocpi).
