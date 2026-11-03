package com.jarvis.assistant.data.remote

import com.jarvis.assistant.domain.CommandInterpreter
import com.jarvis.assistant.domain.InterpretResult
import com.jarvis.assistant.domain.SettingsSource
import com.jarvis.assistant.domain.messages.IncomingMessage
import com.jarvis.assistant.domain.model.CommandParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
private data class InterpretRequestBody(
    val text: String,
    val contacts: List<String>? = null,
    val now: String,
    val locale: String,
)

@Serializable
private data class ScreenRequestBody(
    val question: String,
    val image: String,
    val mimeType: String,
    val locale: String,
)

@Serializable
private data class SummaryMessage(
    val app: String,
    val chat: String,
    val sender: String,
    val text: String,
    val time: String,
)

@Serializable
private data class SummaryRequestBody(
    val question: String,
    val messages: List<SummaryMessage>,
    val now: String,
    val locale: String,
)

/** Every endpoint answers with a subset of these fields. */
@Serializable
private data class BackendResponseBody(
    val tool: String? = null,
    val args: JsonObject? = null,
    val text: String? = null,
    val error: String? = null,
    val message: String? = null,
)

sealed interface HealthResult {
    data class Ok(val provider: String, val model: String, val configured: Boolean) : HealthResult
    data class Failed(val message: String) : HealthResult
}

/** A spoken answer from the backend, or a failure that is safe to read aloud. */
sealed interface TextResult {
    data class Ok(val text: String) : TextResult
    data class Failed(val spokenMessage: String) : TextResult
}

/** Talks to the Jarvis backend. The LLM is only ever reached through here. */
class BackendClient(
    private val settings: SettingsSource,
    private val http: OkHttpClient,
    private val now: () -> Date = ::Date,
) : CommandInterpreter {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Image and summary requests make the model read much more, so they get longer timeouts. */
    private val slowHttp: OkHttpClient by lazy {
        http.newBuilder()
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun interpret(text: String, languageTag: String, contactNames: List<String>?): InterpretResult {
        val body = InterpretRequestBody(
            text = text.take(500),
            contacts = contactNames?.take(2000)?.map { it.take(100) },
            now = timestamp(),
            locale = languageTag,
        )
        return post(
            path = "/interpret",
            payload = json.encodeToString(InterpretRequestBody.serializer(), body),
            client = http,
            failure = InterpretResult::Failure,
        ) { response ->
            response.tool?.let { InterpretResult.Success(CommandParser.parse(it, response.args ?: JsonObject(emptyMap()))) }
        }
    }

    /** Asks about a screenshot ([jpegBase64]); the image is sent once and never stored. */
    suspend fun askAboutScreen(question: String, jpegBase64: String): TextResult {
        val body = ScreenRequestBody(question.take(300), jpegBase64, "image/jpeg", settings.current().languageTag)
        return postForText("/screen", body, ScreenRequestBody.serializer())
    }

    /** Sends the given notifications for a spoken summary. Only called when the user asks for one. */
    suspend fun summarize(question: String, messages: List<IncomingMessage>): TextResult {
        val time = SimpleDateFormat("HH:mm", Locale.US)
        val body = SummaryRequestBody(
            question = question.take(300),
            messages = messages.take(MAX_SUMMARY_MESSAGES).map { m ->
                SummaryMessage(
                    app = m.appLabel.take(50),
                    chat = m.conversation.take(100),
                    sender = m.sender.take(100),
                    text = (m.transcript.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: m.text).take(MAX_SUMMARY_CHARS),
                    time = time.format(Date(m.postedAt)),
                )
            },
            now = timestamp(),
            locale = settings.current().languageTag,
        )
        return postForText("/summarize", body, SummaryRequestBody.serializer())
    }

    private suspend fun <T> postForText(path: String, body: T, serializer: KSerializer<T>): TextResult =
        post(path, json.encodeToString(serializer, body), slowHttp, TextResult::Failed) { response ->
            response.text?.takeIf { it.isNotBlank() }?.let(TextResult::Ok)
        }

    /**
     * Posts [payload] with the shared secret. [success] maps a 2xx body to a result (null = malformed);
     * every failure becomes a spoken message passed to [failure].
     */
    private suspend fun <R> post(
        path: String,
        payload: String,
        client: OkHttpClient,
        failure: (String) -> R,
        success: (BackendResponseBody) -> R?,
    ): R {
        val s = settings.current()
        val request = runCatching {
            Request.Builder()
                .url(s.backendUrl.trimEnd('/') + path)
                .header("X-Jarvis-Secret", s.sharedSecret)
                .post(payload.toRequestBody(JSON))
                .build()
        }.getOrElse {
            return failure("The backend URL in settings looks invalid.")
        }

        return try {
            client.newCall(request).await().use { response ->
                val raw = withContext(Dispatchers.IO) { response.body.string() }
                val parsed = runCatching { json.decodeFromString(BackendResponseBody.serializer(), raw) }.getOrNull()
                val result = if (response.isSuccessful && parsed != null) success(parsed) else null
                when {
                    result != null -> result
                    // An older backend that lacks this endpoint.
                    response.code == 404 -> failure("The backend is out of date. Please restart it with the latest code.")
                    parsed?.message != null -> failure(parsed.message)
                    response.code == 401 -> failure("The backend rejected my shared secret. Please check it in settings.")
                    else -> failure("The backend returned an error, code ${response.code}.")
                }
            }
        } catch (_: SocketTimeoutException) {
            failure("The backend took too long to answer. Please try again.")
        } catch (_: IOException) {
            failure("I can't reach the backend. Check that it's running and your phone is on the same Wi-Fi.")
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm EEE", Locale.US).format(now())

    suspend fun health(baseUrl: String): HealthResult {
        val request = runCatching { Request.Builder().url(baseUrl.trimEnd('/') + "/health").get().build() }
            .getOrElse { return HealthResult.Failed("Invalid URL") }
        return try {
            http.newCall(request).await().use { response ->
                val raw = withContext(Dispatchers.IO) { response.body.string() }
                if (!response.isSuccessful) return HealthResult.Failed("HTTP ${response.code}")
                val obj = json.parseToJsonElement(raw) as JsonObject
                fun field(k: String) = obj[k]?.toString()?.trim('"').orEmpty()
                HealthResult.Ok(field("provider"), field("model"), field("configured") == "true")
            }
        } catch (e: IOException) {
            HealthResult.Failed(e.message ?: "Network error")
        } catch (_: RuntimeException) {
            HealthResult.Failed("Unexpected response")
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_SUMMARY_MESSAGES = 40
        private const val MAX_SUMMARY_CHARS = 2000
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
