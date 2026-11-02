package com.jarvis.assistant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.domain.wakeword.WakeWordEngineState
import com.jarvis.assistant.platform.WhatsAppSendCoordinator
import com.jarvis.assistant.ui.SystemSettings
import com.jarvis.assistant.ui.components.AuroraBackground
import com.jarvis.assistant.ui.components.GlassCard
import com.jarvis.assistant.ui.components.SectionLabel
import com.jarvis.assistant.ui.theme.JarvisColors
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    whatsApp: WhatsAppSendCoordinator,
    onBack: () -> Unit,
    onReplayOnboarding: () -> Unit,
) {
    val settings = viewModel.settings.collectAsStateWithLifecycle().value ?: return
    val engine by viewModel.engineState.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Re-read system state when returning from a system settings screen.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val accessibilityOn = remember(resumeTick) { whatsApp.isServiceEnabled() }
    val batteryExempt = remember(resumeTick) { SystemSettings.ignoringBatteryOptimizations(context) }
    val overlayAllowed = remember(resumeTick) { SystemSettings.canDrawOverlays(context) }

    AuroraBackground {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
            }

            SectionLabel("Wake word")
            GlassCard(Modifier.fillMaxWidth()) {
                SettingSwitch(
                    "Listen for “Jarvis”",
                    when (engine) {
                        WakeWordEngineState.Listening -> "Listening in the background"
                        is WakeWordEngineState.Unavailable -> (engine as WakeWordEngineState.Unavailable).reason
                        WakeWordEngineState.Loading -> "Loading model…"
                        else -> "Runs offline with Vosk. Shows a persistent notification."
                    },
                    settings.wakeWordEnabled,
                    viewModel::setWakeWord,
                )
                Divider()
                SettingSlider(
                    title = "Sensitivity",
                    valueLabel = when {
                        settings.wakeWordSensitivity < 0.34f -> "Strict"
                        settings.wakeWordSensitivity < 0.67f -> "Balanced"
                        else -> "Sensitive"
                    },
                    value = settings.wakeWordSensitivity,
                    range = 0f..1f,
                    enabled = settings.wakeWordEnabled,
                    onChange = viewModel::setSensitivity,
                )
                Text(
                    "Lower it if Jarvis wakes up by itself; raise it if it misses you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisColors.TextMuted,
                )
                SettingSlider(
                    title = "Re-trigger delay",
                    valueLabel = "%.1f s".format(settings.wakeWordDebounceMs / 1000f),
                    value = settings.wakeWordDebounceMs / 1000f,
                    range = 0.5f..5f,
                    steps = 8,
                    enabled = settings.wakeWordEnabled,
                    onChange = { viewModel.setDebounce((it * 1000).roundToInt().toLong()) },
                )
            }

            SectionLabel("Backend")
            GlassCard(Modifier.fillMaxWidth()) {
                BackendFields(
                    initialUrl = settings.backendUrl,
                    initialSecret = settings.sharedSecret,
                    connection = connection,
                    onUrlChange = viewModel::setBackendUrl,
                    onSecretChange = viewModel::setSecret,
                    onTest = viewModel::testConnection,
                )
                Divider()
                SettingSwitch(
                    "Share contact names",
                    "Send your contact names (never numbers) to the backend so the AI can correct misheard names.",
                    settings.shareContactNames,
                    viewModel::setShareContacts,
                )
            }

            SectionLabel("Voice")
            GlassCard(Modifier.fillMaxWidth()) {
                SettingSwitch("Spoken replies", "Jarvis says confirmations like “Calling King”.", settings.voiceReplyEnabled, viewModel::setVoiceReply)
                Divider()
                Text("Language", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("en-US" to "English", "en-IN" to "English (India)", "hi-IN" to "हिन्दी").forEach { (tag, label) ->
                        FilterChip(
                            selected = settings.languageTag == tag,
                            onClick = { viewModel.setLanguage(tag) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = JarvisColors.Violet.copy(alpha = 0.4f)),
                        )
                    }
                }
                Text(
                    "Sets the speech recognition and voice language. The wake word itself stays “Jarvis”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisColors.TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            SectionLabel("Safety")
            GlassCard(Modifier.fillMaxWidth()) {
                SettingSwitch(
                    "Cancel countdown",
                    "Show a countdown with a Cancel button before calls and messages.",
                    settings.confirmationCountdownEnabled,
                    viewModel::setCountdown,
                )
                SettingSlider(
                    title = "Countdown length",
                    valueLabel = "${settings.confirmationSeconds} s",
                    value = settings.confirmationSeconds.toFloat(),
                    range = 1f..10f,
                    steps = 8,
                    enabled = settings.confirmationCountdownEnabled,
                    onChange = { viewModel.setCountdownSeconds(it.roundToInt()) },
                )
            }

            SectionLabel("Permissions & background")
            GlassCard(Modifier.fillMaxWidth()) {
                StatusRow("WhatsApp auto-send (Accessibility)", accessibilityOn, "Open") {
                    SystemSettings.openAccessibilitySettings(context)
                }
                Divider()
                StatusRow("Battery optimization exemption", batteryExempt, "Allow") {
                    SystemSettings.requestIgnoreBatteryOptimizations(context)
                }
                Divider()
                StatusRow("Show over other apps (lets Jarvis pop up)", overlayAllowed, "Allow") {
                    SystemSettings.openOverlaySettings(context)
                }
                Divider()
                SettingSwitch(
                    "Start on boot",
                    "On Android 14+ you'll get a notification to tap instead (system rule for microphone services).",
                    settings.startOnBoot,
                    viewModel::setStartOnBoot,
                )
                Divider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { SystemSettings.openAppSettings(context) }) { Text("App permissions") }
                    OutlinedButton(onClick = onReplayOnboarding) { Text("Setup guide") }
                }
            }

            SectionLabel("If Jarvis stops listening")
            GlassCard(Modifier.fillMaxWidth()) {
                Text(SystemSettings.OEM_GUIDANCE, style = MaterialTheme.typography.bodySmall, color = JarvisColors.TextSecondary)
                TextButton(onClick = { SystemSettings.openUrl(context, SystemSettings.DONT_KILL_MY_APP) }) {
                    Text("More on dontkillmyapp.com")
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Jarvis ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = JarvisColors.TextMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusRow(title: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            contentDescription = if (ok) "Enabled" else "Not enabled",
            tint = if (ok) JarvisColors.Success else JarvisColors.Warning,
        )
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = onClick) { Text(action) }
    }
}
