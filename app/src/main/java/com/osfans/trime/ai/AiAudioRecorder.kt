// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Reads microphone PCM on IO; cancellation releases hardware without writing audio to disk. */
class AiAudioRecorder(private val context: Context) {
    @Volatile private var finished = false

    fun finish() {
        finished = true
    }

    @SuppressLint("MissingPermission") // Controller checks RECORD_AUDIO before starting.
    suspend fun record(onLevel: (Float) -> Unit = {}, onSeconds: (Int) -> Unit = {}): ByteArray = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        check(!finished) { "录音时间太短，请按住空格说完后再松开" }
        val minimum = AudioRecord.getMinBufferSize(WavAudio.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "手机不支持当前录音格式" }
        val builder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(WavAudio.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, 4096))
        if (Build.VERSION.SDK_INT >= 31) builder.setContext(context)
        val recorder = builder.build()
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            currentCoroutineContext().ensureActive()
            check(!finished) { "录音时间太短，请按住空格说完后再松开" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法使用麦克风，请检查权限或是否被占用" }
            val pcm = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var seconds = -1
            var levelBytes = 0
            while (!finished && pcm.size() < WavAudio.MAX_PCM_BYTES) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(buffer, 0, minOf(buffer.size, WavAudio.MAX_PCM_BYTES - pcm.size()), AudioRecord.READ_BLOCKING)
                check(count > 0) { "麦克风读取失败，请重试" }
                pcm.write(buffer, 0, count)
                levelBytes += count
                if (seconds == -1 || levelBytes >= 2048) {
                    levelBytes = 0
                    onLevel(PcmAudioLevel.measure(buffer, count))
                }
                val elapsed = pcm.size() / (WavAudio.SAMPLE_RATE * 2)
                if (elapsed != seconds) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        check(recorder.activeRecordingConfiguration?.isClientSilenced != true) { "系统限制了录音，请保持聊天输入框与键盘在前台，并检查麦克风隐私开关或其他录音应用" }
                    }
                    seconds = elapsed
                    onSeconds(elapsed)
                }
            }
            currentCoroutineContext().ensureActive()
            WavAudio.encode(pcm.toByteArray())
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { recorder.stop() }
            recorder.release()
        }
    }
}
