#!/usr/bin/env bash
set -e

# Configuration
PACKAGE_NAME="janzeer-cli"
MAINTAINER="Janzeer <janzeeer@proton.me>"
SECTION="utils"
LICENSE="Apache-2.0"
HOMEPAGE="https://janzeer.org"
DEPENDS="libc6 (>= 2.34), zlib1g"

ARCH="amd64"
DOWNLOAD=false
VERSION=""

# Parse arguments
while [[ "$#" -gt 0 ]]; do
    case $1 in
        --arch) ARCH="$2"; shift ;;
        --download) DOWNLOAD=true ;;
        *) VERSION="$1" ;;
    esac
    shift
done

if [ -z "$VERSION" ]; then
    echo "Usage: $0 [--arch amd64|arm64] [--download] <version>"
    exit 1
fi

# Map debian arch to tarball arch
TAR_ARCH="x64"
if [ "$ARCH" = "arm64" ]; then
    TAR_ARCH="arm64"
fi

RELEASE_DIR="build/release"
mkdir -p "$RELEASE_DIR"

TARBALL="jz-$VERSION-linux-$TAR_ARCH.tar.gz"
TARBALL_PATH="$RELEASE_DIR/$TARBALL"
DEB_FILE="${PACKAGE_NAME}_${VERSION}-1_${ARCH}.deb"

# Handle download if requested
if [ "$DOWNLOAD" = true ]; then
    echo "Downloading $TARBALL from GitHub releases..."
    URL="https://github.com/janzeerOrg/janzeer-cli/releases/download/$VERSION"
    curl -L -o "$TARBALL_PATH" "$URL/$TARBALL"
    curl -L -o "$RELEASE_DIR/SHA256SUMS.remote" "$URL/SHA256SUMS"
    
    # Verify checksum
    echo "Verifying checksum..."
    (cd "$RELEASE_DIR" && grep "$TARBALL" SHA256SUMS.remote | sha256sum -c -)
    rm "$RELEASE_DIR/SHA256SUMS.remote"
fi

if [ ! -f "$TARBALL_PATH" ]; then
    echo "Error: Tarball $TARBALL_PATH not found."
    exit 1
fi

# Create temporary build structure
BUILD_ROOT=$(mktemp -d)
trap 'rm -rf "$BUILD_ROOT"' EXIT

echo "Preparing package structure in $BUILD_ROOT..."
mkdir -p "$BUILD_ROOT/usr/bin"
mkdir -p "$BUILD_ROOT/usr/share/doc/$PACKAGE_NAME"
mkdir -p "$BUILD_ROOT/usr/share/bash-completion/completions"
mkdir -p "$BUILD_ROOT/usr/share/zsh/vendor-completions"
mkdir -p "$BUILD_ROOT/usr/share/fish/vendor_completions.d"
mkdir -p "$BUILD_ROOT/DEBIAN"

# Extract tarball
tar -xzf "$TARBALL_PATH" -C "$BUILD_ROOT/usr/bin" jz
tar -xzf "$TARBALL_PATH" -C "$BUILD_ROOT/usr/share/doc/$PACKAGE_NAME" README.txt
cp LICENSE "$BUILD_ROOT/usr/share/doc/$PACKAGE_NAME/copyright"

# Generate completions using the binary
chmod +x "$BUILD_ROOT/usr/bin/jz"
"$BUILD_ROOT/usr/bin/jz" completion bash > "$BUILD_ROOT/usr/share/bash-completion/completions/jz"
"$BUILD_ROOT/usr/bin/jz" completion zsh > "$BUILD_ROOT/usr/share/zsh/vendor-completions/_jz"
"$BUILD_ROOT/usr/bin/jz" completion fish > "$BUILD_ROOT/usr/share/fish/vendor_completions.d/jz.fish"

# Create control file
cat <<EOF > "$BUILD_ROOT/DEBIAN/control"
Package: $PACKAGE_NAME
Version: $VERSION-1
Section: $SECTION
Priority: optional
Architecture: $ARCH
Depends: $DEPENDS
Maintainer: $MAINTAINER
Description: Janzeer command-line client
 One binary to read the Janzeer chain, watch it live, keep a local
 encrypted wallet, send transactions, and look after a node.
 Homepage: $HOMEPAGE
EOF

# Build the debian package
echo "Building .deb package..."
dpkg-deb --build --root-owner-group "$BUILD_ROOT" "$RELEASE_DIR/$DEB_FILE"

# Update SHA256SUMS
echo "Updating SHA256SUMS..."
(cd "$RELEASE_DIR" && sha256sum "$DEB_FILE" >> SHA256SUMS)

echo "Done: $RELEASE_DIR/$DEB_FILE"
