// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Real Android screenshots and production client against synthetic local HTTPS only. */
@RequiresApi(30)
object AiQuickCaptureChecks {
    fun run(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        val original = AiConfigStore.load(context)
        val originalQuick = AiQuickCapture.isRequested(context)
        val previousTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        val stageFile = File(context.filesDir, "quick-capture-stage")
        val signalFile = File(context.filesDir, "quick-capture-continue")
        val rows = mutableListOf<String>()
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun stage(value: String) {
            signalFile.delete()
            stageFile.writeText(value)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", value) })
        }
        fun waitFor(timeout: Long = 30_000, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < end) {
                var success = false
                main { success = condition() }
                if (success) return
                SystemClock.sleep(100)
            }
            error("Quick capture check timed out; state=${AiEvents.captureState.status}")
        }
        fun waitSignal() = waitFor(180_000) { signalFile.exists() }
        try {
            val certificate = instrumentation.context.assets.open("test-ca.pem").use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("test-ca", certificate)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            val config = AiConfig("https://10.0.2.2:9443/v1/chat/completions", "mock-text", "mock-vision", "synthetic-test-key", "zh-CN", showReplyOptions = true)
            AiConfigStore.save(context, config)
            stage("QUICK_CONNECT_READY")
            waitFor { AiAccessibilityCaptureService.isConnected }
            main {
                AiEvents.clearSuggestions()
                AiQuickCapture.setRequested(context, false)
                check(!AiAccessibilityCaptureService.tryCapture(context, "no-opt-in"))
                check(AiEvents.captureState.status == CaptureStatus.IDLE)
                AiQuickCapture.setRequested(context, true)
                context.startActivity(
                    Intent().setClassName(instrumentation.context.packageName, TestChatActivity::class.java.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            rows += "PASS: permission alone does not enable capture; explicit app opt-in required"
            SystemClock.sleep(3500)
            for (index in 1..2) {
                val id = "quick-$index"
                main {
                    AiEvents.clearSuggestions()
                    check(AiAccessibilityCaptureService.tryCapture(context, id))
                }
                waitFor { !AiEvents.isCaptureInProgress }
                main {
                    check(AiEvents.captureState.requestId == id && AiEvents.captureState.status == CaptureStatus.READY) { AiEvents.captureState.message }
                    check(AiEvents.suggestions.size == 3)
                }
                rows += "PASS: fresh screenshot $index -> real Android accessibility API -> production HTTPS client -> three suggestions without projection consent"
                SystemClock.sleep(800)
            }
            main {
                AiEvents.clearSuggestions()
                check(AiAccessibilityCaptureService.tryCapture(context, "cancel-immediately"))
                AiAccessibilityCaptureService.cancelCurrent()
                check(AiEvents.captureState.status == CaptureStatus.CANCELLED)
            }
            SystemClock.sleep(1500)
            main { check(AiEvents.captureState.status == CaptureStatus.CANCELLED && AiEvents.suggestions.isEmpty()) }
            rows += "PASS: immediate cancellation discards late screenshot callbacks"
            AiConfigStore.save(context, config.copy(visionModel = "mock-vision-slow"))
            main {
                AiEvents.clearSuggestions()
                check(AiAccessibilityCaptureService.tryCapture(context, "cancel-network"))
            }
            waitFor { AiEvents.captureState.status == CaptureStatus.ANALYZING }
            SystemClock.sleep(500)
            main { AiQuickCapture.setRequested(context, false) }
            SystemClock.sleep(3500)
            main {
                check(AiEvents.captureState.status == CaptureStatus.CANCELLED && AiEvents.suggestions.isEmpty())
                check(!AiAccessibilityCaptureService.tryCapture(context, "disabled"))
            }
            rows += "PASS: disabling quick mode cancels pending analysis and rejects new quick captures"
            AiConfigStore.save(context, config.copy(visionModel = "mock-vision-regenerate"))
            val readyStates = mutableListOf<CaptureState>()
            val replyObserver: (CaptureState) -> Unit = { if (it.status == CaptureStatus.READY) readyStates.add(it) }
            main {
                AiQuickCapture.setRequested(context, true)
                AiEvents.clearSuggestions()
                AiEvents.addCaptureObserver(replyObserver)
            }
            stage("QUICK_KEYBOARD_READY")
            waitSignal()
            main {
                AiEvents.removeCaptureObserver(replyObserver)
                check(AiEvents.captureState.status == CaptureStatus.IDLE) { "Keyboard reply session was not cleared" }
                check(readyStates.size == 6) { "Expected six completed groups, got ${readyStates.size}" }
                check(readyStates.take(3).map { it.attempt } == listOf(0, 1, 2))
                check(readyStates.take(3).map { it.requestId }.distinct().size == 1)
                check(readyStates.drop(2).map { it.requestId }.distinct().size == 4)
            }
            rows += "PASS: actual keyboard regenerates twice in one session and inserts a regenerated-compatible reply"
            rows += "PASS: Close, Back and choosing a reply each force the next look-button press to start a fresh capture"
            SystemClock.sleep(800)
            main { check(AiAccessibilityCaptureService.tryCapture(context, "activity-replies")) }
            waitFor { AiEvents.captureState.status == CaptureStatus.READY }
            main { context.startActivity(Intent(context, AiReplyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            stage("QUICK_REPLY_ACTIVITY_READY")
            waitSignal()
            main { check(AiEvents.captureState.status == CaptureStatus.IDLE) }
            rows += "PASS: reply Activity regenerates from the current image and clears the session on exit"
            AiConfigStore.save(context, config)
            SystemClock.sleep(800)
            main { check(AiAccessibilityCaptureService.tryCapture(context, "cancel-regeneration")) }
            waitFor { AiEvents.captureState.status == CaptureStatus.READY }
            AiConfigStore.save(context, config.copy(visionModel = "mock-vision-slow"))
            main { check(AiCaptureReplies.regenerate(context, AiEvents.captureState)) }
            SystemClock.sleep(500)
            main { AiEvents.finishSession("cancel-regeneration") }
            SystemClock.sleep(3500)
            main { check(AiEvents.captureState.status == CaptureStatus.IDLE && AiEvents.suggestions.isEmpty()) }
            rows += "PASS: leaving during regeneration cancels the request and rejects late responses"
            AiConfigStore.save(context, config)
            stage("QUICK_REVOKE_READY")
            waitSignal()
            waitFor { !AiAccessibilityCaptureService.isConnected }
            main {
                check(!AiQuickCapture.isReady(context))
                check(!AiAccessibilityCaptureService.tryCapture(context, "revoked"))
            }
            rows += "PASS: revoking system permission disconnects service and rejects quick captures"
            stage("QUICK_FALLBACK_READY")
            waitSignal()
            main { check(AiEvents.captureState.status == CaptureStatus.CANCELLED) { "Projection fallback was not cancelled" } }
            rows += "PASS: actual keyboard falls back to ordinary projection consent after permission revocation"
            AiConfigStore.save(context, config.copy(visionModel = "mock-vision-regenerate"))
            main { AiEvents.clearSuggestions() }
            stage("STANDARD_REGENERATION_READY")
            waitSignal()
            main { check(AiEvents.captureState.status == CaptureStatus.IDLE) }
            rows += "PASS: ordinary MediaProjection screenshot also supports same-image regeneration and clears on exit"
            File(context.filesDir, "quick-capture-result.json").writeText(
                kotlinx.serialization.json.buildJsonObject {
                    put("version", kotlinx.serialization.json.JsonPrimitive("0.1.15-ai"))
                    put("provider", kotlinx.serialization.json.JsonPrimitive("Local synthetic HTTPS only; no external AI calls"))
                    put("checks", kotlinx.serialization.json.JsonArray(rows.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                }.toString(),
            )
            result.putString("stream", rows.joinToString("\n"))
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            main {
                AiQuickCapture.setRequested(context, false)
                AiEvents.clearSuggestions()
                AiQuickCapture.setRequested(context, originalQuick)
            }
            AiConfigStore.save(context, original)
            HttpsURLConnection.setDefaultSSLSocketFactory(previousTls)
            stageFile.delete()
            signalFile.delete()
        }
        instrumentation.finish(resultCode, result)
    }
}
