#!/usr/bin/env bash
# Build the jz release artifacts for THIS host: native binary bundle + runnable jar, SHA256SUMS lines, offline smoke test.
#   release/build-cli.sh [--skip-native] [--xmx 4g]
# Needs GraalVM 17 with native-image (GRAAL17, default ~/.sdkman/candidates/java/17.0.9-graalce). Output in build/release/:
#   jz-<ver>-<os>-<arch>.tar.gz|zip   the bundle (jz binary, README.txt, LICENSE.txt, VERSION, SHA256SUMS)
#   jz-<ver>.jar                       the JVM fallback (java -jar)
#   SHA256SUMS                         one line per artifact; the maintainer signs it with minisign after the last platform
# Run it on every platform (Linux x64, Linux arm64, Windows via build-cli.cmd, macOS), merge the SHA256SUMS lines, sign once.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
GRAAL17="${GRAAL17:-$HOME/.sdkman/candidates/java/17.0.9-graalce}"
NATIVE=1; XMX=4g
while [ $# -gt 0 ]; do case "$1" in --skip-native) NATIVE=0 ;; --xmx) shift; XMX="$1" ;; *) echo "unknown option $1"; exit 2 ;; esac; shift; done
VER=$(sed -n 's/^    version = "\(.*\)"/\1/p' build.gradle.kts | head -1)
[ -n "$VER" ] || { echo "version not found in build.gradle.kts"; exit 1; }
case "$(uname -s)" in Linux) OS=linux ;; Darwin) OS=macos ;; MINGW*|MSYS*|CYGWIN*) OS=windows ;; *) OS=$(uname -s | tr 'A-Z' 'a-z') ;; esac
case "$(uname -m)" in x86_64|amd64) ARCH=x64 ;; aarch64|arm64) ARCH=arm64 ;; *) ARCH=$(uname -m) ;; esac
if [ "$NATIVE" = 1 ]; then
  # Linux/macOS ship bin/native-image, Windows ships bin/native-image.cmd (Git Bash: run from the "x64 Native Tools" prompt so cl.exe is on PATH)
  ls "$GRAAL17"/bin/native-image* >/dev/null 2>&1 || { echo "GraalVM 17 with native-image not found at $GRAAL17 (set GRAAL17=…)"; exit 1; }
  export JAVA_HOME="$GRAAL17" GRAALVM_HOME="$GRAAL17" PATH="$GRAAL17/bin:$PATH"
fi
echo "jz $VER — $OS-$ARCH$([ "$NATIVE" = 1 ] && echo " (native image, -Xmx$XMX)" || echo " (jar only)")"
TASKS=(:jz-core:check :jz-app:fatJar); [ "$NATIVE" = 1 ] && TASKS+=(:jz-app:nativeCompile)
./gradlew --no-daemon -q "${TASKS[@]}" "-Pjanzeer.nativeXmx=$XMX"

