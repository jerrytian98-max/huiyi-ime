// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.osfans.trime.core.data.RimeIceDefaults
import com.osfans.trime.daemon.RimeDaemon
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** No network requests: exercise the packaged native engine, dictionaries and Lua. */
internal object IceNativeChecks {
    fun run(instrumentation: Instrumentation) {
        val evidence = File(instrumentation.targetContext.filesDir, "ice-native-result.json")
        val rows = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var established = false
        try {
            // onStart runs concurrently with Application.onCreate. Wait for the
            // main-thread bind to finish before accessing production AppPrefs.
            instrumentation.runOnMainSync { }
            val session = RimeDaemon.createSession("ice-native-checks")
            established = true
            runBlocking {
                withTimeout(15 * 60_000L) {
                    session.runOnReady {
                        // Do not select a schema here: this verifies the production
                        // fresh/upgrade default, after maintenance finishes.
                        while (selectedSchemaId() != RimeIceDefaults.SCHEMA) delay(250)
                        check(!getRuntimeOption("traditionalization")) { "Ice must start in simplified mode" }
                        check(selectedSchemata().first().id == RimeIceDefaults.SCHEMA) { "Ice is not the first enabled schema" }
                        rows += buildJsonObject {
                            put("case", "default-schema")
                            put("schema", selectedSchemaId())
                            put("simplified", true)
                        }
                        evidence.writeText(JsonArray(rows).toString())
                        setRuntimeOption("ascii_mode", false)
                        for ((input, expected) in listOf("hanyu" to "汉语", "shurufa" to "输入法", "hello" to "hello", "rq" to "date", "cC1+2" to "3")) {
                            clearComposition()
                            simulateKeySequence(input)
                            val candidates = getCandidates(0, 12).map { it.text }
                            rows += buildJsonObject {
                                put("case", input)
                                put("candidates", JsonArray(candidates.map(::JsonPrimitive)))
                            }
                            evidence.writeText(JsonArray(rows).toString())
                            check(if (expected == "date") candidates.any { it.matches(Regex("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}")) } else candidates.any { it.equals(expected, ignoreCase = true) }) { "Missing $expected for $input: $candidates" }
                        }
                        clearComposition()
                    }
                }
            }
            val result = buildJsonObject {
                put("version", "0.1.4-ai")
                put("checks", JsonArray(rows))
                put("network_requests", 0)
            }
            evidence.writeText(result.toString())
            instrumentation.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: Ice defaults, simplified candidates, mixed English, date and calculator Lua\n$result") })
        } catch (error: Throwable) {
            instrumentation.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}") })
        } finally {
            if (established) RimeDaemon.destroySession("ice-native-checks")
        }
    }
}
