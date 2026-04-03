# WiFi Audio Streaming (Desktop)

[![Available on GitHub](https://img.shields.io/badge/Available%20on-GitHub-181717?style=for-the-badge&logo=github)](https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop/releases)  
[![Available on GitLab](https://img.shields.io/badge/Available%20on-GitLab-FC6D26?style=for-the-badge&logo=gitlab)](https://gitlab.com/marcomorosi.dev/WiFiAudioStreaming-Desktop/-/releases)

Turn your computer into a **wireless audio transmitter, receiver, or web server**.

This application allows you to send your PC's audio to any device on the same local network, listen to audio from another device, or stream directly to a web browser. It is designed to work seamlessly with the [Android version](https://github.com/marcomorosi06/WiFiAudioStreaming-Android).

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/marcomorosi)

---

# 📸 Overview

*Screenshots of the Material You interface.* *(Note: The screenshots show the Italian interface, but the app automatically switches to **English** if your OS language is not set to Italian.)*

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

- **Multi-Protocol Architecture** Support for the native low-latency **WFAS protocol**, alongside standard **RTP** and **HTTP** streams for maximum compatibility.

- **Integrated Web Player** Serve a high-performance web player (AAC/Opus) directly from the desktop app to any browser on your local network.

- **Smart Automations** - **Auto-Launch:** Start the application automatically with the OS boot.
  - **Instant Server Mode:** Boot directly into Server mode with pre-configured Unicast/Multicast preferences.
  - **Auto-Connect:** Automatically connect to specific, prioritized IP addresses as soon as they are detected online.

- **FFmpeg Audio Engine** Powered by FFmpeg for high-stability, low-latency audio processing, fast handshakes, and strict synchronization.

- **Automatic & Manual Discovery** Clients find available servers via **mDNS**. If mDNS is blocked, you can manually enter the IP address, complete with auto-detection for Unicast/Multicast modes.

- **Network Interface Selection** Specify the active network interface to easily bypass VPN routing issues or manage multiple LAN connections.

---

# 🖥️ Platform Support & Virtual Cables

To capture system audio in **Server Mode**, the application needs to route audio through a virtual cable.

## 🐧 Linux (Best Experience)
Automatic virtual audio cable creation and management, with excellent support for **PulseAudio** and **PipeWire**.

**Important for PipeWire users (e.g., Arch Linux):**
If you encounter an `"Error: Mixer not supported"` message, ensure you have both `pipewire-pulse` and `pipewire-alsa` installed. The application requires these compatibility layers to route ALSA calls properly through PipeWire without hardware conflicts.

Example for Arch Linux:
```bash
sudo pacman -S pipewire-pulse pipewire-alsa
```

## 🪟 Windows (Solid Experience)
You must manually install the free **VB-CABLE Virtual Audio Device**. The app will automatically detect its presence.  
👉 https://vb-audio.com/Cable/index.htm

## 🍎 macOS (Experimental)
Support for macOS is currently **critically buggy**. While there is preliminary compatibility for *BlackHole* virtual routing, audio capture is experimental and Client playback suffers from severe latency. Contributions are highly welcome.

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

# 🚀 Getting Started

## Send Audio (Server Mode)

1. Start the app and select **Send (Server)**.
2. Choose your audio output device (e.g. *VB-CABLE* or the Linux virtual sink).
3. Select the network interface, the streaming mode (Multicast, Unicast) and the protocols (Web, RTP).
4. Click **Start Server**.

## Receive Audio (Client Mode)

1. Start the app and select **Receive (Client)**.
2. Choose your **physical output device** (headphones, speakers, etc.).
3. The app will automatically display active servers. Select one to connect.

*(Tip: Enable Auto-Connect in Settings to streamline this process on future launches).*

---

# 🛠️ Building & Packaging from Source

Requires **JDK 17 or newer**.

```bash
git clone [https://gitlab.com/marcomorosi.dev/WiFiAudioStreaming-Desktop.git](https://gitlab.com/marcomorosi.dev/WiFiAudioStreaming-Desktop.git)
cd WiFiAudioStreaming-Desktop
```

```bash
./gradlew run
```

```cmd
gradlew.bat run
```

### Portable Application (All OS)

```bash
./gradlew createDistributable
```

### OS-Specific Installers

```bash
./gradlew packageMsi
```

```bash
./gradlew packageDeb
```

```bash
./gradlew packageRpm
```

```bash
./gradlew packageDmg
```

---

# 💻 Tech Stack

- **Language:** Kotlin  
- **UI Framework:** Jetpack Compose for Desktop  
- **Networking:** Ktor (UDP sockets, HTTP Server)  
- **Audio Engine:** FFmpeg (Native process integration)

---

# ☕ Support the Project

This project is completely free and open-source. If it helped you as much as it helped me, consider buying me a coffee to support its ongoing development and hardware testing costs!

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/marcomorosi)

---

# 📄 License

This project is released under the **MIT License**.

You are free to:
- use  
- modify  
- distribute  
- and even use the software commercially  

as long as the original copyright and license notice are included.
The software is provided **"as is"**, without warranty of any kind.
For the full legal text, see the `LICENSE.md` file included in this repository.
