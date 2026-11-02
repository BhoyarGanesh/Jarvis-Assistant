package com.jarvis.assistant.domain.apps

import com.jarvis.assistant.domain.contacts.ContactMatcher

data class InstalledApp(val label: String, val packageName: String)

/** Picks the launcher app whose label best matches a spoken app name. */
object AppMatcher {
    private const val MIN_SCORE = 0.82

    private val ALIASES = mapOf(
        "camera" to listOf("camera"),
        "settings" to listOf("settings"),
        "gallery" to listOf("gallery", "photos"),
        "photos" to listOf("photos", "gallery"),
        "browser" to listOf("chrome", "browser", "internet"),
        "maps" to listOf("maps", "google maps"),
        "insta" to listOf("instagram"),
        "yt" to listOf("youtube"),
        "whats app" to listOf("whatsapp"),
        "play store" to listOf("play store", "google play store"),
    )

    fun bestMatch(spoken: String, apps: List<InstalledApp>): InstalledApp? {
        val query = ContactMatcher.normalize(spoken).removeSuffix(" app")
        if (query.isEmpty()) return null
        val candidates = listOf(query) + ALIASES[query].orEmpty()
        return apps
            .map { app -> app to candidates.maxOf { score(it, ContactMatcher.normalize(app.label)) } }
            .filter { it.second >= MIN_SCORE }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun score(query: String, label: String): Double = when {
        query == label -> 1.0
        query.replace(" ", "") == label.replace(" ", "") -> 0.98
        label.startsWith("$query ") -> 0.9
        query in label.split(' ') -> 0.88
        else -> ContactMatcher.jaroWinkler(query, label) * 0.95
    }
}
