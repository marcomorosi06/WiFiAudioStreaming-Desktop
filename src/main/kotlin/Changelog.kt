/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.SpeakerGroup
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Https
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

private fun isItalian(): Boolean = Locale.getDefault().language == "it"

data class Bilingual(val en: String, val it: String) {
    fun get(): String = if (isItalian()) it else en
}

enum class ChangelogAccent { PRIMARY, SECONDARY, TERTIARY }

data class ChangelogItem(
    val icon: ImageVector,
    val title: Bilingual,
    val body: Bilingual,
    val linkLabel: Bilingual? = null,
    val linkUrl: String? = null
)

private const val ANDROID_RELEASES_URL = "https://github.com/marcomorosi06/WiFiAudioStreaming-Android/releases"

data class ChangelogEntry(
    val version: String,
    val date: Bilingual,
    val headline: Bilingual,
    val items: List<ChangelogItem>
)

object Changelog {

    val entries: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            version = "1.3",
            date = Bilingual("November 2026", "Novembre 2026"),
            headline = Bilingual(
                "WFAS v3: a new protocol built around privacy, with pairing by QR code, code or PIN.",
                "WFAS v3: un nuovo protocollo pensato per la privacy, con abbinamento tramite QR code, codice o PIN."
            ),
            items = listOf(
                ChangelogItem(
                    icon = Icons.Outlined.Shield,
                    title = Bilingual("WFAS v3, the new default", "WFAS v3, il nuovo predefinito"),
                    body = Bilingual(
                        "Devices pair once, and from then on only paired devices can connect. The audio is always encrypted, with keys agreed through a hybrid handshake (X25519 and the post-quantum ML-KEM-768). Servers no longer announce themselves on the network, and the v3 port opens only while it is in use. Its specification still marks it experimental until an independent review.",
                        "I dispositivi si abbinano una volta, e da lì in poi solo i dispositivi abbinati possono connettersi. L'audio è sempre cifrato, con chiavi concordate tramite un handshake ibrido (X25519 e il post-quantistico ML-KEM-768). I server non si annunciano più sulla rete, e la porta di v3 resta aperta solo mentre serve. La sua specifica lo indica ancora come sperimentale, in attesa di una revisione indipendente."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.QrCode2,
                    title = Bilingual("Pair with a QR code, a code or a PIN", "Abbinamento con QR code, codice o PIN"),
                    body = Bilingual(
                        "The server opens a pairing window for two minutes and one device. With a PIN, both screens then show the same 6-digit code to compare. You can pair a device as a guest, forgotten when it disconnects, and decide whether it may send its microphone.",
                        "Il server apre una finestra di abbinamento per due minuti e per un dispositivo. Con il PIN, entrambi gli schermi mostrano poi lo stesso codice di 6 cifre da confrontare. Puoi abbinare un dispositivo come ospite, dimenticato quando si disconnette, e decidere se può inviare il suo microfono."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Shield,
                    title = Bilingual("Pairing that holds up", "Un abbinamento che regge"),
                    body = Bilingual(
                        "Pairing follows draft 7 of the WFAS v3 specification. Recorded pairing signals of a label, played again, cannot stop a pairing or raise a false alarm; floods of fake requests from addresses that do not answer cannot keep a PIN window busy; a reply with the wrong key does not stop the real one. The request shows the address it comes from, servers with a PIN window show theirs, and names that would look empty are cleaned. Over IPv4 pairing is accepted only from the local network, and the multicast port takes only multicast audio. A pairing made to listen does not let the server receive this computer's audio. Both devices need WFAS v3 draft 7, as in the 1.3 apps; pairings already made keep working.",
                        "L'abbinamento segue la draft 7 della specifica WFAS v3. I segnali di abbinamento di un'etichetta registrati e ritrasmessi non possono fermare un abbinamento n\u00e9 dare falsi allarmi; le raffiche di richieste false da indirizzi che non rispondono non possono bloccare una finestra PIN; una risposta con la chiave sbagliata non ferma quella vera. La richiesta mostra l'indirizzo da cui arriva, i server con una finestra PIN mostrano il loro, e i nomi che sembrerebbero vuoti vengono ripuliti. Su IPv4 l'abbinamento \u00e8 accettato solo dalla rete locale, e la porta multicast accetta solo audio multicast. Un abbinamento fatto per ascoltare non permette al server di ricevere l'audio di questo computer. Servono WFAS v3 draft 7 su entrambi i dispositivi, come nelle app 1.3; gli abbinamenti gi\u00e0 fatti continuano a funzionare."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.GraphicEq,
                    title = Bilingual("Every server control", "Tutti i controlli del server"),
                    body = Bilingual(
                        "Uncompressed PCM or Opus at the bitrate you choose, latency, synchronised playback across clients (at the cost of a little more delay), multicast, the clients' microphone and whether new devices are accepted. Most changes apply while streaming.",
                        "PCM non compresso oppure Opus al bitrate che scegli, latenza, riproduzione sincronizzata tra i client (al costo di un po' di ritardo in più), multicast, microfono dei client e accettazione di nuovi dispositivi. La maggior parte delle modifiche vale anche durante lo streaming."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.AutoAwesome,
                    title = Bilingual("Plug and play, with room to tune", "Pronto all'uso, con spazio per regolare"),
                    body = Bilingual(
                        "Paste or scan the server's code and the rest happens by itself: the pairing starts, and once done this PC connects and plays. With a PIN, the only server nearby is chosen for you and the eighth digit starts the pairing. On the server, four presets (Hi-Fi, Balanced, Low latency, All together) set the sound in one click; codec, bitrate, packets, latency, sync and who can connect are one fold away.",
                        "Incolla o inquadra il codice del server e il resto avviene da solo: l'abbinamento parte e, appena concluso, questo PC si connette e riproduce. Con il PIN, l'unico server vicino viene scelto per te e l'ottava cifra avvia l'abbinamento. Sul server, quattro preset (Hi-Fi, Bilanciato, Bassa latenza, Tutti insieme) impostano il suono con un clic; codec, bitrate, pacchetti, latenza, sincronia e chi può connettersi sono a una piega di distanza."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Palette,
                    title = Bilingual("A new look and a new icon", "Un aspetto e un'icona nuovi"),
                    body = Bilingual(
                        "A rail on the left with Stream, Devices and Settings, as in WiFi Screen Streaming; the paired devices on their own page; settings in two rounded panes; questions drawn inside the window rather than as popups, with the safe answer first. And a new headphones icon.",
                        "Una barra a sinistra con Stream, Dispositivi e Impostazioni, come in WiFi Screen Streaming; i dispositivi abbinati su una pagina loro; le impostazioni in due pannelli arrotondati; le domande disegnate dentro la finestra invece che come popup, con la risposta sicura per prima. E una nuova icona con le cuffie."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Router,
                    title = Bilingual("IPv6-only networks too", "Anche sulle reti solo IPv6"),
                    body = Bilingual(
                        "Where the network has no IPv4, WFAS v3 now works over IPv6: probes and pairing beacons go to the link's all-nodes address (ff02::1), multicast uses a link-scope group, and nothing ever leaves the local link. The v3 port only listens to devices on that same link, so nothing from the Internet reaches it even with a public IPv6 address. Networks with IPv4 work exactly as before.",
                        "Dove la rete non ha IPv4, WFAS v3 ora funziona su IPv6: le richieste di ricerca e i segnali di abbinamento vanno all'indirizzo di tutti i nodi del collegamento (ff02::1), il multicast usa un gruppo limitato al collegamento, e nulla esce mai dalla rete locale. La porta di v3 ascolta solo i dispositivi di quello stesso collegamento, quindi da Internet non arriva nulla nemmeno con un indirizzo IPv6 pubblico. Le reti con IPv4 funzionano esattamente come prima."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Visibility,
                    title = Bilingual("Send and Listen on the rail, a clearer client", "Invia e Ascolta nella barra, un client più chiaro"),
                    body = Bilingual(
                        "The role of this computer is chosen on the left rail, Send above Listen, and every card of the main screen has the new look. As a client, your v3 servers come first; the other ways in sit below, folded and labelled: connect by address (v3 first, v2 only if you say so), the USB cable (it works with v3 too: the cable is a network link) and the older WFAS v2 devices. On the server, unicast or multicast and the latency are chosen right next to the presets. With v3 that latency holds for everyone who listens; the one in the settings is for v2 and RTP. Changing it while playing no longer restarts the sound. The window opens in the mode you used last, Send or Listen. Every colour of the window follows the one chosen in the settings; the Material colours switch in Appearance gives each section a colour of its own.",
                        "Il ruolo di questo computer si sceglie nella barra a sinistra, Invia sopra Ascolta, e ogni scheda della schermata principale ha il nuovo aspetto. Come client, i tuoi server v3 vengono per primi; gli altri modi stanno sotto, ripiegati e con un nome chiaro: collegarsi con l'indirizzo (prima v3, v2 solo se lo dici tu), il cavo USB (funziona anche con v3: il cavo è un collegamento di rete) e i vecchi dispositivi WFAS v2. Sul server, unicast o multicast e la latenza si scelgono subito accanto ai preset. Con la v3 quella latenza vale per tutti quelli che ascoltano; quella nelle impostazioni è per v2 e RTP. Cambiarla mentre suona non fa più ripartire l'audio. La finestra si apre nella modalità usata l'ultima volta, Invia o Ascolta. Ogni colore della finestra segue quello scelto nelle impostazioni; l'interruttore Colori Material in Aspetto dà a ogni sezione un colore suo."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.History,
                    title = Bilingual("WFAS v2 stays, as legacy", "WFAS v2 resta, come legacy"),
                    body = Bilingual(
                        "WFAS v2 is off by default and can be turned back on in the settings for devices that do not have v3 yet; only one of the two is on at a time. In the device list v2 servers carry a small badge, and the app tells you before connecting to one. With v3 on, the settings show only what v3 uses: the ones for v2 sit together under Older devices (WFAS v2).",
                        "WFAS v2 è disattivato di default e si può riattivare nelle impostazioni per i dispositivi che non hanno ancora v3; è attivo uno solo dei due alla volta. Nell'elenco dei dispositivi i server v2 hanno un piccolo segnalino, e l'app te lo dice prima di connetterti a uno di loro. Con v3 attivo, le impostazioni mostrano solo ciò che v3 usa: quelle di v2 stanno insieme in Dispositivi vecchi (WFAS v2)."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Lock,
                    title = Bilingual("Your keys stay with you", "Le tue chiavi restano tue"),
                    body = Bilingual(
                        "The v3 keys and pairings are kept only on this device, encrypted with a key held in the system credential store. They are never sent anywhere else, and you can erase them at any time. They live in the local, non-roaming folder of this computer (records of an earlier 1.3 build are moved there once) and are left out of Time Machine; on Windows only your account can open them. If the key or the device record is missing while pairings exist, nothing is overwritten and the app says so.",
                        "Le chiavi e gli abbinamenti di v3 restano solo su questo dispositivo, cifrati con una chiave custodita dall'archivio credenziali del sistema. Non vengono mai inviati altrove, e puoi cancellarli in qualsiasi momento. Stanno nella cartella locale, non sincronizzata, di questo computer (i dati di una build 1.3 precedente vi vengono spostati una volta) e sono esclusi da Time Machine; su Windows solo il tuo account pu\u00f2 aprirli. Se mancano la chiave o il record del dispositivo mentre esistono abbinamenti, non viene sovrascritto nulla e l'app lo dice."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.SpeakerGroup,
                    title = Bilingual("New connection sounds", "Nuovi suoni di connessione"),
                    body = Bilingual(
                        "New sounds for connecting and disconnecting. They are free, with no restriction on use.",
                        "Nuovi suoni per la connessione e la disconnessione. Sono liberi, senza alcun vincolo d'uso."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Terminal,
                    title = Bilingual("Command line", "Riga di comando"),
                    body = Bilingual(
                        "'wfas v3' pairs, lists and removes devices; 'wfas v3 pair' asks for the code on the terminal, so that it stays out of the shell history. 'wfas --server' and 'wfas --client' use WFAS v3, and '--legacy-v2' runs a single session with the old protocol.",
                        "'wfas v3' abbina, elenca e rimuove i dispositivi; 'wfas v3 pair' chiede il codice nel terminale, cos\u00ec resta fuori dalla cronologia della shell. 'wfas --server' e 'wfas --client' usano WFAS v3, e '--legacy-v2' avvia una singola sessione con il vecchio protocollo."
                    )
                )
            )
        ),
        ChangelogEntry(
            version = "1.2.1",
            date = Bilingual("November 2026", "Novembre 2026"),
            headline = Bilingual(
                "Clearer information on what is protected, plus general improvements.",
                "Informazioni più chiare su cosa è protetto, e miglioramenti generali."
            ),
            items = listOf(
                ChangelogItem(
                    icon = Icons.Outlined.Shield,
                    title = Bilingual("Know what is protected", "Sai cosa è protetto"),
                    body = Bilingual(
                        "The key, QR pairing and encryption apply to WFAS. With one of them set and RTP, HTTP, DLNA or Snapcast enabled, the app now asks before starting the server: keep only WFAS, continue, or cancel. The command line shows a notice.",
                        "La chiave, il pairing QR e la cifratura valgono per WFAS. Se ne hai impostato uno e sono attivi RTP, HTTP, DLNA o Snapcast, ora l'app chiede prima di avviare il server: tieni solo WFAS, continua o annulla. La riga di comando mostra un avviso."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Lock,
                    title = Bilingual("Encryption on by default", "Cifratura attiva di default"),
                    body = Bilingual(
                        "Encryption is now on by default. Setups already in Key mode keep their setting.",
                        "La cifratura ora è attiva di default. Le configurazioni già in modalità Chiave mantengono la loro impostazione."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.VpnKey,
                    title = Bilingual("Under the hood", "Sotto il cofano"),
                    body = Bilingual(
                        "Stability and reliability improvements across the app, and updated libraries.",
                        "Miglioramenti di stabilità e affidabilità in tutta l'app, e librerie aggiornate."
                    )
                )
            )
        ),
        ChangelogEntry(
            version = "1.2",
            date = Bilingual("August 2026", "Agosto 2026"),
            headline = Bilingual(
                "Two new protocols — DLNA and Snapcast — pairing by QR code, streaming over USB, and a rebuilt interface.",
                "Due nuovi protocolli — DLNA e Snapcast — accoppiamento con QR code, streaming via USB e un'interfaccia ricostruita."
            ),
            items = listOf(
                ChangelogItem(
                    icon = Icons.Outlined.Cast,
                    title = Bilingual("Play on your DLNA devices", "Riproduci sui tuoi dispositivi DLNA"),
                    body = Bilingual(
                        "Send the audio straight to the Smart TVs, AV receivers and network speakers you already own. They need nothing installed: pick a renderer and it starts playing.",
                        "Manda l'audio direttamente alle Smart TV, ai sintoamplificatori e ai diffusori di rete che possiedi già. Su di loro non serve installare nulla: scegli un renderer e parte."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.SpeakerGroup,
                    title = Bilingual("Snapcast multiroom", "Multiroom con Snapcast"),
                    body = Bilingual(
                        "Turn this computer into a Snapcast server: every snapclient on the network — Raspberry Pi, ESP32, Home Assistant, the mobile apps — joins in and plays in sync, room after room.",
                        "Trasforma questo computer in un server Snapcast: ogni snapclient sulla rete — Raspberry Pi, ESP32, Home Assistant, le app mobili — si unisce e riproduce in sincrono, stanza dopo stanza."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.QrCode2,
                    title = Bilingual("Pair with a QR code", "Accoppiamento con QR code"),
                    body = Bilingual(
                        "Address, port and a freshly generated key handed over in one shot. The key is never typed and never travels on the wire — scan the code and you are connected, encrypted.",
                        "Indirizzo, porta e una chiave appena generata consegnati in un colpo solo. La chiave non si digita mai e non viaggia mai sulla rete — inquadri il codice e sei connesso, cifrato."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Usb,
                    title = Bilingual("Stream over USB", "Streaming via USB"),
                    body = Bilingual(
                        "Plug the phone in and the audio takes the cable instead of the air: lower latency, no Wi-Fi congestion, and it keeps working where the network does not.",
                        "Collega il telefono e l'audio prende il cavo invece dell'aria: meno latenza, nessuna congestione Wi-Fi e continua a funzionare dove la rete non arriva."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Palette,
                    title = Bilingual("A rebuilt interface", "Interfaccia ricostruita"),
                    body = Bilingual(
                        "The whole window has been redrawn around what you actually do: start a stream, see who is listening, change protocol. The discovery list is clearer and no longer shows stale servers.",
                        "L'intera finestra è stata ridisegnata attorno a ciò che fai davvero: avviare uno stream, vedere chi ascolta, cambiare protocollo. La lista di discovery è più chiara e non mostra più server ormai spariti."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Router,
                    title = Bilingual("IPv6 and mixed networks", "IPv6 e reti miste"),
                    body = Bilingual(
                        "Discovery and streaming now behave on IPv6 and on networks with several interfaces, picking the one that actually reaches the other device.",
                        "Discovery e streaming ora si comportano bene su IPv6 e su reti con più interfacce, scegliendo quella che raggiunge davvero l'altro dispositivo."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Sync,
                    title = Bilingual("Older clients spotted sooner", "Client vecchi riconosciuti prima"),
                    body = Bilingual(
                        "When a device is running an older version, you are told before the connection fails rather than after, and exactly which side to update.",
                        "Quando un dispositivo usa una versione più vecchia, te lo dice prima che la connessione fallisca invece che dopo, e ti indica esattamente quale lato aggiornare."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.PhoneAndroid,
                    title = Bilingual("Update the Android app too", "Aggiorna anche l'app Android"),
                    body = Bilingual(
                        "WiFi Audio Streaming for Android has been updated as well. Update it too so both ends stay compatible.",
                        "Anche WiFi Audio Streaming per Android è stata aggiornata. Aggiornala anche tu, così i due lati restano compatibili."
                    ),
                    linkLabel = Bilingual("Open on GitHub", "Apri su GitHub"),
                    linkUrl = ANDROID_RELEASES_URL
                )
            )
        ),
        ChangelogEntry(
            version = "1.1",
            date = Bilingual("June 2026", "Giugno 2026"),
            headline = Bilingual(
                "Terminal control, a live visualizer, broader OS support — and connection security with end-to-end encryption.",
                "Controllo da terminale, visualizzatore live, supporto più ampio ai sistemi operativi — e sicurezza delle connessioni con cifratura end-to-end."
            ),
            items = listOf(
                ChangelogItem(
                    icon = Icons.Outlined.Lock,
                    title = Bilingual("Protect your server", "Proteggi il tuo server"),
                    body = Bilingual(
                        "Right in the server panel, choose who can connect: approve every device by hand, or require a shared key. Mutual verification keeps out unknown clients and rogue servers alike.",
                        "Direttamente nel pannello server, scegli chi può connettersi: approva ogni dispositivo a mano, oppure richiedi una chiave condivisa. La verifica reciproca tiene fuori sia i client sconosciuti sia i server malevoli."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.VpnKey,
                    title = Bilingual("Connect with a key", "Connessione con chiave"),
                    body = Bilingual(
                        "When a server is locked, just type its key when prompted — nothing to set up in advance, and you're told right away if the key is wrong.",
                        "Quando un server è protetto, basta digitare la sua chiave quando richiesto — niente da configurare prima, e ti viene detto subito se la chiave è sbagliata."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Https,
                    title = Bilingual("End-to-end encryption", "Cifratura end-to-end"),
                    body = Bilingual(
                        "Your audio travels encrypted from end to end, so only your paired devices can decode the stream — no one else on the network can listen in.",
                        "Il tuo audio viaggia cifrato da un capo all'altro: solo i tuoi dispositivi accoppiati possono decodificare lo stream — nessun altro sulla rete può ascoltare."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Terminal,
                    title = Bilingual("Command-line control", "Controllo da terminale"),
                    body = Bilingual(
                        "Run the whole app from your terminal with the wfas command — start a server, connect, find devices and change the volume, all fully scriptable.",
                        "Usa tutta l'app dal terminale con il comando wfas — avvia un server, connettiti, trova dispositivi e cambia il volume, tutto completamente automatizzabile."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.GraphicEq,
                    title = Bilingual("Live terminal visualizer", "Visualizzatore live nel terminale"),
                    body = Bilingual(
                        "A real-time audio spectrum and a debug overlay right inside your terminal, with color themes to choose from.",
                        "Uno spettro audio in tempo reale e un overlay di debug direttamente nel terminale, con temi di colore tra cui scegliere."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Computer,
                    title = Bilingual("Better Linux & Windows support", "Miglior supporto Linux e Windows"),
                    body = Bilingual(
                        "Works on more Linux distributions with no extra setup, and on Windows it now picks the right audio device even when several have similar names.",
                        "Funziona su più distribuzioni Linux senza configurazioni extra e, su Windows, ora sceglie il dispositivo audio giusto anche quando ce ne sono diversi con nomi simili."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.Sync,
                    title = Bilingual("Smarter compatibility", "Compatibilità più intelligente"),
                    body = Bilingual(
                        "The app checks that your desktop and phone speak the same version, and tells you exactly which one to update if they don't match.",
                        "L'app verifica che desktop e telefono parlino la stessa versione e ti dice esattamente quale aggiornare se non combaciano."
                    )
                ),
                ChangelogItem(
                    icon = Icons.Outlined.PhoneAndroid,
                    title = Bilingual("Update the Android app too", "Aggiorna anche l'app Android"),
                    body = Bilingual(
                        "WiFi Audio Streaming for Android has been updated as well. Update it too so both ends stay compatible.",
                        "Anche WiFi Audio Streaming per Android è stata aggiornata. Aggiornala anche tu, così i due lati restano compatibili."
                    ),
                    linkLabel = Bilingual("Open on GitHub", "Apri su GitHub"),
                    linkUrl = ANDROID_RELEASES_URL
                )
            )
        )
    )

    val latest: ChangelogEntry get() = entries.first()
}

private val whatsNewTitle = Bilingual("What's new", "Novità")
private val versionHistoryTitle = Bilingual("Version history", "Cronologia versioni")
private val continueLabel = Bilingual("Continue", "Continua")
private val closeLabel = Bilingual("Close", "Chiudi")

@Composable
fun ChangelogScreen(
    visible: Boolean,
    standalone: Boolean,
    onContinue: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(400)) + scaleIn(initialScale = 0.92f),
        exit = fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.92f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxHeight()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ChangelogBody(
                    modifier = Modifier.weight(1f),
                    onContinue = if (standalone) onContinue else null
                )

                if (!standalone) {
                    Button(
                        onClick = onContinue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    ) {
                        Text(continueLabel.get())
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangelogBody(
    modifier: Modifier = Modifier,
    onContinue: (() -> Unit)?
) {
    var shown by remember { mutableStateOf(Changelog.latest) }
    var showHistory by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = whatsNewTitle.get(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "v${shown.version} · ${shown.date.get()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showHistory = true }) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = versionHistoryTitle.get()
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = shown.headline.get(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            shown.items.forEachIndexed { i, item -> ChangelogItemCard(item, accentForIndex(i)) }
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (onContinue != null) {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                Text(continueLabel.get())
            }
        }
    }

    if (showHistory) {
        VersionHistoryDialog(
            onDismiss = { showHistory = false },
            onSelect = {
                shown = it
                showHistory = false
            }
        )
    }
}

private fun accentForIndex(i: Int): ChangelogAccent = when (i % 3) {
    0 -> ChangelogAccent.PRIMARY
    1 -> ChangelogAccent.SECONDARY
    else -> ChangelogAccent.TERTIARY
}

@Composable
private fun ChangelogItemCard(item: ChangelogItem, accent: ChangelogAccent) {
    val cs = MaterialTheme.colorScheme
    val (container, onContainer, badge, onBadge) = when (accent) {
        ChangelogAccent.PRIMARY -> listOf(cs.primaryContainer, cs.onPrimaryContainer, cs.primary, cs.onPrimary)
        ChangelogAccent.SECONDARY -> listOf(cs.secondaryContainer, cs.onSecondaryContainer, cs.secondary, cs.onSecondary)
        ChangelogAccent.TERTIARY -> listOf(cs.tertiaryContainer, cs.onTertiaryContainer, cs.tertiary, cs.onTertiary)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = container
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(badge),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = onBadge,
                    modifier = Modifier.size(28.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.title.get(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = onContainer
                )
                Text(
                    text = item.body.get(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer.copy(alpha = 0.85f)
                )
                if (item.linkUrl != null && item.linkLabel != null) {
                    TextButton(
                        onClick = { runCatching { openUrl(item.linkUrl) } },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            item.linkLabel.get(),
                            color = onContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionHistoryDialog(
    onDismiss: () -> Unit,
    onSelect: (ChangelogEntry) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.NewReleases, contentDescription = null) },
        title = { Text(versionHistoryTitle.get()) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Changelog.entries) { entry ->
                    Surface(
                        onClick = { onSelect(entry) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "v${entry.version}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = entry.date.get(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(closeLabel.get()) }
        }
    )
}
