package com.jarvis.assistant

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.platform.UnlockCoordinator
import com.jarvis.assistant.platform.WhatsAppSendCoordinator
import com.jarvis.assistant.service.JarvisListeningService
import com.jarvis.assistant.ui.SystemSettings
import com.jarvis.assistant.ui.components.AuroraBackground
import com.jarvis.assistant.ui.home.HomeScreen
import com.jarvis.assistant.ui.onboarding.OnboardingScreen
import com.jarvis.assistant.ui.settings.SettingsScreen
import com.jarvis.assistant.ui.theme.JarvisTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var whatsApp: WhatsAppSendCoordinator
    @Inject lateinit var unlock: UnlockCoordinator

    /** Bumped for each "start listening" request from the widget, tile or notification. */
    private var listenRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The UI is always dark, so keep light status/nav bar icons even when the phone is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                unlock.requests.collect { answer ->
                    getSystemService(KeyguardManager::class.java).requestDismissKeyguard(
                        this@MainActivity,
                        object : KeyguardManager.KeyguardDismissCallback() {
                            override fun onDismissSucceeded() {
                                answer.complete(true)
                            }

                            override fun onDismissCancelled() {
                                answer.complete(false)
                            }

                            override fun onDismissError() {
                                answer.complete(false)
                            }
                        },
                    )
                }
            }
        }
        setContent {
            JarvisTheme {
                JarvisRoot(settingsRepository, whatsApp, listenRequests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_LISTEN -> {
                listenRequests++
                intent.action = null
            }
            // Debug builds only: lets `adb shell am start` fill in the backend so secrets needn't be typed.
            ACTION_CONFIGURE -> if (BuildConfig.DEBUG) {
                val url = intent.getStringExtra(EXTRA_BACKEND_URL)
                val secret = intent.getStringExtra(EXTRA_SHARED_SECRET)
                intent.action = null
                lifecycleScope.launch {
                    url?.let { settingsRepository.setBackendUrl(it) }
                    secret?.let { settingsRepository.setSharedSecret(it) }
                    Toast.makeText(this@MainActivity, "Backend settings updated", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        const val ACTION_LISTEN = "com.jarvis.assistant.action.LISTEN"
        private const val ACTION_CONFIGURE = "com.jarvis.assistant.action.DEBUG_CONFIGURE"
        private const val EXTRA_BACKEND_URL = "backend_url"
        private const val EXTRA_SHARED_SECRET = "shared_secret"

        fun listenIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_LISTEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

@Composable
private fun JarvisRoot(
    settingsRepository: SettingsRepository,
    whatsApp: WhatsAppSendCoordinator,
    listenRequests: Int,
) {
    val settings = settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null).value
    if (settings == null) {
        AuroraBackground {}
        return
    }
    val context = LocalContext.current

    // Keep the listening service in sync with the setting while the app is visible
    // (a microphone foreground service may only be started from the foreground).
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    LaunchedEffect(settings.wakeWordEnabled, settings.onboardingComplete, resumes) {
        when {
            !settings.onboardingComplete -> Unit
            settings.wakeWordEnabled && SystemSettings.micGranted(context) -> JarvisListeningService.start(context)
            !settings.wakeWordEnabled -> JarvisListeningService.stop(context)
        }
    }

    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = if (settings.onboardingComplete) "home" else "onboarding",
        enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
    ) {
        composable("onboarding") {
            OnboardingScreen(
                viewModel = hiltViewModel(),
                whatsApp = whatsApp,
                onFinished = {
                    nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                },
            )
        }
        composable("home") {
            HomeScreen(
                viewModel = hiltViewModel(),
                onOpenSettings = { nav.navigate("settings") },
                listenRequests = listenRequests,
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel = hiltViewModel(),
                whatsApp = whatsApp,
                onBack = { nav.popBackStack() },
                onReplayOnboarding = { nav.navigate("onboarding") },
            )
        }
    }
}
