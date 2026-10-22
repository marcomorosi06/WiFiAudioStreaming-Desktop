#!/usr/bin/env bash
# Packs the source code of the third-party components of the desktop packages
# into one archive, which .github/workflows/release.yml attaches to every release.
#
# The packages contain FFmpeg (LGPL-3.0-or-later) with LAME, alsa-lib and other
# libraries, a Java runtime (GPL-2.0 with the Classpath Exception) and, in the
# .AppImage files, the AppImage runtime with libfuse (LGPL-2.1). Their licences
# ask whoever distributes the binaries to make the corresponding source
# available; this archive keeps it next to the packages, on the release page.
#
# The archive holds:
#   javacpp-presets/   the JavaCPP Presets build scripts FFmpeg is built with
#                      (cppbuild.sh, ffmpeg/, the Ubuntu deploy action);
#   ffmpeg-libraries/  every source archive ffmpeg/cppbuild.sh downloads
#                      (including nasm, the assembler it patches and builds),
#                      except x264 and x265 (only for the GPL build, not used);
#   raspberrypi-userland-<commit>.tar.gz
#                      the userland the linux-arm64 build links against;
#   OpenJDK17U-jdk-sources_<version>.tar.gz
#                      the source of the Eclipse Temurin runtime (checked
#                      against Adoptium's SHA-256);
#   appimage/          the AppImage type2-runtime, libfuse and squashfuse;
#   SOURCES.txt        where every file comes from, with its SHA-256.
#
# Usage: scripts/package-third-party-sources.sh OUTPUT.tar
# Environment: TEMURIN_VERSION (e.g. 17.0.20+8), the runtime the packages are
# built with (the release workflow sets it).
# Needs bash, git, curl, tar, unzip and sha256sum.

set -euo pipefail

export GIT_NO_REPLACE_OBJECTS=1 GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Raspberry Pi userland: the JavaCPP Presets 1.5.10 deploy action builds
# "master" (.github/actions/deploy-ubuntu/action.yml); this was master when
# those binaries were built (from 2023-10-20 to 2024-12-23).
USERLAND_COMMIT="96a7334ae9d5fc9db7ac92e59852377df63f1848"
# AppImage runtime release used by the workflow (appimagetool --runtime-file),
# and the sources its build script pins (scripts/common/install-dependencies.sh).
RUNTIME_TAG="20251108"
RUNTIME_COMMIT="dd6cebedcbddde9c82f89b011e8e1d40b6e43868"
FUSE_URL="https://github.com/libfuse/libfuse/releases/download/fuse-3.15.0/fuse-3.15.0.tar.xz"
FUSE_SHA256="70589cfd5e1cff7ccd6ac91c86c01be340b227285c5e200baa284e401eea2ca0"
SQUASHFUSE_URL="https://github.com/vasi/squashfuse/archive/0.5.2.tar.gz"
SQUASHFUSE_SHA256="db0238c5981dabbd80ee09ae15387f390091668ca060a7bc38047912491443d3"

die() {
    echo "error: $*" >&2
    exit 1
}

info() {
    echo "==> $*"
}

