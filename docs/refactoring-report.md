# Refactoring: put the Hubject and GIREVE surfaces behind canonical ports (procedure: refactor-legacy)

**Date:** 2026-10-27 · **Scope:** `EV_PNC_hub-gateway` (new), `EV_PNC_hub-contracts` (specs, profiles)

Honest framing first. This repository had no code, so no existing method was cleaned up. The "legacy" here is the vendor surface we cannot change and must not break: the OICP 2.3 contract Hubject publishes, the OCPI 2.2.1 deviations in GIREVE's guide, and the project's own research (122 requirements). The legacy-change procedure still applies one level up: cover first, change second, small steps, surprises recorded and not silently fixed.

## Change points and test points

| Change point | What must change | Test point (where the effect is observable) |
|---|---|---|
| Hubject authorize path | platform policy answers in OICP terms inside the 10 s window | `AuthorizeStartResponse` returned by `HubjectAuthorizeHandler` |
| Hubject CDR path | a CDR is never lost, never stored twice | `OicpAcknowledgement` and what `CdrSink` receives |
| Hubject remote start/stop | commands become requests; outcomes become results | requests recorded by `FakeTransport`; `RemoteResult` |
| OCPI 2.3.0 to 2.2.1 for GIREVE | one conversion layer for every OCPI 2.2.1 hub | JSON out of `Ocpi221Compat` against golden payloads |
| GIREVE authorize, CDR, commands, token push | same ports as Hubject | OCPI envelope, `HubCdr`, recorded requests |
| Contract drift | a vendor file or table change must fail in review | `OicpContractTest`, `ConversionTableTest`, profile sync check |

Pinch point: `HubAdapterContract`, one class every adapter must pass.

## Dependency-breaking steps

No dependency had to be broken in existing code; the seams were built in from the first line, using the same techniques.

| Technique | Where | Why |
|---|---|---|
| Parameterize Constructor | `Clock`, executor, `HubTransport`, `IdempotencyStore`, `CdrSink`, `AuthorizationPolicy` in every handler | time, threads, HTTP and storage are the only side effects; all replaceable in a test |
| Extract Interface | `HubTransport`, `AuthorizationPolicy`, `CdrSink`, `RemoteSessionPort`, `HubAdapter` | the platform depends on ports, not on a hub |
| Adapt Parameter | requests built as `HttpRequestSpec` data | no socket in adapter code; a live system cannot be reached by accident |
| Anti-corruption layer | `HubjectMapping`, `Ocpi221Compat` | the platform never sees OICP or a hub deviation |
| Strangler path | `HubRegistry` | hubs are additive; the bilateral OCPI path in `EV_PNC_ocpi-emsp` is untouched and moves no traffic |

## Characterization tests added

136 Kotlin tests (hub-core 44, hub-hubject 51, hub-gireve 41) and 17 Python tests in hub-contracts.

| Pinned behaviour | Test |
|---|---|
| Our request and response models contain only names the pinned OICP contract has, and every required contract field is modelled (authorize start, remote start/stop, CDR) | `OicpContractTest` |
| Our StatusCode catalogue equals the pinned contract's 31-row table, codes and descriptions | `OicpContractTest` |
| The decision to StatusCode table uses only official codes | `AuthorizeStatusMappingTest`, `tests/test_specs.py` |
| Every rule in the OCPI 2.3.0 to 2.2.1 table is handled or marked as needing no transform; a new or removed rule fails the build and adapter start-up | `ConversionTableTest` |
| GIREVE-shaped 2.2.1 payloads become the canonical shape (prices, tax amounts, eMAID tokens, tariff limits, location defaults, ids moved to side data, credentials) and back | `Ocpi221CompatTest` (golden JSON) |
| Rate limits, windows, party ids, retry schedule, wait statuses, unsupported commands come from the profiles, not constants | `HubjectProfileTest`, `GireveProfileTest` |
| Authorize: every refusal reason maps on every channel; slow or failing policy gets 021 inside the window; invalid UID is 102 without asking the policy; a QR PIN never reaches an answer or a log line | `HubjectAuthorizeHandlerTest`, `ModelTest` |
| CDR: stored once, resend acknowledged, failing sink parks and replays, unreadable timestamps kept under `extensions.oicp`, +03:00 stored as instants | `CdrIntakeTest`, `HubjectCdrHandlerTest`, `GireveCdrHandlerTest` |
| Remote start/stop: request shape and path, every failure code to a driver-facing reason, 017 is configuration, timeout is unknown state, 429 and 5xx retryable, throttling | `HubjectRemoteClientTest`, `GireveCommandClientTest` |
| Traceability: no broken contract or feature reference; every requirement is traced | `tests/test_specs.py` |

