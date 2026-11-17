/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The user supplies an OpenAI-compatible chat-completions endpoint and key. */
data class AiConfig(
    val endpoint: String,
    val model: String,
    val visionModel: String,
    val apiKey: String,
    val speechLanguage: String,
    val cloudSpeech: Boolean = false,
    val speechEndpoint: String = "",
    val speechModel: String = "",
    val speechProvider: AiSpeechProvider = AiSpeechProvider.OPENAI_COMPATIBLE,
    val speechApiKey: String = "",
    val tencentSpeech: TencentSpeechConfig = TencentSpeechConfig(),
    val replySystemPrompt: String = AiReplyPrompt.DEFAULT,
    val showReplyOptions: Boolean = false,
) {
    val hasValidEndpoint: Boolean
        get() = isHttpsEndpoint(endpoint)

    val effectiveSpeechApiKey: String get() = speechApiKey.ifBlank { apiKey }
    val isSpeechReady: Boolean get() = when (speechProvider) {
        AiSpeechProvider.TENCENT_FLASH -> tencentSpeech.isReady
        AiSpeechProvider.OPENAI_COMPATIBLE -> isHttpsEndpoint(speechEndpoint) && speechModel.isNotBlank() && effectiveSpeechApiKey.isNotBlank()
    }

    val isTextReady: Boolean get() = hasValidEndpoint && model.isNotBlank() && apiKey.isNotBlank()
    val isVisionReady: Boolean get() = hasValidEndpoint && visionModel.isNotBlank() && apiKey.isNotBlank()
    val isReady: Boolean get() = isTextReady && isVisionReady

    companion object {
        fun isHttpsEndpoint(value: String): Boolean = runCatching {
            val uri = URI(value.trim())
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
        }.getOrDefault(false)
    }
}

object AiConfigStore {
    private const val NAME = "ai_assistant_config"
    const val DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions"
    const val DEFAULT_MODEL = "deepseek-flash"

    @Synchronized
    fun load(context: Context): AiConfig {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        applyBundledDefault(context, prefs)
        applySpeechDefault(prefs)
        return AiConfig(
            endpoint = prefs.getString("endpoint", DEFAULT_ENDPOINT).orEmpty(),
            model = prefs.getString("model", DEFAULT_MODEL).orEmpty(),
            visionModel = prefs.getString("vision_model", DEFAULT_MODEL).orEmpty(),
            apiKey = runCatching { decrypt(prefs.getString("api_key_encrypted", "").orEmpty()) }.getOrDefault(""),
            speechLanguage = prefs.getString("speech_language", "zh-CN").orEmpty(),
            cloudSpeech = prefs.getBoolean("cloud_speech", false),
            speechEndpoint = prefs.getString("speech_endpoint", "").orEmpty(),
            speechModel = prefs.getString("speech_model", "").orEmpty(),
            speechProvider = runCatching { AiSpeechProvider.valueOf(prefs.getString("speech_provider", "").orEmpty()) }.getOrDefault(AiSpeechProvider.OPENAI_COMPATIBLE),
            speechApiKey = readSecret(prefs, "speech_api_key_encrypted"),
            tencentSpeech = TencentSpeechConfig(
                prefs.getString("tencent_app_id", "").orEmpty(),
                readSecret(prefs, "tencent_secret_id_encrypted"),
                readSecret(prefs, "tencent_secret_key_encrypted"),
                prefs.getString("tencent_speech_model", "16k_zh").orEmpty(),
            ),
            replySystemPrompt = prefs.getString("reply_system_prompt", AiReplyPrompt.DEFAULT).orEmpty(),
            showReplyOptions = prefs.getBoolean("reply_multiple_options", false),
        )
    }

    @Synchronized
    fun save(context: Context, config: AiConfig) {
        AiReplyPrompt.validate(config.replySystemPrompt)
        AiBundledDefaults.requireAllowed(config.endpoint, config.apiKey)
        if (config.cloudSpeech && config.speechProvider == AiSpeechProvider.OPENAI_COMPATIBLE) {
            AiBundledDefaults.requireAllowed(config.speechEndpoint, config.effectiveSpeechApiKey)
        }
        // Encrypt every credential before editing preferences, so a failed encryption writes nothing.
        val textKey = encrypt(config.apiKey.trim())
        val speechKey = encrypt(config.speechApiKey.trim())
        val tencentId = encrypt(config.tencentSpeech.secretId.trim())
        val tencentKey = encrypt(config.tencentSpeech.secretKey.trim())
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("endpoint", config.endpoint.trim())
            .putString("model", config.model.trim())
            .putString("vision_model", config.visionModel.trim())
            .putString("reply_system_prompt", config.replySystemPrompt.trim())
            .putBoolean("reply_multiple_options", config.showReplyOptions)
            .remove("direct_reply_insert")
            .putString("api_key_encrypted", textKey)
            .remove("api_key")
            .putString("speech_language", config.speechLanguage)
            .putBoolean("cloud_speech", config.cloudSpeech)
            .putString("speech_endpoint", config.speechEndpoint.trim())
            .putString("speech_model", config.speechModel.trim())
            .putString("speech_provider", config.speechProvider.name)
            .putString("speech_api_key_encrypted", speechKey)
            .putString("tencent_app_id", config.tencentSpeech.appId.trim())
            .putString("tencent_secret_id_encrypted", tencentId)
            .putString("tencent_secret_key_encrypted", tencentKey)
            .putString("tencent_speech_model", config.tencentSpeech.model.trim())
            .putString("tencent_default_applied", TencentSpeechDefaults.id)
            .putBoolean("tencent_cloud_default_v1", true)
            .putBoolean("tencent_default_key", config.tencentSpeech.secretId.isNotBlank() && config.tencentSpeech.secretId == TencentSpeechDefaults.config.secretId && config.tencentSpeech.secretKey == TencentSpeechDefaults.config.secretKey)
            .putString("bundled_default_applied", AiBundledDefaults.id)
            .putString("key_source_bundle", if (AiBundledDefaults.isBundledKey(config.apiKey)) AiBundledDefaults.id else "")
            .apply()
    }

