package com.jarvis.assistant.data.remote

import com.jarvis.assistant.domain.CommandInterpreter
import com.jarvis.assistant.domain.InterpretResult
import com.jarvis.assistant.domain.SettingsSource
import com.jarvis.assistant.domain.model.CommandParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
private data class InterpretResponseBody(
    val tool: String? = null,
    val args: JsonObject? = null,
    val error: String? = null,
    val message: String? = null,
)

sealed interface HealthResult {
    data class Ok(val provider: String, val model: String, val configured: Boolean) : HealthResult
    data class Failed(val message: String) : HealthResult
}

/** Talks to the Jarvis backend. The LLM is only ever reached through here. */
class BackendClient(
    private val settings: SettingsSource,
    private val http: OkHttpClient,
    private val now: () -> Date = ::Date,
) : CommandInterpreter {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun interpret(text: String, languageTag: String, contactNames: List<String>?): InterpretResult {
        val s = settings.current()
        val url = s.backendUrl.trimEnd('/') + "/interpret"
        val body = InterpretRequestBody(
            text = text.take(500),
            contacts = contactNames?.take(2000)?.map { it.take(100) },
            now = SimpleDateFormat("yyyy-MM-dd HH:mm EEE", Locale.US).format(now()),
            locale = languageTag,
        )
        val request = runCatching {
            Request.Builder()
                .url(url)
                .header("X-Jarvis-Secret", s.sharedSecret)
                .post(json.encodeToString(InterpretRequestBody.serializer(), body).toRequestBody(JSON))
                .build()
        }.getOrElse {
            return InterpretResult.Failure("The backend URL in settings looks invalid.")
        }

        return try {
            http.newCall(request).await().use { response ->
                val raw = withContext(Dispatchers.IO) { response.body.string() }
                val parsed = runCatching { json.decodeFromString(InterpretResponseBody.serializer(), raw) }.getOrNull()
                when {
                    response.isSuccessful && parsed?.tool != null ->
                        InterpretResult.Success(CommandParser.parse(parsed.tool, parsed.args ?: JsonObject(emptyMap())))
                    parsed?.message != null -> InterpretResult.Failure(parsed.message)
                    response.code == 401 -> InterpretResult.Failure("The backend rejected my shared secret. Please check it in settings.")
                    else -> InterpretResult.Failure("The backend returned an error, code ${response.code}.")
                }
            }
        } catch (_: SocketTimeoutException) {
            InterpretResult.Failure("The backend took too long to answer. Please try again.")
        } catch (_: IOException) {
            InterpretResult.Failure("I can't reach the backend. Check that it's running and your phone is on the same Wi-Fi.")
        }
    }

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
