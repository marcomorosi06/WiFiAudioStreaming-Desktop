#!/usr/bin/env bash
# Copies two licence texts that are not on GitHub into src/main/resources/licenses/,
# verbatim from their upstream sources, and records them in licenses/SOURCES.txt:
#
#   opencore-amr/  OpenCORE AMR 0.1.6, compiled into the FFmpeg libraries: its
#                  licence files from the same tarball the JavaCPP Presets 1.5.10
#                  build downloads (licenses/opencore-amr/LICENSE so far is
#                  Bytedeco's copy; this adds the upstream files, NOTICE included);
#   dng-sdk/       Adobe DNG SDK, compiled into Skia/Skiko (the commit Skia m126
#                  pins in its DEPS: c8d0c9b1d16bfda56f15165d39e0ffa360a11123).
#
# Run it once from the repository root and commit the result. The release
# workflow refuses to build if these folders are missing.
#
# Needs bash, curl, tar, base64 and sha256sum. Reads nothing outside the
# repository and the two URLs below; writes only inside src/main/resources/licenses/.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LICENSES="$ROOT/src/main/resources/licenses"
SOURCES="$LICENSES/SOURCES.txt"

die() {
    echo "error: $*" >&2
    exit 1
}

[[ -f "$SOURCES" ]] || die "$SOURCES is missing: run this from a checkout of the repository"
for tool in curl tar base64 sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is needed"
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Appends one entry to SOURCES.txt (replacing an older entry for the same file).
record() {
    local file="$1" origin="$2" sha
    sha="$(sha256sum "$LICENSES/$file" | cut -d' ' -f1)"
    awk -v f="$file" '
        $0 == f { skip = 3 }
        skip > 0 { skip--; next }
        { print }
    ' "$SOURCES" >"$WORK/sources" && cat "$WORK/sources" >"$SOURCES"
    printf '%s\n    %s\n    sha256 %s\n' "$file" "$origin" "$sha" >>"$SOURCES"
    echo "  $file ($sha)"
}

# --- OpenCORE AMR 0.1.6 ---------------------------------------------------------
OPENCORE_URL="https://sourceforge.net/projects/opencore-amr/files/opencore-amr/opencore-amr-0.1.6.tar.gz/download"
echo "OpenCORE AMR 0.1.6"
curl -fsSL --retry 3 -o "$WORK/opencore-amr.tar.gz" "$OPENCORE_URL" || die "cannot download $OPENCORE_URL"
TARBALL_SHA="$(sha256sum "$WORK/opencore-amr.tar.gz" | cut -d' ' -f1)"
tar -xzf "$WORK/opencore-amr.tar.gz" -C "$WORK"
SRC="$WORK/opencore-amr-0.1.6"
[[ -d "$SRC" ]] || die "the tarball has no opencore-amr-0.1.6 folder"
mkdir -p "$LICENSES/opencore-amr"
found=0
for name in LICENSE COPYING NOTICE; do
    if [[ -f "$SRC/$name" ]]; then
        cp "$SRC/$name" "$LICENSES/opencore-amr/$name"
        record "opencore-amr/$name" "opencore-amr-0.1.6.tar.gz ($OPENCORE_URL, SHA-256 $TARBALL_SHA)  $name"
        found=1
    fi
done
((found)) || die "no LICENSE, COPYING or NOTICE in opencore-amr-0.1.6: look at the tarball and copy its licence files by hand"

# --- Adobe DNG SDK (Skia's pinned commit) ------------------------------------------
DNG_COMMIT="c8d0c9b1d16bfda56f15165d39e0ffa360a11123"
DNG_BASE="https://android.googlesource.com/platform/external/dng_sdk/+/$DNG_COMMIT"
echo "Adobe DNG SDK ($DNG_COMMIT)"
mkdir -p "$LICENSES/dng-sdk"
found=0
for name in LICENSE NOTICE LICENSE.source_code LICENSE.technology; do
    # googlesource serves raw files base64-encoded with ?format=TEXT.
    if curl -fsSL --retry 3 -o "$WORK/$name.b64" "$DNG_BASE/$name?format=TEXT" 2>/dev/null; then
        base64 -d "$WORK/$name.b64" >"$LICENSES/dng-sdk/$name"
        record "dng-sdk/$name" "https://android.googlesource.com/platform/external/dng_sdk  $DNG_COMMIT  $name"
        found=1
    fi
done
((found)) || die "no licence file found at $DNG_BASE"

echo "Done. Check the new files, then commit src/main/resources/licenses/."
