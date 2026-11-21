/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by
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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The key, QR pairing and encryption only apply to WFAS. RTP, HTTP, DLNA and
 * Snapcast carry the same audio in cleartext and to anyone who asks, so a
 * server start that has a key set and any of them enabled is confirmed first.
 */
object UnprotectedProtocols {

    /** Display names of the enabled protocols that bypass the WFAS key. */
    fun of(rtp: Boolean, http: Boolean, dlna: Boolean, snapcast: Boolean): List<String> = buildList {
        if (rtp) add("RTP")
        if (http) add("HTTP")
        if (dlna) add("DLNA")
        if (snapcast) add("Snapcast")
    }

    fun of(settings: AppSettings): List<String> =
        of(settings.rtpEnabled, settings.httpEnabled, settings.dlnaEnabled, settings.snapcastEnabled)

    /** Whether a start with this security mode and these protocols needs the warning. */
    fun needsWarning(securityMode: String?, protocols: List<String>): Boolean {
        // "QR" is a UI mode stored as KEY, but accept it here too in case a
        // caller passes the UI value through.
        val keyed = securityMode.equals(SecurityMode.UI_MODE_QR, ignoreCase = true) ||
            SecurityMode.requiresKey(securityMode)
        // With WFAS v3 the native stream is always protected, so the other
        // protocols are the unprotected part whatever the v2 mode says.
        return (keyed || Wfas3Desktop.v3Enabled) && protocols.isNotEmpty()
    }

    /** One line for the terminal, where a dialog is not available. */
    fun cliWarning(protocols: List<String>): String =
        "Warning: the key, QR pairing and encryption only protect WFAS. " +
            "${protocols.joinToString(", ")} will carry the same audio without encryption and without a key: " +
            "anyone on the network can listen to it."
}

/**
 * [onWfasOnly] is null when WFAS is not active on any link: switching the other
 * protocols off would then leave nothing to serve, so the choice is not offered.
 */
@Composable
fun UnprotectedProtocolsDialog(
    protocols: List<String>,
    onWfasOnly: (() -> Unit)?,
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                Icons.Outlined.LockOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                stringResource("unprotected_protocols_title"),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(
                    if (Wfas3Desktop.v3Enabled) "w3_unprotected_body" else "unprotected_protocols_body",
                    protocols.joinToString(", ")
                ))
                if (onWfasOnly == null) {
                    Text(
                        stringResource("unprotected_protocols_nothing_left"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (onWfasOnly != null) {
                    Button(onClick = onWfasOnly) {
                        Icon(Icons.Outlined.GppGood, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource("unprotected_protocols_wfas_only"))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                FilledTonalButton(
                    onClick = onContinue,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Outlined.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource("unprotected_protocols_continue"))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource("cancel")) }
        }
    )
}
