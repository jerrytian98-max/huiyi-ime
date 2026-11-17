// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.core.data

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory

class RimeIceDefaultsTest :
    FunSpec({
        test("adding the T9 layout preserves custom patches and the selected schema") {
            val dir = createTempDirectory("t9-upgrade").toFile()
            val text = "patch:\n  schema_list: [{schema: rime_ice}, {schema: my_custom}]\n  menu/page_size: 7\n"
            File(dir, "default.custom.yaml").writeText(text)
            File(dir, "user.yaml").writeText("var: {previously_selected_schema: my_custom}\n")
            RimeIceDefaults.ensureT9(dir) shouldBe true
            val root = Json.parseToJsonElement(File(dir, "default.custom.yaml").readText()) as JsonObject
            val patch = root["patch"] as JsonObject
            (patch["schema_list"] as JsonArray).map { (it as JsonObject)["schema"]?.jsonPrimitive?.content } shouldBe listOf("rime_ice", "my_custom", "rime_ice_t9")
            patch["menu/page_size"]?.jsonPrimitive?.content shouldBe "7"
            File(dir, "default.custom.yaml.before-t9-v1").readText() shouldBe text
            File(dir, "user.yaml").readText() shouldBe "var: {previously_selected_schema: my_custom}\n"
            RimeIceDefaults.ensureT9(dir) shouldBe false
        }
        test("malformed T9 migration input is preserved and not marked as migrated") {
            val dir = createTempDirectory("t9-invalid").toFile()
            val file = File(dir, "default.custom.yaml").apply { writeText("patch: [broken") }
            shouldThrowAny { RimeIceDefaults.ensureT9(dir) }
            file.readText() shouldBe "patch: [broken"
            File(dir, ".huiyi-t9-v1").exists() shouldBe false
        }
        test("fresh install enables Ice first and selects it before engine startup") {
            val dir = createTempDirectory("ice-fresh").toFile()
            RimeIceDefaults.ensure(dir) shouldBe true
            val custom = Json.parseToJsonElement(File(dir, "default.custom.yaml").readText()) as JsonObject
            val schemas = (custom["patch"] as JsonObject)["schema_list"] as JsonArray
            (schemas.first() as JsonObject)["schema"]?.jsonPrimitive?.content shouldBe "rime_ice"
            val user = Json.parseToJsonElement(File(dir, "user.yaml").readText()) as JsonObject
            (user["var"] as JsonObject)["previously_selected_schema"]?.jsonPrimitive?.content shouldBe "rime_ice"
        }
        test("upgrade preserves custom schema order, other patches and original backups") {
            val dir = createTempDirectory("ice-upgrade").toFile()
            val customText = "patch:\n  schema_list: [{schema: luna_pinyin}, {schema: my_custom}]\n  menu/page_size: 7\n  ascii_composer/good_old_caps_lock: true\n"
            val userText = "var:\n  previously_selected_schema: luna_pinyin\n  option:\n    full_shape: false\ncustom: keep-me\n"
            File(dir, "default.custom.yaml").writeText(customText)
            File(dir, "user.yaml").writeText(userText)
            RimeIceDefaults.ensure(dir) shouldBe true
            val custom = Json.parseToJsonElement(File(dir, "default.custom.yaml").readText()) as JsonObject
            val patch = custom["patch"] as JsonObject
            (patch["schema_list"] as JsonArray).map { (it as JsonObject)["schema"]?.jsonPrimitive?.content } shouldBe listOf("rime_ice", "luna_pinyin", "my_custom")
            patch["menu/page_size"]?.jsonPrimitive?.content shouldBe "7"
            patch["ascii_composer/good_old_caps_lock"]?.jsonPrimitive?.content shouldBe "true"
            val user = Json.parseToJsonElement(File(dir, "user.yaml").readText()) as JsonObject
            user["custom"]?.jsonPrimitive?.content shouldBe "keep-me"
            ((user["var"] as JsonObject)["option"] as JsonObject)["full_shape"]?.jsonPrimitive?.content shouldBe "false"
            File(dir, "default.custom.yaml.before-ice-v1").readText() shouldBe customText
            File(dir, "user.yaml.before-ice-v1").readText() shouldBe userText
        }
        test("migration is one-time and subsequent manual choices are not reset") {
            val dir = createTempDirectory("ice-once").toFile()
            File(dir, "default.custom.yaml").writeText("patch:\n  schema_list: [{schema: rime_ice}, {schema: luna_pinyin_simp}]\n")
            RimeIceDefaults.ensure(dir)
            val user = File(dir, "user.yaml")
            user.writeText("var:\n  previously_selected_schema: luna_pinyin_simp\n")
            RimeIceDefaults.ensure(dir) shouldBe false
            user.readText() shouldBe "var:\n  previously_selected_schema: luna_pinyin_simp\n"
        }
        test("malformed user config aborts without overwriting either original") {
            val dir = createTempDirectory("ice-invalid").toFile()
            val custom = File(dir, "default.custom.yaml").apply { writeText("patch: {schema_list: [{schema: luna_pinyin}]}\n") }
            val user = File(dir, "user.yaml").apply { writeText("var: [broken") }
            val before = custom.readText()
            shouldThrowAny { RimeIceDefaults.ensure(dir) }
            custom.readText() shouldBe before
            user.readText() shouldBe "var: [broken"
            File(dir, ".rime-ai-ice-v1").exists() shouldBe false
        }
    })
