# Third-Party Software & Licenses — WiFi Audio Streaming (Desktop)

Copyright (c) 2025-2026 Marco Morosi and contributors. The app is licensed
under the **EUPL v1.2** (see `LICENSE.md`), except for the two sound files
described below.

This page summarises the licences of what the desktop packages contain. The
complete, authoritative list — every component with its version, copyright and
licence — is
**[`src/main/resources/third_party_licenses.txt`](src/main/resources/third_party_licenses.txt)**,
and the full text of every licence it names is in
**[`src/main/resources/licenses/`](src/main/resources/licenses/)**
([`SOURCES.txt`](src/main/resources/licenses/SOURCES.txt) records where each text
was copied from). Both are inside the app: Settings → *Open-source licenses*,
`wfas --licenses`, and the `licenses/` folder of the WiFiAudioStreaming jar.

## Not under the EUPL

| File | Origin | Terms |
|---|---|---|
| `src/main/resources/raw/connection_sound.wav`, `src/main/resources/raw/disconnection_sound.wav` | Generated with ElevenLabs, free plan | Attribution "elevenlabs.io" required; no commercial use. Remove or replace them before any commercial redistribution. |

## Credits for the app's own content

- **Tux**: the image of Tux in the app was generated with ChatGPT; Tux was
  originally drawn by Larry Ewing (lewing@isc.tamu.edu) with The GIMP.
- **WFAS v2 protocol**: the C reference implementation is published separately
  under the MIT License ([`wfas-protocol`](https://github.com/marcomorosi06/wfas-protocol),
  © 2026 Marco Morosi). It is not bundled in this app.

## What the packages contain

| Component | Licence |
|---|---|
| FFmpeg 6.1.1 (`org.bytedeco:ffmpeg:6.1.1-1.5.10`, no GPL components), separate shared libraries | **LGPL-3.0-or-later** (configured with `--enable-version3`; `libavutil` reports "LGPL version 3 or later") |
| Compiled into FFmpeg: zlib, LAME, Speex, Opus, OpenCORE AMR, VisualOn AMR-WB, OpenSSL 3.2.0, OpenH264, libvpx, FreeType, libxml2, SRT, libwebp, libaom, SVT-AV1, nv-codec-headers, Vulkan-Headers; on Windows also mfx_dispatch, winpthreads and the GCC runtime | zlib, LGPL-2.0-or-later (LAME), BSD-2/BSD-3/BSD-3-Clear, Apache-2.0, FreeType License, MIT, MPL-2.0 (SRT), AOM Patent License 1.0, GCC Runtime Library Exception |
| Separate libraries in the FFmpeg jar: alsa-lib 1.2.10 and Raspberry Pi userland (linux-arm64), libdrm (linux-x86_64) | LGPL-2.1-or-later, BSD-3-Clause, MIT |
| JavaCPP, JavaCV, JavaCPP Presets 1.5.10 (Java bindings only for the presets other than FFmpeg) | Apache-2.0 or GPL-2.0 with Classpath Exception |
| Skiko 0.8.18 with Skia m126 and HarfBuzz, FreeType, ICU 74, libjpeg-turbo, libpng, libwebp, Expat, zlib, Wuffs, piex, Adobe DNG SDK | Apache-2.0, BSD-3-Clause, MIT, FreeType License, Unicode License v3, IJG, libpng-2.0, zlib, DNG SDK License |
| Kotlin, kotlinx (coroutines, datetime, io, serialization, atomicfu), Compose Multiplatform and the AndroidX libraries it uses, Ktor, ZXing, JNA (with libffi), dorkbox, Javassist, Java UUID Generator, JetBrains annotations | Apache-2.0 (JNA and Javassist used under Apache-2.0; libffi MIT; dorkbox include MIT, BSD-3-Clause and CC0 code) |
| Bouncy Castle 1.78.1 | Bouncy Castle Licence (MIT) |
| SLF4J 2.0.16 / 2.0.9 | MIT |
| Java runtime: Eclipse Temurin 17.0.20+8 | GPL-2.0 with Classpath Exception (notices in the runtime's `legal` folder) |
| AppImage type2-runtime 20251108 (`.AppImage` files only) with libfuse 3.15.0, squashfuse, Zstandard, zlib, musl, mimalloc | MIT, LGPL-2.1, BSD-2/BSD-3, zlib |

## Source code

Every release has, next to the packages,
`wifi-audio-streaming-<version>-third-party-sources.tar`, made by
[`scripts/package-third-party-sources.sh`](scripts/package-third-party-sources.sh):
the JavaCPP Presets build scripts and patches with the source of FFmpeg and of
every library compiled into it, the OpenJDK source of the bundled Eclipse
Temurin runtime, and the source of the AppImage runtime with libfuse and
squashfuse. The source of the app itself is this repository at the release tag.

FFmpeg is loaded at run time from `ffmpeg-6.1.1-1.5.10-<platform>.jar` as
separate shared libraries: you can replace them with your own build of FFmpeg
6.1.1 (same file names and ABI) by replacing that jar with one that contains
your libraries at the same paths.
