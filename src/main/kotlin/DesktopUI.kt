import androidx.compose.animation.*
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.net.URI
import javax.sound.sampled.Mixer
import kotlin.math.roundToInt

// =================================================================
// ==                  SCHERMATA PRINCIPALE                       ==
// =================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(
    appSettings: AppSettings,
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
    onOpenSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi Audio Streamer") }, // Sostituito stringResource
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Apri impostazioni")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(connectionStatus, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
            }

            item {
                StreamingControlCenter(
                    isStreaming = isStreaming,
                    isServer = isServer,
                    isServerReady = selectedInputDevice != null && (!appSettings.experimentalFeaturesEnabled || selectedServerMicOutput != null),
                    onStart = onStartStreaming,
                    onStop = onStopStreaming
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
                            experimentalFeaturesEnabled = appSettings.experimentalFeaturesEnabled,
                            inputDevices = inputDevices,
                            selectedInputDevice = selectedInputDevice,
                            onInputDeviceSelected = onSelectedInputDeviceChange,
                            outputDevices = outputDevices,
                            selectedServerMicOutput = selectedServerMicOutput,
                            onServerMicOutputSelected = onSelectedServerMicOutputChange,
                            isMulticast = isMulticastMode,
                            onMulticastChanged = onMulticastModeChange
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
                                experimentalFeaturesEnabled = appSettings.experimentalFeaturesEnabled,
                                outputDevices = outputDevices,
                                selectedOutputDevice = selectedOutputDevice,
                                onOutputDeviceSelected = onSelectedOutputDeviceChange,
                                sendMicrophone = sendMicrophone,
                                onSendMicrophoneChanged = onSendMicrophoneChange,
                                inputDevices = inputDevices,
                                selectedClientMic = selectedClientMic,
                                onClientMicSelected = onSelectedClientMicChange
                            )
                            DeviceDiscoveryList(
                                devices = discoveredDevices,
                                onConnect = onConnectToServer,
                                onRefresh = onRefreshDevices,
                                enabled = selectedOutputDevice != null && (!sendMicrophone || selectedClientMic != null)
                            )
                        }
                    }
                }
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
    onClose: () -> Unit
) {
    var showExperimentalWarningDialog by remember { mutableStateOf(false) }

    if (showExperimentalWarningDialog) {
        AlertDialog(
            onDismissRequest = { showExperimentalWarningDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = stringResource("warning")) },
            title = { Text(stringResource("experimental_features_title")) },
            text = { Text(stringResource("experimental_features_warning_text")) },
            confirmButton = {
                TextButton(onClick = {
                    onAppSettingsChange(appSettings.copy(experimentalFeaturesEnabled = true))
                    showExperimentalWarningDialog = false
                }) { Text(stringResource("activate_anyway")) }
            },
            dismissButton = {
                TextButton(onClick = { showExperimentalWarningDialog = false }) { Text(stringResource("cancel")) }
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
                    title = { Text(stringResource("settings")) },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource("close")) } }
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
                            experimentalFeaturesEnabled = appSettings.experimentalFeaturesEnabled
                        )
                    }
                }
                item {
                    SettingsGroup(title = stringResource("advanced"), icon = Icons.Outlined.Science) {
                        SwitchSetting(
                            title = stringResource("experimental_features"),
                            description = stringResource("experimental_features_description"),
                            icon = Icons.Outlined.Mic,
                            checked = appSettings.experimentalFeaturesEnabled,
                            onCheckedChange = { isEnabled ->
                                if (isEnabled) {
                                    showExperimentalWarningDialog = true
                                } else {
                                    onAppSettingsChange(appSettings.copy(experimentalFeaturesEnabled = false))
                                }
                            }
                        )
                    }
                }
                item {
                    SettingsGroup(title = stringResource("about&help"), icon = Icons.Outlined.Info) {
                        ClickableSetting(
                            title = stringResource("android_version"),
                            description = stringResource("source_code_github"),
                            icon = Icons.Outlined.PhoneAndroid, // Icona per rappresentare Android
                            onClick = {
                                val url = "https://github.com/tuo-username/tuo-repo-android" //TODO LINK ANDROID
                                try {
                                    Desktop.getDesktop().browse(URI(url))
                                } catch (e: Exception) {
                                    println("Could not open link: ${e.message}")
                                }
                            }
                        )
                        Divider(modifier = Modifier.padding(vertical = 8.dp))

                        InfoSetting(
                            title = stringResource("developed_by"),
                            description = "Marco Morosi",
                            icon = Icons.Outlined.Person
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
    isStreaming: Boolean, isServer: Boolean, isServerReady: Boolean, onStart: () -> Unit, onStop: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        AnimatedContent(
            targetState = isStreaming,
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) + scaleIn(initialScale = 0.9f) togetherWith
                        fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.9f)
            },
            modifier = Modifier.padding(24.dp).fillMaxWidth()
        ) { streaming ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (streaming) {
                    Icon(Icons.Filled.Podcasts, stringResource("streaming_active"), modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource("streaming_active"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text(stringResource("stop"))
                    }
                } else {
                    val icon = if (isServer) Icons.Outlined.PlayCircle else Icons.Filled.Sensors
                    val title = if (isServer) stringResource("ready_to_stream") else stringResource("waiting_for_connection")
                    val subtitle = if (isServer) stringResource("press_start_server") else stringResource("select_server_from_list")

                    Icon(icon, title, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)

                    if (isServer) {
                        Button(onClick = onStart, enabled = isServerReady, modifier = Modifier.padding(top = 8.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp)); Text(stringResource("start_server"))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSelectorCard(isServer: Boolean, onModeChange: (Boolean) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource("mode"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
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
    experimentalFeaturesEnabled: Boolean,
    inputDevices: List<Mixer.Info>, selectedInputDevice: Mixer.Info?, onInputDeviceSelected: (Mixer.Info) -> Unit,
    outputDevices: List<Mixer.Info>, selectedServerMicOutput: Mixer.Info?, onServerMicOutputSelected: (Mixer.Info) -> Unit,
    isMulticast: Boolean, onMulticastChanged: (Boolean) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource("server_configuration"), style = MaterialTheme.typography.titleLarge)
            DeviceDropdown(stringResource("main_audio_source_input"), inputDevices, selectedInputDevice, onInputDeviceSelected)

            AnimatedVisibility(experimentalFeaturesEnabled) {
                DeviceDropdown(stringResource("mic_output_received"), outputDevices, selectedServerMicOutput, onServerMicOutputSelected)
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource("multicast_mode"), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource("multicast_description"), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = isMulticast, onCheckedChange = onMulticastChanged)
            }
        }
    }
}

@Composable
fun ClientConfigCard(
    experimentalFeaturesEnabled: Boolean,
    outputDevices: List<Mixer.Info>, selectedOutputDevice: Mixer.Info?, onOutputDeviceSelected: (Mixer.Info) -> Unit,
    sendMicrophone: Boolean, onSendMicrophoneChanged: (Boolean) -> Unit,
    inputDevices: List<Mixer.Info>, selectedClientMic: Mixer.Info?, onClientMicSelected: (Mixer.Info) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource("client_configuration"), style = MaterialTheme.typography.titleLarge)
            DeviceDropdown(stringResource("audio_output_device"), outputDevices, selectedOutputDevice, onOutputDeviceSelected)

            AnimatedVisibility(experimentalFeaturesEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource("send_mic_to_server"), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = sendMicrophone, onCheckedChange = onSendMicrophoneChanged)
                    }
                    AnimatedVisibility(sendMicrophone) {
                        DeviceDropdown(stringResource("select_mic_to_send"), inputDevices, selectedClientMic, onClientMicSelected)
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceDiscoveryList(
    devices: Map<String, ServerInfo>, onConnect: (ServerInfo) -> Unit, onRefresh: () -> Unit, enabled: Boolean
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource("servers_found"), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, stringResource("refresh")) }
            }
            Spacer(Modifier.height(12.dp))

            if (devices.isEmpty()) {
                val infiniteTransition = rememberInfiniteTransition(label = "searching-indicator")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1f,
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
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    devices.forEach { (hostname, serverInfo) ->
                        DeviceItem(
                            hostname = hostname,
                            serverInfo = serverInfo,
                            onClick = { onConnect(serverInfo) },
                            enabled = enabled
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceItem(hostname: String, serverInfo: ServerInfo, onClick: () -> Unit, enabled: Boolean) {
    val modeText = if (serverInfo.isMulticast) stringResource("multicast") else stringResource("unicast")
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(if (serverInfo.isMulticast) Icons.Filled.Groups else Icons.Default.Person, contentDescription = modeText)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(hostname, fontWeight = FontWeight.Bold)
                Text("${serverInfo.ip}:${serverInfo.port} ($modeText)", style = MaterialTheme.typography.bodySmall)
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
            Divider()
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
        ExposedDropdown(stringResource("sample_rate"), "${settings.sampleRate.toInt()} Hz", sampleRates.map { "${it.toInt()} Hz" to it }, { onSettingsChange(settings.copy(sampleRate = it)) }, Modifier.weight(1f))
        ExposedDropdown(stringResource("bit_depth"), "${settings.bitDepth}-bit", bitDepths.map { "$it-bit" to it }, { onSettingsChange(settings.copy(bitDepth = it)) }, Modifier.weight(1f))
    }
    Text(stringResource("channels"), style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = settings.channels == 1,
            onClick = { onSettingsChange(settings.copy(channels = 1)) },
            shape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
        ) {
            Text(stringResource("mono"))
        }
        SegmentedButton(
            selected = settings.channels == 2,
            onClick = { onSettingsChange(settings.copy(channels = 2)) },
            shape = RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
        ) {
            Text(stringResource("stereo"))
        }
    }
    Text(stringResource("buffer_size_label", settings.bufferSize), style = MaterialTheme.typography.labelLarge)
    Slider(settings.bufferSize.toFloat(), { onSettingsChange(settings.copy(bufferSize = it.roundToInt())) }, valueRange = 512f..8192f, steps = ((8192f - 512f) / 256f).toInt() - 1)
}

@Composable
fun NetworkSettingsContent(
    port: String, onPortChange: (String) -> Unit,
    micPort: String, onMicPortChange: (String) -> Unit,
    experimentalFeaturesEnabled: Boolean
) {
    OutlinedTextField(port, { if (it.all(Char::isDigit) && it.length <= 5) onPortChange(it) }, label = { Text(stringResource("main_audio_port")) }, singleLine = true, modifier = Modifier.fillMaxWidth())

    AnimatedVisibility(experimentalFeaturesEnabled) {
        OutlinedTextField(micPort, { if (it.all(Char::isDigit) && it.length <= 5) onMicPortChange(it) }, label = { Text(stringResource("mic_port")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
            selected = currentTheme == Theme.Light,
            onClick = { onThemeChange(Theme.Light) },
            shape = SegmentedButtonDefaults.itemShape(0, 3),
            icon = { Icon(Icons.Outlined.LightMode, contentDescription = null, Modifier.size(ButtonDefaults.IconSize)) }
        ) { Text(stringResource("light")) }

        SegmentedButton(
            selected = currentTheme == Theme.Dark,
            onClick = { onThemeChange(Theme.Dark) },
            shape = SegmentedButtonDefaults.itemShape(1, 3),
            icon = { Icon(Icons.Outlined.DarkMode, contentDescription = null, Modifier.size(ButtonDefaults.IconSize)) }
        ) { Text(stringResource("dark")) }

        SegmentedButton(
            selected = currentTheme == Theme.System,
            onClick = { onThemeChange(Theme.System) },
            shape = SegmentedButtonDefaults.itemShape(2, 3),
            icon = { Icon(Icons.Outlined.Tonality, contentDescription = null, Modifier.size(ButtonDefaults.IconSize)) }
        ) { Text(stringResource("system")) }
    }
}

@Composable
fun SwitchSetting(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
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
    label: String,
    value: String,
    options: List<Pair<String, T>>,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        // Assegna il nuovo stato fornito dal callback direttamente alla tua variabile.
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {}, // Corretto, non serve onValueChange essendo readOnly
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false } // onDismissRequest chiude il menu
        ) {
            options.forEach { (text, optionValue) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onOptionSelected(optionValue)
                        expanded = false // Chiudi il menu dopo la selezione
                    }
                )
            }
        }
    }
}

@Composable
fun DeviceDropdown(label: String, devices: List<Mixer.Info>, selectedDevice: Mixer.Info?, onDeviceSelected: (Mixer.Info) -> Unit) {
    ExposedDropdown(label, selectedDevice?.name ?: stringResource("no_device"), devices.map { it.name to it }, onDeviceSelected)
}

/**
 * Mostra un'impostazione statica con titolo, descrizione e icona.
 * Non è cliccabile.
 */
@Composable
fun InfoSetting(
    title: String,
    description: String,
    icon: ImageVector
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
    }
}

/**
 * Mostra un'impostazione cliccabile che esegue un'azione.
 * Mostra una freccia per indicare l'interattività.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClickableSetting(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
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