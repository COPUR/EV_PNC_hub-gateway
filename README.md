# EV_PNC_hub-gateway

Roaming hub adapters behind canonical ports: Hubject (OICP 2.3, OPCP), GIREVE (OCPI 2.2.1, PNCP), e-clearing.net (ECHX, phase 5). One module per hub; the platform sees one model.

Part of the **EV_PNC** platform: a neutral eMSP integration layer for Turkey-first, Europe-ready Plug&Charge, navigation and payments. The platform is neither a CPO nor a payment institution.

> Plug in, charging starts, payment completes in the background.

Hubs are future integration points for the Europe expansion. This code is built and tested from published specifications only (the contracts in [EV_PNC_hub-contracts](https://github.com/COPUR/EV_PNC_hub-contracts)); no hub account, contract or live system is involved. Design: [ADR-0018](docs/adr/0018-emsp-hub-adapter-framework.md).

## Modules

| Module | Content |
|---|---|
| `hub-core` | Ports (`AuthorizationPolicy`, `CdrSink`, `RemoteSessionPort`, `HubTransport`), `HubAdapter` and `HubRegistry` with the capability matrix, failure classes, resilience (time budget, guarded policy, throttler, backoff, idempotent CDR intake), profile loader, the OCPI 2.3.0 to 2.2.1 compatibility layer and routing headers. Test fixtures include `HubAdapterContract`. |
| `hub-hubject` | OICP 2.3 online EMP: authorize start with policy hook and the 10 s budget, StatusCode catalogue and decision mapping, CDR receiver, remote start/stop client. |
| `hub-gireve` | OCPI 2.2.1 IOP partner: real-time token authorize, CDR intake with remark pricing, START/STOP_SESSION, token PUT with UID rules. |
| `hub-echx` | Created in phase 5 (ADR-0004); see [adding a hub](docs/adding-a-hub.md). |

## Capabilities today

| Capability | Hubject | GIREVE |
|---|---|---|
| Online authorize | yes | yes |
| Remote start / stop | yes | yes |
| CDR receive (idempotent, answer first) | yes | yes |
| Token push | no (online EMP) | yes (PUT, no EMAID) |
| CDR reconciliation, EVSE data and status, notifications | no | n/a |
| Registration, locations, tariffs, sessions | n/a | no |
| Plug&Charge contracts (OPCP, PNCP) | no | no |

## Build and test

```bash
make test        # fetches the pinned contracts, checks the vendored profiles, runs all tests
make sync-check  # only the profile check
```

Needs JDK 21 and Gradle 8.14. `contracts.lock` pins the commit of EV_PNC_hub-contracts; profiles are vendored under each module's `src/main/resources/profiles/` and CI fails when they differ from that commit.

## Packaging

- Published components: hub-core, hub-hubject, hub-gireve, hub-echx (later)
- Depends on: EV_PNC_hub-contracts (data, pinned), EV_PNC_ocpi-model (canonical JSON shape; no code dependency yet), EV_PNC_plugcharge, EV_PNC_policy-engine, EV_PnC_Consent_contracts (through the policy hook)
- Depended on by: no repo
- Dependency rules: no cycles (ADP); depend towards more stable components (SDP); abstractions in hub-core, concrete code in the hub modules (SAP). CI runs `tools/packaging_check.py --strict` from `EV_PNC_docs` once the check is wired.
- Owner: Backend agent · Wave 1 · Visibility: public

## Status

Framework and the first adapters are in place and tested against mocks and pinned contracts. Not production ready: no HTTP binding, transport, persistence or Kafka yet, and no hub certification (it needs a contract). Open work is listed in ADR-0018.

## License

Apache-2.0
