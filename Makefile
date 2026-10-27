# Fetch the pinned hub contracts, run the tests.
CONTRACTS_REPO := $(shell sed -n 's/^repo=//p' contracts.lock)
CONTRACTS_COMMIT := $(shell sed -n 's/^commit=//p' contracts.lock)

.PHONY: contracts test

contracts:
	rm -rf .contracts && git init -q .contracts
	git -C .contracts fetch -q --depth 1 $(CONTRACTS_REPO) $(CONTRACTS_COMMIT)
	git -C .contracts checkout -q FETCH_HEAD

test:
	gradle test --no-daemon
