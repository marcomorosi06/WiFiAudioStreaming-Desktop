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

import androidx.compose.animation.*
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.net.URI
import javax.sound.sampled.Mixer
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

fun openUrl(url: String) {
    val os = System.getProperty("os.name").lowercase()
    try {
        if (os.contains("linux")) {
            ProcessBuilder("xdg-open", url).start()
        } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    } catch (e: Exception) {
        println("Could not open link: ${e.message}")
    }
}

// =================================================================
// ==                  SCHERMATA PRINCIPALE                       ==
// =================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(
    appSettings: AppSettings,
    audioSettings: AudioSettings_V1,
    isServer: Boolean,
    isStreaming: Boolean,
    connectionStatus: String,
    discoveredDevices: Map<String, ServerInfo>,
    isMulticastMode: Boolean,
    sendMicrophone: Boolean,
    outputDevices: List<Mixer.Info>,
    selectedOutputDevice: Mixer.Info?,
    inputDevices: List<Mixer.Info>,
    selectedInputDevice: Mixer.Info?,
    selectedClientMic: Mixer.Info?,
    selectedServerMicOutput: Mixer.Info?,
    streamingPort: String,
    localIp: String,
    httpUrl: String?,
    serverVolume: Float,
    onServerVolumeChange: (Float) -> Unit,
    isWindowsOS: Boolean,
    onDismissPrivacyBanner: (Boolean) -> Unit,
    onDismissRoutingBanner: (Boolean) -> Unit,
    virtualDriverStatus: VirtualDriverStatus,
    onConnectManual: (String) -> Unit,
    onModeChange: (Boolean) -> Unit,
    onStartStreaming: () -> Unit,
    onStopStreaming: () -> Unit,
    onConnectToServer: (ServerInfo) -> Unit,
    onRefreshDevices: () -> Unit,
    onMulticastModeChange: (Boolean) -> Unit,
    onSendMicrophoneChange: (Boolean) -> Unit,
    onSelectedOutputDeviceChange: (Mixer.Info) -> Unit,
    onSelectedInputDeviceChange: (Mixer.Info) -> Unit,
    onSelectedClientMicChange: (Mixer.Info) -> Unit,
    onSelectedServerMicOutputChange: (Mixer.Info) -> Unit,
    onAppSettingsChange: (AppSettings) -> Unit,
    onOpenSettings: () -> Unit,
    isMicMuted: Boolean = false,
    onMicMuteToggle: () -> Unit = {},
    micRoutingMode: MicRoutingMode = MicRoutingMode.OFF,
    onMicRoutingModeChange: (MicRoutingMode) -> Unit = {},
    selectedMicMixInput: Mixer.Info? = null,
    onMicMixInputSelected: (Mixer.Info) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi Audio Streamer") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        // --- INIZIO DIALOG LINUX ---
        val clipboardManager = LocalClipboardManager.current
        if (isServer && !appSettings.useNativeEngine && virtualDriverStatus is VirtualDriverStatus.LinuxActionRequired) {
            var showLinuxDialog by remember { mutableStateOf(true) }
            if (showLinuxDialog) {
                AlertDialog(
                    onDismissRequest = { showLinuxDialog = false },
                    title = { Text("Missing Audio Dependencies") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(virtualDriverStatus.message)
                            OutlinedTextField(
                                value = virtualDriverStatus.commands,
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            clipboardManager.setText(AnnotatedString(virtualDriverStatus.commands))
                            showLinuxDialog = false
                        }) {
                            Text("Copy & Close")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showLinuxDialog = false }) { Text("Ignore") }
                    }
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // --- Virtual Driver Warning Banner ---
            // Only shown in server mode (ffmpeg) when the required virtual audio driver is missing.
            if (isServer && !appSettings.useNativeEngine) {
                val driverStatus = virtualDriverStatus
                if (driverStatus is VirtualDriverStatus.Missing) {
                    item {
                        VirtualDriverBanner(status = driverStatus)
                    }
                }
            }

            // --- BANNER WINDOWS (Instradamento e Privacy) ---
            if (isWindowsOS && isServer && !isStreaming && !appSettings.useNativeEngine) {
                if (!appSettings.hideWindowsRoutingBanner) {
                    item { WindowsRoutingBanner(onDismiss = onDismissRoutingBanner) }
                }
                if (!appSettings.hideWindowsPrivacyBanner) {
                    item { WindowsPrivacyBanner(onDismiss = onDismissPrivacyBanner) }
                }
            }

            item {
                ServerStatusBar(
                    isServer = isServer,
                    isStreaming = isStreaming,
                    connectionStatus = connectionStatus,
                    localIp = localIp,
                    streamingPort = streamingPort,
                    httpUrl = httpUrl
                )
            }

            if (isServer && isStreaming && appSettings.rtpEnabled) {
                item {
                    RtpSdpBanner(
                        localIp = localIp,
                        isMulticast = isMulticastMode,
                        port = appSettings.rtpPort,
                        sampleRate = audioSettings.sampleRate.toInt(),
                        channels = audioSettings.channels
                    )
                }
            }

            item {
                StreamingControlCenter(
                    isStreaming = isStreaming,
                    isServer = isServer,
                    isServerReady = !(isServer && !appSettings.useNativeEngine && virtualDriverStatus is VirtualDriverStatus.Missing),
                    serverVolume = serverVolume,
                    onServerVolumeChange = onServerVolumeChange,
                    onStart = onStartStreaming,
                    onStop = onStopStreaming,
                    showMicMute = (!isServer && sendMicrophone) || (isServer && micRoutingMode == MicRoutingMode.MIX_INTO_STREAM),
                    isMicMuted = isMicMuted,
                    onMicMuteToggle = onMicMuteToggle
                )
            }

            if (!isStreaming) {
                item {
                    ModeSelectorCard(
                        isServer = isServer,
                        onModeChange = onModeChange
                    )
                }
                item {
                    AnimatedVisibility(
                        visible = isServer,
                        enter = fadeIn(animationSpec = tween(durationMillis = 300)),
                        exit = fadeOut(animationSpec = tween(durationMillis = 300))
                    ) {
                        ServerConfigCard(
                            inputDevices = inputDevices,
                            selectedInputDevice = selectedInputDevice,
                            onInputDeviceSelected = onSelectedInputDeviceChange,
                            outputDevices = outputDevices,
                            selectedServerMicOutput = selectedServerMicOutput,
                            onServerMicOutputSelected = onSelectedServerMicOutputChange,
                            isMulticast = isMulticastMode,
                            onMulticastChanged = onMulticastModeChange,
                            virtualDriverStatus = virtualDriverStatus,
                            rtpEnabled = appSettings.rtpEnabled,
                            useNativeEngine = appSettings.useNativeEngine,
                            micRoutingMode = micRoutingMode,
                            onMicRoutingModeChange = onMicRoutingModeChange,
                            micRoutingDisabled = appSettings.rtpEnabled,
                            virtualMicDisabled = isMulticastMode,
                            selectedMicMixInput = selectedMicMixInput,
                            onMicMixInputSelected = onMicMixInputSelected
                        )
                    }
                }
                item {
                    AnimatedVisibility(
                        visible = !isServer,
                        enter = fadeIn(animationSpec = tween(durationMillis = 300)),
                        exit = fadeOut(animationSpec = tween(durationMillis = 300))
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ClientConfigCard(
                                outputDevices = outputDevices,
                                selectedOutputDevice = selectedOutputDevice,
                                onOutputDeviceSelected = onSelectedOutputDeviceChange,
                                sendMicrophone = sendMicrophone,
                                onSendMicrophoneChanged = onSendMicrophoneChange,
                                inputDevices = inputDevices,
                                selectedClientMic = selectedClientMic,
                                onClientMicSelected = onSelectedClientMicChange
                            )

                            var manualIp by remember { mutableStateOf("") }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = manualIp,
                                    onValueChange = { manualIp = it },
                                    label = { Text("Manual Server IP") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Button(
                                    onClick = { onConnectManual(manualIp) },
                                    enabled = manualIp.isNotBlank() && selectedOutputDevice != null
                                ) {
                                    Text("Connect")
                                }
                            }

                            DeviceDiscoveryList(
                                devices = discoveredDevices,
                                onConnect = onConnectToServer,
                                onRefresh = onRefreshDevices,
                                enabled = selectedOutputDevice != null && (!sendMicrophone || selectedClientMic != null),
                                autoConnectIps = appSettings.autoConnectIps,
                                onToggleAutoConnectIp = { ip ->
                                    val newList = appSettings.autoConnectIps.toMutableList()
                                    if (newList.contains(ip)) {
                                        newList.remove(ip)
                                    } else {
                                        newList.add(ip)
                                    }
                                    onAppSettingsChange(appSettings.copy(autoConnectIps = newList))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// =================================================================
// ==          VIRTUAL DRIVER WARNING BANNER                      ==
// =================================================================

/**
 * A prominent warning card shown when the required virtual audio driver
 * (VB-Cable on Windows, BlackHole on macOS) is not installed.
 * Provides a direct download button so the user never has to guess why
 * streaming doesn't work.
 */
@Composable
fun VirtualDriverBanner(status: VirtualDriverStatus.Missing) {
    val errorColor = MaterialTheme.colorScheme.errorContainer
    val onErrorColor = MaterialTheme.colorScheme.onErrorContainer

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = errorColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Warning",
                tint = onErrorColor,
                modifier = Modifier.size(32.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "${status.driverName} not installed",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = onErrorColor
                )
                Text(
                    text = "This app needs a virtual audio cable to capture system audio. " +
                            "Without it, the server cannot stream anything. Install Blackhole 2inch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = onErrorColor
                )
            }
            Button(
                onClick = {
                    openUrl(status.downloadUrl)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("Download")
            }
        }
    }
}

// =================================================================
// ==               SCHERMATA IMPOSTAZIONI                        ==
// =================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    visible: Boolean,
    appSettings: AppSettings,
    audioSettings: AudioSettings_V1,
    streamingPort: String,
    micPort: String,
    onAppSettingsChange: (AppSettings) -> Unit,
    onAudioSettingsChange: (AudioSettings_V1) -> Unit,
    onStreamingPortChange: (String) -> Unit,
    onMicPortChange: (String) -> Unit,
    onCustomColorChange: (Long?) -> Unit,
    onClose: () -> Unit
) {
    var linuxAutostartInfo by remember { mutableStateOf<String?>(null) }

    if (linuxAutostartInfo != null) {
        AlertDialog(
            onDismissRequest = { linuxAutostartInfo = null },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text(stringResource("linux_autostart_title")) },
            text = {
                OutlinedTextField(
                    value = linuxAutostartInfo!!,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 300.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { linuxAutostartInfo = null }) { Text(stringResource("ok")) }
            }
        )
    }

    var showEasterEgg by remember { mutableStateOf(false) }
    val isLinux = remember { System.getProperty("os.name").lowercase().contains("linux") }

    if (showEasterEgg) {
        AlertDialog(
            onDismissRequest = { showEasterEgg = false },
            confirmButton = {
                TextButton(onClick = { showEasterEgg = false }) {
                    Text(stringResource("close"))
                }
            },
            text = {
                Image(
                    painter = painterResource(if (isLinux) "derFWithTux.png" else "derF.jpeg"),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                )
            }
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource("settings"),
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                showEasterEgg = true
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource("close"))
                        }
                    }
                )
            },
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
        ) { padding ->
            LazyColumn(
                contentPadding = padding,
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SettingsGroup(title = stringResource("appearance"), icon = Icons.Outlined.Palette) {
                        ThemeSelector(
                            currentTheme = appSettings.theme,
                            onThemeChange = { onAppSettingsChange(appSettings.copy(theme = it)) }
                        )
                    }
                }
                item {
                    SettingsGroup(title = "Material You Theming", icon = Icons.Outlined.FormatPaint) {

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. Il nostro nuovo Color Picker Avanzato
                            AdvancedColorPicker(
                                initialColor = appSettings.customThemeColor,
                                onColorChange = { colorLong -> onCustomColorChange(colorLong) }
                            )

                            // 2. I controlli aggiuntivi sulla destra
                            Column(
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Button(
                                    onClick = {
                                        val extractedColor = ThemeEngine.pickImageAndExtractColor()
                                        if (extractedColor != null) onCustomColorChange(extractedColor)
                                    }
                                ) {
                                    Icon(Icons.Outlined.Image, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Extract from Image")
                                }

                                OutlinedButton(
                                    onClick = { onCustomColorChange(null) },
                                    enabled = appSettings.customThemeColor != null
                                ) {
                                    Icon(Icons.Outlined.Restore, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Reset to Default")
                                }

                                if (appSettings.customThemeColor != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Current Seed: ", style = MaterialTheme.typography.bodyMedium)
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(Color(appSettings.customThemeColor!!.toULong()))
                                                .border(1.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    SettingsGroup(title = stringResource("audio_quality"), icon = Icons.Outlined.Tune) {
                        AudioSettingsContent(settings = audioSettings, onSettingsChange = onAudioSettingsChange)
                    }
                }
                item {
                    SettingsGroup(title = stringResource("network"), icon = Icons.Outlined.SettingsEthernet) {
                        NetworkSettingsContent(
                            port = streamingPort,
                            onPortChange = onStreamingPortChange,
                            micPort = micPort,
                            onMicPortChange = onMicPortChange,
                            appSettings = appSettings,
                            onAppSettingsChange = onAppSettingsChange
                        )
                    }
                }
                item {
                    SettingsGroup(title = stringResource("advanced"), icon = Icons.Outlined.Science) {
                        SwitchSetting(
                            title = stringResource("launch_at_startup"),
                            description = stringResource("launch_at_startup_desc"),
                            icon = Icons.Outlined.PowerSettingsNew,
                            checked = appSettings.launchAtStartup,
                            onCheckedChange = { isEnabled ->
                                onAppSettingsChange(appSettings.copy(launchAtStartup = isEnabled))
                                val result = AutostartManager.toggleAutostart(isEnabled)
                                if (result != null && System.getProperty("os.name").lowercase().contains("linux")) {
                                    linuxAutostartInfo = result
                                }
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        SwitchSetting(
                            title = stringResource("start_minimized_tray"),
                            description = stringResource("start_minimized_tray_desc"),
                            icon = Icons.Outlined.VisibilityOff,
                            checked = appSettings.startMinimizedToTray,
                            onCheckedChange = { onAppSettingsChange(appSettings.copy(startMinimizedToTray = it)) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        SwitchSetting(
                            title = stringResource("close_to_tray"),
                            description = stringResource("close_to_tray_desc"),
                            icon = Icons.Outlined.ExitToApp,
                            checked = appSettings.closeToTray,
                            onCheckedChange = { onAppSettingsChange(appSettings.copy(closeToTray = it)) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // NUOVO: Switch Auto-start Server
                        SwitchSetting(
                            title = stringResource("auto_start_server"),
                            description = stringResource("auto_start_server_desc"),
                            icon = Icons.Outlined.PlayCircleOutline,
                            checked = appSettings.autoStartServer,
                            onCheckedChange = { onAppSettingsChange(appSettings.copy(autoStartServer = it)) }
                        )

                        // Selettore modalità (appare solo se auto-start è attivo)
                        AnimatedVisibility(visible = appSettings.autoStartServer) {
                            Column(modifier = Modifier.padding(start = 48.dp, top = 8.dp)) {
                                Text(stringResource("auto_start_mode"), style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.height(8.dp))
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    SegmentedButton(
                                        selected = appSettings.autoStartMulticast,
                                        onClick = { onAppSettingsChange(appSettings.copy(autoStartMulticast = true)) },
                                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                                    ) { Text(stringResource("multicast")) }
                                    SegmentedButton(
                                        selected = !appSettings.autoStartMulticast,
                                        onClick = { onAppSettingsChange(appSettings.copy(autoStartMulticast = false)) },
                                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                                    ) { Text(stringResource("unicast")) }
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        SwitchSetting(
                            title = stringResource("auto_connect_client_title"),
                            description = stringResource("auto_connect_client_desc"),
                            icon = Icons.Outlined.Sensors,
                            checked = appSettings.autoConnectClientEnabled,
                            onCheckedChange = { onAppSettingsChange(appSettings.copy(autoConnectClientEnabled = it)) }
                        )

                        AnimatedVisibility(visible = appSettings.autoConnectClientEnabled) {
                            Column(modifier = Modifier.padding(start = 48.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource("priority_ips"), style = MaterialTheme.typography.labelLarge)
                                appSettings.autoConnectIps.forEachIndexed { index, ip ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        var textValue by remember(ip) { mutableStateOf(ip) }
                                        OutlinedTextField(
                                            value = textValue,
                                            onValueChange = {
                                                textValue = it
                                                val newList = appSettings.autoConnectIps.toMutableList()
                                                newList[index] = it
                                                onAppSettingsChange(appSettings.copy(autoConnectIps = newList))
                                            },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true
                                        )
                                        IconButton(onClick = {
                                            if (index > 0) {
                                                val newList = appSettings.autoConnectIps.toMutableList()
                                                val temp = newList[index]
                                                newList[index] = newList[index - 1]
                                                newList[index - 1] = temp
                                                onAppSettingsChange(appSettings.copy(autoConnectIps = newList))
                                            }
                                        }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, null) }
                                        IconButton(onClick = {
                                            if (index < appSettings.autoConnectIps.size - 1) {
                                                val newList = appSettings.autoConnectIps.toMutableList()
                                                val temp = newList[index]
                                                newList[index] = newList[index + 1]
                                                newList[index + 1] = temp
                                                onAppSettingsChange(appSettings.copy(autoConnectIps = newList))
                                            }
                                        }, enabled = index < appSettings.autoConnectIps.size - 1) { Icon(Icons.Default.KeyboardArrowDown, null) }
                                        IconButton(onClick = {
                                            val newList = appSettings.autoConnectIps.toMutableList()
                                            newList.removeAt(index)
                                            onAppSettingsChange(appSettings.copy(autoConnectIps = newList))
                                        }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                                    }
                                }
                                OutlinedButton(onClick = {
                                    val newList = appSettings.autoConnectIps.toMutableList()
                                    newList.add("")
                                    onAppSettingsChange(appSettings.copy(autoConnectIps = newList))
                                }) {
                                    Text(stringResource("add_ip"))
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        SwitchSetting(
                            title = stringResource("native_engine_title"),
                            description = stringResource("native_engine_desc"),
                            icon = Icons.Outlined.Memory,
                            checked = appSettings.useNativeEngine,
                            onCheckedChange = { onAppSettingsChange(appSettings.copy(useNativeEngine = it)) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        SwitchSetting(
                            title = stringResource("connection_sound_title"),
                            description = stringResource("connection_sound_desc"),
                            icon = Icons.Outlined.VolumeUp,
                            checked = appSettings.connectionSoundEnabled,
                            onCheckedChange = { onAppSettingsChange(appSettings.copy(connectionSoundEnabled = it)) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        SwitchSetting(
                            title = stringResource("disconnection_sound_title"),
                            description = stringResource("disconnection_sound_desc"),
                            icon = Icons.Outlined.VolumeOff,
                            checked = appSettings.disconnectionSoundEnabled,
                            onCheckedChange = { onAppSettingsChange(appSettings.copy(disconnectionSoundEnabled = it)) }
                        )

                    }
                }
                item {
                    SettingsGroup(title = stringResource("about&help"), icon = Icons.Outlined.Info) {
                        ClickableSetting(
                            title = stringResource("android_version"),
                            description = stringResource("source_code_github"),
                            icon = Icons.Outlined.PhoneAndroid,
                            onClick = {
                                openUrl("https://github.com/marcomorosi06/WiFiAudioStreaming-Android")
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        InfoSetting(
                            title = stringResource("developed_by"),
                            description = "Marco Morosi",
                            icon = Icons.Outlined.Person
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        ClickableSetting(
                            title = stringResource("support_kofi_title"),
                            description = stringResource("support_kofi_desc"),
                            icon = Icons.Outlined.LocalCafe,
                            onClick = {
                                openUrl("https://ko-fi.com/marcomorosi")
                            }
                        )
                    }
                }
            }
        }
    }
}

// =================================================================
// ==              COMPONENTI UI MODERNI                          ==
// =================================================================

@Composable
fun StreamingControlCenter(
    isStreaming: Boolean, isServer: Boolean, isServerReady: Boolean,
    serverVolume: Float, onServerVolumeChange: (Float) -> Unit,
    onStart: () -> Unit, onStop: () -> Unit,
    showMicMute: Boolean = false, isMicMuted: Boolean = false, onMicMuteToggle: () -> Unit = {}
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        AnimatedContent(
            targetState = isStreaming,
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) + scaleIn(initialScale = 0.95f) togetherWith
                        fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.95f)
            },
            modifier = Modifier.fillMaxWidth()
        ) { streaming ->
            if (streaming) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    // Indicatore live + pulsante stop sulla stessa riga
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LivePulse()
                            Text(
                                stringResource("streaming_active"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        FilledTonalButton(
                            onClick = onStop,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource("stop"))
                        }
                    }
                    if (showMicMute) {
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = onMicMuteToggle,
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (isMicMuted) ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ) else ButtonDefaults.filledTonalButtonColors()
                        ) {
                            Icon(
                                if (isMicMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (isMicMuted) "mic_muted" else "mic_active"))
                        }
                    }
                    // Slider volume — solo in modalità server
                    if (isServer) {
                        Spacer(Modifier.height(20.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                if (serverVolume == 0f) Icons.Outlined.VolumeOff
                                else if (serverVolume < 1f) Icons.Outlined.VolumeDown
                                else Icons.Outlined.VolumeUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Slider(
                                value = serverVolume,
                                onValueChange = onServerVolumeChange,
                                valueRange = 0f..2f,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${(serverVolume * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(40.dp)
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (isServer) stringResource("ready_to_stream") else stringResource("waiting_for_connection"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (isServer) stringResource("press_start_server") else stringResource("select_server_from_list"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isServer && !isServerReady) {
                            Text(
                                stringResource("install_driver_hint"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (isServer) {
                        Spacer(Modifier.width(16.dp))
                        Button(onClick = onStart, enabled = isServerReady) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource("start_server"))
                        }
                    }
                }
            }
        }
    }
}

// Indicatore rosso pulsante "Live"
@Composable
fun LivePulse() {
    val infiniteTransition = rememberInfiniteTransition(label = "live_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.3f, label = "alpha",
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse)
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(MaterialTheme.colorScheme.error.copy(alpha = alpha), CircleShape)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSelectorCard(isServer: Boolean, onModeChange: (Boolean) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource("mode"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    shape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50),
                    onClick = { onModeChange(false) },
                    selected = !isServer,
                    icon = {
                        val icon: ImageVector = if (!isServer) Icons.Filled.Download else Icons.Outlined.Download
                        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    }
                ) { Text(stringResource("receive_client")) }

                SegmentedButton(
                    shape = RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50),
                    onClick = { onModeChange(true) },
                    selected = isServer,
                    icon = {
                        val icon: ImageVector = if (isServer) Icons.Filled.Upload else Icons.Outlined.Upload
                        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    }
                ) { Text(stringResource("send_server")) }
            }
        }
    }
}

@Composable
fun ServerConfigCard(
    inputDevices: List<Mixer.Info>, selectedInputDevice: Mixer.Info?, onInputDeviceSelected: (Mixer.Info) -> Unit,
    outputDevices: List<Mixer.Info>, selectedServerMicOutput: Mixer.Info?, onServerMicOutputSelected: (Mixer.Info) -> Unit,
    isMulticast: Boolean, onMulticastChanged: (Boolean) -> Unit,
    virtualDriverStatus: VirtualDriverStatus,
    rtpEnabled: Boolean,
    useNativeEngine: Boolean,
    micRoutingMode: MicRoutingMode = MicRoutingMode.OFF,
    onMicRoutingModeChange: (MicRoutingMode) -> Unit = {},
    micRoutingDisabled: Boolean = false,
    virtualMicDisabled: Boolean = false,
    selectedMicMixInput: Mixer.Info? = null,
    onMicMixInputSelected: (Mixer.Info) -> Unit = {}
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource("server_configuration"), style = MaterialTheme.typography.titleLarge)

            // Stato driver — badge compatto colorato (solo in modalità ffmpeg/legacy)
            if (!useNativeEngine) {
                val (driverIcon, driverLabel, driverOk) = when (virtualDriverStatus) {
                    is VirtualDriverStatus.Ok ->
                        Triple(Icons.Outlined.CheckCircle, stringResource("driver_detected"), true)
                    is VirtualDriverStatus.Missing ->
                        Triple(Icons.Outlined.Warning, "${virtualDriverStatus.driverName} — ${stringResource("driver_not_installed", virtualDriverStatus.driverName)}", false)
                    is VirtualDriverStatus.LinuxActionRequired ->
                        Triple(Icons.Outlined.Terminal, stringResource("linux_deps_inline"), false)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (driverOk) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Icon(
                        driverIcon, contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (driverOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text(
                        driverLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (driverOk) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            MicRoutingSelector(
                mode = micRoutingMode,
                onModeChange = onMicRoutingModeChange,
                outputDevices = outputDevices,
                selectedServerMicOutput = selectedServerMicOutput,
                onServerMicOutputSelected = onServerMicOutputSelected,
                disabled = micRoutingDisabled,
                virtualMicDisabled = virtualMicDisabled,
                inputDevices = inputDevices,
                selectedMicMixInput = selectedMicMixInput,
                onMicMixInputSelected = onMicMixInputSelected
            )

            // Multicast toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val actualMulticast = isMulticast || rtpEnabled

                Column(Modifier.weight(1f)) {
                    Text(stringResource("multicast_mode"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (rtpEnabled) stringResource("rtp_forces_multicast") else stringResource("multicast_description"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = actualMulticast,
                    enabled = !rtpEnabled, // Disabilita lo switch se RTP è attivo
                    onCheckedChange = onMulticastChanged
                )
            }
        }
    }
}

@Composable
fun MicRoutingSelector(
    mode: MicRoutingMode,
    onModeChange: (MicRoutingMode) -> Unit,
    outputDevices: List<Mixer.Info>,
    selectedServerMicOutput: Mixer.Info?,
    onServerMicOutputSelected: (Mixer.Info) -> Unit,
    disabled: Boolean = false,
    virtualMicDisabled: Boolean = false,
    inputDevices: List<Mixer.Info> = emptyList(),
    selectedMicMixInput: Mixer.Info? = null,
    onMicMixInputSelected: (Mixer.Info) -> Unit = {}
) {
    val virtualOnlyBlocked = virtualMicDisabled && !disabled

    LaunchedEffect(virtualOnlyBlocked, mode) {
        if (virtualOnlyBlocked && mode == MicRoutingMode.VIRTUAL_MIC) {
            onModeChange(MicRoutingMode.OFF)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource("mic_routing_title"), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource("mic_routing_desc"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (disabled) {
            MicRoutingInfoBanner(
                icon = Icons.Outlined.Info,
                text = stringResource("mic_routing_unicast_only"),
                accent = false
            )
        } else if (virtualOnlyBlocked) {
            MicRoutingInfoBanner(
                icon = Icons.Outlined.Info,
                text = stringResource("mic_routing_virtual_unicast_only"),
                accent = false
            )
        }

        val effectiveMode = if (disabled) MicRoutingMode.OFF else mode
        val offEnabled = !disabled
        val virtualEnabled = !disabled && !virtualOnlyBlocked
        val mixEnabled = !disabled

        MicRoutingOptionRow(
            selected = effectiveMode == MicRoutingMode.OFF,
            title = stringResource("mic_routing_off"),
            subtitle = stringResource("mic_routing_off_desc"),
            onClick = { if (offEnabled) onModeChange(MicRoutingMode.OFF) },
            enabled = offEnabled
        )
        MicRoutingOptionRow(
            selected = effectiveMode == MicRoutingMode.VIRTUAL_MIC,
            title = stringResource("mic_routing_virtual"),
            subtitle = stringResource("mic_routing_virtual_desc"),
            onClick = { if (virtualEnabled) onModeChange(MicRoutingMode.VIRTUAL_MIC) },
            enabled = virtualEnabled
        )
        MicRoutingOptionRow(
            selected = effectiveMode == MicRoutingMode.MIX_INTO_STREAM,
            title = stringResource("mic_routing_mix"),
            subtitle = stringResource("mic_routing_mix_desc"),
            onClick = { if (mixEnabled) onModeChange(MicRoutingMode.MIX_INTO_STREAM) },
            enabled = mixEnabled
        )

        AnimatedVisibility(mode == MicRoutingMode.VIRTUAL_MIC && !virtualOnlyBlocked && !disabled) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    VirtualMicAutodetect.isLinux() -> {
                        MicRoutingInfoBanner(
                            icon = Icons.Outlined.Info,
                            text = stringResource("mic_routing_linux_auto"),
                            accent = true
                        )
                    }
                    else -> {
                        val detected = remember(outputDevices) {
                            VirtualMicAutodetect.detectManualCable(outputDevices)
                        }
                        if (detected != null) {
                            LaunchedEffect(detected.mixerInfo) {
                                val m = detected.mixerInfo
                                if (m != null && selectedServerMicOutput != m) {
                                    onServerMicOutputSelected(m)
                                }
                            }
                            MicRoutingInfoBanner(
                                icon = Icons.Outlined.CheckCircle,
                                text = stringResource("mic_routing_vcable_detected", detected.displayName),
                                accent = true
                            )
                        } else {
                            MicRoutingInfoBanner(
                                icon = Icons.Outlined.Warning,
                                text = stringResource("mic_routing_vcable_missing"),
                                accent = false
                            )
                        }
                        DeviceDropdown(
                            stringResource("mic_routing_manual_device"),
                            outputDevices,
                            selectedServerMicOutput,
                            onServerMicOutputSelected
                        )
                    }
                }
                VirtualMicHelpExpander()
            }
        }

        AnimatedVisibility(mode == MicRoutingMode.MIX_INTO_STREAM && !disabled) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MicRoutingInfoBanner(
                    icon = Icons.Outlined.Info,
                    text = stringResource("mic_routing_mix_info"),
                    accent = true
                )
                DeviceDropdown(
                    stringResource("mic_routing_mix_input_device"),
                    inputDevices,
                    selectedMicMixInput,
                    onMicMixInputSelected
                )
            }
        }
    }
}

@Composable
private fun VirtualMicHelpExpander() {
    var expanded by remember { mutableStateOf(false) }

    val isLinux   = remember { VirtualMicAutodetect.isLinux() }
    val isMac     = remember { VirtualMicAutodetect.isMac() }

    val title: String
    val steps: List<String>
    val linkLabel: String
    val linkUrl: String

    when {
        isLinux -> {
            title     = stringResource("vcable_help_linux_title")
            steps     = listOf(
                stringResource("vcable_help_linux_1"),
                stringResource("vcable_help_linux_2"),
                stringResource("vcable_help_linux_3"),
                stringResource("vcable_help_linux_4")
            )
            linkLabel = stringResource("vcable_help_linux_link")
            linkUrl   = "https://apps.kde.org/pavucontrol/"
        }
        isMac -> {
            title     = stringResource("vcable_help_mac_title")
            steps     = listOf(
                stringResource("vcable_help_mac_1"),
                stringResource("vcable_help_mac_2"),
                stringResource("vcable_help_mac_3"),
                stringResource("vcable_help_mac_4"),
                stringResource("vcable_help_mac_5")
            )
            linkLabel = stringResource("vcable_help_mac_link")
            linkUrl   = "https://github.com/ExistentialAudio/BlackHole"
        }
        else -> {
            title     = stringResource("vcable_help_win_title")
            steps     = listOf(
                stringResource("vcable_help_win_1"),
                stringResource("vcable_help_win_2"),
                stringResource("vcable_help_win_3"),
                stringResource("vcable_help_win_4"),
                stringResource("vcable_help_win_5")
            )
            linkLabel = stringResource("vcable_help_win_link")
            linkUrl   = "https://vb-audio.com/Cable/"
        }
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Outlined.HelpOutline,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                stringResource("vcable_help_button"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                steps.forEachIndexed { index, step ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            step,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { openUrl(linkUrl) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        linkLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun MicRoutingOptionRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f * alpha)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f * alpha)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
        }
    }
}

@Composable
private fun MicRoutingInfoBanner(icon: ImageVector, text: String, accent: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (accent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Icon(
            icon, contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (accent) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
fun ClientConfigCard(
    outputDevices: List<Mixer.Info>, selectedOutputDevice: Mixer.Info?, onOutputDeviceSelected: (Mixer.Info) -> Unit,
    sendMicrophone: Boolean, onSendMicrophoneChanged: (Boolean) -> Unit,
    inputDevices: List<Mixer.Info>, selectedClientMic: Mixer.Info?, onClientMicSelected: (Mixer.Info) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource("client_configuration"), style = MaterialTheme.typography.titleLarge)
            DeviceDropdown(stringResource("audio_output_device"), outputDevices, selectedOutputDevice, onOutputDeviceSelected)

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource("send_mic_to_server"), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource("send_mic_to_server_desc"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = sendMicrophone, onCheckedChange = onSendMicrophoneChanged)
                }
                AnimatedVisibility(sendMicrophone) {
                    DeviceDropdown(stringResource("select_mic_to_send"), inputDevices, selectedClientMic, onClientMicSelected)
                }
            }
        }
    }
}

@Composable
fun DeviceDiscoveryList(
    devices: Map<String, ServerInfo>,
    onConnect: (ServerInfo) -> Unit,
    onRefresh: () -> Unit,
    enabled: Boolean,
    autoConnectIps: List<String>,
    onToggleAutoConnectIp: (String) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource("servers_found"), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, stringResource("refresh")) }
            }
            Spacer(Modifier.height(12.dp))

            if (devices.isEmpty()) {
                val infiniteTransition = rememberInfiniteTransition(label = "searching-indicator")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.6f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(animation = tween(800), repeatMode = RepeatMode.Reverse),
                    label = "alpha-animation"
                )

                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource("status_searching_servers"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    devices.forEach { (hostname, serverInfo) ->
                        DeviceItem(
                            hostname = hostname,
                            serverInfo = serverInfo,
                            onClick = { onConnect(serverInfo) },
                            enabled = enabled,
                            isStarred = autoConnectIps.contains(serverInfo.ip),
                            onStarClick = { onToggleAutoConnectIp(serverInfo.ip) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceItem(
    hostname: String,
    serverInfo: ServerInfo,
    onClick: () -> Unit,
    enabled: Boolean,
    isStarred: Boolean,
    onStarClick: () -> Unit
) {
    val modeText = if (serverInfo.isMulticast) stringResource("multicast") else stringResource("unicast")
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(if (serverInfo.isMulticast) Icons.Filled.Groups else Icons.Default.Person, contentDescription = modeText)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(hostname, fontWeight = FontWeight.Bold)
                Text("${serverInfo.ip}:${serverInfo.port} ($modeText)", style = MaterialTheme.typography.bodySmall)
                val caps = serverInfo.capabilities
                if (caps != null && caps.protocols.size > 1) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        caps.protocols.forEach { proto ->
                            val (label, color) = when (proto) {
                                StreamingProtocol.WFAS -> "WFAS" to MaterialTheme.colorScheme.primaryContainer
                                StreamingProtocol.RTP  -> "RTP"  to MaterialTheme.colorScheme.tertiaryContainer
                                StreamingProtocol.HTTP -> "HTTP" to MaterialTheme.colorScheme.secondaryContainer
                            }
                            Surface(shape = RoundedCornerShape(50), color = color) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
            IconButton(onClick = onStarClick) {
                Icon(
                    imageVector = if (isStarred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = null,
                    tint = if (isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource("connect"))
        }
    }
}

@Composable
fun SettingsGroup(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp)); Text(title, style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsContent(settings: AudioSettings_V1, onSettingsChange: (AudioSettings_V1) -> Unit) {
    val sampleRates = listOf(44100f, 48000f, 96000f)
    val bitDepths = listOf(8, 16)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdown(
            stringResource("sample_rate"), "${settings.sampleRate.toInt()} Hz",
            sampleRates.map { "${it.toInt()} Hz" to it },
            { onSettingsChange(settings.copy(sampleRate = it)) }, Modifier.weight(1f)
        )
        ExposedDropdown(
            stringResource("bit_depth"), "${settings.bitDepth}-bit",
            bitDepths.map { "$it-bit" to it },
            { onSettingsChange(settings.copy(bitDepth = it)) }, Modifier.weight(1f)
        )
    }
    Text(stringResource("channels"), style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = settings.channels == 1,
            onClick = { onSettingsChange(settings.copy(channels = 1)) },
            shape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
        ) { Text(stringResource("mono")) }
        SegmentedButton(
            selected = settings.channels == 2,
            onClick = { onSettingsChange(settings.copy(channels = 2)) },
            shape = RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
        ) { Text(stringResource("stereo")) }
    }
    Text(stringResource("buffer_size_label", settings.bufferSize), style = MaterialTheme.typography.labelLarge)
    Slider(
        settings.bufferSize.toFloat(),
        { onSettingsChange(settings.copy(bufferSize = it.roundToInt())) },
        valueRange = 512f..8192f,
        steps = ((8192f - 512f) / 256f).toInt() - 1
    )
}

@Composable
fun ServerStatusBar(
    isServer: Boolean,
    isStreaming: Boolean,
    connectionStatus: String,
    localIp: String,
    streamingPort: String,
    httpUrl: String?
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        // Status pill
        Surface(
            shape = RoundedCornerShape(50),
            color = if (isStreaming) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isStreaming) LivePulse()
                Text(
                    connectionStatus,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isStreaming) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // IP / porta — solo in modalità server
        if (isServer) {
            Text(
                "IP $localIp · porta $streamingPort",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
        // Banner HTTP
        if (httpUrl != null) {
            HttpUrlBanner(url = httpUrl)
        }
    }
}

@Composable
fun HttpUrlBanner(url: String) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Outlined.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource("http_stream_active"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    url,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(url))
                    copied = true
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    if (copied) Icons.Outlined.CheckCircle else Icons.Outlined.ContentCopy,
                    contentDescription = stringResource("copy_url"),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
            // Apri nel browser di sistema
            IconButton(
                onClick = {
                    openUrl(url)
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Outlined.OpenInBrowser,
                    contentDescription = stringResource("open_in_browser"),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
    // Resetta l'icona "copied" dopo 2s
    if (copied) {
        LaunchedEffect(Unit) {
            delay(2000)
            copied = false
        }
    }
}

@Composable
fun NetworkSettingsContent(
    port: String, onPortChange: (String) -> Unit,
    micPort: String, onMicPortChange: (String) -> Unit,
    appSettings: AppSettings,
    onAppSettingsChange: (AppSettings) -> Unit
) {
    OutlinedTextField(
        port,
        { if (it.all(Char::isDigit) && it.length <= 5) onPortChange(it) },
        label = { Text(stringResource("main_audio_port")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        micPort,
        { if (it.all(Char::isDigit) && it.length <= 5) onMicPortChange(it) },
        label = { Text(stringResource("mic_port")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    val interfaces = remember {
        try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && it.inetAddresses.toList().any { addr -> addr is java.net.Inet4Address } }
                .map { it.displayName to it.displayName }
        } catch (e: Exception) {
            emptyList()
        }
    }

    val interfaceOptions = listOf("Auto" to "Auto") + interfaces

    ExposedDropdown(
        label = stringResource("network_interface"),
        value = appSettings.networkInterface,
        options = interfaceOptions,
        onOptionSelected = { onAppSettingsChange(appSettings.copy(networkInterface = it)) },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = stringResource("network_interface_desc"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp)
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text(
        stringResource("server_protocols"),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
    Text(
        stringResource("server_protocols_desc"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // WFAS — sempre attivo, non disattivabile
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val iconLayoutSize = 30.dp

        Icon(
            painter = painterResource("wfas_protocol.png"),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(iconLayoutSize)
        )
        Column(Modifier.weight(1f)) {
            Text("WFAS (custom)", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Descrizione",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Badge "sempre attivo" al posto dello switch
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                stringResource("always_on"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }

    // RTP toggle
    SwitchSetting(
        title = "RTP/PCM",
        description = stringResource("protocol_rtp_desc"),
        icon = Icons.Outlined.Radio,
        checked = appSettings.rtpEnabled,
        onCheckedChange = { onAppSettingsChange(appSettings.copy(rtpEnabled = it)) }
    )
    AnimatedVisibility(appSettings.rtpEnabled) {
        OutlinedTextField(
            appSettings.rtpPort,
            { if (it.all(Char::isDigit) && it.length <= 5) onAppSettingsChange(appSettings.copy(rtpPort = it)) },
            label = { Text("Porta RTP") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
    }

    // HTTP toggle + campo porta
    SwitchSetting(
        title = stringResource("protocol_http_title"),
        description = stringResource("protocol_http_desc"),
        icon = Icons.Outlined.Language,
        checked = appSettings.httpEnabled,
        onCheckedChange = { onAppSettingsChange(appSettings.copy(httpEnabled = it)) }
    )
    AnimatedVisibility(appSettings.httpEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                appSettings.httpPort,
                { if (it.all(Char::isDigit) && it.length <= 5) onAppSettingsChange(appSettings.copy(httpPort = it)) },
                label = { Text(stringResource("http_port")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource("http_port_hint")) }
            )
            // Scelta codec — due opzioni chiare
            Text(stringResource("http_codec_label"), style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !appSettings.httpSafariMode,
                    onClick = { onAppSettingsChange(appSettings.copy(httpSafariMode = false)) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { Icon(Icons.Outlined.Speed, null, Modifier.size(ButtonDefaults.IconSize)) }
                ) { Text("Opus") }
                SegmentedButton(
                    selected = appSettings.httpSafariMode,
                    onClick = { onAppSettingsChange(appSettings.copy(httpSafariMode = true)) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { Icon(Icons.Outlined.PhoneIphone, null, Modifier.size(ButtonDefaults.IconSize)) }
                ) { Text("AAC (Safari)") }
            }
            Text(
                if (appSettings.httpSafariMode) stringResource("http_codec_aac_desc")
                else stringResource("http_codec_opus_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource("http_latency_warning"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSelector(currentTheme: Theme, onThemeChange: (Theme) -> Unit) {
    Text(
        stringResource("theme"),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = currentTheme == Theme.Light, onClick = { onThemeChange(Theme.Light) },
            shape = SegmentedButtonDefaults.itemShape(0, 3),
            icon = { Icon(Icons.Outlined.LightMode, contentDescription = null, Modifier.size(ButtonDefaults.IconSize)) }
        ) { Text(stringResource("light")) }
        SegmentedButton(
            selected = currentTheme == Theme.Dark, onClick = { onThemeChange(Theme.Dark) },
            shape = SegmentedButtonDefaults.itemShape(1, 3),
            icon = { Icon(Icons.Outlined.DarkMode, contentDescription = null, Modifier.size(ButtonDefaults.IconSize)) }
        ) { Text(stringResource("dark")) }
        SegmentedButton(
            selected = currentTheme == Theme.System, onClick = { onThemeChange(Theme.System) },
            shape = SegmentedButtonDefaults.itemShape(2, 3),
            icon = { Icon(Icons.Outlined.Tonality, contentDescription = null, Modifier.size(ButtonDefaults.IconSize)) }
        ) { Text(stringResource("system")) }
    }
}

@Composable
fun SwitchSetting(
    title: String, description: String, icon: ImageVector,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ExposedDropdown(
    label: String, value: String, options: List<Pair<String, T>>,
    onOptionSelected: (T) -> Unit, modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, optionValue) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onOptionSelected(optionValue); expanded = false }
                )
            }
        }
    }
}

@Composable
fun DeviceDropdown(label: String, devices: List<Mixer.Info>, selectedDevice: Mixer.Info?, onDeviceSelected: (Mixer.Info) -> Unit) {
    ExposedDropdown(label, selectedDevice?.name ?: stringResource("no_device"), devices.map { it.name to it }, onDeviceSelected)
}

@Composable
fun InfoSetting(title: String, description: String, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClickableSetting(title: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Go")
        }
    }
}

@Composable
fun WindowsPrivacyBanner(onDismiss: (Boolean) -> Unit) {
    var isVisible by remember { mutableStateOf(true) } // <-- Stato locale aggiunto
    var dontShowAgain by remember { mutableStateOf(false) }

    // Se è stato nascosto per questa sessione, distrugge il componente
    if (!isVisible) return

    val containerColor = MaterialTheme.colorScheme.tertiaryContainer
    val onContainerColor = MaterialTheme.colorScheme.onTertiaryContainer

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MicOff, contentDescription = "Privacy", tint = onContainerColor, modifier = Modifier.size(32.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Windows Mic Privacy", fontWeight = FontWeight.Bold, color = onContainerColor)
                    Text(
                        "If the client hears total silence, Windows is blocking the virtual cable. You MUST allow desktop apps to access the microphone in Windows Settings.",
                        style = MaterialTheme.typography.bodySmall, color = onContainerColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = { dontShowAgain = it },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.tertiary)
                    )
                    Text("Do not show again", style = MaterialTheme.typography.bodySmall, color = onContainerColor)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            try { Runtime.getRuntime().exec("cmd /c start ms-settings:privacy-microphone") } catch (e: Exception) {}
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary)
                    ) { Text("Fix") }

                    Button(
                        onClick = {
                            isVisible = false // <-- Nasconde IMMEDIATAMENTE il banner dalla UI
                            onDismiss(dontShowAgain) // Comunica a Main.kt se deve salvarlo per sempre
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary)
                    ) { Text("OK") }
                }
            }
        }
    }
}

@Composable
fun WindowsRoutingBanner(onDismiss: (Boolean) -> Unit) {
    var isVisible by remember { mutableStateOf(true) } // <-- Stato locale aggiunto
    var dontShowAgain by remember { mutableStateOf(false) }

    // Se è stato nascosto per questa sessione, distrugge il componente
    if (!isVisible) return

    val containerColor = MaterialTheme.colorScheme.secondaryContainer
    val onContainerColor = MaterialTheme.colorScheme.onSecondaryContainer

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VolumeUp, contentDescription = "Routing", tint = onContainerColor, modifier = Modifier.size(32.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Action Required: Set Audio Output", fontWeight = FontWeight.Bold, color = onContainerColor)
                    Text(
                        "To stream system audio, you must click the speaker icon in your Windows taskbar and set 'CABLE Input' as your default playback device before starting.",
                        style = MaterialTheme.typography.bodySmall, color = onContainerColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = { dontShowAgain = it },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.secondary)
                    )
                    Text("Do not show again", style = MaterialTheme.typography.bodySmall, color = onContainerColor)
                }
                Button(
                    onClick = {
                        isVisible = false // <-- Nasconde IMMEDIATAMENTE il banner dalla UI
                        onDismiss(dontShowAgain)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)
                ) { Text("OK") }
            }
        }
    }
}

@Composable
fun AdvancedColorPicker(
    initialColor: Long?,
    onColorChange: (Long) -> Unit
) {
    var hue by remember { mutableFloatStateOf(0f) } // 0f .. 360f
    var lightness by remember { mutableFloatStateOf(0.5f) } // 0f (Nero) .. 0.5f (Puro) .. 1f (Bianco)
    var hexText by remember { mutableStateOf("") }

    // Motore HSL -> RGB
    fun hslToColor(h: Float, s: Float, l: Float): Color {
        val c = (1f - Math.abs(2f * l - 1f)) * s
        val x = c * (1f - Math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        var r = 0f; var g = 0f; var b = 0f
        when {
            h < 60f -> { r = c; g = x; b = 0f }
            h < 120f -> { r = x; g = c; b = 0f }
            h < 180f -> { r = 0f; g = c; b = x }
            h < 240f -> { r = 0f; g = x; b = c }
            h < 300f -> { r = x; g = 0f; b = c }
            else -> { r = c; g = 0f; b = x }
        }
        return Color(r + m, g + m, b + m)
    }

    // Motore RGB -> HSL
    fun colorToHsl(color: Color): FloatArray {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        var h = 0f
        var s = 0f
        if (max != min) {
            val d = max - min
            s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            h = when (max) {
                r -> (g - b) / d + (if (g < b) 6f else 0f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
            h *= 60f
            if (h < 0f) h += 360f
        }
        return floatArrayOf(h, s, l)
    }

    // Caricamento del colore salvato
    LaunchedEffect(initialColor) {
        if (initialColor != null) {
            val c = Color(initialColor.toULong())
            val hsl = colorToHsl(c)
            hue = hsl[0]
            lightness = hsl[2]
            hexText = String.format("%06X", 0xFFFFFF and c.toArgb())
        } else {
            hue = 0f
            lightness = 0.5f // 0.5 è il colore naturale!
            hexText = "FF0000"
        }
    }

    fun updateColor(newHue: Float, newLightness: Float) {
        hue = newHue
        lightness = newLightness
        val composeColor = hslToColor(newHue, 1f, newLightness)
        hexText = String.format("%06X", 0xFFFFFF and composeColor.toArgb())
        onColorChange(composeColor.value.toLong())
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. LA RUOTA DEI COLORI (Ora reagisce visivamente alla luminosità!)
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val x = change.position.x - size.width / 2
                        val y = change.position.y - size.height / 2
                        var angle = Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat()
                        if (angle < 0) angle += 360f
                        updateColor(angle, lightness)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val x = offset.x - size.width / 2
                        val y = offset.y - size.height / 2
                        var angle = Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat()
                        if (angle < 0) angle += 360f
                        updateColor(angle, lightness)
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // I colori della ruota si aggiornano in tempo reale se muovi lo slider!
                val sweepColors = listOf(
                    hslToColor(0f, 1f, lightness), hslToColor(60f, 1f, lightness),
                    hslToColor(120f, 1f, lightness), hslToColor(180f, 1f, lightness),
                    hslToColor(240f, 1f, lightness), hslToColor(300f, 1f, lightness),
                    hslToColor(360f, 1f, lightness)
                )
                drawCircle(brush = Brush.sweepGradient(colors = sweepColors))

                // Cursore bianco e nero per essere sempre visibile
                val angleRad = Math.toRadians(hue.toDouble())
                val radius = (size.width / 2) - 10f
                val dotX = center.x + (radius * cos(angleRad)).toFloat()
                val dotY = center.y + (radius * sin(angleRad)).toFloat()

                drawCircle(color = Color.Black, radius = 8f, center = Offset(dotX, dotY), style = Stroke(width = 2f))
                drawCircle(color = Color.White, radius = 6f, center = Offset(dotX, dotY))
            }
        }

        // 2. SLIDER DELLA LUMINANZA (0 = Nero, 0.5 = Naturale, 1 = Bianco)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Lightness: ${(lightness * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = lightness,
                onValueChange = { updateColor(hue, it) },
                valueRange = 0.05f..0.95f, // Evitiamo gli estremi assoluti per non perdere la Tinta
                modifier = Modifier.width(160.dp)
            )
        }

        // 3. INPUT HEX
        OutlinedTextField(
            value = hexText,
            onValueChange = { input ->
                // Filtra solo i caratteri esadecimali validi
                val filtered = input.uppercase().filter { it in "0123456789ABCDEF" }

                if (filtered.length <= 6) {
                    hexText = filtered // Aggiorna il testo a schermo

                    // MAGIA REATTIVA: Applica il colore da solo non appena scrivi il 6° carattere!
                    if (filtered.length == 6) {
                        try {
                            val parsedColor = java.awt.Color.decode("#$filtered")
                            val c = Color(parsedColor.red, parsedColor.green, parsedColor.blue)
                            val hsl = colorToHsl(c)

                            // Aggiorniamo le variabili visive (ruota e slider)
                            hue = hsl[0]
                            lightness = hsl[2]

                            // Inviamo il colore direttamente al nostro motore Material You
                            onColorChange(c.value.toLong())
                        } catch (e: Exception) {}
                    }
                }
            },
            label = { Text("HEX Color") },
            prefix = { Text("#") },
            singleLine = true,
            modifier = Modifier.width(160.dp),
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
        )
    }
}

@Composable
fun RtpSdpBanner(
    localIp: String,
    isMulticast: Boolean,
    port: String,
    sampleRate: Int,
    channels: Int
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    // Generazione dinamica del file SDP
    val targetIp = if (isMulticast) "239.255.0.1" else localIp
    val sdpContent = """
        v=0
        o=- 0 0 IN IP4 $localIp
        s=WiFiAudioStreamer RTP
        c=IN IP4 $targetIp
        t=0 0
        m=audio $port RTP/AVP 96
        a=rtpmap:96 L16/$sampleRate/$channels
    """.trimIndent()

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Outlined.Radio,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource("rtp_active"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = stringResource("rtp_description"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                )
            }

            // Pulsante: Copia negli appunti
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(sdpContent))
                    copied = true
                },
                modifier = Modifier.background(MaterialTheme.colorScheme.tertiary, CircleShape).size(36.dp)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = stringResource("copy_sdp"),
                    tint = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Pulsante: Salva su file
            IconButton(
                onClick = {
                    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "SDP", java.awt.FileDialog.SAVE)
                    dialog.file = "stream.sdp"
                    dialog.isVisible = true

                    if (dialog.directory != null && dialog.file != null) {
                        val file = java.io.File(dialog.directory, dialog.file)
                        file.writeText(sdpContent)
                    }
                },
                modifier = Modifier.background(MaterialTheme.colorScheme.tertiary, CircleShape).size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.SaveAlt,
                    contentDescription = stringResource("save_sdp"),
                    tint = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    if (copied) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }
}
