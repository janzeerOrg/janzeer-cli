# Security

`jz` is non-custodial: wallet secrets live only in the encrypted files under your config directory (PBKDF2-HMAC-SHA256,
250 000 rounds, AES-256-GCM — the Janzeer SDK vault format) and are decrypted in memory for the duration of one command.
The node never receives a key; it only verifies signatures.

- Report a vulnerability privately to janzeeer@proton.me. Please do not open a public issue for it.
- Releases are signed: verify `SHA256SUMS.minisig` with the project key printed in the README before running a binary.
- `jz` never asks for a password on the command line (`--password` does not exist); use the prompt or `--password-file`.