[[ $# -eq 1 ]] || die "usage: scripts/package-third-party-sources.sh OUTPUT.tar"
OUT="$1"
case "$OUT" in
    /*) ;;
    *) OUT="$PWD/$OUT" ;;
esac
[[ "$OUT" == *.tar ]] || die "the output must be a .tar file (its content is already compressed)"
[[ -n "${TEMURIN_VERSION:-}" ]] || die "set TEMURIN_VERSION (e.g. 17.0.20+8)"
[[ "$TEMURIN_VERSION" =~ ^([0-9]+)\.[0-9]+\.[0-9]+(\.[0-9]+)?\+[0-9]+$ ]] || die "TEMURIN_VERSION must look like 17.0.20+8"
for tool in git curl tar unzip sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is needed"
done

TOP="$(basename "$OUT" .tar)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
STAGE="$WORK/stage/$TOP"
mkdir -p "$STAGE"
MANIFEST="$WORK/manifest.txt"
: >"$MANIFEST"

# One entry of SOURCES.txt: file, origin.
entry() {
    printf '%s\n    %s\n    sha256 %s\n' "$1" "$2" "$(sha256sum "$STAGE/$1" | cut -d' ' -f1)" >>"$MANIFEST"
}

# True if $1 is a readable archive (not an HTML error page saved under its name).
is_archive() {
    case "$1" in
        *.zip) unzip -tq "$1" >/dev/null 2>&1 ;;
        *) tar -tf "$1" >/dev/null 2>&1 ;;
    esac
}

# Downloads the first of the URLs $3... that gives a readable archive into
# $STAGE/$1/, under its own file name ($2 for the first URL). Prints the file.
fetch_any() {
    local dir="$1" name="$2" url file
    shift 2
    mkdir -p "$STAGE/$dir"
    for url in "$@"; do
        file="$name"
        [[ "$url" == "$1" ]] || file="$(basename "${url%%\?*}")"
        [[ "$file" == download ]] && file="$name"
        if curl -fsSL --retry 3 --retry-delay 5 -o "$STAGE/$dir/$file" "$url" 2>/dev/null &&
            is_archive "$STAGE/$dir/$file"; then
            entry "$dir/$file" "$url"
            echo "$file"
            return 0
        fi
        rm -f "$STAGE/$dir/$file"
    done
    die "cannot download $name from: $*"
}

# Puts commit $3 of repository $2 in $STAGE/$4 as a .tar.gz (checked against $3).
git_snapshot() {
    local name="$1" url="$2" commit="$3" out="$4" repo="$WORK/git-$1" actual
    git -c init.defaultBranch=main init -q "$repo"
    git -C "$repo" fetch -q --depth 1 "$url" "$commit" || die "cannot fetch $name $commit from $url"
    actual="$(git -C "$repo" rev-parse 'FETCH_HEAD^{commit}')"
    [[ "$actual" == "$commit" ]] || die "$name: $url gave commit $actual, expected $commit"
    mkdir -p "$STAGE/$(dirname "$out")" "$WORK/tree-$name/$name"
    git -C "$repo" --work-tree="$WORK/tree-$name/$name" -c core.autocrlf=false checkout -q -f "$actual" -- .
    tar --sort=name --owner=0 --group=0 --numeric-owner --mtime=@0 \
        -czf "$STAGE/$out" -C "$WORK/tree-$name" "$name"
    entry "$out" "$url  commit $commit"
    rm -rf "$repo" "$WORK/tree-$name"
}

# --- The FFmpeg libraries: which JavaCPP Presets ------------------------------------

GRADLE="$ROOT/build.gradle.kts"
PRESETS_VERSION="$(sed -n 's/^[[:space:]]*val javacvVersion = "\([0-9.]*\)".*/\1/p' "$GRADLE")"
[[ -n "$PRESETS_VERSION" ]] || die "no javacvVersion in build.gradle.kts"
# The licence notes describe the build without GPL components: stop if that changes.
# shellcheck disable=SC2016 # matched literally in build.gradle.kts
grep -q 'org.bytedeco:ffmpeg:\$javacvVersion' "$GRADLE" || die "build.gradle.kts no longer uses org.bytedeco:ffmpeg:\$javacvVersion: update this script and the licence notes"
if grep -q -E 'ffmpeg-platform-gpl|ffmpeg:[^"]*-gpl|javacv-platform' "$GRADLE"; then
    die "build.gradle.kts uses a GPL or -platform JavaCV artifact: update this script and the licence notes"
fi

info "Fetching JavaCPP Presets $PRESETS_VERSION"
PRESETS_URL="https://github.com/bytedeco/javacpp-presets"
PRESETS_REPO="$WORK/git-javacpp-presets"
git -c init.defaultBranch=main init -q "$PRESETS_REPO"
git -C "$PRESETS_REPO" fetch -q --depth 1 "$PRESETS_URL" "refs/tags/$PRESETS_VERSION" || die "no tag $PRESETS_VERSION in $PRESETS_URL"
PRESETS_COMMIT="$(git -C "$PRESETS_REPO" rev-parse 'FETCH_HEAD^{commit}')"
mkdir -p "$STAGE/javacpp-presets"
git -C "$PRESETS_REPO" --work-tree="$STAGE/javacpp-presets" -c core.autocrlf=false checkout -q -f FETCH_HEAD -- \
    cppbuild.sh ffmpeg .github/actions/deploy-ubuntu .github/actions/deploy-macosx .github/actions/deploy-windows
while IFS= read -r f; do
    entry "javacpp-presets/$f" "$PRESETS_URL  tag $PRESETS_VERSION  commit $PRESETS_COMMIT  $f"
done < <(cd "$STAGE/javacpp-presets" && find . -type f | sed 's,^\./,,' | sort)
CPPBUILD="$STAGE/javacpp-presets/ffmpeg/cppbuild.sh"

# --- Every source archive ffmpeg/cppbuild.sh downloads ---------------------------------

# Other places the same files are published, for the ones whose first site moves
# old releases away.
fallbacks() {
    case "$1" in
        zlib-*.tar.gz)
            local v="${1#zlib-}"
            v="${v%.tar.gz}"
            echo "https://zlib.net/fossils/$1 https://github.com/madler/zlib/releases/download/v$v/$1"
            ;;
        openssl-*.tar.gz)
            local v="${1#openssl-}"
            v="${v%.tar.gz}"
            echo "https://www.openssl.org/source/old/${v%.*}/$1 https://github.com/openssl/openssl/releases/download/openssl-$v/$1"
            ;;
        libxml2-*.tar.gz)
            local v="${1#libxml2-}"
            v="${v%.tar.gz}"
            echo "https://download.gnome.org/sources/libxml2/${v%.*}/libxml2-$v.tar.xz"
            ;;
    esac
}
info "Downloading the sources ffmpeg/cppbuild.sh uses"
SKIPPED=()
download() {
    local url="$1" name="$2" https candidates=()
    case "$name" in
        x264-* | x265-*)
            # Only the -gpl build of FFmpeg uses them: not in these packages.
            SKIPPED+=("$name")
            return 0
            ;;
    esac
    https="${url/#http:\/\//https://}"
    candidates+=("$https")
    [[ "$https" == "$url" ]] || candidates+=("$url")
    # shellcheck disable=SC2207
    candidates+=($(fallbacks "$name"))
    fetch_any ffmpeg-libraries "$name" "${candidates[@]}" >/dev/null
    echo "    $name"
}
# The version variables and the download lines of cppbuild.sh, evaluated with
# the download function above: the list follows the build script itself.
DOWNLOADS="$(grep -E '^[A-Z][A-Z0-9_]*=|^download ' "$CPPBUILD")"
grep -q '^FFMPEG_VERSION=' <<<"$DOWNLOADS" || die "no FFMPEG_VERSION in ffmpeg/cppbuild.sh"
grep -q '^download ' <<<"$DOWNLOADS" || die "no download lines in ffmpeg/cppbuild.sh"
set +u
eval "$DOWNLOADS"
set -u
FFMPEG_VERSION_USED="$(grep -E '^FFMPEG_VERSION=' "$CPPBUILD" | cut -d= -f2)"

