# EV_PNC_hub-gateway

Hub adapters of the EV_PNC platform (ADR-0001: translate at the edge).

| Module | What | Docs |
|---|---|---|
| `hub-core` | typed hub profile loader and the OCPI 2.3.0 to 2.2.1 compatibility layer (H-02) | [docs/hub-core.md](docs/hub-core.md) |

Hub contracts and profiles are not copied here: `contracts.lock` pins an
[EV_PNC_hub-contracts](https://github.com/COPUR/EV_PNC_hub-contracts) commit (`make contracts`).
