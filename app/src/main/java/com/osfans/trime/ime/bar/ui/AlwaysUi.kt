/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewAnimator
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import androidx.core.widget.TextViewCompat
import com.osfans.trime.R
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.ToolBar
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber

class AlwaysUi(
    override val ctx: Context,
    private val scope: ThemeScope,
    private val onButtonClick: ((String) -> Unit)? = null,
    private val onAiScreen: (() -> Unit)? = null,
    private val onAiSettings: (() -> Unit)? = null,
    private val onAiPolish: (() -> Unit)? = null,
    private val onAiLongScreenStart: ((Int, (String?) -> Unit) -> String?)? = null,
    private val onAiLongScreenFinish: ((String) -> Unit)? = null,
    private val onAiLongScreenCancel: ((String) -> Unit)? = null,
    private val onAiReplyIntentStart: (((String?) -> Unit) -> Long?)? = null,
    private val onAiReplyIntentFinish: ((Long) -> Unit)? = null,
    private val onAiReplyIntentCancel: ((Long) -> Unit)? = null,
    private val onAiReplyIntentCancelling: ((Long, Boolean) -> Unit)? = null,
) : Ui {
    private val theme: Theme get() = scope.theme

    enum class State {
        Toolbar,
        Clipboard,
        InlineSuggestion,
    }

    var currentState = State.Toolbar
        private set

    private fun toolButton(
        buttonConfig: ToolBar.Button?,
        @DrawableRes icon: Int = 0,
    ): ToolButton = if (buttonConfig != null) {
        ToolButton(ctx, buttonConfig, scope).apply {
            setOnClickListener { onButtonClick?.invoke(buttonConfig.action) }
            val longPressAction = buttonConfig.longPressAction
            if (longPressAction.isNotEmpty()) {
                setOnLongClickListener {
                    onButtonClick?.invoke(longPressAction)
                    true
                }
            }
        }
    } else {
        ToolButton(ctx, icon, scope).apply {
            setOnClickListener { onButtonClick?.invoke("") }
        }
    }

    val buttonsUi = ButtonsBarUi(ctx, scope, onButtonClick)

    val clipboardUi = ClipboardSuggestionUi(ctx, scope)

    val inlineSuggestionsUi = InlineSuggestionsUi(ctx)

    val hideKeyboardButton = ToolButton(ctx, R.drawable.ic_ai_chevron_down, scope)
    private val rightMostButton =
        ViewAnimator(ctx).apply {
            add(hideKeyboardButton, lParams(matchParent, matchParent))
            buttonsUi.firstButton?.let { add(it, lParams(matchParent, matchParent)) }
        }

    private val leftMostButton = toolButton(
        theme.toolBar.primaryButton,
        R.drawable.ic_ai_grid,
    )

    private class AiButton(context: Context, label: String, @DrawableRes icon: Int) : LinearLayout(context) {
        var onDetach: (() -> Unit)? = null

        override fun onDetachedFromWindow() {
            onDetach?.invoke()
            super.onDetachedFromWindow()
        }
        val iconView = ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            setPadding(context.dp(8), 0, context.dp(8), 0)
            addView(iconView, LayoutParams(context.dp(18), context.dp(18)))
            addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(4) })
        }
    }

    private fun aiButton(label: String, @DrawableRes icon: Int, click: (() -> Unit)?): AiButton = AiButton(ctx, label, icon).apply {
        contentDescription = when (label) {
            "整理" -> "AI 整理输入框文字或选中文字"
            else -> "AI回复"
        }
        styleAiButton(this)
        setOnClickListener { click?.invoke() }
        setOnLongClickListener {
            onAiSettings?.invoke()
            true
        }
    }

    private fun styleAiButton(view: AiButton) {
        val accent = scope.colors.hilitedTextColor
        val foreground = scope.colors.candidateTextColor
        view.labelView.setTextColor(foreground)
        view.iconView.imageTintList = ColorStateList.valueOf(foreground)
        val shape = GradientDrawable().apply {
            cornerRadius = ctx.dp(8).toFloat()
            setColor(android.graphics.Color.TRANSPARENT)
        }
        view.background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 28)), InsetDrawable(shape, ctx.dp(2), ctx.dp(4), ctx.dp(2), ctx.dp(4)), null)
    }

    private val screenButton = aiButton("AI回复", R.drawable.ic_ai_screen, onAiScreen).apply {
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(labelView, 10, 13, 1, TypedValue.COMPLEX_UNIT_SP)
        val gesture = ScreenCaptureGesture(
            this,
            start = { top, status -> onAiLongScreenStart?.invoke(top, status) },
            finish = { onAiLongScreenFinish?.invoke(it) },
            cancel = { onAiLongScreenCancel?.invoke(it) },
            label = {
                labelView.text = when {
                    it == null -> "AI回复"
                    it == "上拖" || it == "上下滑" || it == "松手" || it == "准备中" -> it
                    it == "松手取消" -> "取消"
                    it == "说回复要求" || it == "准备录音" || it.endsWith("秒·松手回复") -> "说要求"
                    it == "转写中…" -> "转写中"
                    it == "截图中…" -> "截图中"
                    it.endsWith("屏·松手生成") -> it.substringBefore("·")
                    else -> "停止"
                }
                contentDescription = if (it == null) "AI回复，短按截屏，上滑截长图，下滑说回复要求，松手截图回复" else "AI回复：$it"
            },
            voiceStart = { status -> onAiReplyIntentStart?.invoke(status) },
            voiceFinish = { onAiReplyIntentFinish?.invoke(it) },
            voiceCancel = { onAiReplyIntentCancel?.invoke(it) },
            voiceCancelling = { id, cancelling -> onAiReplyIntentCancelling?.invoke(id, cancelling) },
        )
        setOnTouchListener(gesture)
        onDetach = gesture::onDetach
    }
    private val polishButton = aiButton("整理", R.drawable.ic_ai_polish, onAiPolish)

    private val animator =
        ViewAnimator(ctx).apply {
            add(buttonsUi.root, lParams(matchParent, matchParent))
            add(clipboardUi.root, lParams(matchParent, matchParent))
            add(inlineSuggestionsUi.root, lParams(matchParent, matchParent))
        }

    override val root: ConstraintLayout = constraintLayout {
        val (leftWidth, leftHeight) = buttonsUi.getButtonSize(theme.toolBar.primaryButton)
        val (rightWidth, rightHeight) = buttonsUi.getButtonSize(theme.toolBar.buttons.firstOrNull())

        add(
            leftMostButton,
            lParams(leftWidth, leftHeight) {
                startOfParent()
                centerVertically()
            },
        )
        add(
            screenButton,
            lParams(ctx.dp(80), matchParent) {
                after(polishButton)
                centerVertically()
            },
        )
        add(
            polishButton,
            lParams(ctx.dp(80), matchParent) {
                after(leftMostButton)
                centerVertically()
            },
        )
        add(
            rightMostButton,
            lParams(rightWidth, rightHeight) {
                endOfParent()
                centerVertically()
            },
        )
        add(
            animator,
            lParams(matchConstraints, matchParent) {
                after(screenButton)
                before(rightMostButton)
                endOfParent()
                centerVertically()
            },
        )
    }.apply {
        updateRightMostButton(State.Toolbar)
    }

    fun updateButtonsStyle(option: String, enabled: Boolean) {
        leftMostButton.updateStyle(option, enabled)
        buttonsUi.firstButton?.updateStyle(option, enabled)
        buttonsUi.updateStyle(option, enabled)
    }

    fun toggleOptions(): Set<String> = buildSet {
        leftMostButton.option?.let { add(it) }
        buttonsUi.firstButton?.option?.let { add(it) }
        buttonsUi.root.children.forEach { (it as ToolButton).option?.let { add(it) } }
    }

    fun updateState(state: State) {
        Timber.d("Switch always ui to $state")
        animator.displayedChild = state.ordinal
        currentState = state
        updateRightMostButton(state)
        updateLeftMostButton(state)
    }

    /** Restyles the toolbar area after a scheme switch. */
    fun refreshColors() {
        leftMostButton.refreshColors()
        styleAiButton(screenButton)
        styleAiButton(polishButton)
        hideKeyboardButton.refreshColors()
        buttonsUi.refreshColors()
        clipboardUi.refreshColors()
    }

    private fun updateRightMostButton(state: State) {
        val hasFirstButton = buttonsUi.firstButton != null
        val showFirst = hasFirstButton && (theme.toolBar.buttons.isNotEmpty() || state != State.Toolbar)
        rightMostButton.displayedChild = if (showFirst) 1 else 0
    }

    private fun updateLeftMostButton(state: State) {
        val buttonConfig =
            if (state == State.Toolbar) {
                theme.toolBar.primaryButton
            } else {
                theme.toolBar.buttons.firstOrNull()
            }

        val (buttonWidth, buttonHeight) = buttonsUi.getButtonSize(buttonConfig)
        leftMostButton.layoutParams = leftMostButton.layoutParams.apply {
            width = buttonWidth
            height = buttonHeight
        }
    }
}