info "Fetching Raspberry Pi userland $USERLAND_COMMIT"
git_snapshot raspberrypi-userland https://github.com/raspberrypi/userland "$USERLAND_COMMIT" \
    "raspberrypi-userland-$USERLAND_COMMIT.tar.gz"

# --- The Java runtime (Eclipse Temurin) ------------------------------------------------------

MAJOR="${TEMURIN_VERSION%%.*}"
TEMURIN_FILE_VERSION="${TEMURIN_VERSION//+/_}"
TEMURIN_TAG="jdk-${TEMURIN_VERSION//+/%2B}"
TEMURIN_FILE="OpenJDK${MAJOR}U-jdk-sources_${TEMURIN_FILE_VERSION}.tar.gz"
TEMURIN_URL="https://github.com/adoptium/temurin${MAJOR}-binaries/releases/download/$TEMURIN_TAG/$TEMURIN_FILE"
info "Downloading $TEMURIN_FILE"
curl -fsSL --retry 3 --retry-delay 5 -o "$STAGE/$TEMURIN_FILE" "$TEMURIN_URL" || die "cannot download $TEMURIN_URL"
curl -fsSL --retry 3 --retry-delay 5 -o "$WORK/temurin.sha256" "$TEMURIN_URL.sha256.txt" || die "cannot download $TEMURIN_URL.sha256.txt"
expected="$(awk '{print $1; exit}' "$WORK/temurin.sha256")"
actual="$(sha256sum "$STAGE/$TEMURIN_FILE" | cut -d' ' -f1)"
[[ "$expected" == "$actual" ]] || die "$TEMURIN_FILE has SHA-256 $actual, Adoptium says $expected"
entry "$TEMURIN_FILE" "$TEMURIN_URL (SHA-256 checked against Adoptium's .sha256.txt)"

