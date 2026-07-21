# Virtual Microphone on Windows — VB-CABLE

> 🇬🇧 English below · 🇮🇹 Italiano più sotto

When the desktop app acts as **server** and you enable **Microphone routing → Virtual Mic**,
WFAS takes the microphone sent by the connected phone and feeds it into a virtual audio
device. Apps like Discord, Zoom, Teams or OBS can then select that device as their microphone.

Windows has **no built-in virtual audio device**, so a small free driver is required:
**VB-CABLE**. You install it once.

---

## 🇬🇧 English

### 1. Install VB-CABLE

1. Download it from the official site: <https://vb-audio.com/Cable/>
   (button *Download VB-CABLE Driver Pack*).
2. Extract the ZIP to a folder.
3. **Right-click `VBCABLE_Setup_x64.exe` → Run as administrator.**
   (use `VBCABLE_Setup.exe` only on 32-bit Windows).
4. Click *Install Driver*, accept the prompts.
5. **Reboot the PC.** This step is mandatory — without it the device stays half-initialized.

After rebooting you will have two new devices:

| Device | Type | Where it appears |
|---|---|---|
| **CABLE Input** | Playback (output) | Windows *Playback* tab — WFAS writes here |
| **CABLE Output** | Recording (input) | Windows *Recording* tab — Discord reads here |

### 2. Configure WFAS

1. Open WFAS, switch to **Server**.
2. **Microphone routing → Virtual Mic.**
3. As the virtual-mic output device, select **CABLE Input (VB-Audio Virtual Cable)**.
   (WFAS also auto-detects it, but selecting it explicitly is more reliable.)
4. Start the server and connect the phone with *Send microphone* enabled.

### 3. Configure Discord / Zoom / etc. — ⚠️ the #1 mistake

In the other app, set the **microphone** to:

> **CABLE Output (VB-Audio Virtual Cable)**

**Not** *CABLE Input*. Input is where audio goes *in*, Output is where apps *read* it.
Picking the wrong one is the most common reason for *"no sound / mic not working"*.

### 4. Match the sample rate (fixes silence and distortion)

If you hear nothing, or robotic/too-fast/too-slow audio, the formats don't match:

1. Right-click the speaker icon → *Sounds* → **Playback** tab.
2. *CABLE Input* → *Properties* → *Advanced* → set **16 bit, 48000 Hz (DVD Quality)**.
3. **Recording** tab → *CABLE Output* → *Properties* → *Advanced* → set the **same** value.
4. Use the **same sample rate in WFAS** (Settings → Sample rate, e.g. 48000 Hz).

All three (CABLE Input, CABLE Output, WFAS) must use the same Hz and channel count.

### 5. Removing the echo

Echo happens because the **phone's microphone picks up the phone's own speaker**
playing back the streamed audio. Fixes, in order of effectiveness:

1. **Use headphones/earbuds on the phone** — removes the echo loop completely.
2. The Android app now enables **hardware echo cancellation + noise suppression**
   automatically when sending the mic, which strongly reduces it.
3. Lower the phone speaker volume.
4. Do **not** enable *"Listen to this device"* on *CABLE Output* in Windows — it creates
   a feedback loop and a second echo.

### 6. Uninstall

Run `VBCABLE_Setup_x64.exe` as administrator again → *Remove Driver* → reboot.

### Quick troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Discord shows no mic activity | Selected *CABLE Input* as mic | Select **CABLE Output** |
| Total silence | Sample-rate mismatch | Set both cable ends + WFAS to 48000 Hz |
| Robotic / fast / slow audio | Channel or rate mismatch | Match channels (stereo/mono) and Hz everywhere |
| Strong echo | Phone mic hears phone speaker | Use headphones on the phone |
| No "CABLE" device at all | Reboot skipped | Reboot after install |

---

## 🇮🇹 Italiano

Quando l'app desktop fa da **server** e attivi **Routing del microfono → Microfono virtuale**,
WFAS prende il microfono inviato dal telefono connesso e lo inietta in un dispositivo audio
virtuale. App come Discord, Zoom, Teams o OBS possono poi selezionarlo come microfono.

