package com.stealth.browser.network

import com.stealth.browser.data.prefs.SearchEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Live search suggestions, PRD §5/§15. Real HTTPS fetch against the chosen
 * engine's suggestion endpoint, with tolerant parsing of the two wire shapes
 * engines actually emit:
 *  - Firefox-shape:  ["query", ["s1","s2",…]]
 *  - DDG-shape:      [{"phrase":"s1"},…]
 *
 * Engines without an implemented endpoint return an empty list (honest).
 */
@Singleton
class SuggestionClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.SECONDS)
        .connectTimeout(2, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(engine: SearchEngine, query: String): List<String> = withContext(Dispatchers.IO) {
        val url = when (engine) {
            SearchEngine.DUCKDUCKGO -> "https://duckduckgo.com/ac/?q=" + enc(query) + "&type=list"
            SearchEngine.GOOGLE ->
                "https://suggestqueries.google.com/complete/search?client=firefox&q=" + enc(query)
            // Engines without a public suggestion endpoint — honest, no fake data.
            SearchEngine.STARTPAGE,
            SearchEngine.BRAVE,
            SearchEngine.MOJEEK,
            -> return@withContext emptyList()
        }
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string().orEmpty()
                parseSuggestions(body)
            }
        }.getOrDefault(emptyList())
    }

    /** Tolerant extraction across both wire shapes. */
    internal fun parseSuggestions(raw: String): List<String> {
        val element: JsonElement = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<String>()
        when (element) {
            is JsonArray -> {
                // Firefox shape: [query, [suggestions...]]
                val inner: JsonElement? = element.jsonArray.getOrNull(1)
                when (inner) {
                    is JsonArray -> inner.jsonArray.forEach loop@{ e ->
                        val text = e.toString().trim('"')
                        if (text.isNotBlank()) out += text
                    }
                    // DDG shape: [{"phrase": "..."}] — array of objects at top level.
                    else -> if (element.jsonArray.firstOrNull() is JsonObject) {
                        element.jsonArray.forEach { e ->
                            if (e is JsonObject) {
                                e.jsonObject["phrase"]?.toString()?.trim('"')?.let { phrase ->
                                    if (phrase.isNotBlank() && phrase != "null") out += phrase
                                }
                            }
                        }
                    }
                }
            }
            else -> Unit
        }
        return out.distinct().take(8)
    }

    private fun enc(q: String): String = java.net.URLEncoder.encode(q, "UTF-8")
}
