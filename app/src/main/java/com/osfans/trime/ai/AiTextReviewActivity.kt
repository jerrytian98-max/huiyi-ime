// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Correct an AI draft in a normal editor, then return to the original IME target. */
class AiTextReviewActivity : Activity() {
    private val scope = MainScope()
    private var polishJob: Job? = null
    private var requestId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        AiUiStyle.prepare(this)
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        render(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        polishJob?.cancel()
        setIntent(intent)
        render(intent)
    }
    private fun render(intent: Intent) {
        requestId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val session = AiTextReviewStore.current?.takeIf { it.id == requestId && it.result == null }
        if (session == null) {
            finish()
            return
        }
        val id = session.id
        val root = LinearLayout(this).apply {
            setBackgroundColor(AiUiStyle.background)
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(12))
        }
        root.addView(
            TextView(this).apply {
                text = "编辑后插入"
                textSize = 24f
            },
        )
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            TextView(this).apply {
                text = "修改待插入文字，确认后返回原输入框。"
                setPadding(0, dp(12), 0, dp(8))
            },
        )
        val draft = EditText(this).apply {
            setText(session.draft)
            AiUiStyle.input(this)
            minLines = 4
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            contentDescription = "待插入文字"
            doAfterTextChanged { AiTextReviewStore.edit(id, it.toString()) }
        }
        column.addView(draft, LinearLayout.LayoutParams(-1, -2))
        column.addView(
            TextView(this).apply {
                text = "原文（供核对）：\n${session.original}"
                maxLines = 5
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(12), 0, dp(12))
            },
        )
        val status = TextView(this).apply {
            text = "可直接修改并插入。重新整理只会上传编辑框中的文字。"
            textSize = 14f
            setTextColor(AiUiStyle.secondary)
        }
        column.addView(status)
        val restore = Button(this).apply {
            text = "用原文替换"
            AiUiStyle.button(this)
            setOnClickListener {
                draft.setText(session.original)
                status.text = "已填入原文，可以修改后插入或重新整理。"
            }
        }
        val polish = Button(this).apply {
            text = "AI 重新整理"
            AiUiStyle.button(this)
        }
        polish.setOnClickListener {
            if (polishJob?.isActive == true) {
                polishJob?.cancel()
                return@setOnClickListener
            }
            val input = draft.text.toString()
            if (input.isBlank() || input.length > AiClient.MAX_TEXT_LENGTH) {
                status.text = "重新整理需要1至${AiClient.MAX_TEXT_LENGTH}个字符；也可以继续编辑后直接插入。"
                return@setOnClickListener
            }
            polishJob = scope.launch {
                draft.isEnabled = false
                restore.isEnabled = false
                polish.text = "停止整理"
                status.text = "正在整理，停止时保留你的修改稿。"
                try {
                    val config = withContext(Dispatchers.IO) { AiConfigStore.load(this@AiTextReviewActivity) }
                    val output = withTimeout(60_000) {
                        AiClient.polish(config, input) { partial ->
                            runOnUiThread {
                                if (requestId == id && polishJob?.isActive == true) status.text = "整理预览：\n${partial.takeLast(300)}"
                            }
                        }
                    }
                    if (AiTextReviewStore.current?.id == id && requestId == id) {
                        draft.setText(output)
                        status.text = "整理完成，请核对并确认插入。"
                    }
                } catch (cancelled: CancellationException) {
                    status.text = "已停止整理，保留你的修改稿。"
                    throw cancelled
                } catch (error: Exception) {
                    status.text = "${error.message ?: "整理失败"}。已保留你的修改稿，可以直接插入。"
                } finally {
                    draft.isEnabled = true
                    restore.isEnabled = true
                    polish.text = "AI 重新整理"
                    AiUiStyle.button(polish)
                    if (requestId == id) polishJob = null
                }
            }
        }
        root.addView(ScrollView(this).apply { addView(column) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val tools = LinearLayout(this)
        tools.addView(restore, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        tools.addView(polish, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val actions = LinearLayout(this)
        actions.addView(
            Button(this).apply {
                text = "取消"
                AiUiStyle.button(this)
                setOnClickListener {
                    AiTextReviewStore.cancelEditing(id)
                    finish()
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) },
        )
        actions.addView(
            Button(this).apply {
                text = "确认插入"
                AiUiStyle.button(this, primary = true)
                setOnClickListener {
                    val text = draft.text.toString()
                    if (text.isBlank() || text.length > MAX_DRAFT_LENGTH) {
                        status.text = "请输入1至$MAX_DRAFT_LENGTH 个字符"
                        return@setOnClickListener
                    }
                    polishJob?.cancel()
                    if (AiTextReviewStore.submit(id, text)) returnToInputApp()
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        root.addView(actions)
        AiUiStyle.content(this, root)
    }
    override fun onStop() {
        polishJob?.cancel()
        super.onStop()
        if (!isChangingConfigurations) AiTextReviewStore.notifyReturnedToEditor()
    }
    override fun onDestroy() {
        scope.cancel()
        if (isFinishing) AiTextReviewStore.cancelEditing(requestId)
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        const val EXTRA_ID = "review_request_id"
        private const val MAX_DRAFT_LENGTH = 12000
    }
}
