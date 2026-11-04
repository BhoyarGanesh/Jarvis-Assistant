package com.jarvis.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.domain.live.LiveEvent
import com.jarvis.assistant.domain.live.LiveSession
import com.jarvis.assistant.domain.live.LiveStart
import com.jarvis.assistant.domain.live.LiveStartRequest
import com.jarvis.assistant.domain.live.LiveVoice
import com.jarvis.assistant.domain.model.CommandParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class LiveStartBody(
    val type: String,
    val voice: String,
    val now: String,
    val locale: String,
    val contacts: List<String>? = null,
)

private val startJson = Json { explicitNulls = false }

/** The first message on /live. Every field is written out explicitly: the backend rejects a start without "type". */
internal fun liveStartMessage(voice: String, now: String, locale: String, contacts: List<String>?): String =
    startJson.encodeToString(
        LiveStartBody.serializer(),
        LiveStartBody(type = "start", voice = voice, now = now, locale = locale, contacts = contacts?.take(2000)?.map { it.take(100) }),
    )

/**
 * Live mode over the backend's `/live` WebSocket (which holds the Gemini key): streams the microphone
 * with echo cancellation and plays her voice as it arrives, cutting it off the moment Gemini reports
 * the user talking over her. Audio and transcripts are never logged or stored.
 */