REL=build/release; mkdir -p "$REL"
JAR=app/build/libs/jz-$VER.jar; [ -f "$JAR" ] || { echo "no $JAR"; exit 1; }
cp "$JAR" "$REL/jz-$VER.jar"
ART=""
if [ "$NATIVE" = 1 ]; then
  NAME="jz-$VER-$OS-$ARCH"; STAGE="$REL/$NAME"; rm -rf "${STAGE:?}"; mkdir -p "$STAGE"
  BIN=app/build/native/nativeCompile/jz; [ "$OS" = windows ] && BIN=$BIN.exe
  [ -f "$BIN" ] || { echo "no native binary at $BIN"; exit 1; }
  cp "$BIN" "$STAGE/"; chmod 755 "$STAGE"/jz*; cp app/build/native/nativeCompile/*.so "$STAGE/" 2>/dev/null || true; cp app/build/native/nativeCompile/*.dll "$STAGE/" 2>/dev/null || true
  cp LICENSE "$STAGE/LICENSE.txt"
  { echo "version=$VER"; echo "os=$OS"; echo "arch=$ARCH"; echo "commit=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"; echo "built=$(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > "$STAGE/VERSION"
  cat > "$STAGE/README.txt" <<TXT
jz $VER — command-line client for the Janzeer blockchain ($OS-$ARCH, single binary, no runtime needed)

  ./jz --help                 every command
  ./jz info                   the public network (mainnet) through explorer.janzeer.org
  ./jz wallet new             a local encrypted wallet; keys never leave this machine
  ./jz config init            jz.toml with the mainnet / testnet / local profiles
  ./jz completion bash|zsh|fish

Install: copy jz somewhere on your PATH (e.g. /usr/local/bin). Verify this download with the SHA256SUMS of the release
and its minisign signature (key in the README at https://github.com/janzeerOrg/janzeer-cli). Apache-2.0.
TXT
  ( cd "$STAGE" && sha256sum jz* LICENSE.txt VERSION README.txt > SHA256SUMS )
  # leak guard: nothing that looks like a wallet or a key may ship
  if grep -rlE 'janzeer-wallet|mnemonic|PRIVATE KEY|"ct":' "$STAGE" --exclude=jz --exclude=jz.exe >/dev/null; then echo "!! secret-looking content in $STAGE"; grep -rlE 'janzeer-wallet|mnemonic|PRIVATE KEY|"ct":' "$STAGE" --exclude=jz --exclude=jz.exe; exit 1; fi
  if [ "$OS" = windows ]; then
    # zip entries MUST use forward slashes (PowerShell 5.1 Compress-Archive writes backslashes, which other unzips take literally):
    # real Info-ZIP zip, else Windows' own bsdtar (System32\tar.exe, Windows 10 1803+), else .NET ZipFile (forward slashes since .NET 4.6.1).
    ( cd "$REL" && rm -f "${NAME:?}.zip"
      if zip -v 2>/dev/null | grep -q "Info-ZIP"; then zip -qr "$NAME.zip" "$NAME"   # a 7-Zip "zip" on PATH refuses -qr
      elif [ -x /c/Windows/System32/tar.exe ]; then /c/Windows/System32/tar.exe -a -cf "$NAME.zip" "$NAME"
      else powershell -NoProfile -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; [IO.Compression.ZipFile]::CreateFromDirectory((Resolve-Path '$NAME'), (Join-Path (Get-Location) '$NAME.zip'), 'Optimal', \$true)"; fi
      if unzip -l "$NAME.zip" 2>/dev/null | grep -q '\\'; then echo "!! $NAME.zip has backslash entry names"; exit 1; fi ); ART="$NAME.zip"
  else ( cd "$REL" && tar --owner=0 --group=0 -czf "$NAME.tar.gz" "$NAME" ); ART="$NAME.tar.gz"; fi
  release/smoke-native.sh "$STAGE/$(basename "$BIN")"
fi
# SHA256SUMS: replace this host's lines, keep the others (merged across platforms before signing)
( cd "$REL" && { [ -f SHA256SUMS ] && grep -vE "  (jz-$VER-$OS-$ARCH\.(tar\.gz|zip)|jz-$VER\.jar)\$" SHA256SUMS || true; } > SHA256SUMS.tmp \
  && { [ -n "$ART" ] && sha256sum "$ART" || true; sha256sum "jz-$VER.jar"; } >> SHA256SUMS.tmp && mv SHA256SUMS.tmp SHA256SUMS )
echo; ls -lh "$REL"/jz-"$VER"* 2>/dev/null | grep -v "^d"; echo; cat "$REL/SHA256SUMS"
echo; echo "next: build on the other platforms, copy their archives into $REL, regenerate the sums from the files, then sign once:"
echo "  (cd $REL && sha256sum jz-$VER-*.tar.gz jz-$VER-*.zip jz-$VER.jar janzeer-cli-bin-$VER-*.pkg.tar.zst 2>/dev/null > SHA256SUMS)"
echo "  minisign -Sm $REL/SHA256SUMS -s ~/janzeer-release.key -t \"janzeer cli $VER\""
