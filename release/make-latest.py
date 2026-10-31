#!/usr/bin/env python3
"""Write the jz release manifest: downloads/cli/latest.json (schema 1, same shape as the node's).
    release/make-latest.py <dir with the jz-<ver>-* archives + SHA256SUMS> <base URL of that dir> [> latest.json]
Version from build.gradle.kts, release date today, the release page on GitHub. Every archive found becomes one entry under
"files", keyed by platform (linux-x64, linux-arm64, windows-x64, macos-arm64, macos-x64, arch-x86_64, jar), with its SHA-256
(from the dir's SHA256SUMS) and size. "sums"/"sig" point at SHA256SUMS and its minisign signature in the same dir.
"""
import datetime, json, os, re, sys

here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLATFORMS = (('-linux-x64.tar.gz', 'linux-x64'), ('-linux-arm64.tar.gz', 'linux-arm64'), ('-windows-x64.zip', 'windows-x64'),
             ('-macos-arm64.tar.gz', 'macos-arm64'), ('-macos-x64.tar.gz', 'macos-x64'), ('-x86_64.pkg.tar.zst', 'arch-x86_64'), ('.jar', 'jar'))

def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    folder, base = sys.argv[1], sys.argv[2].rstrip('/')
    version = re.search(r'^    version = "([^"]+)"', open(os.path.join(here, 'build.gradle.kts')).read(), re.M).group(1)
    sums = {}
    sums_path = os.path.join(folder, 'SHA256SUMS')
    if os.path.exists(sums_path):
        for line in open(sums_path):
            parts = line.split()
            if len(parts) == 2:
                sums[parts[1].lstrip('*')] = parts[0]
    files = {}
    for name in sorted(os.listdir(folder)):
        if not (name.startswith('jz-') or name.startswith('janzeer-cli-bin-')) or version not in name:
            continue
        for suffix, key in PLATFORMS:
            if name.endswith(suffix):
                files[key] = {'name': name, 'size': os.path.getsize(os.path.join(folder, name)), 'sha256': sums.get(name, ''), 'url': f'{base}/{name}'}
    manifest = {
        'schema': 1, 'version': version, 'released': datetime.date.today().isoformat(),
        'url': f'https://github.com/janzeerOrg/janzeer-cli/releases/tag/v{version}',
        'sums': f'{base}/SHA256SUMS', 'sig': f'{base}/SHA256SUMS.minisig',
        'signed': os.path.exists(os.path.join(folder, 'SHA256SUMS.minisig')),
        'files': files,
    }
    json.dump(manifest, sys.stdout, indent=2)
    print()

if __name__ == '__main__':
    main()
