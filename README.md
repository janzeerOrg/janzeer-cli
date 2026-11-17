# janzeer-cli

[50 JNZ] Nix package (flake) for jz

## Overview

NixOS and Nix users cannot run the released `jz` binary directly (it is a normal glibc binary), and there is no Nix package.

**What to do:** a `flake.nix` at the repository root plus `packaging/nix/package.nix`:

- `packages.x86_64-linux.default` and `packages.aarch64-linux.default`: fetch `jz-<ver>-linux-x64.tar.gz` / `jz-<ver>-linux-arm64.tar.gz` from the GitHub release with `fetchurl` and the hash from that release's `SHA256SUMS`, patch the binary with `autoPatchelfHook` (it needs only glibc

## Installation

```bash
# Clone the repository
git clone https://github.com/janzeerOrg/janzeer-cli.git
cd janzeer-cli

# Install dependencies (if applicable)
pip install -r requirements.txt  # Python
# or
npm install  # Node.js
```

## Usage

```bash
# Run the project
python main.py  # Python
# or
node index.js  # Node.js
```

## Contributing

Contributions are welcome! Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes (`git commit -m 'Add my feature'`)
4. Push to the branch (`git push origin feature/my-feature`)
5. Open a Pull Request

## License

See [LICENSE](LICENSE) for details.