### Surprises (recorded as questions, not fixed)

1. The pinned OpenAPI lists `SessionID` as required on remote start; the OICP EMP services text says the EMP must not fill it. We follow the text. Pinned in `OicpContractTest` and `HubjectRemoteClientTest`.
2. The pinned remote stop path names its parameter `externalID`; the integration guide fills it with the ProviderID. We follow the guide.
3. Hubject's integration guide lists status codes 401, 404 and 500 that are not in the specification's list. Not in our catalogue; unknown codes are handled generically.
4. `PullEVSEData` is limited to 100 per minute in the Acceptable Use Policy and 25 per minute on the support page. The profile records both; no code reads the pull limit yet (the pull is not built).
5. The CPO remote window is 40 s (support) or 60 s (guide). The client timeout is the larger plus 5 s, 65 s.
6. "Zero-padded" RFID UIDs in GIREVE's guide: read as "leading zeros kept"; a 4-byte UID is not widened to 7 bytes. Unconfirmed.
7. The conversion table asks for `tax_included=UNKNOWN`; the canonical enum has no such value. Interim `N/A` plus a side-data flag.
8. GIREVE marks `hubclientinfo` as not implemented in one place and lists its use cases in another; `FromIOP` and `ToIOP` are not defined. Both stay in the profile as ambiguities, and the module is refused.
9. The pinned QR identification carries `PIN` and `HashedPIN` (Bcrypt); the earlier analysis only mentioned the clear PIN. Both are treated as secrets; verifying a hashed PIN is the policy engine's job and is not built.
10. `Description` in a StatusCode may only hold defined values; the pinned schema's `maximum: 200` is a string-length hint written on a string field. Treated as a length bound.

## Refactoring steps

All behaviour-preserving, tests green after each:

1. Core model and ports, no behaviour.
2. Resilience primitives (budget, guarded policy, throttler, backoff, idempotent intake) with tests.
3. Profile document and typed views; vendored profiles per module.
4. `Ocpi221Compat` and the coverage check.
5. Hubject: wire model, catalogue, mapping, handlers, adapter.
6. Decision mapping and `Description` rule extracted to data after the first handler test showed an invalid UID reaching the wrong code (103 instead of 102); fixed by sending format failures through TOKEN_UNKNOWN. This is the one place a first guess was wrong and a test caught it.
7. GIREVE: profile view, UID rule, handlers, adapter.
8. Test fakes (`FakeTransport`, `RecordingSink`, `MutableClock`) moved to the shared test fixtures once the second adapter needed them.

## Verification

```bash
tools/fetch-contracts.sh && tools/sync-contracts.sh --check   # vendored profiles equal the pinned contracts
CONTRACTS_DIR=$PWD/.contracts gradle build --no-daemon        # 136 tests, warnings are errors
uv run --with pyyaml python -m unittest discover -s tests     # in hub-contracts: 17 tests
```

Result on 2026-10-27: all green. Not verified: JDK 25, Spring Boot 4, any call to a real hub (none is made or possible), Gradle 9.

## Follow-ups

- Questions that stay open and no longer have a channel to the hubs: Hubject Q-ERR-1..9, GIREVE error codes, timeouts and webhook retries, the PNCP OpenAPI export, the meaning of FromIOP and ToIOP, whether PNCP can be bought without BOOST!. They stay `unknown` or `inference` in the profiles.
- Debt left on purpose: in-memory idempotency and parking (needs the outbox, ADR-0008, ADR-0011); mTLS `HubTransport`; Spring Boot wiring; EVSE data, status, notifications and reconciliation for Hubject; registration, locations, tariffs and sessions for GIREVE; OPCP and PNCP clients; mock hubs in `EV_PNC_simulator`.
- ocpi-model: add `UNKNOWN` to `TaxIncluded` and an `extensions` slot on Location, EVSE, Connector and Tariff.
