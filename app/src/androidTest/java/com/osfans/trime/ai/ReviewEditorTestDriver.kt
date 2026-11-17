// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ai

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import java.lang.ref.WeakReference

/** Test-APK-only equivalent of UiObject2.setText; independent of keyboard language. */
internal class ReviewEditorTestDriver(private val context: Context) : AutoCloseable {
    private var review = WeakReference<Activity>(null)
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity is AiTextReviewActivity) review = WeakReference(activity)
        }
        override fun onActivityDestroyed(activity: Activity) {
            if (review.get() === activity) review.clear()
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val activity = review.get() ?: return
            val views = arrayListOf<View>()
            activity.window.decorView.findViewsWithText(views, "待插入文字", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
            val editor = views.filterIsInstance<EditText>().firstOrNull() ?: return
            val value = intent.getStringExtra("value") ?: return
            editor.setText(value)
            editor.setSelection(value.length)
            resultData = "review text updated"
        }
    }
    init {
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(callbacks)
        val filter = IntentFilter("com.rimeai.keyboard.debug.test.SET_REVIEW_TEXT")
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, "android.permission.READ_INPUT_STATE", null, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter, "android.permission.READ_INPUT_STATE", null)
        }
    }
    override fun close() {
        context.unregisterReceiver(receiver)
        (context.applicationContext as Application).unregisterActivityLifecycleCallbacks(callbacks)
    }
}
