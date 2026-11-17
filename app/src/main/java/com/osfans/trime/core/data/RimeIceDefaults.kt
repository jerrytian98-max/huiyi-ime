// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.core.data

import com.charleskorn.kaml.AnchorsAndAliases
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One-time, backed-up migration of defaults; subsequent user choices survive restarts. */
internal object RimeIceDefaults {
    const val SCHEMA = "rime_ice"
    private const val MARKER = ".rime-ai-ice-v1"
    private val yaml = Yaml(configuration = YamlConfiguration(anchorsAndAliases = AnchorsAndAliases.Permitted(1000u)))
    private val json = Json { prettyPrint = true }

    fun ensure(directory: File): Boolean {
        directory.mkdirs()
        val marker = File(directory, MARKER)
        if (marker.exists()) return false
        val custom = File(directory, "default.custom.yaml")
        val user = File(directory, "user.yaml")
        // Read both before changing either: malformed custom data is never discarded.
        val customRoot = read(custom)
        val userRoot = read(user)
        val patch = customRoot["patch"]?.let { it as? JsonObject ?: error("default.custom.yaml patch must be a mapping") } ?: JsonObject(emptyMap())
        val oldSchemas = patch["schema_list"]?.let { it as? JsonArray ?: error("schema_list must be a sequence") }
            ?: JsonArray(listOf(schema("luna_pinyin_simp"), schema("luna_pinyin")))
        val schemas = JsonArray(listOf(schema(SCHEMA)) + oldSchemas.filterNot { (it as? JsonObject)?.get("schema") == JsonPrimitive(SCHEMA) })
        val newCustom = JsonObject(customRoot + ("patch" to JsonObject(patch + ("schema_list" to schemas))))
        val variables = userRoot["var"]?.let { it as? JsonObject ?: error("user.yaml var must be a mapping") } ?: JsonObject(emptyMap())
        val newUser = JsonObject(userRoot + ("var" to JsonObject(variables + ("previously_selected_schema" to JsonPrimitive(SCHEMA)))))
        for (file in listOf(custom, user)) {
            val backup = File(directory, "${file.name}.before-ice-v1")
            if (file.exists() && !backup.exists()) file.copyTo(backup)
        }
        // JSON is a YAML subset. Rime scalar values are strings, including numbers
        // and booleans; quoting them preserves their ConfigValue interpretation.
        write(custom, json.encodeToString(JsonElement.serializer(), newCustom))
        write(user, json.encodeToString(JsonElement.serializer(), newUser))
        write(marker, "rime_ice\n")
        return true
    }

    private fun schema(id: String) = JsonObject(mapOf("schema" to JsonPrimitive(id)))

    /** Register the additional layout without resetting the selected schema or user patches. */
    fun ensureT9(directory: File): Boolean {
        val marker = File(directory, ".huiyi-t9-v1")
        if (marker.exists()) return false
        val custom = File(directory, "default.custom.yaml")
        val root = read(custom)
        val patch = root["patch"]?.let { it as? JsonObject ?: error("default.custom.yaml patch must be a mapping") } ?: JsonObject(emptyMap())
        val schemas = patch["schema_list"]?.let { it as? JsonArray ?: error("schema_list must be a sequence") } ?: JsonArray(listOf(schema(SCHEMA)))
        if (schemas.none { (it as? JsonObject)?.get("schema") == JsonPrimitive("rime_ice_t9") }) {
            val backup = File(directory, "default.custom.yaml.before-t9-v1")
            if (custom.exists() && !backup.exists()) custom.copyTo(backup)
            val updatedSchemas = JsonArray(schemas + schema("rime_ice_t9"))
            val updatedPatch = JsonObject(patch + ("schema_list" to updatedSchemas))
            val updated = JsonObject(root + ("patch" to updatedPatch))
            write(custom, json.encodeToString(JsonElement.serializer(), updated))
        }
        write(marker, "rime_ice_t9\n")
        return true
    }
    private fun read(file: File): JsonObject {
        if (!file.exists() || file.readText().isBlank()) return JsonObject(emptyMap())
        return node(yaml.parseToYamlNode(file.readText())) as? JsonObject ?: error("${file.name} must be a mapping")
    }
    private fun node(value: YamlNode): JsonElement = when (value) {
        is YamlMap -> JsonObject(value.entries.map { it.key.content to node(it.value) }.toMap())
        is YamlList -> JsonArray(value.items.map(::node))
        is YamlScalar -> JsonPrimitive(value.content)
        is YamlNull -> JsonNull
        is YamlTaggedNode -> node(value.innerNode)
    }
    private fun write(file: File, text: String) {
        val temp = File(file.parentFile, "${file.name}.ice-tmp")
        temp.writeText(text + "\n")
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
