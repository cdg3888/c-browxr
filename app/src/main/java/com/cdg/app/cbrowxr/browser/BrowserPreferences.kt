package com.cdg.app.cbrowxr.browser

import android.content.Context

enum class SearchEngine(val label: String, val baseUrl: String) {
    DUCK_DUCK_GO("DuckDuckGo", "https://duckduckgo.com"),
    GOOGLE("Google", "https://www.google.com"),
    BING("Bing", "https://www.bing.com"),
    BRAVE("Brave", "https://search.brave.com");

    fun searchUrl(query: String): String =
        when (this) {
            DUCK_DUCK_GO -> "$baseUrl/?q=${android.net.Uri.encode(query)}"
            GOOGLE -> "$baseUrl/search?q=${android.net.Uri.encode(query)}"
            BING -> "$baseUrl/search?q=${android.net.Uri.encode(query)}"
            BRAVE -> "$baseUrl/search?q=${android.net.Uri.encode(query)}"
        }
}

enum class CookiePolicy(val label: String) {
    ALLOW("Permitir"),
    FIRST_PARTY("Solo sitios principales"),
    BLOCK_ALL("Bloquear");
}

enum class DownloadEngine(val label: String) {
    YTDLP("yt-dlp"),
    IDM("IDM multihilo");
}

enum class ThemeMode(val label: String) {
    DARK("Oscuro"),
    AMOLED("Negro AMOLED");
}

enum class AppAccent(val label: String, val primaryHex: Long, val secondaryHex: Long) {
    BLUE("Azul", 0xFF78A9FF, 0xFF78E7C7),
    PURPLE("Purpura", 0xFFC6A0FF, 0xFFFF9FCB),
    GREEN("Verde", 0xFF78E7A1, 0xFF78A9FF),
    ORANGE("Naranja", 0xFFFFB86B, 0xFFFFD580),
    RED("Rojo", 0xFFFF7A7A, 0xFFFFB86B);
}

data class BrowserSettings(
    val searchEngine: SearchEngine = SearchEngine.DUCK_DUCK_GO,
    val adBlockEnabled: Boolean = true,
    val trackingEnabled: Boolean = true,
    val javaScriptEnabled: Boolean = true,
    val blockPopups: Boolean = true,
    val cookiePolicy: CookiePolicy = CookiePolicy.FIRST_PARTY,
    val privateMode: Boolean = false,
    val downloadEngine: DownloadEngine = DownloadEngine.YTDLP,
    val idmThreads: Int = 8,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accent: AppAccent = AppAccent.BLUE
)

object BrowserPreferences {
    private const val NAME = "cbrowxr_browser_settings"

    fun load(context: Context): BrowserSettings {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return BrowserSettings(
            searchEngine = prefs.enum("search_engine", SearchEngine.DUCK_DUCK_GO),
            adBlockEnabled = prefs.getBoolean("ad_block", true),
            trackingEnabled = prefs.getBoolean("tracking", true),
            javaScriptEnabled = prefs.getBoolean("javascript", true),
            blockPopups = prefs.getBoolean("block_popups", true),
            cookiePolicy = prefs.enum("cookie_policy", CookiePolicy.FIRST_PARTY),
            privateMode = prefs.getBoolean("private_mode", false),
            downloadEngine = prefs.enum("download_engine", DownloadEngine.YTDLP),
            idmThreads = prefs.getInt("idm_threads", 8).coerceIn(2, 16),
            themeMode = prefs.enum("theme_mode", ThemeMode.DARK),
            accent = prefs.enum("accent", AppAccent.BLUE)
        )
    }

    fun save(context: Context, settings: BrowserSettings) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("search_engine", settings.searchEngine.name)
            .putBoolean("ad_block", settings.adBlockEnabled)
            .putBoolean("tracking", settings.trackingEnabled)
            .putBoolean("javascript", settings.javaScriptEnabled)
            .putBoolean("block_popups", settings.blockPopups)
            .putString("cookie_policy", settings.cookiePolicy.name)
            .putBoolean("private_mode", settings.privateMode)
            .putString("download_engine", settings.downloadEngine.name)
            .putInt("idm_threads", settings.idmThreads.coerceIn(2, 16))
            .putString("theme_mode", settings.themeMode.name)
            .putString("accent", settings.accent.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> android.content.SharedPreferences.enum(
        key: String,
        default: T
    ): T {
        val value = getString(key, default.name)
        return try {
            enumValueOf<T>(value ?: default.name)
        } catch (_: IllegalArgumentException) {
            default
        }
    }
}
