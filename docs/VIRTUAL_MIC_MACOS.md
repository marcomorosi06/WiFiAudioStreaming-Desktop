# Virtual Microphone on macOS — BlackHole

> 🇬🇧 English below · 🇮🇹 Italiano più sotto

When the desktop app acts as **server** and you enable **Microphone routing → Virtual Mic**,
WFAS takes the microphone sent by the connected phone and feeds it into a virtual audio
device. Apps like Discord, Zoom, Teams or OBS can then select that device as their microphone.

macOS has **no built-in virtual audio device**, so a small free, open-source one is required:
**BlackHole (2ch)**. You install it once.

---

## 🇬🇧 English

### 1. Install BlackHole 2ch

**Option A — installer (simplest):**
1. Go to <https://existential.audio/blackhole/> and request the **2ch** version
   (you receive a download link by email).
2. Open the downloaded `.pkg` and follow the installer (it asks for your admin password).
3. Log out and back in (or reboot).

**Option B — Homebrew:**
```bash
brew install blackhole-2ch
```
Then log out and back in.

After installing, **BlackHole 2ch** appears both as an **output** and as an **input** device.

### 2. Grant macOS permissions

1. **System Settings → Privacy & Security → Screen Recording → enable WFAS.**
   WFAS captures system audio via ScreenCaptureKit, which lives under Screen Recording.
   (Requires macOS 13 or newer.)
2. **System Settings → Privacy & Security → Microphone → enable Discord/Zoom.**

### 3. Configure WFAS

1. Open WFAS, switch to **Server**.
2. **Microphone routing → Virtual Mic.**
3. As the virtual-mic output device, select **BlackHole 2ch**.
4. Start the server and connect the phone with *Send microphone* enabled.

### 4. Configure Discord / Zoom / etc.

In the other app, set the **microphone (input)** to:

> **BlackHole 2ch**

### 5. Match the sample rate (fixes silence and distortion)

1. Open **Audio MIDI Setup** (Applications → Utilities).
2. Select **BlackHole 2ch** → set **Format to 48000 Hz, 2 ch** (or whatever WFAS uses).
3. Use the **same sample rate in WFAS** (Settings → Sample rate).

If Hz or channel count differ between BlackHole and WFAS you get silence or
fast/slow/robotic audio.

### 6. Still want to hear other audio? (Multi-Output Device)

BlackHole is only used as the **microphone return path**, so you normally don't need this.
But if you also route *playback* through BlackHole and lose your speakers, create a
**Multi-Output Device** in *Audio MIDI Setup* (`+` button) that includes both your
speakers/headphones **and** BlackHole, and select it as the system output.

### 7. Removing the echo

Echo happens because the **phone's microphone picks up the phone's own speaker**
playing back the streamed audio. Fixes, in order of effectiveness:

1. **Use headphones/earbuds on the phone** — removes the echo loop completely.
2. The Android app now enables **hardware echo cancellation + noise suppression**
   automatically when sending the mic, which strongly reduces it.
3. Lower the phone speaker volume.

### 8. Uninstall

```bash
brew uninstall blackhole-2ch
```
or, if installed via the `.pkg`, remove
`/Library/Audio/Plug-Ins/HAL/BlackHole2ch.driver` and reboot.

### Quick troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Discord shows no mic activity | BlackHole not selected as mic | Select **BlackHole 2ch** as input |
| Total silence | Sample-rate mismatch | Set BlackHole + WFAS to 48000 Hz |
| Robotic / fast / slow audio | Channel or rate mismatch | Match channels and Hz everywhere |
| No system audio captured | Screen Recording permission off | Enable WFAS under Screen Recording |
| Strong echo | Phone mic hears phone speaker | Use headphones on the phone |

---

## 🇮🇹 Italiano

Quando l'app desktop fa da **server** e attivi **Routing del microfono → Microfono virtuale**,
WFAS prende il microfono inviato dal telefono connesso e lo inietta in un dispositivo audio
virtuale. App come Discord, Zoom, Teams o OBS possono poi selezionarlo come microfono.

