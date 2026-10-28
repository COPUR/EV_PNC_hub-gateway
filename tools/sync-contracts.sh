#!/usr/bin/env bash
# Keeps the profiles vendored into the adapter modules equal to EV_PNC_hub-contracts/profiles at the pinned commit.
#   tools/sync-contracts.sh --check   exit 1 and list the differences (CI)
#   tools/sync-contracts.sh           copy the pinned files into the modules
# Placement: the conversion table goes to hub-core, hubject-* to hub-hubject, gireve-* to hub-gireve.
# e-clearing-* profiles are not vendored until the phase 5 adapter exists.
set -euo pipefail
cd "$(dirname "$0")/.."
src=${CONTRACTS_DIR:-.contracts}/profiles
[ -d "$src" ] || { echo "no $src; run tools/fetch-contracts.sh first" >&2; exit 2; }

mode=copy
[ "${1:-}" = "--check" ] && mode=check
status=0

place() { # file module
  local dest="$2/src/main/resources/profiles/$1"
  if [ "$mode" = check ]; then
    cmp -s "$src/$1" "$dest" || { echo "DIFFERS: $dest" >&2; status=1; }
  else
    mkdir -p "$(dirname "$dest")" && cp "$src/$1" "$dest"
  fi
}

place ocpi-2.3.0-to-2.2.1.conversion.yaml hub-core
for f in "$src"/hubject-*; do place "$(basename "$f")" hub-hubject; done
for f in "$src"/gireve-*; do place "$(basename "$f")" hub-gireve; done

# A vendored file whose source was removed upstream is also a difference.
if [ "$mode" = check ]; then
  for dest in hub-*/src/main/resources/profiles/*; do
    [ -e "$src/$(basename "$dest")" ] || { echo "ORPHAN: $dest" >&2; status=1; }
  done
fi
exit $status
