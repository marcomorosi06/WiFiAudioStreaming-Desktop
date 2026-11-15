# Privacy Policy for WiFi Audio Streaming (Desktop)

Last updated: November 2026 (app version 1.2.1)

This policy describes what the **WiFi Audio Streaming** desktop app (and its
`wfas` command line) sends, to whom, what it keeps on the computer, and what
it changes on the system.

---

## 1. In short

- No account, no ads, no analytics, no crash reporting, no tracking.
- Nothing is sold or shared with third parties.
- Audio and discovery stay on your local network (or the USB link), between
  the devices you use.
- The only connection to the internet the app makes by itself is the update
  check described in section 3, which you can turn off.

---

## 2. What travels on your local network

These exchanges happen only between devices on the same network (or USB
tethering link), and only while you use the related feature.

- **Discovery beacon** (server mode): every few seconds the app sends a UDP
  multicast message to `239.255.0.1:9091` with this computer's **host name**,
  the streaming port and mode, the protocols offered, the audio format and the
  security hints (`auth=`, `enc=`, `mic=`). Anyone on the network can read it.
- **Audio stream**: the audio you choose to stream goes to the connected
  devices. With WFAS in Key or QR mode and encryption on (the default), it is
  encrypted with ChaCha20-Poly1305. **RTP, HTTP, DLNA and Snapcast are never
  encrypted and never ask for a key**: anyone on the network can listen to
  them. The app asks before starting a server where this applies, and the CLI
  prints a warning.
- **HTTP server** (when enabled): serves the stream and a small player page on
  the configured port. It does not allow other web sites to read the stream
  (no cross-origin access, WebSocket only from its own page, and only for
  local host names).
- **Talk-back microphone**: as a client, if you turn it on, your microphone is
  sent to the server, only after the server has completed the handshake, and
  encrypted when the WFAS session is. In an encrypted multicast group it is
  not sent at all. As a unicast server, talk-back is accepted only from the
  client that completed the handshake.
- **Snapcast and DLNA**: when enabled, the app announces the Snapcast service
  with the host name (mDNS) and searches for DLNA renderers (SSDP). As a
  Snapcast client it identifies itself to the server with a network adapter's
  hardware (MAC) address, as the reference `snapclient` does, so that the
  server remembers its volume and latency.
- **Security modes**: in **Off** mode any device on the network can connect
  and listen. In **Ask** mode you approve each device. In **Key** mode a
  device must prove it knows the key; the key itself never travels.

---

## 3. What goes to the internet

- **Update check**: on start (on by default; you can turn it off on the
  welcome screen, in Settings, or with `wfas --auto-check-update off`) and on
  request, the app sends one HTTPS request to `api.github.com` for the latest
  release tag of this app. GitHub receives your IP address and the user agent
  `WFAS-UpdateChecker`, as for any web request; no identifier or other data is
  sent. GitHub's handling of this request is covered by the
  [GitHub Privacy Statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
- **Third-party library ping, disabled**: the library behind the Linux tray
  icon (dorkbox SystemTray) contains an update check that would contact
  `updates.dorkbox.com` over plain HTTP. The app switches it off at startup.
- **Links you open**: release pages, the project site and the donation page
  open in your browser only when you click them.
- **Pairing links** (`https://www.marcomorosi.eu/wifi-audio-streaming/pair#...`):
  the pairing data, key included, is in the part after `#`, which a browser
  never sends to the server.

The app makes no other connection to the internet.

---

## 4. What is stored on the computer

- **Settings**: `config.json` in the `wfas` configuration folder
  (`%APPDATA%\wfas` on Windows, `~/Library/Application Support/wfas` on macOS,
  `$XDG_CONFIG_HOME/wfas` or `~/.config/wfas` on Linux). Secrets are never
  written there.
- **Secrets** (the pre-shared key and the keys of saved auto-connect servers):
  in the system credential store, Windows DPAPI (`secrets.dat` in the
  configuration folder, readable only by your Windows account), the macOS
  Keychain, or the Secret Service (GNOME Keyring, KWallet) on Linux. Without a
  credential store the key is not remembered and is asked for each time, or
  read from the `WFAS_AUTH_KEY` environment variable.
- **Small flags** (welcome screens seen, the highest multicast epoch accepted
  from each server) in Java preferences: the registry on Windows, a file under
  your home folder elsewhere.
- **Control session file**: while the app runs, a file with a random session
  token, readable only by you, in `$XDG_RUNTIME_DIR/wfas` or a private folder
  (`wfas-<user>` in the temp folder, or `~/.cache/wfas/run`). It is deleted on
  exit.
- **RTP descriptor**: when RTP is enabled, `stream.sdp` in the temp folder,
  describing the stream (address, port, format).
- No audio is recorded or saved: audio is processed in memory while streaming
  and discarded.

---

## 5. What the app changes on the system

- **`wifiaudio://` link handler**: registered for your account at every start,
  so pairing links open the app (`HKCU\Software\Classes\wifiaudio` on Windows,
  `~/.local/share/applications/wifi-audio-streaming-url.desktop` on Linux).
- **Only when you ask for it**: start at login (Run key, LaunchAgent or
  autostart entry), the `wfas` command on the PATH (on macOS a link in
  `/usr/local/bin`, after the administrator prompt; on Linux `~/.local/bin/wfas`
  and the man page, adding `MANPATH` to `~/.bashrc` and `~/.profile`; on
  Windows a folder in `%APPDATA%` added to your PATH), and the inbound
  firewall rules on Windows (after the administrator prompt).
- **Audio routing while streaming**: on Linux a virtual sink is created with
  PulseAudio/PipeWire and the default output is restored when streaming stops;
  on Windows the app remembers in `HKCU\Software\WiFiAudioStreaming` which
  output it muted, to restore it.

---

## 6. Changes

Changes to this policy are published in this file, in the source repository,
together with the app version they apply to.

---

## 7. Contact

For questions about this policy: **marcomorosi.dev@gmail.com**