Windows **non ha un dispositivo audio virtuale integrato**, quindi serve un piccolo driver
gratuito: **VB-CABLE**. Si installa una volta sola.

### 1. Installare VB-CABLE

1. Scaricalo dal sito ufficiale: <https://vb-audio.com/Cable/>
   (pulsante *Download VB-CABLE Driver Pack*).
2. Estrai lo ZIP in una cartella.
3. **Tasto destro su `VBCABLE_Setup_x64.exe` → Esegui come amministratore.**
   (usa `VBCABLE_Setup.exe` solo su Windows a 32 bit).
4. Premi *Install Driver* e accetta le richieste.
5. **Riavvia il PC.** Passaggio obbligatorio: senza riavvio il dispositivo resta a metà.

Dopo il riavvio avrai due nuovi dispositivi:

| Dispositivo | Tipo | Dove compare |
|---|---|---|
| **CABLE Input** | Riproduzione (uscita) | Scheda *Riproduzione* — WFAS scrive qui |
| **CABLE Output** | Registrazione (ingresso) | Scheda *Registrazione* — Discord legge qui |

### 2. Configurare WFAS

1. Apri WFAS, passa a **Server**.
2. **Routing del microfono → Microfono virtuale.**
3. Come dispositivo di uscita del microfono virtuale, seleziona
   **CABLE Input (VB-Audio Virtual Cable)**.
   (WFAS lo rileva anche da solo, ma selezionarlo esplicitamente è più affidabile.)
4. Avvia il server e connetti il telefono con *Invia microfono* attivo.

### 3. Configurare Discord / Zoom / ecc. — ⚠️ l'errore numero 1

Nell'altra app imposta il **microfono** su:

> **CABLE Output (VB-Audio Virtual Cable)**

**Non** *CABLE Input*. Input è dove l'audio *entra*, Output è dove le app lo *leggono*.
Sceglierlo sbagliato è la causa più frequente di *"non si sente / il mic non funziona"*.

### 4. Allineare il sample rate (risolve silenzio e distorsione)

Se non senti nulla, oppure l'audio è robotico/troppo veloce/troppo lento, i formati non
coincidono:

1. Tasto destro sull'icona altoparlante → *Suoni* → scheda **Riproduzione**.
2. *CABLE Input* → *Proprietà* → *Avanzate* → imposta **16 bit, 48000 Hz (qualità DVD)**.
3. Scheda **Registrazione** → *CABLE Output* → *Proprietà* → *Avanzate* → stesso valore.
4. Usa lo **stesso sample rate in WFAS** (Impostazioni → Sample rate, es. 48000 Hz).

Tutti e tre (CABLE Input, CABLE Output, WFAS) devono usare gli stessi Hz e canali.

### 5. Togliere l'eco

L'eco nasce perché il **microfono del telefono capta l'altoparlante del telefono stesso**
che riproduce l'audio in streaming. Rimedi, dal più efficace:

1. **Usa cuffie/auricolari sul telefono** — elimina del tutto il loop di eco.
2. L'app Android ora attiva **cancellazione d'eco + soppressione del rumore hardware**
   in automatico quando invia il microfono, riducendola molto.
3. Abbassa il volume dell'altoparlante del telefono.
4. **Non** attivare *"Ascolta questo dispositivo"* su *CABLE Output* in Windows: crea un
   loop di feedback e una seconda eco.

### 6. Disinstallare

Esegui di nuovo `VBCABLE_Setup_x64.exe` come amministratore → *Remove Driver* → riavvia.

### Risoluzione rapida

| Sintomo | Causa | Soluzione |
|---|---|---|
| Discord non mostra attività mic | Selezionato *CABLE Input* come mic | Seleziona **CABLE Output** |
| Silenzio totale | Sample rate non allineato | Imposta entrambi i lati + WFAS a 48000 Hz |
| Audio robotico / veloce / lento | Canali o Hz diversi | Allinea canali (stereo/mono) e Hz ovunque |
| Eco forte | Il mic del telefono sente l'altoparlante | Usa le cuffie sul telefono |
| Nessun dispositivo "CABLE" | Riavvio saltato | Riavvia dopo l'installazione |
