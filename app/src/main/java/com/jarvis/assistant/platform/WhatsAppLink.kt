package com.jarvis.assistant.platform

import java.net.URLEncoder

object WhatsAppLink {
    /** wa.me wants the full international number as bare digits, no "+" or leading "00". */
    fun digits(number: String): String = number.filter(Char::isDigit).removePrefix("00")

    fun build(digits: String, message: String): String =
        "https://wa.me/$digits?text=" + URLEncoder.encode(message, Charsets.UTF_8.name()).replace("+", "%20")
}
