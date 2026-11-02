package com.jarvis.assistant.di

import android.content.Context
import androidx.room.Room
import com.jarvis.assistant.data.contacts.AndroidContactsSource
import com.jarvis.assistant.data.history.ActionLogDao
import com.jarvis.assistant.data.history.HistoryRepository
import com.jarvis.assistant.data.history.JarvisDatabase
import com.jarvis.assistant.data.remote.BackendClient
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.domain.AssistantOrchestrator
import com.jarvis.assistant.domain.wakeword.WakeWordEngine
import com.jarvis.assistant.platform.AndroidActionExecutor
import com.jarvis.assistant.voice.AndroidSpeechInput
import com.jarvis.assistant.voice.AndroidSpeechOutput
import com.jarvis.assistant.voice.ToneChime
import com.jarvis.assistant.voice.VoskWakeWordEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): JarvisDatabase =
        Room.databaseBuilder(context, JarvisDatabase::class.java, "jarvis.db").build()

    @Provides
    fun provideActionLogDao(db: JarvisDatabase): ActionLogDao = db.actionLogDao()

    @Provides
    @Singleton
    fun provideBackendClient(settings: SettingsRepository, http: OkHttpClient): BackendClient =
        BackendClient(settings, http)

    @Provides
    @Singleton
    fun provideOrchestrator(
        settings: SettingsRepository,
        backend: BackendClient,
        contacts: AndroidContactsSource,
        speechInput: AndroidSpeechInput,
        speechOutput: AndroidSpeechOutput,
        chime: ToneChime,
        wakeWord: WakeWordEngine,
        executor: AndroidActionExecutor,
        history: HistoryRepository,
        @ApplicationScope scope: CoroutineScope,
    ): AssistantOrchestrator = AssistantOrchestrator(
        settingsSource = settings,
        interpreter = backend,
        contacts = contacts,
        speechInput = speechInput,
        speechOutput = speechOutput,
        chime = chime,
        wakeWord = wakeWord,
        executor = executor,
        actionLog = history,
        scope = scope,
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    /** Swap this binding to use a different wake word engine. */
    @Binds
    abstract fun bindWakeWordEngine(impl: VoskWakeWordEngine): WakeWordEngine
}
