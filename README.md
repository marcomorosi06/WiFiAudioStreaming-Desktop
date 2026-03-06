# WiFi Audio Streaming (Desktop)

[![Available on GitHub](https://img.shields.io/badge/Available%20on-GitHub-181717?style=for-the-badge&logo=github)](https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop/releases)  
[![Available on GitLab](https://img.shields.io/badge/Available%20on-GitLab-FC6D26?style=for-the-badge&logo=gitlab)](https://gitlab.com/marcomorosi.dev/WiFiAudioStreaming-Desktop/-/releases)

Turn your computer into a **wireless audio transmitter or receiver**.

This application allows you to send your PC's audio to any device on the same local network, or listen to audio from another device. It is designed to work seamlessly with the [Android version](https://github.com/marcomorosi06/WiFiAudioStreaming-Android).

---

# 📸 Overview

*Screenshots of the new Material You interface.*  
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

- **FFmpeg Audio Engine**  
  Powered by FFmpeg for high-stability, low-latency audio processing and synchronization.

- **Automatic & Manual Discovery**  
  Clients on the local network automatically find available servers via **mDNS**.  
  If your router blocks this, you can **manually enter the IP address** to force a connection.

- **Unicast & Multicast Support**  
  Choose between:
  - **Unicast** → direct streaming to a single device  
  - **Multicast** → simultaneous transmission to multiple clients (ideal for multi-room audio)

- **Detailed Audio Configuration**  
  Customize the audio stream with:
  - Sample rate
  - Bit depth
  - Channels (mono/stereo)
  - Buffer size

- **Modern Interface**  
  A clean **Material You** interface built with **Jetpack Compose for Desktop**.

- **Multi-Server Support**  
  Run multiple servers on the same network by changing the **connection port** in the settings.

- **Bilingual Support (EN/IT)**  
  The application automatically adapts to your operating system's language.

---

# 🖥️ Platform Support & Virtual Cables

To capture system audio in **Server Mode**, the application needs to route audio through a virtual cable.

## 🐧 Linux (Best Experience)

The application automatically creates and manages the virtual audio cable.  
**No extra installation required.**

## 🪟 Windows (Solid Experience)

You must manually install the free **VB-CABLE Virtual Audio Device**.

The app will automatically detect if it is present.

👉 https://vb-audio.com/Cable/index.htm

## 🍎 macOS (Experimental)

Support for macOS is currently **critically buggy**.

- Audio capture is highly experimental
- Client playback suffers from severe latency

Contributions are welcome.

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
2. Choose your audio output device  
   (e.g. *VB-CABLE* on Windows or the automatically generated virtual sink on Linux).
3. Select the streaming mode:
   - **Multicast** → multiple clients
   - **Unicast** → single client
4. Click **Start Server**.

---

## Receive Audio (Client Mode)

1. Start the app and select **Receive (Client)**.
2. Choose your **physical output device** (headphones, speakers, etc.).
3. The app will automatically display active servers on the network.
4. Select one to connect.

**Fallback:**  
If the server does not appear, manually enter its **local IP address**.

---

# 🛠️ Building & Packaging from Source

To compile the project you need **JDK 17 or newer** installed.

## 1️⃣ Clone the repository

```bash
git clone https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop.git
```

## 2️⃣ Enter the project directory

```bash
cd WiFiAudioStreaming-Desktop
```

## 3️⃣ Run the application (for testing)

```bash
./gradlew run
```

On **Windows**, use:

```bash
gradlew.bat run
```

## 📦 Create Installers / Executables

### Portable Application (All OS)

```bash
./gradlew createDistributable
```

Output directory:

```
build/compose/binaries/main/app/
```

### Windows Installer

```bash
./gradlew packageMsi
```

### Debian / Ubuntu

```bash
./gradlew packageDeb
```

### Fedora / CentOS

```bash
./gradlew packageRpm
```

### macOS

```bash
./gradlew packageDmg
```

---

# 💻 Tech Stack

- **Language:** Kotlin  
- **UI Framework:** Jetpack Compose for Desktop  
- **Networking:** Ktor (UDP sockets)  
- **Audio Engine:** FFmpeg (via command-line/process integration)

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
