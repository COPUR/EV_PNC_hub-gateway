# hub-core (H-02)

The hub-independent part of the gateway: a typed hub profile and the OCPI 2.3.0 (canonical) to 2.2.1 compatibility
layer. It is a library with no HTTP stack, so the gateway service, the simulator checks and later the ECHX adapter
(TR-21) can all use it. ADR-0001: translate at the edge, hub rules as data.

```bash
make contracts     # fetch the pinned EV_PNC_hub-contracts commit into .contracts/ (see contracts.lock)
make test          # gradle test
```

Status: **assumptions**, all reversible.
- Kotlin library, JVM 17 bytecode, built with JDK 21 here. ADR-0008..0015 (v0.2, EV_PNC_docs#4) pick JDK 25 and Spring
  Boot 4 for the services; this module has no Spring dependency, so moving the toolchain is a one-line change.
- The layer works on the JSON wire form (`kotlinx.serialization` trees), not on the generated model. EV_PNC_ocpi-model
  is still an open draft PR and the conversion table is about wire fields anyway. Typed wrappers can come once v0.1.0 exists.

## Profile (T-GIREVE-01, T-GIREVE-14)

`HubProfile.fromContracts("gireve-ocpi-2.2.1.profile.yaml")` reads the pinned YAML and fails with the key's path if
anything is missing or malformed. The profile decides, in code that knows nothing about GIREVE:

| Question | Call |
|---|---|
| Which party id does a credentials object get? | `hubPartyId(env)`: `FR107` pre-production, `FR007` production |
| Which headers does a call need? | `requiredHeaders(module)`: routing on all modules except credentials and versions, tracing always |
| How is the token sent? | `authorizationHeader(token)`: `Token <Base64>` |
| Which modules and commands exist? | `supportsModule`, `supportsCommand` |
| How large may a pull be? | `pageLimit(requested)`, `withinPullRange(from, to)`, `safePullWindow()` |
| May this RFID uid be pushed? | `rfidUidProblems(uid)`: upper-case hex, 4 or 7 bytes |
| Who priced this CDR? | `pricedBy(remark)`: `[*]` hub, `[?]` not significant, otherwise the CPO |

`safePullWindow()` is 28 days for a "1 month" limit. The GIREVE guide does not say how GIREVE counts a month; the
simulator's mock accepts 31 days, the gateway should split pulls so they pass whichever way it is counted.

## Compatibility layer (T-GIREVE-02, T-GIREVE-15)

`Ocpi221Compat(profile, env).outbound(type, json)` and `.inbound(type, json)` return the converted JSON and the names of
the rules that fired (`token.type:EMAID->OTHER`). Every rule is one line of `ocpi-2.3.0-to-2.2.1.conversion.yaml`.

| Direction | Rule |
|---|---|
| out | version 2.3.0 is spoken as 2.2.1; `hub_party_id` is dropped from credentials; EMAID tokens go as OTHER with `uid = contract_id = eMAID` (disagreeing values are refused, not guessed); START_SESSION needs `evse_uid` |
| out | `payments`, `chargingprofiles`, `hubclientinfo`, session charging preferences and the unsupported commands are blocked (`Blocked`) |
| in | credentials get `hub_party_id` from the profile; the HUB role is accepted as it is |
| in | OTHER tokens with an eMAID uid become EMAID, also inside `cdr_token` of sessions and CDRs |
| in | Price `{excl_vat, incl_vat}` becomes `{before_taxes, taxes[]}` in session and CDR costs; the VAT line is the exact difference (BigDecimal, no float noise). Tariff `min_price`/`max_price` become price limits |
| in | tariffs without `tax_included` get `UNKNOWN`; EVSEs get empty `parking` and `accepted_service_providers`; `gireve_id` becomes `external_ref {system, id}`; PnC capabilities found on an EVSE are also put on its connectors |

Scenario inputs are the samples the GIREVE mock serves (`src/test/resources/scenarios/`, source commit named there). A live
test against the running mock image is a follow-up once the simulator image is published.

## Findings, for the squad

1. **The canonical model needs room** (`Ocpi221Compat.CANONICAL_NEEDS`, to be done in EV_PNC_ocpi-model): `tax_included`
   has no UNKNOWN value, `Role` has no HUB, there is no `external_ref`, and three string limits are narrower than
   what a 2.2.1 hub sends (Location address 255, state 45, SignedValue plain_data 5000). The layer does not truncate.
2. **Pushing EMAID tokens**: the conversion table maps EMAID to OTHER on the way out, OCPI 2.3.0 recommends not pushing
   EMAID tokens at all, and the simulator's mock refuses to receive them. The layer only converts; whether the
   gateway pushes them is a gateway decision (Plug&Charge is authorized in real time).
3. **VAT lines are an inference**: the 2.2.1 price has an incl. VAT total but no tax name or rate, so the layer names
   the line "VAT" and gives no percentage.
4. **PnC capabilities on the connector** is an inference from "accept at EVSE level too" in the conversion table.

## Not covered

- The HTTP client, registration automation and routing middleware (T-GIREVE-03, 04), which sit on top of this module.
- The Hubject adapter (OICP, different profile shape).
- Prose-only rule changes inside unchanged 2.2.1/2.3.0 tables (the table diff covers tables only).
