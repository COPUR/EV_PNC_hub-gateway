# EV_PNC_hub-gateway

Roaming hub adapters behind canonical ports: Hubject (OICP, OPCP), GIREVE (OCPI 2.2.1, PNCP), e-clearing.net (ECHX, phase 5). One deployable per hub with its own mTLS identity and egress IP.

Part of the **EV_PNC** platform: a neutral eMSP integration layer for Turkey-first, Europe-ready Plug&Charge, navigation and payments. The platform is neither a CPO nor a payment institution.

> Plug in, charging starts, payment completes in the background.

## Scope

- hub-core: profile loader, 2.3.0 to 2.2.1 compatibility layer, routing and tracing, error mapping, throttling
- hub-hubject, hub-gireve: authorize, remote start/stop, CDR, EVSE data, PNC contract provisioning (implements the plugcharge port)
- hub-echx: created in phase 5 (ADR-0004), mock-first
- Calls policy API and consent decision SDK before any token leaves the country
- Certification work: Hubject QA/PROD protocol, GIREVE eMSP 2.2.1 test list

## Tech stack

Kotlin, Spring Boot 3

## Packaging

- Published components: hub-core, hub-hubject, hub-gireve, hub-echx
- Depends on: EV_PNC_hub-contracts, EV_PNC_ocpi-model, EV_PNC_plugcharge, EV_PNC_policy-engine, EV_PnC_Consent_contracts
- Depended on by: no repo
- Dependency rules: no cycles (ADP); depend towards more stable components (SDP); published `*-api` components hold abstractions, deployables hold concrete code (SAP). CI runs `tools/packaging_check.py --strict` from `EV_PNC_docs`.
- Owner: Backend agent · Wave 1 (now, before the 2026-12-07 gate) · Visibility: public

## Proposed layout

```
hub-core/
hub-hubject/
hub-gireve/
hub-echx/
```

## Related

- Plan: `repo-plan/v2/README.md` (project files); consent repos: `EV_PnC_Consent_*` (`repo-plan/consent/README.md`)
- Docs: [EV_PNC_docs](https://github.com/COPUR/EV_PNC_docs)

## Status

Scaffold only. Not production ready.

## License

Apache-2.0 (proposed)
