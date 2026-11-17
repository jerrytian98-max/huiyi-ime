// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

/** Checks the actual settings controls, persistence, and default restoration. */
internal object AiReplySettingsChecks {
    private const val CUSTOM = "只说重点，不要寒暄。"

    fun run(instrumentation: Instrumentation, base: AiConfig) {
        val context = instrumentation.targetContext
        AiConfigStore.save(context, base.copy(showReplyOptions = false))
        val prefs = context.getSharedPreferences("ai_assistant_config", 0)
        prefs.edit().remove("reply_multiple_options").putBoolean("direct_reply_insert", false).commit()
        check(!AiConfigStore.load(context).showReplyOptions) { "Multiple reply options must default to off" }
        prefs.edit().putBoolean("direct_reply_insert", true).commit()
        check(!AiConfigStore.load(context).showReplyOptions) { "Old direct-reply setting must not reverse the new default" }
        AiConfigStore.save(context, base.copy(showReplyOptions = false))
        check(!prefs.contains("direct_reply_insert")) { "Old reply setting was not removed" }
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        fun openAndEdit(action: (EditText, View) -> Unit) {
            val activity = instrumentation.startActivitySync(
                Intent(context, AiSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            instrumentation.runOnMainSync {
                val root = activity.window.decorView
                val label = descendants(root).filterIsInstance<TextView>().first { it.text.toString() == "AI回复系统提示词" }
                val parent = label.parent as ViewGroup
                val editor = parent.getChildAt(parent.indexOfChild(label) + 1) as EditText
                check(!editor.isSingleLine && editor.maxLines >= 4)
                action(editor, root)
                descendants(root).filterIsInstance<Button>().first { it.text.toString() == "保存设置" }.performClick()
            }
        }
        openAndEdit { editor, root ->
            editor.setText(CUSTOM)
            descendants(root).filterIsInstance<Switch>().first { it.text.toString() == "提供多个回复选项" }.apply {
                check(!isChecked) { "Multiple reply options switch must initially be off" }
                isChecked = true
            }
        }
        check(AiConfigStore.load(context).replySystemPrompt == CUSTOM) { "Edited reply prompt was not saved" }
        check(AiConfigStore.load(context).showReplyOptions) { "Multiple reply options were not saved" }
        openAndEdit { editor, root ->
            check(editor.text.toString() == CUSTOM) { "Edited reply prompt was not shown after reopening" }
            check(descendants(root).filterIsInstance<Switch>().first { it.text.toString() == "提供多个回复选项" }.isChecked)
            descendants(root).filterIsInstance<Button>().first { it.text.toString() == "恢复默认回复风格" }.performClick()
            check(editor.text.toString() == AiReplyPrompt.DEFAULT) { "Reset did not refill the default prompt" }
        }
        check(AiConfigStore.load(context).replySystemPrompt == AiReplyPrompt.DEFAULT) { "Default reply prompt was not saved" }
        check(AiConfigStore.load(context).showReplyOptions) { "Reply mode changed when resetting the prompt" }
        AiConfigStore.save(context, base)
        check(AiConfigStore.load(context).showReplyOptions == base.showReplyOptions)
    }
}
