# jz — the Janzeer command-line client

[![ci](https://github.com/janzeerOrg/janzeer-cli/actions/workflows/ci.yml/badge.svg)](https://github.com/janzeerOrg/janzeer-cli/actions/workflows/ci.yml)
![license](https://img.shields.io/badge/license-Apache--2.0-blue)

One binary for people who like terminals: read the [Janzeer](https://janzeer.org) chain, watch it live, keep a local
encrypted wallet, send transactions, and look after a node. Built on the published Kotlin SDK
([`org.janzeer:janzeer-sdk-core`](https://central.sonatype.com/artifact/org.janzeer/janzeer-sdk-core)); shipped as a
GraalVM native image (no Java needed) and as a runnable jar.

**Non-custodial.** Wallet secrets live only in encrypted files on your machine (PBKDF2-HMAC-SHA256 × 250 000 + AES-256-GCM,
the same vault format as the TypeScript, Dart, Python and Kotlin SDKs). The node receives signed transactions, never a key.

## Install

Download the archive for your platform from the [releases page](https://github.com/janzeerOrg/janzeer-cli/releases), verify
it, put `jz` on your `PATH`:

```bash
tar xzf jz-<version>-linux-x64.tar.gz && cd jz-<version>-linux-x64
sha256sum -c SHA256SUMS                      # the files inside the bundle
sudo install -m 755 jz /usr/local/bin/jz
jz completion bash > ~/.local/share/bash-completion/completions/jz   # or zsh / fish
```

Every release's `SHA256SUMS` is signed with the project's [minisign](https://jedisct1.github.io/minisign/) key, the same key
that signs the node releases:

```bash
minisign -Vm SHA256SUMS -P RWQBrkWzg4V5n5wUBa9GNPpa95Z7WFT2EY5QrabMv/DP+KG+gJsPIgdr
sha256sum -c SHA256SUMS --ignore-missing
```

| Platform | Archive | Notes |
|---|---|---|
| Linux x86-64 | `jz-<v>-linux-x64.tar.gz` | glibc 2.34+ (Debian 12, Ubuntu 22.04 or newer); also `janzeer-cli-bin` for Arch (`packaging/arch/`) |
| Linux arm64 | `jz-<v>-linux-arm64.tar.gz` | Raspberry Pi 3/4/5 and other aarch64 boards |
| Windows x64 | `jz-<v>-windows-x64.zip` | `jz.exe`; config under `%APPDATA%\janzeer`; also via Scoop: `scoop install ./packaging/scoop/jz.json` |
| macOS | `jz-<v>.jar` | no native build yet — use the jar, or build one with `release/build-cli.sh` on a Mac |
| Any JVM 17+ | `jz-<v>.jar` | `java -jar jz-<v>.jar …` |

Node bundles after 0.1.10 (`janzeer-node-<v>-…`) ship the same `jz` next to the `janzeer` binary, so a validator operator gets it with the node.

## Sixty seconds

```bash
jz info                                    # mainnet through explorer.janzeer.org: version, tip, epoch, peers, sync
jz block tip --txs                         # the newest block and its transactions
jz address 0x06e1c0fa9955a700876f8cb0acc7f13fba9fb8ba --txs
jz watch blocks                            # live feed over WebSocket, Ctrl-C to stop

jz wallet new                              # 12-word phrase shown once, stored encrypted as ~/.config/janzeer/wallets/main.json
jz send 0x598b1301acef3baba6ce25e38dd17b723f7b98b1 1.25 --memo "hello" --wait
jz tx <hash>                               # anything you were told the hash of

jz --node http://localhost:7019 node status   # your own node: version, sync, health, update availability
jz node update                             # exit 10 when a newer release exists; --run installs it through the bundle's update.sh
```

Every command takes `--json` for the node's raw JSON (one document on stdout; errors as JSON on stderr), so `jz … --json | jq`
is the scripting interface. `balance` and `nonce` print one value.

## Commands

```
jz [--node URL|PROFILE] [--profile NAME] [--json] [-q] [--no-color] [--timeout SEC] [-v] [-y] [--password-file FILE]

  info                                     node + chain overview
  block <height|hash|tip> [--txs]          one block
  tx <hash> [--wait]                       one transaction; --wait blocks until final (exit 7 on timeout)
  address <addr> [--txs] [--tokens]        balance, nonces, pending; history and JZT-1 balances
  balance <addr> | nonce <addr>            one value each
  validators [--active] | validators show <key>
  tokens | tokens show <id> | tokens holders <id>
  watch blocks [--from H] [--txs] | watch address <addr>...     live feeds (NDJSON with --json)

  wallet new|import|list|show|export|remove
  send <to> <amount> [--memo T]            transfer JNZ
  token create|mint|burn|setcap|transfer   JZT-1 tokens (amounts in base units)
  validator register|exit --key <nodeKey>  deposit is NOT refundable; asks you to type the word
      write options: --wallet NAME --fee JNZ --nonce N --wait [--wait-timeout SEC] --dry-run --yes

  node status|sync|version|health|peers|update [--check|--run|--rollback]|logs [-n N] [-f] [--file F]
  key derive --mnemonic "…" [--reveal] | key sign --hash HEX | key verify … | key address <pubkey|addr>
  vectors check [--dir PATH]               conformance self-test against the public vectors
  config show|path|init  ·  completion bash|zsh|fish  ·  version
```

Global options may be written anywhere: `jz info --json` and `jz --json info` are the same.

### How a write works

`wallet` is opened (password prompt, or `--password-file` / `JZ_PASSWORD_FILE`; a wallet created with `--passphrase` also asks for the BIP39 passphrase, or reads `JZ_PASSPHRASE_FILE`, and refuses one that does not give the wallet's address) → the node's network id is read and checked
against the profile → nonce from the node (or `--nonce`) → fee from `janzeer_estimateFee` (a fixed fee cannot be changed; a
minimum may be raised with `--fee`) → the SDK builds and signs → a summary is shown → `Send this transaction? [y/N]` (skip
with `--yes`) → `POST /api/v1/transactions/…` → with `--wait`, the node's `janzeer_waitForFinality` long-poll until the block
is committed. A nonce mismatch is retried once with the nonce the node expects, unless you set `--nonce` yourself.
`--dry-run` stops after signing and prints the exact request body.

## Configuration

`jz config init` writes `~/.config/janzeer/jz.toml` (`$XDG_CONFIG_HOME`, or `%APPDATA%\janzeer` on Windows; `JZ_CONFIG_DIR`
overrides):

```toml
default_profile = "mainnet"
default_wallet  = "main"

[profiles.mainnet]
node = "https://explorer.janzeer.org"   # REST <node>/api/v1, JSON-RPC <node>/rpc, WebSocket <node>/rpc/ws
network_id = "janzeer"                   # a node on another network is refused (exit 9)

[profiles.local]
node = "http://localhost:7019"
network_id = "janzeer"
node_dir = "/opt/janzeer"                # where update.sh and VERSION live, for jz node update / logs
```

Node selection, first wins: `--node URL` → `JZ_NODE` → `--profile NAME` → `JZ_PROFILE` → `default_profile` → mainnet.
`--node` also accepts a profile name.

Wallet files are `~/.config/janzeer/wallets/<name>.json`, mode 0600, with the address in clear (so `list` and `show` need no
password) and the secret inside an SDK vault blob. `wallet export --blob` writes the bare blob the other SDKs open;
`wallet import --vault-file` accepts either form. There is no `--password` option on purpose.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | ok |
| 1 | unexpected error |
| 2 | usage (bad option or argument, cancelled confirmation) |
| 3 | network: node unreachable or not JSON |
| 4 | node busy or not ready: rate-limited, not synchronized, health not UP |
| 5 | transaction rejected by the node (validation, nonce, fee, signature) |
| 6 | not found (block, transaction, token, validator) |
| 7 | finality timeout — `jz tx <hash> --wait` to keep waiting |
| 8 | wallet: missing, wrong password, no terminal for the prompt |
| 9 | the node is on another network than the profile says |
| 10 | update available (`jz node update`, `jz node version`) |
| 130 | interrupted |

## Build from source

```bash
./gradlew build :jz-app:fatJar                 # tests + app/build/libs/jz-<version>.jar   (JDK 17)
JAVA_HOME=~/.sdkman/candidates/java/17.0.9-graalce ./gradlew :jz-app:nativeCompile   # app/build/native/nativeCompile/jz
release/smoke-native.sh                        # offline checks of the binary; ONLINE=1 adds https + WebSocket
release/build-cli.sh                           # the release bundle for this host (+ SHA256SUMS lines)
```

Layout: `core/` is a Kotlin Multiplatform module whose `commonMain` holds the command tree, formatting and config and whose
`jvmMain` adapts the SDK; `app/` is the thin JVM entry point that the GraalVM plugin turns into the binary. The vendored
conformance vectors under `core/src/jvmMain/resources/vectors/` are kept in step by `sdk_conformance/sync.sh --check`.

## Security

See [SECURITY.md](SECURITY.md). Report vulnerabilities privately to janzeeer@proton.me.

## License

Apache-2.0 — see [LICENSE](LICENSE).
