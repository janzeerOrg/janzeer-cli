#!/usr/bin/env bash
# Wrap the published Linux x64 jz bundle into an Arch package: build/release/janzeer-cli-bin-<ver>-<rel>-x86_64.pkg.tar.zst
#   release/build-arch-package.sh [--pkgrel N]        (after release/build-cli.sh on Linux x64)
# Same bytes as the tarball on GitHub (makepkg checks the hash). The rendered PKGBUILD + .SRCINFO go to packaging/arch/ in this repo.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
PKGREL=1
while [ $# -gt 0 ]; do case "$1" in --pkgrel) shift; PKGREL="$1" ;; *) echo "unknown option $1"; exit 2 ;; esac; shift; done
for t in makepkg fakeroot bsdtar zstd; do command -v "$t" >/dev/null || { echo "missing $t — sudo apt install makepkg pacman-package-manager libarchive-tools fakeroot"; exit 1; }; done
VER=$(sed -n 's/^    version = "\(.*\)"/\1/p' build.gradle.kts | head -1)
REL=build/release; BUNDLE="jz-$VER-linux-x64"; TGZ="$REL/$BUNDLE.tar.gz"
[ -f "$TGZ" ] || { echo "no $TGZ — run release/build-cli.sh first"; exit 1; }
SHA=$(grep "  $BUNDLE.tar.gz\$" "$REL/SHA256SUMS" | cut -d' ' -f1); [ -n "$SHA" ] || SHA=$(sha256sum "$TGZ" | cut -d' ' -f1)
OUT="$REL/arch"; rm -rf "${OUT:?}"; mkdir -p "$OUT"
sed -e "s/@VERSION@/$VER/g" -e "s/@PKGREL@/$PKGREL/g" -e "s/@SHA256@/$SHA/g" release/packaging/arch/PKGBUILD.in > "$OUT/PKGBUILD"
grep -qE '@[A-Z_]+@' "$OUT/PKGBUILD" && { echo "unrendered placeholder in PKGBUILD"; exit 1; }
ln -sf "$(pwd)/$TGZ" "$OUT/$BUNDLE.tar.gz"
echo "arch package — $BUNDLE (sha256 $SHA), pkgrel $PKGREL"
( cd "$OUT" && PKGEXT='.pkg.tar.zst' PKGDEST="$(pwd)/.." SRCDEST="$(pwd)" BUILDDIR="$(pwd)/build" makepkg --nodeps --force --clean --noconfirm && makepkg --printsrcinfo > .SRCINFO )
PKG=$(ls -t "$REL"/janzeer-cli-bin-"$VER"-"$PKGREL"-x86_64.pkg.tar.zst | head -1)
rm -f "${OUT:?}/${BUNDLE:?}.tar.gz"; rm -rf "${OUT:?}/build" "${OUT:?}"/*.log
grep -v "  $(basename "$PKG")\$" "$REL/SHA256SUMS" > "$REL/SHA256SUMS.tmp" 2>/dev/null || true
( cd "$REL" && sha256sum "$(basename "$PKG")" ) >> "$REL/SHA256SUMS.tmp"; mv "$REL/SHA256SUMS.tmp" "$REL/SHA256SUMS"
mkdir -p packaging/arch && cp "$OUT/PKGBUILD" "$OUT/.SRCINFO" packaging/arch/
echo; echo "contents:"; bsdtar -tf "$PKG" | grep -v '^\.' | sed 's/^/  /'; echo; ls -lh "$PKG"
echo "PKGBUILD + .SRCINFO copied to packaging/arch/ (commit them with the release)"
