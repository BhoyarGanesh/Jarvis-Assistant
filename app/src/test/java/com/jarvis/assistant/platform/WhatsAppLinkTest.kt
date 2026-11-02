package com.jarvis.assistant.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsAppLinkTest {
    @Test fun digitsStripFormatting() {
        assertEquals("919876543210", WhatsAppLink.digits("+91 98765-43210"))
        assertEquals("447700900123", WhatsAppLink.digits("0044 7700 900123"))
    }

    @Test fun buildEncodesMessage() {
        assertEquals("https://wa.me/919876543210?text=hi%20there%20%26%20bye", WhatsAppLink.build("919876543210", "hi there & bye"))
        assertEquals("https://wa.me/1?text=%E0%A4%A8%E0%A4%AE%E0%A4%B8%E0%A5%8D%E0%A4%A4%E0%A5%87", WhatsAppLink.build("1", "नमस्ते"))
    }
}