@Singleton
class GeminiLiveVoice @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val http: OkHttpClient,
    private val mouth: MouthLevel,
) : LiveVoice {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val liveHttp: OkHttpClient by lazy {
        http.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun start(request: LiveStartRequest): LiveStart {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // The normal pipeline reports the missing permission out loud.
            return LiveStart.Unavailable("no microphone permission")
        }
        val s = settings.current()
        val url = s.backendUrl.trimEnd('/').replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws") + "/live"
        val httpRequest = runCatching {
            Request.Builder().url(url).header("X-Jarvis-Secret", s.sharedSecret).build()
        }.getOrElse { return LiveStart.Unavailable("invalid backend URL") }

        val session = Session(request)
        val start = liveStartMessage(
            voice = request.voice,
            now = SimpleDateFormat("yyyy-MM-dd HH:mm EEE", Locale.US).format(Date()),
            locale = request.languageTag,
            contacts = request.contactNames,
        )
        session.socket = liveHttp.newWebSocket(httpRequest, session.Listener(start))
        val ready = withTimeoutOrNull(READY_TIMEOUT_MS) { session.ready.await() }
        if (ready !is Ready.Ok) Log.w(TAG, "Live unavailable: ${(ready as? Ready.Failed)?.reason ?: "no answer in ${READY_TIMEOUT_MS} ms"}")
        return when {
            ready == null -> {
                session.close()
                LiveStart.Unavailable("Live didn't answer in time")
            }
            ready is Ready.Failed -> {
                session.close()
                LiveStart.Unavailable(ready.reason, ready.quota)
            }
            else -> {
                if (!session.startAudio()) {
                    session.close()
                    LiveStart.Unavailable("couldn't open the microphone or speaker")
                } else {
                    LiveStart.Started(session)
                }
            }
        }
    }

    private sealed interface Ready {
        data object Ok : Ready
        data class Failed(val reason: String, val quota: Boolean) : Ready
    }

    private inner class Session(private val request: LiveStartRequest) : LiveSession {
        override val events = Channel<LiveEvent>(Channel.UNLIMITED)
        val ready = CompletableDeferred<Ready>()
        @Volatile var socket: WebSocket? = null
        private val closed = AtomicBoolean(false)
        private var mic: LiveMicrophone? = null
        private var player: LivePlayer? = null
        private var route: CommunicationRoute? = null

        fun startAudio(): Boolean {
            route = CommunicationRoute(context).also { it.enter() }
            val out = LivePlayer(OUTPUT_RATE, mouth) { speaking -> events.trySend(LiveEvent.Speaking(speaking)) }
            val input = LiveMicrophone(INPUT_RATE, onLevel = request.onMicLevel) { chunk -> socket?.send(chunk.toByteString()) }
            if (!out.start() || !input.start()) {
                out.release()
                input.release()
                return false
            }
            player = out
            mic = input
            return true
        }

        override fun sendToolResult(id: String, name: String, result: String) {
            send(buildJson("toolResult", "id" to id, "name" to name, "result" to result.take(4000)))
        }

        override fun sendText(text: String) {
            send(buildJson("text", "text" to text.take(500)))
        }

        private fun send(text: String) {
            if (!closed.get()) socket?.send(text)
        }

        private fun buildJson(type: String, vararg fields: Pair<String, String>): String =
            JsonObject(mapOf("type" to JsonPrimitive(type)) + fields.associate { (k, v) -> k to JsonPrimitive(v) }).toString()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            Log.i(TAG, "live session closed", if (BuildConfig.DEBUG) Throwable("closed by") else null)
            mic?.release()
            player?.release()
            route?.exit()
            mic = null
            player = null
            route = null
            mouth.silent()
            socket?.close(1000, null)
            ready.complete(Ready.Failed("closed", false))
            events.trySend(LiveEvent.Ended())
            events.close()
        }

        /** Ends with a failure the user should hear about. */
        private fun end(message: String?, quota: Boolean) {
            if (closed.get()) return
            events.trySend(LiveEvent.Ended(message, quota))
            close()
        }

        inner class Listener(private val startMessage: String) : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(startMessage)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                player?.write(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                fun field(name: String) = runCatching { msg[name]?.jsonPrimitive?.content }.getOrNull()
                // Event types and lengths only: what was said is never logged.
                Log.d(TAG, "event ${field("type")}${field("text")?.let { " (${it.length} chars)" }.orEmpty()}")
                when (field("type")) {
                    "ready" -> ready.complete(Ready.Ok)
                    "heard" -> field("text")?.let { events.trySend(LiveEvent.Heard(it)) }
                    "said" -> field("text")?.let { events.trySend(LiveEvent.Said(it)) }
                    "interrupted" -> {
                        // Cut her off right now, before anything else is processed.
                        player?.interrupt()
                        events.trySend(LiveEvent.Interrupted)
                    }
                    "turnComplete" -> events.trySend(LiveEvent.TurnComplete)
                    "toolCall" -> {
                        val id = field("id") ?: return
                        val tool = field("tool") ?: return
                        val args = runCatching { msg["args"]?.jsonObject }.getOrNull() ?: JsonObject(emptyMap())
                        events.trySend(LiveEvent.ToolCall(id, tool, CommandParser.parse(tool, args)))
                    }
                    "toolCancelled" -> {
                        val ids = runCatching { msg["ids"]!!.jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList())
                        events.trySend(LiveEvent.ToolCancelled(ids))
                    }
                    "goAway" -> Log.i(TAG, "Live session ending soon (goAway)")
                    "error" -> {
                        val quota = field("error") == "rate_limited"
                        val message = field("message")
                        if (!ready.isCompleted) {
                            ready.complete(Ready.Failed("${field("error") ?: "error"}: ${message.orEmpty()}", quota))
                        } else {
                            end(message, quota)
                        }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "backend closed the live session ($code)")
                ready.complete(Ready.Failed("closed $code", false))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code
                Log.w(TAG, "Live connection failed: ${status ?: t.javaClass.simpleName}")
                if (!ready.isCompleted) {
                    ready.complete(Ready.Failed(if (status != null) "HTTP $status" else (t.message ?: "network error"), status == 429))
                } else {
                    end("The live connection dropped. Say Jarvis to start again.", quota = false)
                }
            }
        }
    }

    private companion object {
        const val TAG = "JarvisLive"
        const val INPUT_RATE = 16_000
        const val OUTPUT_RATE = 24_000
        const val READY_TIMEOUT_MS = 9_000L
    }
}
