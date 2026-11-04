package com.jarvis.assistant.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStartMessageTest {
    @Test fun startMessageCarriesItsTypeAndFields() {
        val msg = Json.parseToJsonElement(liveStartMessage("Despina", "2026-11-04 13:41 Wed", "en-US", listOf("King"))).jsonObject
        assertEquals("start", msg["type"]!!.jsonPrimitive.content)
        assertEquals("Despina", msg["voice"]!!.jsonPrimitive.content)
        assertEquals("2026-11-04 13:41 Wed", msg["now"]!!.jsonPrimitive.content)
        assertEquals("en-US", msg["locale"]!!.jsonPrimitive.content)
        assertEquals("King", msg["contacts"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun contactsAreLeftOutWhenNotShared() {
        val msg = Json.parseToJsonElement(liveStartMessage("Despina", "now", "en-US", null)).jsonObject
        assertTrue("contacts" !in msg)
        assertEquals("start", msg["type"]!!.jsonPrimitive.content)
    }
}
