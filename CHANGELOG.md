# Changelog

All notable changes to `jz` are documented here ([Keep a Changelog](https://keepachangelog.com/en/1.1.0/), [SemVer](https://semver.org/)).

## Packaging — 2026-11-14 (no new release)

- **Scoop manifest for Windows** (`packaging/scoop/jz.json`): installs the signed 0.1.0 zip, checks its SHA-256, and updates itself from the release's `SHA256SUMS`.

## 0.1.1 — 2026-11-11

Two wallet fixes from a code review. Same SDK and node pins as 0.1.0.

- **`jz wallet remove NAME` deletes the file you named.** It deleted the file matching the name stored INSIDE the wallet file. After a wallet file was renamed by hand the two differ, and the command removed another wallet while reporting the one you named. The confirmation now shows the file that will be deleted.
- **Wallets created with `--passphrase` can sign.** No send, token, validator or `key sign` command ever asked for the BIP39 passphrase, so the key was derived without it and every transaction came from a different, empty account. A passphrase wallet is now recognised by its stored address: `jz` asks for the passphrase (terminal prompt without echo, or `JZ_PASSPHRASE_FILE`, first line) and stops with exit 8 when the derived address does not match. `wallet show --verify` and `wallet export --reveal` use the same check.

## 0.1.0 — 2026-10-31

First release, built on `org.janzeer:janzeer-sdk-core|client` 0.1.3 (node 0.1.10, API 1.1.0, protocol 3.4.0). GraalVM native
image for linux-x64.