# --- The AppImage runtime ------------------------------------------------------------------------

info "Fetching the AppImage runtime $RUNTIME_TAG, libfuse and squashfuse"
git_snapshot appimage-type2-runtime https://github.com/AppImage/type2-runtime "$RUNTIME_COMMIT" \
    "appimage/type2-runtime-$RUNTIME_TAG.tar.gz"
for spec in "fuse-3.15.0.tar.xz $FUSE_URL $FUSE_SHA256" "squashfuse-0.5.2.tar.gz $SQUASHFUSE_URL $SQUASHFUSE_SHA256"; do
    read -r name url sha <<<"$spec"
    mkdir -p "$STAGE/appimage"
    curl -fsSL --retry 3 --retry-delay 5 -o "$STAGE/appimage/$name" "$url" || die "cannot download $url"
    actual="$(sha256sum "$STAGE/appimage/$name" | cut -d' ' -f1)"
    [[ "$actual" == "$sha" ]] || die "$url has SHA-256 $actual, expected $sha"
    entry "appimage/$name" "$url"
done

# --- SOURCES.txt and the archive ------------------------------------------------------------------

{
    cat <<EOF
Third-party source code of the WiFi Audio Streaming desktop packages
=====================================================================

This archive holds the source code of the third-party components of the
packages released next to it, as described in third_party_licenses.txt of
the app (section 8).

- FFmpeg $FFMPEG_VERSION_USED and the libraries compiled into it: built by Bytedeco with
  javacpp-presets/ffmpeg/cppbuild.sh (JavaCPP Presets $PRESETS_VERSION, commit
  $PRESETS_COMMIT). That script downloads the archives in ffmpeg-libraries/,
  then changes some of them before building: the patches in
  javacpp-presets/ffmpeg/ (ffmpeg.patch, and ffmpeg-macosx.patch on macOS, to
  FFmpeg; lame.patch to LAME; openssl-android.patch to OpenSSL;
  libvpx-linux-arm.patch to libvpx on linux-arm64) and the "sedinplace" edits
  it contains. The linux-arm64 build also uses
  raspberrypi-userland-$USERLAND_COMMIT.tar.gz, built as
  javacpp-presets/.github/actions/deploy-ubuntu/action.yml shows.
  Not included: ${SKIPPED[*]:-nothing} (x264 and x265 are only used by the
  GPL build of FFmpeg, which these packages do not contain).
- Java runtime: $TEMURIN_FILE, the source of Eclipse Temurin $TEMURIN_VERSION.
- AppImage runtime: appimage/, the type2-runtime release $RUNTIME_TAG and the
  libfuse and squashfuse sources its build script pins.

FILES
EOF
    cat "$MANIFEST"
} >"$STAGE/SOURCES.txt"

info "Writing $OUT"
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime=@0 \
    -cf "$OUT.partial" -C "$WORK/stage" "$TOP"
mv "$OUT.partial" "$OUT"
info "Done: $(du -h "$OUT" | cut -f1), SHA-256 $(sha256sum "$OUT" | cut -d' ' -f1)"
