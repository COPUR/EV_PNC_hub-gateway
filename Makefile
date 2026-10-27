.PHONY: contracts sync-check test build

contracts:
	tools/fetch-contracts.sh

sync-check: contracts
	tools/sync-contracts.sh --check

# Tests that compare against the pinned vendor files read them from ./.contracts.
test: contracts
	CONTRACTS_DIR=$(CURDIR)/.contracts gradle test --no-daemon

build: contracts
	CONTRACTS_DIR=$(CURDIR)/.contracts gradle build --no-daemon
