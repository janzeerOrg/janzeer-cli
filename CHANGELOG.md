# Changelog

All notable changes to `jz` are documented here ([Keep a Changelog](https://keepachangelog.com/en/1.1.0/), [SemVer](https://semver.org/)).

## Packaging — 2026-11-15 (no new release)

- **Debian/Ubuntu package** (`release/build-deb-package.sh`): generates `.deb` packages for `amd64` and `arm64` architectures. Wraps the native Linux binary, includes shell completions and documentation.
- **Homebrew formula for macOS** (`packaging/homebrew/jz.rb`, contributed by @genda-benjamin): installs the signed 0.1.1 runnable jar with OpenJDK 17+, wraps it with `bin.write_jar_script`, and checks its SHA-256 against `SHA256SUMS`.
- **Scoop manifest for Windows** (`packaging/scoop/jz.json`, contributed by @genda-benjamin): installs the signed 0.1.1 zip, checks its SHA-256, and updates itself from the release's `SHA256SUMS`.

## 0.1.1 — 2026-11-11
...
