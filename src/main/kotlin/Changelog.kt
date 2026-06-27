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
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Https
import androidx.compose.material.icons.outlined.Lock
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
