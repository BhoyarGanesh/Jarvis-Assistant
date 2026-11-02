package com.jarvis.assistant.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.domain.AssistantPhase
import com.jarvis.assistant.platform.WhatsAppSendCoordinator
import com.jarvis.assistant.ui.SystemSettings
import com.jarvis.assistant.ui.components.AuroraBackground
import com.jarvis.assistant.ui.components.GlassCard
import com.jarvis.assistant.ui.components.IconBubble
import com.jarvis.assistant.ui.components.JarvisOrb
import com.jarvis.assistant.ui.settings.BackendFields
import com.jarvis.assistant.ui.settings.SettingsViewModel
import com.jarvis.assistant.ui.theme.JarvisColors

private enum class Step { WELCOME, MIC, CONTACTS, NOTIFICATIONS, WHATSAPP, MESSAGES, BATTERY, BACKEND }

@Composable
fun OnboardingScreen(
    viewModel: SettingsViewModel,
    whatsApp: WhatsAppSendCoordinator,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val settings = viewModel.settings.collectAsStateWithLifecycle().value ?: return
    val connection by viewModel.connection.collectAsStateWithLifecycle()

    val steps = remember {
        Step.entries.filter { it != Step.NOTIFICATIONS || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU }
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = steps[index]

    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val refresh = { tick++ }

    val micGranted = remember(tick) { SystemSettings.micGranted(context) }
    val contactsGranted = remember(tick) { SystemSettings.contactPermissions.all { SystemSettings.granted(context, it) } }
    val notificationsGranted = remember(tick) { SystemSettings.notificationsGranted(context) }
    val accessibilityOn = remember(tick) { whatsApp.isServiceEnabled() }
    val batteryExempt = remember(tick) { SystemSettings.ignoringBatteryOptimizations(context) }
    val messagesOn = remember(tick) { SystemSettings.notificationAccessGranted(context) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val multiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    val next: () -> Unit = {
        if (index < steps.lastIndex) {
            index++
        } else {
            viewModel.completeOnboarding()
            onFinished()
        }
    }

    AuroraBackground {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
        ) {
            ProgressDots(count = steps.size, current = index)
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
                },
                modifier = Modifier.weight(1f),
                label = "onboarding-step",
            ) { current ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (current) {
                        Step.WELCOME -> Welcome()
                        Step.MIC -> PermissionStep(
                            Icons.Rounded.Mic, "Let Jarvis hear you",
                            "The microphone powers the offline wake word and your spoken commands. Audio for the wake word never leaves your phone.",
                            granted = micGranted,
                            actionLabel = "Allow microphone",
                        ) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                        Step.CONTACTS -> PermissionStep(
                            Icons.Rounded.Contacts, "Calls & messages",
                            "Contacts let Jarvis find “King” or “Junior”. Phone and SMS let it call and text for you. Each is optional; Jarvis will tell you if something is missing.",
                            granted = contactsGranted,
                            actionLabel = "Allow contacts, calls & SMS",
                        ) { multiLauncher.launch(SystemSettings.contactPermissions) }
                        Step.NOTIFICATIONS -> PermissionStep(
                            Icons.Rounded.Notifications, "Stay on, quietly",
                            "Android requires a small persistent notification while Jarvis listens for its wake word.",
                            granted = notificationsGranted,
                            actionLabel = "Allow notifications",
                        ) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                multiLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                            }
                        }
                        Step.WHATSAPP -> PermissionStep(
                            Icons.AutoMirrored.Rounded.Chat, "Hands-free WhatsApp",
                            "Jarvis opens the chat with your message typed in, then taps Send for you. To allow that:\n\n" +
                                "1. Tap the button below\n2. Find “Jarvis WhatsApp auto-send” (maybe under Installed apps / Downloaded services)\n" +
                                "3. Turn it on and confirm\n\nOn Android 13+, if it's greyed out: App info > ⋮ menu > Allow restricted settings, then try again.\n\n" +
                                "Jarvis only acts inside WhatsApp, right after you ask it to send something.",
                            granted = accessibilityOn,
                            actionLabel = "Open Accessibility settings",
                        ) { SystemSettings.openAccessibilitySettings(context) }
                        Step.MESSAGES -> PermissionStep(
                            Icons.Rounded.Forum, "Read & reply to messages",
                            "Say “Jarvis, read my messages” or “reply I'm on my way”. Jarvis reads new notifications aloud and replies through the notification's own Reply button, so it works with WhatsApp, SMS, Telegram and more.\n\n" +
                                "Turn on “Jarvis message reader” on the next screen. Messages stay on your phone; only what you say is sent to the backend.",
                            granted = messagesOn,
                            actionLabel = "Allow notification access",
                        ) { SystemSettings.openNotificationAccessSettings(context) }
                        Step.BATTERY -> PermissionStep(
                            Icons.Rounded.BatteryChargingFull, "Keep listening",
                            "Exempt Jarvis from battery optimization so Android doesn't stop the wake word in the background.\n\n" +
                                "Xiaomi, Samsung, OnePlus, Oppo, Vivo and Huawei phones also need Autostart / background permission. See Settings > “If Jarvis stops listening”.",
                            granted = batteryExempt,
                            actionLabel = "Allow background use",
                        ) { SystemSettings.requestIgnoreBatteryOptimizations(context) }
                        Step.BACKEND -> {
                            StepHeader(Icons.Rounded.Dns, "Connect the brain")
                            Text(
                                "Run the backend on your PC (see README), then enter its LAN address, e.g. http://192.168.1.20:8787, and the APP_SHARED_SECRET from backend/.env.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = JarvisColors.TextSecondary,
                            )
                            Spacer(Modifier.height(16.dp))
                            GlassCard(Modifier.fillMaxWidth()) {
                                BackendFields(
                                    initialUrl = settings.backendUrl,
                                    initialSecret = settings.sharedSecret,
                                    connection = connection,
                                    onUrlChange = viewModel::setBackendUrl,
                                    onSecretChange = viewModel::setSecret,
                                    onTest = viewModel::testConnection,
                                )
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) TextButton(onClick = { index-- }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = next,
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.Violet),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.height(52.dp),
                ) {
                    Text(
                        when {
                            index == steps.lastIndex -> "Start using Jarvis"
                            step == Step.WELCOME -> "Get started"
                            else -> "Continue"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun Welcome() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        JarvisOrb(AssistantPhase.SPEAKING, 0f, Modifier.size(240.dp))
        Spacer(Modifier.height(16.dp))
        Text("Meet Jarvis", style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Say “Jarvis”, wait for the chime, then “call King” or “message Junior on WhatsApp hi”. Let's set up a few permissions.",
            style = MaterialTheme.typography.bodyLarge,
            color = JarvisColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StepHeader(icon: ImageVector, title: String) {
    IconBubble(icon, JarvisColors.Violet, size = 64)
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun PermissionStep(
    icon: ImageVector,
    title: String,
    body: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    StepHeader(icon, title)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = JarvisColors.TextSecondary)
    Spacer(Modifier.height(24.dp))
    GlassCard(Modifier.fillMaxWidth().animateContentSize(), tint = if (granted) JarvisColors.Success else JarvisColors.Violet) {
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = JarvisColors.Success)
                Spacer(Modifier.width(10.dp))
                Text("All set", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.Violet),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(actionLabel) }
            Text(
                "You can skip this and enable it later in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = JarvisColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ProgressDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 28.dp else 8.dp, spring(), label = "dot")
            Box(
                Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(if (i <= current) JarvisColors.Violet else JarvisColors.GlassBorder),
            )
        }
    }
}
