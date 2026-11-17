// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import com.osfans.trime.BuildConfig
import java.net.URI
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Authorized internal-test default. APK wrapping material can be extracted. */
internal object AiBundledDefaults {
    val id: String get() = BuildConfig.AI_DEFAULT_BUNDLE_ID
    val apiKey: String by lazy {
        runCatching { decrypt(BuildConfig.AI_DEFAULT_CIPHERTEXT, BuildConfig.AI_DEFAULT_WRAP_KEY) }.getOrDefault("")
    }

    internal fun decrypt(envelope: String, wrappingKey: String, associatedData: String = "rime-ai:deepseek-flash:v1"): String {
        if (envelope.isEmpty() || wrappingKey.isEmpty()) return ""
        val bytes = Base64.getDecoder().decode(envelope)
        val key = Base64.getDecoder().decode(wrappingKey)
        require(bytes.size > 28 && key.size == 32) { "Invalid bundled credential envelope" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    fun isOfficialEndpoint(endpoint: String): Boolean = runCatching {
        val uri = URI(endpoint.trim())
        uri.scheme == "https" && uri.host.equals("api.deepseek.com", ignoreCase = true) &&
            uri.port in listOf(-1, 443) && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null &&
            uri.path in listOf("/chat/completions", "/v1/chat/completions")
    }.getOrDefault(false)

    fun isBundledKey(value: String): Boolean = apiKey.isNotEmpty() && value.trim() == apiKey

    fun requireAllowed(endpoint: String, key: String) {
        require(!isBundledKey(key) || isOfficialEndpoint(endpoint)) {
            "内测默认 Key 仅用于 DeepSeek 官方聊天接口；更换服务或云端转写时请填写对应的 API Key"
        }
    }
}