macOS **non ha un dispositivo audio virtuale integrato**, quindi ne serve uno piccolo,
gratuito e open-source: **BlackHole (2ch)**. Si installa una volta sola.

### 1. Installare BlackHole 2ch

**Opzione A — installer (più semplice):**
1. Vai su <https://existential.audio/blackhole/> e richiedi la versione **2ch**
   (ricevi un link per email).
2. Apri il `.pkg` scaricato e segui l'installer (chiede la password di amministratore).
3. Esci e rientra dall'account (o riavvia).

**Opzione B — Homebrew:**
```bash
brew install blackhole-2ch
```
Poi esci e rientra dall'account.

Dopo l'installazione, **BlackHole 2ch** compare sia come dispositivo di **uscita** sia di
**ingresso**.

### 2. Concedere i permessi macOS

1. **Impostazioni di Sistema → Privacy e sicurezza → Registrazione schermo → abilita WFAS.**
   WFAS cattura l'audio di sistema tramite ScreenCaptureKit, che sta sotto Registrazione
   schermo. (Richiede macOS 13 o successivo.)
2. **Impostazioni di Sistema → Privacy e sicurezza → Microfono → abilita Discord/Zoom.**

### 3. Configurare WFAS

1. Apri WFAS, passa a **Server**.
2. **Routing del microfono → Microfono virtuale.**
3. Come dispositivo di uscita del microfono virtuale, seleziona **BlackHole 2ch**.
4. Avvia il server e connetti il telefono con *Invia microfono* attivo.

### 4. Configurare Discord / Zoom / ecc.

Nell'altra app imposta il **microfono (ingresso)** su:

> **BlackHole 2ch**

### 5. Allineare il sample rate (risolve silenzio e distorsione)

1. Apri **Audio MIDI Setup** (Applicazioni → Utility).
2. Seleziona **BlackHole 2ch** → imposta **Formato 48000 Hz, 2 canali**
   (o ciò che usa WFAS).
3. Usa lo **stesso sample rate in WFAS** (Impostazioni → Sample rate).

Se Hz o numero di canali differiscono tra BlackHole e WFAS ottieni silenzio o audio
veloce/lento/robotico.

### 6. Vuoi comunque sentire l'altro audio? (Dispositivo Multi-Uscita)

BlackHole serve solo come **percorso di ritorno del microfono**, quindi di norma non
è necessario. Ma se instradi anche la *riproduzione* in BlackHole e perdi gli altoparlanti,
crea un **Dispositivo Multi-Uscita** in *Audio MIDI Setup* (pulsante `+`) che includa sia
i tuoi altoparlanti/cuffie **sia** BlackHole, e selezionalo come uscita di sistema.

### 7. Togliere l'eco

L'eco nasce perché il **microfono del telefono capta l'altoparlante del telefono stesso**
che riproduce l'audio in streaming. Rimedi, dal più efficace:

1. **Usa cuffie/auricolari sul telefono** — elimina del tutto il loop di eco.
2. L'app Android ora attiva **cancellazione d'eco + soppressione del rumore hardware**
   in automatico quando invia il microfono, riducendola molto.
3. Abbassa il volume dell'altoparlante del telefono.

### 8. Disinstallare

```bash
brew uninstall blackhole-2ch
```
oppure, se installato via `.pkg`, rimuovi
`/Library/Audio/Plug-Ins/HAL/BlackHole2ch.driver` e riavvia.

### Risoluzione rapida

| Sintomo | Causa | Soluzione |
|---|---|---|
| Discord non mostra attività mic | BlackHole non selezionato come mic | Seleziona **BlackHole 2ch** come ingresso |
| Silenzio totale | Sample rate non allineato | Imposta BlackHole + WFAS a 48000 Hz |
| Audio robotico / veloce / lento | Canali o Hz diversi | Allinea canali e Hz ovunque |
| Audio di sistema non catturato | Permesso Registrazione schermo off | Abilita WFAS in Registrazione schermo |
| Eco forte | Il mic del telefono sente l'altoparlante | Usa le cuffie sul telefono |
