#!/usr/bin/env bash
# Build a minimal, stream-copy-only ffmpeg + ffprobe for Android.
#
# yt-dlp only uses ffmpeg to merge separate video/audio streams and to fix up containers,
# always with `-c copy`. So this build has no decoders, encoders, network or external
# libraries, which keeps it to a few MB per ABI instead of 30+.
#
# Output: .cache/ffmpeg/<abi>/{ffmpeg,ffprobe}; build_android_runtime.py picks them up and
# ships them as libffmpeg.so / libffprobe.so. Without them the module falls back to
# Android's MediaMuxer (H.264/AAC only).
#
# Requires Linux or macOS with make, curl and an NDK r27+ in ANDROID_NDK_HOME.
# NOTE: not yet verified end to end; .github/workflows/android-runtime.yml runs it on CI.
#
# Usage: scripts/build_ffmpeg_android.sh [abi...]    (default: arm64-v8a x86_64)
set -euo pipefail

FFMPEG_VERSION=8.1.3
FFMPEG_SHA256=7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3
API=24

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/.cache"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "Set ANDROID_NDK_HOME to an Android NDK r27+" >&2
  exit 1
fi

case "$(uname -s)" in
  Linux) HOST_TAG=linux-x86_64 ;;
  Darwin) HOST_TAG=darwin-x86_64 ;;
  *) echo "Unsupported host $(uname -s); use Linux, macOS or the GitHub workflow" >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

TARBALL="$CACHE/downloads/ffmpeg-$FFMPEG_VERSION.tar.xz"
mkdir -p "$CACHE/downloads" "$CACHE/src"
if [ ! -f "$TARBALL" ] || [ "$(sha256 "$TARBALL")" != "$FFMPEG_SHA256" ]; then
  curl -fsSL -o "$TARBALL" "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
  if [ "$(sha256 "$TARBALL")" != "$FFMPEG_SHA256" ]; then
    echo "sha256 mismatch for $TARBALL" >&2
    exit 1
  fi
fi
SRC="$CACHE/src/ffmpeg-$FFMPEG_VERSION"
[ -d "$SRC" ] || tar -xJf "$TARBALL" -C "$CACHE/src"

build_abi() {
  local abi=$1 arch triple
  case "$abi" in
    arm64-v8a) arch=aarch64; triple=aarch64-linux-android ;;
    x86_64) arch=x86_64; triple=x86_64-linux-android ;;
    *) echo "Unsupported ABI $abi" >&2; exit 1 ;;
  esac
  local build="$CACHE/build/$abi/ffmpeg" out="$CACHE/ffmpeg/$abi"
  rm -rf "$build"
  mkdir -p "$build" "$out"

  (
    cd "$build"
    "$SRC/configure" \
      --target-os=android --arch="$arch" --enable-cross-compile \
      --cc="$TOOLCHAIN/bin/${triple}${API}-clang" \
      --ar="$TOOLCHAIN/bin/llvm-ar" --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
      --nm="$TOOLCHAIN/bin/llvm-nm" --strip="$TOOLCHAIN/bin/llvm-strip" \
      --sysroot="$TOOLCHAIN/sysroot" \
      --extra-ldflags="-Wl,-z,max-page-size=16384" \
      --disable-everything --disable-autodetect --disable-network \
      --disable-doc --disable-debug --disable-asm \
      --disable-shared --enable-static --enable-pic --enable-small \
      --disable-ffplay --enable-ffmpeg --enable-ffprobe \
      --enable-protocol=file,pipe \
      --enable-demuxer=mov,matroska,aac,mp3,ogg,mpegts,flv,h264,hevc,av1 \
      --enable-muxer=mp4,mov,ipod,matroska,webm,mp3,ogg,opus,adts,mpegts \
      --enable-parser=h264,hevc,av1,vp8,vp9,aac,aac_latm,opus,vorbis,mpegaudio \
      --enable-bsf=aac_adtstoasc,h264_mp4toannexb,hevc_mp4toannexb,vp9_superframe,av1_frame_split,extract_extradata
    make -j"$(getconf _NPROCESSORS_ONLN)" ffmpeg ffprobe
  )
  cp "$build/ffmpeg" "$build/ffprobe" "$out/"
  echo "$abi: ffmpeg $(du -h "$out/ffmpeg" | cut -f1), ffprobe $(du -h "$out/ffprobe" | cut -f1) -> $out"
}

if [ $# -eq 0 ]; then set -- arm64-v8a x86_64; fi
for abi in "$@"; do
  build_abi "$abi"
done
