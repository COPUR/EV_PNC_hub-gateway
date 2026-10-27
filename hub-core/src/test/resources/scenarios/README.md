# Scenario inputs

Wire-form samples as the GIREVE mock sends them (`ev_pnc_simulator.gireve.fixtures`, EV_PNC_simulator commit 17cf059).
They are the "mock" half of the H-02 acceptance: the conversion layer is tested on what the mock really serves, not on
hand-made shapes. Regenerate after the mock's fixtures change:

    cd EV_PNC_simulator && uv run python -c "
    import json; from ev_pnc_simulator.gireve import fixtures as f
    d = '<this dir>/'
    json.dump(f.locations(), open(d + 'locations.json', 'w'), indent=2)
    json.dump(f.tariffs(), open(d + 'tariffs.json', 'w'), indent=2)
    json.dump(f.session('SES0001'), open(d + 'session.json', 'w'), indent=2)
    json.dump(f.cdr('CDR0001', 'SES0001'), open(d + 'cdr.json', 'w'), indent=2)"

A live check against the running mock image is a follow-up for when the simulator image is published.
