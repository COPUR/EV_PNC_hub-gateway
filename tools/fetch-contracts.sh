#!/usr/bin/env bash
# Checks out EV_PNC_hub-contracts at the commit in contracts.lock into ./.contracts (gitignored).
# Tests read the pinned vendor files from there (CONTRACTS_DIR); sync-contracts.sh compares the vendored profiles with it.
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
source contracts.lock
rm -rf .contracts
git init -q .contracts
git -C .contracts fetch -q --depth 1 "$repo" "$commit"
git -C .contracts checkout -q FETCH_HEAD
echo "contracts at $(git -C .contracts rev-parse --short HEAD)"
