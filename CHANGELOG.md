# Changelog

All notable changes to `jz` are documented here ([Keep a Changelog](https://keepachangelog.com/en/1.1.0/), [SemVer](https://semver.org/)).

## 0.1.0 — 2026-10-31

First release, built on `org.janzeer:janzeer-sdk-core|client` 0.1.3 (node 0.1.10, API 1.1.0, protocol 3.4.0). GraalVM native
image for linux-x64, linux-arm64 and windows-x64; runnable jar for any JVM 17+ (macOS runs the jar for now).

- Chain reads: `info`, `block`, `tx` (`--wait`), `address` (`--txs`, `--tokens`), `balance`, `nonce`, `validators`, `tokens`; tables or `--json`.
- Live feeds over the node's WebSocket: `watch blocks`, `watch address`; NDJSON with `--json`.
- Local encrypted wallets in the SDK vault format: `wallet new|import|list|show|export|remove`; `--password-file` / `JZ_PASSWORD_FILE` for scripts; secrets only with `--reveal` and a typed confirmation.
- Writes with one flow (network id check, nonce, fee estimate, sign, confirm, submit, optional finality wait, one nonce-mismatch retry): `send`, `token create|mint|burn|setcap|transfer`, `validator register|exit`; `--dry-run` prints the request body.
- Node operations: `node status|sync|version|health|peers|update|logs`; `update --check` mirrors the bundle's `update.sh` (exit 10 = update available), `--run` executes it after a typed confirmation.
- Dev tools: `key derive|sign|verify|address`, `vectors check` (the public conformance vectors, vendored), `completion bash|zsh|fish`.
- Profiles in `jz.toml` (mainnet, testnet, local; `--node` / `JZ_NODE` for ad-hoc nodes); documented exit codes.
