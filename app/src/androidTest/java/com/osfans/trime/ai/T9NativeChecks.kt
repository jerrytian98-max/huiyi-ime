// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.osfans.trime.daemon.RimeDaemon
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Exercises digit-to-pinyin decoding and confirms that the full-pinyin prism stays intact. */
internal object T9NativeChecks {
    fun run(instrumentation: Instrumentation) {
        val evidence = File(instrumentation.targetContext.filesDir, "t9-native-result.json")
        val rows = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var established = false
        var originalSchema: String? = null
        try {
            instrumentation.runOnMainSync { }
            val session = RimeDaemon.createSession("t9-native-checks")
            established = true
            runBlocking {
                withTimeout(15 * 60_000L) {
                    session.runOnReady {
                        originalSchema = selectedSchemaId().takeUnless { it.startsWith('.') }
                        check(selectedSchemata().any { it.id == "rime_ice_t9" }) { "T9 not enabled after upgrade" }
                        // Rime's dispatcher is ready before its asynchronous maintenance ends.
                        // Keep the process alive while the first T9 prism is being compiled.
                        while (!selectSchema("rime_ice_t9")) delay(500)
                        for ((schema, cases) in listOf(
                            "rime_ice_t9" to listOf("64426" to "你好", "42698" to "汉语", "7487832" to "输入法"),
                            "rime_ice" to listOf("hanyu" to "汉语", "shurufa" to "输入法"),
                        )) {
                            check(selectSchema(schema)) { "Unable to select $schema" }
                            setRuntimeOption("ascii_mode", false)
                            setRuntimeOption("traditionalization", false)
                            for ((input, expected) in cases) {
                                clearComposition()
                                simulateKeySequence(input)
                                val candidates = getCandidates(0, 50).map { it.text }
                                rows += buildJsonObject {
                                    put("schema", schema)
                                    put("input", input)
                                    put("expected", expected)
                                    put("candidates", JsonArray(candidates.map(::JsonPrimitive)))
                                }
                                evidence.writeText(JsonArray(rows).toString())
                                check(expected in candidates) { "Missing $expected for $input: $candidates" }
                            }
                        }
                        clearComposition()
                        originalSchema?.let { check(selectSchema(it)) }
                    }
                }
            }
            val result = buildJsonObject {
                put("version", "0.1.11-ai")
                put("checks", JsonArray(rows))
                put("network_requests", 0)
            }
            evidence.writeText(result.toString())
            instrumentation.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: T9 Chinese candidates and unchanged full-pinyin prism\n$result") })
        } catch (error: Throwable) {
            instrumentation.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${error.stackTraceToString()}") })
        } finally {
            if (established) {
                runCatching {
                    runBlocking {
                        RimeDaemon.createSession("t9-native-checks").runOnReady {
                            clearComposition()
                            originalSchema?.let { selectSchema(it) }
                        }
                    }
                }
                RimeDaemon.destroySession("t9-native-checks")
            }
        }
    }
}
