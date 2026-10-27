# Adding a hub

e-clearing.net (ECHX, OCPI 2.2.1, phase 5 under ADR-0004) is the next hub; any other follows the same steps. Nothing here needs contact with the hub.

1. **Contracts first, in `EV_PNC_hub-contracts`.** Add `profiles/<hub>-<protocol>.profile.yaml` with `source` and `confidence` on every entry, requirements and Gherkin under `specs/<hub>/`, and rows in `specs/traceability.csv`. Pin vendor files in `_sources/` if the hub publishes machine-readable contracts. `tests/test_specs.py` keeps the bundle consistent.
2. **Vendor the profile.** Add the placement rule for the new prefix to `tools/sync-contracts.sh`, bump `contracts.lock`, run `tools/sync-contracts.sh`.
3. **New module `hub-<hub>`** (`build.gradle.kts` like `hub-gireve`, add it to `settings.gradle.kts`). A hub that speaks OCPI 2.2.1 reuses `Ocpi221Compat`; add its deviations to the profile and to `Ocpi221Config`, not to the compat code, unless the deviation changes the shape of an object (then add a rule to the conversion table and to `Ocpi221Compat.COVERAGE`; the coverage test fails until you do).
4. **Typed profile view** (like `GireveProfile`): the adapter reads rules from it, never from constants.
5. **Implement `HubAdapter`.** Declare in `capabilities` only what is built. Implement the outbound ports the hub supports; expose inbound entry points as plain functions that take and return the hub's JSON text, so the web layer stays a binding.
6. **Pass the framework contract.** Subclass `HubAdapterContract` from the `hub-core` test fixtures. Add characterization tests against the pinned contract where one exists (see `OicpContractTest`).
7. **Register** the adapter in the application's `HubRegistry`; the capability matrix then shows it.
8. **Record decisions** that choose between conflicting sources in the module's tests and in `docs/refactoring-report.md` (surprises section), and update `docs/adr/0018-emsp-hub-adapter-framework.md` if a rule of the framework changes.
