# Third-Party Software & Licenses — WiFi Audio Streaming (Desktop)

This application is licensed under the **EUPL v1.2** (see `LICENSE.md`).
It bundles and uses the third-party open-source components listed below. Each
remains the property of its respective authors and is distributed under its own
licence. We are grateful to all of these projects.

> ⚠️ **FFmpeg (LGPL) — important:** see the dedicated section below. FFmpeg is
> distributed under the GNU LGPL v2.1 or later (some optional components may be
> under the GPL). Its source code and the build scripts are publicly available
> as required by that licence.

---

## Summary table

| Component | Version | Licence | Copyright |
|---|---|---|---|
| FFmpeg (native libs, via JavaCPP Presets) | 6.1.1 (preset 1.5.10) | **LGPL v2.1+** (some parts GPL — see note) | The FFmpeg developers |
| JavaCV | 1.5.10 | Apache License 2.0 | Samuel Audet / Bytedeco |
| JavaCPP | 1.5.10 | Apache License 2.0 (or GPLv2 + Classpath Exception) | Samuel Audet / Bytedeco |
| Bytedeco FFmpeg preset | 1.5.10 | Apache License 2.0 (preset code) | Bytedeco |
| JetBrains Compose Multiplatform (Desktop, Material 3, icons) | 1.7.3 | Apache License 2.0 | JetBrains s.r.o. and Google LLC |
| Kotlin standard library | 2.1.0 | Apache License 2.0 | JetBrains s.r.o. |
| kotlinx.coroutines | 1.9.0 | Apache License 2.0 | JetBrains s.r.o. |
| Ktor (`ktor-network`, `ktor-network-tls`) | 3.0.3 | Apache License 2.0 | JetBrains s.r.o. |
| Bouncy Castle (`bcprov`, `bctls`, `bcpkix` jdk18on) | 1.78.1 | Bouncy Castle Licence (MIT-style) | The Legion of the Bouncy Castle Inc. |
| dorkbox SystemTray (+ Utilities, OS, Collections, Executor, Updates) | 4.4 | Apache License 2.0 | dorkbox, llc |
| Java Native Access (JNA, JNA Platform) | 5.13.0 | Apache License 2.0 / LGPL 2.1 (dual) | Timothy Wall and contributors |
| SLF4J (`slf4j-nop`) | 2.0.9 | MIT License | QOS.ch Sàrl |
| kotlinx-datetime / kotlinx-io (transitive) | — | Apache License 2.0 | JetBrains s.r.o. |
| Java UUID Generator (transitive) | 4.2.0 | Apache License 2.0 | FasterXML, LLC |

Versions reflect `build.gradle.kts` at the time of writing; transitive
dependencies inherit the licence of their project.

---

## FFmpeg (LGPL v2.1 or later)

This application uses **FFmpeg** (https://ffmpeg.org) for AAC/Opus audio
encoding in the optional HTTP streaming server and, in the legacy capture path,
for audio grabbing. The FFmpeg native libraries are **not** modified by us; they
are obtained, unchanged, from the **JavaCPP Presets** project
(https://github.com/bytedeco/javacpp-presets) and loaded dynamically at runtime
as separate shared libraries (`avcodec`, `avformat`, `avutil`, …).

FFmpeg is free software licensed under the **GNU Lesser General Public License
(LGPL) version 2.1 or later**. Depending on how the binaries were configured,
some optional FFmpeg components may be licensed under the **GNU General Public
License (GPL)**.

In accordance with the LGPL:

* **Copyright:** © the FFmpeg developers. FFmpeg is a trademark of Fabrice
  Bellard, originator of the FFmpeg project.
* **Source code:** the complete corresponding source of FFmpeg is available at
  <https://ffmpeg.org/download.html>, and the exact build configuration and
  scripts used to produce the bundled binaries are available from the JavaCPP
  Presets project at <https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg>.
* **A copy of the LGPL v2.1** is provided at <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>
  (and the GPL at <https://www.gnu.org/licenses/gpl-3.0.html>).
* **Relinking (LGPL §6):** because FFmpeg is shipped as separate, dynamically
  loaded shared libraries, users may replace them with their own (compatible)
  build of FFmpeg.

> 🔧 **Maintainer action — verify the exact FFmpeg licence of the shipped build.**
> The JavaCPP Presets FFmpeg binaries may be built either as pure LGPL or with
> GPL components enabled (e.g. x264/x265). If GPL components are included, the
> combined distribution must be under a GPL-compatible licence — EUPL v1.2 is
> one-way compatible with the GPL, but you should confirm this consciously.
> You can check the actual licence at runtime via JavaCV:
> `org.bytedeco.ffmpeg.global.avutil.avutil_license()` (returns e.g.
> `"LGPL version 2.1 or later"` or `"GPL version 2 or later"`), and the build
> options via `org.bytedeco.ffmpeg.global.avutil.av_version_info()`. Record the
> result here once verified.

---

## Apache License 2.0

The following components are licensed under the Apache License, Version 2.0
(<https://www.apache.org/licenses/LICENSE-2.0>):

* JavaCV, JavaCPP and the Bytedeco FFmpeg preset — © Samuel Audet / Bytedeco
* JetBrains Compose Multiplatform (Desktop, Material 3, Material Icons Extended) — © JetBrains s.r.o. and Google LLC
* Kotlin standard library — © JetBrains s.r.o.
* kotlinx.coroutines, kotlinx-datetime, kotlinx-io — © JetBrains s.r.o.
* Ktor — © JetBrains s.r.o.
* dorkbox SystemTray, Utilities, OS, Collections, Executor, Updates — © dorkbox, llc
* Java Native Access (JNA) — © Timothy Wall and contributors (dual-licensed Apache 2.0 / LGPL 2.1; used here under Apache 2.0)
* Java UUID Generator — © FasterXML, LLC

> A full copy of the Apache License 2.0 is distributed with each of these
> projects and is reproduced at the URL above. Any `NOTICE` files shipped by
> these projects are preserved in their respective JARs.

---

## Bouncy Castle Licence

Bouncy Castle (`bcprov-jdk18on`, `bctls-jdk18on`, `bcpkix-jdk18on` 1.78.1) is
© 2000–2024 The Legion of the Bouncy Castle Inc. (<https://www.bouncycastle.org>)
and is distributed under an MIT-style licence:

```
Copyright (c) 2000–2024 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. ...
```

---

## MIT License

SLF4J (`slf4j-nop` 2.0.9) is © 2004–2023 QOS.ch Sàrl and is distributed under
the MIT License (<https://www.slf4j.org/license.html>).

---

## How to regenerate this list

The authoritative source is `build.gradle.kts`. After changing dependencies,
update the table above. To dump the full resolved dependency tree (including
transitive dependencies) run:

```
./gradlew dependencies --configuration runtimeClasspath
```