    fun showReplyOptions(context: Context): Boolean = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        .getBoolean("reply_multiple_options", false)

    private fun readSecret(prefs: android.content.SharedPreferences, name: String): String = runCatching { decrypt(prefs.getString(name, "").orEmpty()) }.getOrDefault("")

    private fun applySpeechDefault(prefs: android.content.SharedPreferences) {
        val defaults = TencentSpeechDefaults.config
        val id = TencentSpeechDefaults.id
        if (id.isEmpty() || !defaults.isReady || prefs.getString("tencent_default_applied", "") == id) return
        val hasOwnTencentKey = (prefs.contains("tencent_default_applied") || !prefs.getString("tencent_secret_key_encrypted", "").isNullOrEmpty()) && !prefs.getBoolean("tencent_default_key", false)
        val existingCloud = prefs.getBoolean("cloud_speech", false) &&
            (
                prefs.getString("speech_provider", "") == AiSpeechProvider.TENCENT_FLASH.name ||
                    (!prefs.getString("speech_endpoint", "").isNullOrBlank() && !prefs.getString("speech_model", "").isNullOrBlank())
                )
        val encryptedId = if (hasOwnTencentKey) "" else encrypt(defaults.secretId)
        val encryptedKey = if (hasOwnTencentKey) "" else encrypt(defaults.secretKey)
        prefs.edit().apply {
            if (!hasOwnTencentKey) {
                putString("tencent_app_id", defaults.appId)
                putString("tencent_secret_id_encrypted", encryptedId)
                putString("tencent_secret_key_encrypted", encryptedKey)
                val model = prefs.getString("tencent_speech_model", null)
                if (model == null || model == "16k_zh_en") putString("tencent_speech_model", defaults.model)
                putBoolean("tencent_default_key", true)
            }
            if (!existingCloud && !prefs.getBoolean("tencent_cloud_default_v1", false)) {
                putBoolean("cloud_speech", true)
                putString("speech_provider", AiSpeechProvider.TENCENT_FLASH.name)
            }
            putString("tencent_default_applied", id)
            putBoolean("tencent_cloud_default_v1", true)
        }.commit()
    }

    private fun applyBundledDefault(context: Context, prefs: android.content.SharedPreferences) {
        val id = AiBundledDefaults.id
        if (id.isEmpty() || prefs.getString("bundled_default_applied", "") == id || AiBundledDefaults.apiKey.isEmpty()) return
        val endpoint = prefs.getString("endpoint", null)
        val legacyProfile =
            (endpoint == "https://tokenlink.woa.com/openapi/v1/chat/completions" && prefs.getString("model", "hy4") == "hy4") ||
                (endpoint == "https://api.openai.com/v1/chat/completions" && prefs.getString("model", "gpt-4o-mini") == "gpt-4o-mini" && prefs.getString("api_key_encrypted", "").isNullOrEmpty())
        val previousBundle = AiBundledDefaults.isOfficialEndpoint(endpoint.orEmpty()) && !prefs.getString("key_source_bundle", "").isNullOrEmpty()
        val shouldApply = endpoint == null || legacyProfile || previousBundle
        if (shouldApply && endpoint != null) {
            // Preserve the previous service profile using its existing Keystore
            // ciphertext. No plaintext credential enters this local backup.
            val backup = context.getSharedPreferences("ai_assistant_before_deepseek_v1", Context.MODE_PRIVATE)
            if (!backup.contains("endpoint")) {
                backup.edit().apply {
                    for ((name, value) in prefs.all) {
                        if (name == "api_key") continue
                        when (value) {
                            is String -> putString(name, value)
                            is Boolean -> putBoolean(name, value)
                        }
                    }
                }.commit()
            }
        }
        val encrypted = if (shouldApply) encrypt(AiBundledDefaults.apiKey) else ""
        prefs.edit().apply {
            if (shouldApply) {
                putString("endpoint", DEFAULT_ENDPOINT)
                putString("model", DEFAULT_MODEL)
                putString("vision_model", DEFAULT_MODEL)
                putString("api_key_encrypted", encrypted)
                remove("api_key")
                if (!prefs.contains("speech_provider")) {
                    putBoolean("cloud_speech", false)
                    putString("speech_endpoint", "")
                    putString("speech_model", "")
                }
                putString("key_source_bundle", id)
            }
            putString("bundled_default_applied", id)
        }.commit()
    }

    private const val KEY_ALIAS = "trime_ai_api_key_v1"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        val bytes = Base64.decode(value, Base64.DEFAULT)
        require(bytes.size > 12) { "Invalid encrypted key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}
