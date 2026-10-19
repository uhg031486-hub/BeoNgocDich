package vn.huy.truyendich

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ModelOption(val id: String, val label: String)

object TranslationCatalog {
    // Internal distribution only: values are injected during the private build.
    const val EMBEDDED_ROUTER_KEY = "sk-03e57ebc0d35b288-sld2yo-498df0af"
    const val EMBEDDED_SERVER_KEY = "sk-03e57ebc0d35b288-sld2yo-498df0af"
    const val DEFAULT_ENDPOINT = "https://r4pqkqr.abc-tunnel.us/v1"
    const val DEFAULT_TAILSCALE_URL = ""
    const val DEFAULT_FUNNEL_URL = "https://smart-tts.tail4cee0d.ts.net"
    const val DEFAULT_MODEL = "ag/gemini-3.8-flash"

    val models = listOf(
        ModelOption("ag/gemini-3.8-flash", "Gemini Flash 3.8 (minimal)"),
        ModelOption("ag/gemini-3.8-flash-low", "Gemini Flash 3.8 Low"),
        ModelOption("ag/gemini-3.8-flash-medium", "Gemini Flash 3.8 Medium"),
        ModelOption("ag/gemini-3.8-flash-high", "Gemini Flash 3.8 High"),
        ModelOption("Terra-Flash-3.8", "Terra Flash 3.8"),
        ModelOption("kc/inclusionai/ling-3.0-flash-sante:free", "Ling 3.0 Flash Sante (minimal)"),
        ModelOption("kc/inclusionai/ling-3.1-flash", "Ling 3.1 Flash"),
        ModelOption("kc/nvidia/nemotron-3-super-120b-a12b:free", "Nemotron Super 120B (minimal)"),
        ModelOption("cx/gpt-6-luna", "Luna 6 (minimal)"),
        ModelOption("kc/nvidia/nemotron-3.5-lightning:free", "Lightning (tắt suy luận)"),
        ModelOption("ag/claude-sonnet-5-5", "Claude Sonnet 5.5"),
        ModelOption("ag/claude-opus-5-5", "Claude Opus 5.5")
    )

    val genres = listOf(
        "tiên hiệp", "huyền huyễn/fantasy", "võ hiệp", "đô thị/hiện đại", "ngôn tình",
        "cổ trang/lịch sử", "cung đấu", "quân sự", "kinh dị/linh dị", "trinh thám",
        "khoa học viễn tưởng", "game/võng du", "anime/manga", "đồng nhân/fanfic", "đam mỹ/bách hợp"
    )
}

data class TranslationSettings(
    val apiKey: String,
    val endpoint: String,
    val model: String,
    val charsPerRequest: Int,
    val concurrency: Int,
    val genre: String,
    val styleNotes: String,
    val glossary: String,
    val syncEnabled: Boolean,
    val tailscaleUrl: String,
    val funnelUrl: String,
    val serverKey: String,
    val vbookBookId: String,
    val vbookSource: String,
    val chapterStart: Int,
    val chapterEnd: Int
)

class SecurePreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("translator_settings", Context.MODE_PRIVATE)

    fun getString(key: String, fallback: String = ""): String = prefs.getString(key, fallback) ?: fallback

    fun getBoolean(key: String, fallback: Boolean): Boolean = prefs.getBoolean(key, fallback)

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun putSecret(key: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove("secure_$key").apply()
            return
        }
        prefs.edit().putString("secure_$key", encrypt(value)).apply()
    }

    fun getSecret(key: String): String {
        val stored = prefs.getString("secure_$key", null) ?: return ""
        return try {
            decrypt(stored)
        } catch (_: Exception) {
            prefs.edit().remove("secure_$key").apply()
            ""
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2) { "Encrypted preference is malformed" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        )
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "hieu_truyen_translation_secrets_v1"
    }
}

class SettingsRepository(context: Context) {
    private val preferences = SecurePreferences(context)

    fun load(): TranslationSettings = TranslationSettings(
        apiKey = TranslationCatalog.EMBEDDED_ROUTER_KEY,
        endpoint = preferences.getString("endpoint", TranslationCatalog.DEFAULT_ENDPOINT),
        model = preferences.getString("model", TranslationCatalog.DEFAULT_MODEL),
        charsPerRequest = 6_000,
        concurrency = preferences.getString("concurrency", "10").toIntOrNull() ?: 10,
        genre = preferences.getString("genre", TranslationCatalog.genres.first()),
        styleNotes = preferences.getString("style_notes"),
        glossary = preferences.getString("glossary"),
        syncEnabled = preferences.getBoolean("sync_enabled", true),
        tailscaleUrl = preferences.getString("tailscale", TranslationCatalog.DEFAULT_TAILSCALE_URL),
        funnelUrl = preferences.getString("funnel", TranslationCatalog.DEFAULT_FUNNEL_URL),
        serverKey = TranslationCatalog.EMBEDDED_SERVER_KEY,
        vbookBookId = preferences.getString("vbook_book_id"),
        vbookSource = preferences.getString("vbook_source"),
        chapterStart = preferences.getString("chapter_start", "1").toIntOrNull()?.coerceAtLeast(1) ?: 1,
        chapterEnd = preferences.getString("chapter_end", "").toIntOrNull()?.coerceAtLeast(0) ?: 0
    )

    fun save(settings: TranslationSettings) {
        preferences.putString("endpoint", settings.endpoint.trim())
        preferences.putString("model", settings.model)
        preferences.putString("concurrency", settings.concurrency.toString())
        preferences.putString("genre", settings.genre)
        preferences.putString("style_notes", settings.styleNotes)
        preferences.putString("glossary", settings.glossary)
        preferences.putBoolean("sync_enabled", settings.syncEnabled)
        preferences.putString("tailscale", settings.tailscaleUrl.trim())
        preferences.putString("funnel", settings.funnelUrl.trim())
        preferences.putString("vbook_book_id", settings.vbookBookId.trim())
        preferences.putString("vbook_source", settings.vbookSource.trim())
        preferences.putString("chapter_start", settings.chapterStart.toString())
        preferences.putString("chapter_end", settings.chapterEnd.toString())
    }
}
