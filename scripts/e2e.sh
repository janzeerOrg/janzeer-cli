#!/usr/bin/env bash
# End-to-end run of jz against the LOCAL dev net started by ../sdk_conformance/e2e/node-up.sh.
#   eval "$(../sdk_conformance/e2e/node-up.sh --env)"; scripts/e2e.sh [path/to/jz]
# JZ defaults to the fat jar; pass the native binary to prove the image. Uses a throw-away config dir and the canonical
# funded test wallet (JANZEER_E2E_MNEMONIC) — never a real one. Prints PASS/FAIL per step, exits non-zero on any failure.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
JZ=${1:-}
if [[ -z "$JZ" ]]; then JAR=$(ls "$HERE"/app/build/libs/jz-[0-9]*.jar 2>/dev/null | head -1); [[ -n "$JAR" ]] || { echo "build the jar first: ./gradlew :jz-app:fatJar" >&2; exit 1; }; JZ="java -jar $JAR"; fi
: "${JANZEER_NODE_URL:?run eval \"\$(../sdk_conformance/e2e/node-up.sh --env)\" first}"
: "${JANZEER_E2E_MNEMONIC:?}" "${JANZEER_E2E_RECIPIENT:?}"
NODE=${JANZEER_NODE_URL%/api/v1/}; NODE=${NODE%/api/v1}
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
export JZ_CONFIG_DIR="$TMP/cfg" JZ_PASSWORD_FILE="$TMP/pw" JZ_NODE="$NODE" NO_COLOR=1
echo "e2e-pass" > "$TMP/pw"
fail=0
step() { echo "--- $1"; shift; if "$@"; then echo "PASS"; else echo "FAIL ($?)"; fail=1; fi; }
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }

step "info" bash -c "$JZ info | grep -q SYNCHRONIZED"
step "config shows the ad-hoc node" bash -c "$JZ config show | grep -q 'JZ_NODE'"
step "wallet import (funded test wallet)" bash -c "$JZ wallet import --name e2e --mnemonic \"$JANZEER_E2E_MNEMONIC\" >/dev/null"
FROM=$($JZ wallet show e2e --json | json "d['address']")
BAL0=$($JZ balance "$JANZEER_E2E_RECIPIENT")
step "dry run builds a body" bash -c "$JZ send $JANZEER_E2E_RECIPIENT 1.25 --memo jz-e2e --wallet e2e --dry-run --json | grep -q '\"senderSignature\"'"
OUT=$($JZ send "$JANZEER_E2E_RECIPIENT" 1.25 --memo jz-e2e --wallet e2e --wait --yes --json 2>"$TMP/err") || { echo "send failed:"; cat "$TMP/err"; fail=1; OUT='{}'; }
HASH=$(echo "$OUT" | json "d.get('hash','')")
STATUS=$(echo "$OUT" | json "d.get('status','')")
step "send --wait reaches FINAL" test "$STATUS" = FINAL
BAL1=$($JZ balance "$JANZEER_E2E_RECIPIENT")
step "recipient balance grew by exactly 1.25" python3 -c "from decimal import Decimal as D; import sys; sys.exit(0 if D('$BAL1')-D('$BAL0')==D('1.25') else 1)"
step "tx shows FINAL with the memo" bash -c "$JZ tx $HASH | grep -q 'jz-e2e'"
step "address history lists it" bash -c "$JZ address $FROM --txs --json | grep -q '$HASH'"
step "block of the tx by height" bash -c "H=\$($JZ tx $HASH --json | python3 -c 'import json,sys; print(json.load(sys.stdin)[\"blockHeight\"])'); $JZ block \$H --txs | grep -q ${HASH:0:10}"
# nonce gap → exit 5
set +e; $JZ send "$JANZEER_E2E_RECIPIENT" 0.5 --wallet e2e --nonce 999999 --yes >/dev/null 2>&1; rc=$?; set -e
step "nonce gap is refused with exit 5" test "$rc" = 5
# watch: start the feed, send once more, expect the hash in the feed
timeout 60 $JZ watch address "$JANZEER_E2E_RECIPIENT" --json > "$TMP/watch" 2>/dev/null &
WPID=$!; sleep 3
H2=$($JZ send "$JANZEER_E2E_RECIPIENT" 0.1 --wallet e2e --yes --json | json "d['hash']")
for i in $(seq 1 45); do grep -q "$H2" "$TMP/watch" 2>/dev/null && break; sleep 1; done
kill $WPID 2>/dev/null || true
step "watch address saw the transfer over WebSocket" grep -q "$H2" "$TMP/watch"
step "node status (dev node)" bash -c "$JZ node status >/dev/null"
step "node health" bash -c "$JZ node health | grep -q UP"
step "node sync" bash -c "$JZ node sync | grep -q SYNCHRONIZED"
if [[ -n "${JANZEER_E2E_VALIDATOR_MNEMONIC:-}" ]]; then
  step "wallet import (validator wallet)" bash -c "$JZ wallet import --name val --mnemonic \"$JANZEER_E2E_VALIDATOR_MNEMONIC\" >/dev/null"
  TOK=$($JZ token create --symbol JZE --name "jz e2e" --decimals 2 --mint 100000 --wallet val --wait --yes --json | json "d['hash']") && step "token create is FINAL (validator wallet)" test -n "$TOK"
  step "token transfer" bash -c "$JZ token transfer $TOK 250 $JANZEER_E2E_RECIPIENT --wallet val --wait --yes --json | grep -q FINAL"
  step "token balances of the recipient" bash -c "$JZ address $JANZEER_E2E_RECIPIENT --tokens | grep -q 250"
fi
step "vectors check" bash -c "$JZ vectors check >/dev/null"
[[ $fail == 0 ]] && echo "E2E PASS" || { echo "E2E FAIL"; exit 1; }
