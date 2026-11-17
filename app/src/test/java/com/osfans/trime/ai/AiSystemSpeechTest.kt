// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AiSystemSpeechTest :
    FunSpec({
        test("client error explains service connection and offers the system window") {
            val message = AiSystemSpeech.errorMessage(5)
            message.contains("连接失败（5）") shouldBe true
            message.contains("系统语音窗口") shouldBe true
        }
        test("unsupported and missing language packages have an actionable message") {
            AiSystemSpeech.errorMessage(12).contains("语言包") shouldBe true
            AiSystemSpeech.errorMessage(13).contains("语言包") shouldBe true
        }
        test("permission failure covers both the service and the global microphone switch") {
            val message = AiSystemSpeech.errorMessage(9)
            message.contains("语音服务") shouldBe true
            message.contains("麦克风开关") shouldBe true
        }
    })
