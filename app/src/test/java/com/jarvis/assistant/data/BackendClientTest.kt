package com.jarvis.assistant.data

import com.jarvis.assistant.data.remote.BackendClient
import com.jarvis.assistant.data.remote.HealthResult
import com.jarvis.assistant.data.remote.SpeechAudio
import com.jarvis.assistant.data.remote.TextResult
import com.jarvis.assistant.domain.AppSettings
import com.jarvis.assistant.domain.InterpretResult
import com.jarvis.assistant.domain.SettingsSource
import com.jarvis.assistant.domain.messages.IncomingMessage
import com.jarvis.assistant.domain.model.AssistantCommand
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

class BackendClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: BackendClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val settings = AppSettings(backendUrl = server.url("/").toString(), sharedSecret = "top-secret")
        client = BackendClient(
            settings = object : SettingsSource {
                override val settings = flowOf(settings)
                override suspend fun current() = settings
            },
            http = OkHttpClient(),
            now = { Date(0) },
        )
    }

    @After fun tearDown() {
        server.close()
    }

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    @Test fun successParsesToolCallAndSendsSecret() = runTest {
        enqueue(200, """{"tool":"send_whatsapp","args":{"contact_name":"Junior","message":"hi"},"provider":"gemini","latencyMs":5}""")
        val result = client.interpret("message junior on whatsapp hi", "en-US", listOf("Junior", "King"))

        assertEquals(InterpretResult.Success(AssistantCommand.SendWhatsApp("Junior", "hi")), result)
        val request = server.takeRequest()
        assertEquals("/interpret", request.url.encodedPath)
        assertEquals("top-secret", request.headers["X-Jarvis-Secret"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("message junior on whatsapp hi", body["text"]!!.jsonPrimitive.content)
        assertEquals(2, body["contacts"]!!.jsonArray.size)
        assertEquals("en-US", body["locale"]!!.jsonPrimitive.content)
    }

    @Test fun omitsContactsWhenNotShared() = runTest {
        enqueue(200, """{"tool":"answer","args":{"text":"hi"}}""")
        client.interpret("hello", "en-US", null)
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertTrue("contacts" !in body)
    }

    @Test fun backendErrorMessageIsSpoken() = runTest {
        enqueue(429, """{"error":"rate_limited","message":"I'm getting too many requests right now. Please try again in a minute."}""")
        val result = client.interpret("call king", "en-US", null)
        assertEquals(InterpretResult.Failure("I'm getting too many requests right now. Please try again in a minute."), result)
    }

    @Test fun unauthorizedWithoutBody() = runTest {
        enqueue(401, "")
        val result = client.interpret("call king", "en-US", null) as InterpretResult.Failure
        assertTrue(result.spokenMessage.contains("shared secret"))
    }

    @Test fun unreachableBackend() = runTest {
        server.close()
        val result = client.interpret("call king", "en-US", null) as InterpretResult.Failure
        assertTrue(result.spokenMessage.contains("can't reach"))
    }

    @Test fun healthCheck() = runTest {
        enqueue(200, """{"ok":true,"provider":"gemini","model":"gemini-3.8-flash","configured":true}""")
        assertEquals(HealthResult.Ok("gemini", "gemini-3.8-flash", true), client.health(server.url("/").toString()))
    }

    @Test fun screenQuestionSendsImageAndReturnsText() = runTest {
        enqueue(200, """{"text":"This looks like a scam.","provider":"gemini"}""")
        val result = client.askAboutScreen("is this a scam?", "AAAA")

        assertEquals(TextResult.Ok("This looks like a scam."), result)
        val request = server.takeRequest()
        assertEquals("/screen", request.url.encodedPath)
        assertEquals("top-secret", request.headers["X-Jarvis-Secret"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("AAAA", body["image"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", body["mimeType"]!!.jsonPrimitive.content)
        assertEquals("is this a scam?", body["question"]!!.jsonPrimitive.content)
    }

    @Test fun summarySendsTranscriptLinesAndGroupName() = runTest {
        enqueue(200, """{"text":"Mom wants milk."}""")
        val family = IncomingMessage(
            key = "f", packageName = "com.whatsapp", appLabel = "WhatsApp", sender = "Mom in Family",
            text = "bring milk", postedAt = 0, canReply = true, isConversation = true,
            conversation = "Family", transcript = listOf("Ravi: dinner at 8", "Mom: bring milk"),
        )
        assertEquals(TextResult.Ok("Mom wants milk."), client.summarize("summarise my family group", listOf(family)))

        val request = server.takeRequest()
        assertEquals("/summarize", request.url.encodedPath)
        val message = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["messages"]!!.jsonArray.single().jsonObject
        assertEquals("Family", message["chat"]!!.jsonPrimitive.content)
        assertEquals("Ravi: dinner at 8\nMom: bring milk", message["text"]!!.jsonPrimitive.content)
    }

    @Test fun textEndpointFailuresAreSpoken() = runTest {
        enqueue(429, """{"error":"rate_limited","message":"Try again in a minute."}""")
        assertEquals(TextResult.Failed("Try again in a minute."), client.askAboutScreen("explain this", "AAAA"))
        enqueue(404, """{"error":"not_found","message":"Not found"}""")
        assertTrue((client.askAboutScreen("explain this", "AAAA") as TextResult.Failed).spokenMessage.contains("out of date"))
        enqueue(200, """{"text":"  "}""")
        assertTrue(client.askAboutScreen("explain this", "AAAA") is TextResult.Failed)
    }

    @Test fun speechStreamsAudioWithSampleRate() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("X-Sample-Rate", "24000")
                .body(okio.Buffer().write(byteArrayOf(1, 0, 2, 0))).build(),
        )
        val audio = client.openSpeech("Calling King", "Despina") as SpeechAudio.Stream
        assertEquals(24_000, audio.sampleRate)
        assertEquals(listOf<Byte>(1, 0, 2, 0), audio.body.use { it.bytes().toList() })

        val request = server.takeRequest()
        assertEquals("/speak", request.url.encodedPath)
        assertEquals("top-secret", request.headers["X-Jarvis-Secret"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("Despina", body["voice"]!!.jsonPrimitive.content)
        assertEquals("Calling King", body["text"]!!.jsonPrimitive.content)
    }

    @Test fun speechFailuresMeanUseThePhoneVoice() = runTest {
        enqueue(429, """{"error":"rate_limited"}""")
        assertEquals(SpeechAudio.Unavailable("HTTP 429"), client.openSpeech("hi", "Despina"))
        server.close()
        assertTrue(client.openSpeech("hi", "Despina") is SpeechAudio.Unavailable)
    }
}
