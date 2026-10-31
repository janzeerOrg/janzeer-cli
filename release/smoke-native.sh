#!/usr/bin/env bash
# Smoke test for a jz binary (native image or `java -jar`): run on every platform before an archive is published.
#   release/smoke-native.sh [path/to/jz]            # offline checks only
#   ONLINE=1 release/smoke-native.sh [path/to/jz]   # + https info and a 20-s WebSocket watch against NODE (default explorer.janzeer.org)
# Exit code 0 = all checks passed. Uses a throw-away config dir; never touches ~/.config/janzeer.
set -euo pipefail
JZ=${1:-app/build/native/nativeCompile/jz}
NODE=${NODE:-https://explorer.janzeer.org}
if [[ ! -x "$JZ" && "$JZ" != java* ]]; then echo "no executable at $JZ" >&2; exit 1; fi
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
export JZ_CONFIG_DIR="$TMP/cfg" JZ_PASSWORD_FILE="$TMP/pw" NO_COLOR=1
echo "smoke-pass-1" > "$TMP/pw"
fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo "--- $*"; }

step version;            $JZ version | grep -q "^jz " || fail version
step "version --json";   $JZ version --json | grep -q '"jz"' || fail "version --json"
step completion;         $JZ completion bash | grep -q "_jz" || fail completion
step "config init/show"; $JZ config init >/dev/null && $JZ config show | grep -q "mainnet" || fail config
step "key derive";       ADDR=$($JZ key derive --json --mnemonic "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about" | sed -n 's/.*"address": "\([^"]*\)".*/\1/p')
[[ "${ADDR,,}" == "0x06e1c0fa9955a700876f8cb0acc7f13fba9fb8ba" ]] || fail "derive gave $ADDR"
step "wallet new/show/export (JCA AES-GCM + SecureRandom)"
$JZ wallet new --name smoke --words 12 --no-show >/dev/null || fail "wallet new"
$JZ wallet show smoke --verify | grep -q "derived" || fail "wallet show --verify"
$JZ wallet export smoke --blob | grep -q '"ct"' || fail "wallet export --blob"
WORDS=$($JZ wallet export smoke --reveal --yes --json | sed -n 's/.*"secret": "\([^"]*\)".*/\1/p' | wc -w)
[[ "$WORDS" == 12 ]] || fail "reveal gave $WORDS words"
echo wrong > "$TMP/pw"; set +e; $JZ wallet show smoke --verify >/dev/null 2>&1; rc=$?; set -e; [[ $rc == 8 ]] || fail "wrong password exit $rc (want 8)"
echo "smoke-pass-1" > "$TMP/pw"
step "exit codes";       set +e; $JZ --bogus >/dev/null 2>&1; [[ $? == 2 ]] || fail "usage exit"; $JZ wallet show nope >/dev/null 2>&1; [[ $? == 8 ]] || fail "missing wallet exit"; set -e
has() { $JZ --help | grep -qE "^  $1 "; }
if has vectors; then step "vectors check"; $JZ vectors check >/dev/null || fail "vectors"; fi
if [[ "${ONLINE:-0}" == 1 ]]; then
  step "info over https ($NODE)"; $JZ info --node "$NODE" | grep -q "SYNCHRONIZED\|tip" || fail "info"
  if has watch; then
    step "watch blocks for 20 s (WebSocket)"
    set +e; timeout 20 $JZ watch blocks --node "$NODE" > "$TMP/watch.out" 2>&1; set -e
    grep -q "#" "$TMP/watch.out" || fail "no block seen in 20 s: $(cat "$TMP/watch.out")"
  fi
fi
echo "OK: all smoke checks passed for $JZ"
