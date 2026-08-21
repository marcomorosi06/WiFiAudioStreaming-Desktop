# 🚀 WiFi Audio Streaming v1.2-rebuild (Desktop & Android)

**WiFi Audio Streaming `v1.2-rebuild`** is a custom rebuild based on the latest upstream `v1.2` release by Marco Morosi, maintained and enhanced by **Ali_ (`alithw`)**.

This edition introduces **20 global languages** with real-time dynamic localization, comprehensive **encrypted password persistence (Encrypted Vault)** on both Desktop and Android, fixes player/auto-connect state deadlocks on password-protected servers, and includes multiple stability improvements.

---

## 🌟 What's New & Highlights

### 🌐 1. Full 20 Global Languages Support & Real-time Language Switcher
- **Comprehensive Multilingual Localization**: Full translations for 20 major world languages:
  - 🇬🇧 **English**, 🇻🇳 **Tiếng Việt (Vietnamese)**, 🇮🇹 **Italiano (Italian)**, 🇪🇸 **Español (Spanish)**, 🇫🇷 **Français (French)**, 🇩🇪 **Deutsch (German)**, 🇵🇹 **Português (Portuguese)**, 🇷🇺 **Русский (Russian)**, 🇯🇵 **日本語 (Japanese)**, 🇰🇷 **한국어 (Korean)**, 🇨🇳 **简体中文 (Simplified Chinese)**, 🇹🇼 **繁體中文 (Traditional Chinese)**, 🇸🇦 **العربية (Arabic)**, 🇮🇳 **हिन्दी (Hindi)**, 🇮🇩 **Bahasa Indonesia (Indonesian)**, 🇹🇷 **Türkçe (Turkish)**, 🇵🇱 **Polski (Polish)**, 🇳🇱 **Nederlands (Dutch)**, 🇹🇭 **ไทย (Thai)**, 🇺🇦 **Українська (Ukrainian)**.
- **Dynamic Runtime Switching**: Added `LanguageSelector` dropdown under **Settings -> Appearance**. Selecting a new language immediately re-renders all UI strings on the fly (Reactive Compose) without requiring an app restart.
- **Synchronized Across Platforms**: Fully unified between the **Desktop** and **Android** applications.

---

### 🔐 2. Encrypted Vault & Seamless Auto-Login
- **OS-Native Hardware & TEE Security**:
  - **Desktop**: Integrated with `SecretVault` using **Windows DPAPI** (`CryptProtectData`), **macOS Keychain**, and **Linux SecretService**.
  - **Android**: Integrated with `SecretStore` backed by **Android Keystore (AES256-GCM / TEE)** and `EncryptedSharedPreferences`.
- **Seamless Reconnect**:
  - Checking *"Remember password"* during connection securely saves the preshared key indexed by server identity (`IP`, `hostname`, `IP:Port`).
  - Normal disconnections **never wipe** the saved credentials. Subsequent reconnections automatically complete the HMAC-SHA256 handshake without interrupting the user with password modals.
- **Intelligent Auto-Forget**:
  - If a server updates its password or returns `WFAS_UNAUTHORIZED`, the invalid key is instantly wiped from the Vault, and the credential modal pops up immediately for the new password.
- **Saved Servers Manager**:
  - Added a dedicated **Saved Servers** tab in **Settings**, allowing users to review all remembered devices and forget passwords individually with one click.

---

### 🛠️ 3. Auto-Connect & Player State Bug Fixes
- **Fixed Starred (`*`) Auto-Connect Auth Deadlock**:
  - Previously, auto-connecting to a password-protected server jumped directly into the active streaming screen (`isStreaming = true`) while suppressing the credential prompt (`clientKeyPromptAllowed = false`). Handshakes would fail silently, trapping the user in the streaming screen until manual disconnection.
  - **Resolved**: Auto-connect now queries the Vault first. If unauthenticated or wrong, the password dialog **pops up immediately** on top of discovery.
  - Added `status_key_required` to `clientDisconnectKeys` so cancelling the prompt cleanly releases the streaming state and restores the device discovery list.
- **Smart Auto-Connect Activation**:
  - Clicking the star (`*`) on any discovered server automatically toggles client auto-connect on so devices connect seamlessly when detected.

---

### ♾️ 4. Unicast Server Persist Mode
- In 1-to-1 (Unicast) mode, the server remains alive and listening for subsequent connections when a client disconnects, preventing unexpected server shutdowns.

---

## 📦 Release Deliverables

### 💻 Desktop (Windows / Linux / macOS)
- 🪟 **Windows Portable ZIP**: `WiFi-Audio-Streaming-1.2.0-windows-x86_64.zip` (`140.64 MB`)
- 🪟 **Windows Portable Tarball**: `WiFi-Audio-Streaming-1.2.0-windows-x86_64.tar.gz` (`140.43 MB`)
- 📁 **Windows Portable Directory**: `WiFi-Audio-Streaming-Portable` (Directly execute `WiFi Audio Streaming.exe` or CLI `wfas.cmd`)
- 🐧 **Linux (x86_64 & ARM64)**: `.AppImage`, `.deb`, `.rpm` *(Automated via GitHub Actions)*
- 🍏 **macOS (Apple Silicon & Intel)**: `.dmg` *(Automated via GitHub Actions)*

### 📱 Android
- 📱 **Android Package**: `WiFi-Audio-Streaming-Android-v1.2-rebuild.apk` (`37.52 MB`)

---

## 📜 Credits & License
- **Original Upstream Author**: [Marco Morosi](https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop) (Licensed under EUPL-1.2)
- **Rebuilt & Maintained by**: **Ali_ (`alithw`)**
  - Desktop: [github.com/alithw/WiFiAudioStreaming-Desktop](https://github.com/alithw/WiFiAudioStreaming-Desktop)
  - Android: [github.com/alithw/WiFiAudioStreaming-Android](https://github.com/alithw/WiFiAudioStreaming-Android)
