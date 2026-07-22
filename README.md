# WiFi Audio Streaming (Desktop)

[![Available on GitHub](https://img.shields.io/badge/Available%20on-GitHub-181717?style=for-the-badge&logo=github)](https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop/releases)
[![Available on GitLab](https://img.shields.io/badge/Available%20on-GitLab-FC6D26?style=for-the-badge&logo=gitlab)](https://gitlab.com/marcomorosi.dev/WiFiAudioStreaming-Desktop/-/releases)
[![AUR version](https://img.shields.io/aur/version/wifi-audio-streaming-desktop?color=blue&logo=arch-linux)](https://aur.archlinux.org/packages/wifi-audio-streaming-desktop)

Turn your computer into a **wireless audio transmitter or receiver**.

This application allows you to send your PC's audio to any device on the same local network, or listen to audio from another device. It is designed to work seamlessly with the [Android version](https://github.com/marcomorosi06/WiFiAudioStreaming-Android).

🌐 **Website**: [marcomorosi.eu/wifi-audio-streaming](https://www.marcomorosi.eu/wifi-audio-streaming/)

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/marcomorosi)

---

# 📸 Overview

*Screenshots of the Material You interface.*
*(Note: The screenshots show the Italian interface, but the app automatically switches to **English** if your OS language is not set to Italian.)*

<table>
<tr>
<td align="center">
<img src="https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop/blob/master/images/server_wfas.jpg?raw=true" alt="Server Mode">
</td>
<td align="center">
<img src="https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop/blob/master/images/client_wfas.jpg?raw=true" alt="Client Mode">
</td>
<td align="center">
<img src="https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop/blob/master/images/settings_wfas.jpg?raw=true" alt="Settings">
</td>
</tr>
<tr>
<td align="center"><i>Server Mode</i></td>
<td align="center"><i>Client Mode</i></td>
<td align="center"><i>Settings</i></td>
</tr>
</table>

---

# ✨ Key Features

- **Server & Client Modes**
  Use the app to **send (Server)** or **receive (Client)** audio.

- **Native Audio Engine (Windows & macOS)**
  On Windows and macOS, audio is captured directly via a native C library loaded through JNI. No VB-Cable, no BlackHole, no third-party virtual drivers required. FFmpeg handles encoding only for the HTTP streaming protocols.

- **Microphone Routing**
  The server can receive microphone audio from a connected client and route it in three ways: ignore it, expose it as a **virtual microphone** to other apps (Discord, Zoom, games), or **mix it directly into the outgoing stream**. There is also a real-time mute button.

- **System Tray**
  The app lives in your system tray. Configure it to start minimized and close to tray so it runs silently in the background like AirPlay or Chromecast.

- **Automatic & Manual Discovery**
  Clients automatically find available servers on the network via multicast beacon, and the device list badges each server as Multicast/Unicast and whether it is encrypted or requires a key. If your router blocks it, you can manually enter the IP address.

- **Unicast & Multicast Support**
  - **Unicast** → direct streaming to a single device
  - **Multicast** → simultaneous transmission to multiple clients

- **Multiple Streaming Protocols**
  - **WFAS v2** (native protocol, lowest latency)
  - **RTP** (compatible with any RTP-capable receiver)
  - **HTTP/AAC** (Safari, iOS)
  - **HTTP/Opus WebM** (Chrome, Firefox, any browser)

  Peers running an incompatible protocol version are rejected immediately during the handshake with a clear error, instead of hanging silently.

- **Security & Encryption**
  Optionally gate who can connect (**Off**, **Ask** to approve each device, or **Key** for a pre-shared password checked via mutual HMAC-SHA256 challenge-response) and encrypt the audio end-to-end with **ChaCha20-Poly1305** (per-packet AEAD, anti-replay, keys derived via HKDF). Works for both unicast and multicast; no PKI required. See [`WFAS_PROTOCOL.md`](WFAS_PROTOCOL.md) §7–8.

- **Firewall Assistant**
  On Windows, the app can create the required inbound firewall rule automatically with one click. On Linux, it detects `ufw` or `firewalld` and shows the exact command to open the required ports.

- **Automatic Update Checker**
  Optionally checks GitHub for new releases on startup, or on demand, with release notes and a direct download link shown right in the app.

- **Command-Line Interface (`wfas`)**
  A full headless daemon and control tool, installable to your system's PATH during first-run setup: start a server or client, run `wfas control volume 75` or `wfas control mute` against a running instance, manage configuration profiles with `wfas config`, and fix Windows Firewall with `wfas firewall allow`. Add `--json` to almost any command for scripting. Run `wfas --viz` for a real-time ASCII audio visualizer, with theming (`--viz rainbow`) and a `--groove` mode that highlights melody over raw bass. Run `wfas --debug` for a live-updating table of UDP packets and internal state. See `wfas --help` or the man page for the full command reference.

- **Detailed Audio Configuration**
  Customize sample rate, bit depth, channels, and buffer size.

- **Modern Interface**
  A **Material You** interface built with Jetpack Compose for Desktop, with wallpaper-based dynamic theming.

- **Bilingual Support (EN/IT)**
  Automatically adapts to your OS language.

---

# 🖥️ Platform Support

| Platform | Architecture | Audio Capture | Tray |
|----------|-------------|---------------|------|
| Windows 10/11 | x86_64 | ✅ Native (no drivers needed) | ✅ |
| macOS 13+ (Ventura) | x86_64, arm64 | ✅ Native (no drivers needed) | ✅ |
| Linux | x86_64 | ✅ Native (no drivers needed) | ✅ |

> **macOS note:** ScreenCaptureKit (used by the native engine) requires macOS 12.3 or later. The app will ask for screen recording and audio permissions on first launch. Yes, there are quite a few permission dialogs. Grant them all and the audio quality is genuinely clean and crisp on the receiving end.
>
> **macOS legacy:** If you are on macOS 12.2 or earlier, you can still use the FFmpeg + BlackHole path by disabling the native engine in Settings → Advanced.

---

# 🚀 Getting Started

## Send Audio (Server Mode)

1. Open the app and select **Send (Server)**.
2. On **Linux**, the virtual sink is created automatically. On **Windows and macOS**, the native engine captures system audio directly with no extra setup.
3. Select **Multicast** (multiple clients) or **Unicast** (single client). In Unicast the server serves one client at a time: while a session is running it stops advertising itself, and any other device that tries to connect is told the server is busy instead of being left waiting.
4. Optionally set an authorization mode (**Off**, **Ask**, or **Key**) in Settings → Security to control who can connect, and enable encryption if you set a key.
5. Click **Start Server**.

## Receive Audio (Client Mode)

1. Open the app and select **Receive (Client)**.
2. Choose your physical output device (headphones, speakers).
3. The app will automatically list active servers on the network.
4. Select one to connect. If it's password-protected, you'll be prompted for the key.

If the server does not appear automatically, enter its local IP address manually.

If the server is already streaming to another device in Unicast mode, the app reports *"That server is already streaming to another device"* immediately instead of waiting for a timeout.

If Windows Firewall is blocking the connection, the app will offer to fix it for you automatically.

---

# 🎙️ Microphone Routing

If you are using the Android app as a microphone source, the desktop server can handle the incoming audio in three ways, selectable from the UI:

| Mode | What it does |
|------|-------------|
| **Off** | Ignores the incoming mic stream |
| **Virtual Microphone** | Exposes the mic as a virtual input device, visible in Discord, Zoom, games |
| **Mix Into Stream** | Blends the mic into the outgoing audio directly |

A mute button is available at any time during streaming.

---

# ⌨️ Command-Line Interface

WiFi Audio Streaming ships with `wfas`, a fully-featured CLI and headless daemon, useful for scripting, running on a headless box, or just staying in the terminal.

```bash
wfas --server --rtp                     # start a headless RTP server
wfas --client 192.168.1.42              # connect as a client
wfas --mode discover --json             # scan the network, output JSON
wfas control volume 75                  # adjust volume on a running instance
wfas control mute                       # toggle mic mute on a running instance
wfas config list                        # show every setting and its value
wfas config edit                        # edit the shared JSON configuration
wfas firewall allow                     # open the default ports (Windows)
wfas --viz rainbow                      # ASCII audio visualizer, rainbow theme
wfas --monitor --groove                 # visualize local system audio only
wfas --debug                            # live UDP packet / state table
wfas --help                             # full command reference
```

During first-run setup, the app offers to install `wfas` to your system's PATH automatically.

---

# 📱 Android Version

Turn your smartphone into a **portable audio receiver or transmitter**.

<a href="https://github.com/marcomorosi06/WiFiAudioStreaming-Android">
<img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png" height="80">
</a>

<a href="https://gitlab.com/marcomorosi.dev/wifiaudiostreaming-android">
<img src="https://gitlab.com/marcomorosi.dev/WiFiAudioStreaming-Desktop/-/raw/master/images/get-it-on-gitlab-badge.png" height="80">
</a>

<a href="https://apt.izzysoft.de/packages/com.cuscus.wifiaudiostreaming">
<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" height="80">
</a>

---

# 🔌 Open Protocol & Embedded Devices

The WFAS v2 wire protocol is documented separately from the apps, with a tiny, dependency-free C99 reference implementation for embedded and firmware projects (ESP32, STM32, RP2040, Raspberry Pi, and more):

👉 [wfas-protocol on GitHub](https://github.com/marcomorosi06/wfas-protocol)

It has been tested end to end on real hardware, an ESP32 streaming audio from an analog line-in source, decoded live by both this desktop app and the Android app using the exact same protocol implementation. If you're experimenting with an analog capture setup like that, there's an optional **Noise Reduction** filter under Developer Settings that helps with the background hiss.

---

# 🛠️ Building from Source

Requires **JDK 17 or newer**.

```bash
git clone https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop.git
cd WiFiAudioStreaming-Desktop
```

The native C library is compiled as part of the build. On the first build (or after changes to `src/main/native/`), Gradle will invoke CMake automatically. On Windows, it searches for `cmake.exe` in all known locations (Visual Studio, CLion, Scoop, Chocolatey, winget).

### Run for testing

```bash
./gradlew run
# Windows:
gradlew.bat run
```

### Create distributable packages

| Format | Command |
|--------|---------|
| Portable app (all OS) | `./gradlew createDistributable` |
| Windows installer | `./gradlew packageMsi` |
| Debian / Ubuntu | `./gradlew packageDeb` |
| Fedora / CentOS | `./gradlew packageRpm` |
| macOS | `./gradlew packageDmg` |

Output: `build/compose/binaries/main/`

---

# 💻 Tech Stack

- **Language:** Kotlin
- **UI Framework:** Jetpack Compose for Desktop
- **Networking:** Ktor (UDP/TCP sockets)
- **Audio Capture:** Native C library via JNI (Windows, macOS) / FFmpeg via JavaCV (Linux)
- **Audio Encoding:** FFmpeg via JavaCV (AAC, Opus for HTTP streaming)
- **Cryptography:** HMAC-SHA256 challenge-response authentication, ChaCha20-Poly1305 AEAD encryption, HKDF-SHA256 key derivation (Bouncy Castle)

---

# ☕ Support the Project

This project is completely free and open-source. If it helped you, consider buying me a coffee.

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/marcomorosi)

---

# 📄 License

This project is licensed under the **European Union Public Licence v1.2 (EUPL v1.2)**.

It started as a personal script. Then it grew. Then I rewrote the audio engine during exam season, which is when I realized I had become genuinely invested in it, and that the license deserved a second thought. MIT lets anyone take the code and close it off. EUPL does not.

**You are free to:**
- Use, modify, and distribute the software
- Use it for commercial purposes

**Key obligations:**
- **Copyleft:** modified distributions must be released under the same EUPL license
- **Network copyleft:** if you run a modified version as a networked service, you must release the source
- **Attribution:** retain all copyright and trademark notices

For the full legal text, see `LICENSE.md` or visit the [official EUPL website](https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12).

The **app** is EUPL, but the **WFAS v2 wire protocol** is not locked up: a C reference implementation is published separately under the permissive **MIT License** ([`wfas-protocol`](https://github.com/marcomorosi06/wfas-protocol), © 2026 Marco Morosi), so anyone — including embedded/firmware projects — can implement WFAS v2 freely. The copyleft protects this application; the protocol stays open.

---

# 🧩 Third-Party Software & Licenses

This application bundles and uses several open-source components, each under its
own licence. The complete attribution list is in
**[`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md)**, and is also available
inside the app (Settings → *Open-source licenses*) and from the command line via
`wfas --licenses`.

In particular, this software uses **[FFmpeg](https://ffmpeg.org)** for AAC/Opus
audio encoding, distributed under the **GNU LGPL v2.1 or later** (some optional
components may be under the GPL). The FFmpeg native libraries are unmodified and
provided by the [JavaCPP Presets](https://github.com/bytedeco/javacpp-presets)
project; their source is available at <https://ffmpeg.org/download.html>. See
`THIRD_PARTY_LICENSES.md` for the full FFmpeg notice and relinking information.

Other bundled components include JavaCV/JavaCPP, JetBrains Compose Multiplatform,
Kotlin and kotlinx.coroutines, Ktor, Bouncy Castle, dorkbox SystemTray, JNA and
SLF4J — see the full list for versions, copyrights and licences.