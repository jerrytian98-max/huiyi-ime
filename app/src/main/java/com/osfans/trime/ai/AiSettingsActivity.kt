// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ai

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.osfans.trime.R

class AiSettingsActivity : Activity() {
    private var quickCaptureSwitch: Switch? = null
    private var quickCaptureStatus: TextView? = null
    private var refreshingQuickCapture = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AiUiStyle.prepare(this)
        super.onCreate(savedInstanceState)
        title = getString(R.string.ai_settings)
        val config = AiConfigStore.load(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(12))
        }
        column.addView(
            TextView(this).apply {
                text = getString(R.string.ai_settings)
                textSize = 24f
            },
        )
        column.addView(
            TextView(this).apply {
                text = "按功能调整回复、截屏和语音；模型连接信息放在最后。"
                textSize = 13f
                setTextColor(AiUiStyle.secondary)
                setPadding(0, dp(4), 0, dp(8))
            },
        )
        val replySection = section(column, "AI 回复", "决定回复如何呈现，以及回复的语气。")
        val captureSection = section(column, "截屏与权限", "控制是否每次都显示系统截屏确认。")
        val voiceSection = section(column, "语音输入", "选择识别方式、语言和对应的服务配置。")
        val modelSection = section(column, "文字与图片模型", "整理和 AI 回复共用此服务；修改后点击保存。")
        addQuickCaptureSettings(captureSection)
        val showReplyOptions = Switch(this).apply {
            text = "提供多个回复选项"
            isChecked = config.showReplyOptions
            setPadding(0, dp(16), 0, dp(8))
        }
        replySection.addView(showReplyOptions, LinearLayout.LayoutParams(-1, -2))
        replySection.addView(
            TextView(this).apply {
                text = "关闭（默认）：直接把第一条回复填入原输入框。开启：显示 3 条回复供选择。短按和长按 AI回复均适用。无法确认输入位置时不会写入其他位置。"
                textSize = 13f
                setTextColor(AiUiStyle.secondary)
            },
        )
        val endpoint = field(modelSection, "服务地址（HTTPS）", config.endpoint)
        val model = field(modelSection, "文字整理模型", config.model)
        val visionModel = field(modelSection, "AI 回复模型（需支持图片）", config.visionModel)
        val apiKey = secretField(modelSection, "文字与 AI 回复 API Key", config.apiKey)
        val replySystemPrompt = field(replySection, "AI回复系统提示词", config.replySystemPrompt).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            filters = arrayOf(InputFilter.LengthFilter(AiReplyPrompt.MAX_LENGTH))
        }
        replySection.addView(
            TextView(this).apply {
                text = "只影响AI回复的语气。留空使用默认风格；截图识别和输出规则仍会生效。"
                textSize = 13f
                setTextColor(AiUiStyle.secondary)
            },
        )
        replySection.addView(
            Button(this).apply {
                text = "恢复默认回复风格"
                AiUiStyle.button(this)
                setOnClickListener { replySystemPrompt.setText(AiReplyPrompt.DEFAULT) }
            },
        )
        if (AiBundledDefaults.isBundledKey(config.apiKey)) {
            modelSection.addView(
                TextView(this).apply {
                    text = "已配置内测默认 Key，本机使用 Android Keystore 加密保存。共享 Key 随内测包分发仍可被提取，请勿公开分发。"
                    textSize = 13f
                    setTextColor(AiUiStyle.secondary)
                    setPadding(0, dp(8), 0, dp(8))
                },
            )
        }
        label(voiceSection, "识别语言")
        val speechLanguage = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AiSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("中文", "English"),
            )
            setSelection(if (config.speechLanguage == "en-US") 1 else 0)
        }
        voiceSection.addView(
            speechLanguage.apply {
                background = AiUiStyle.panel(this@AiSettingsActivity)
                minimumHeight = dp(48)
            },
        )
        label(voiceSection, "识别方式")
        val speechMode = Spinner(this).apply {
            adapter = ArrayAdapter(this@AiSettingsActivity, android.R.layout.simple_spinner_dropdown_item, listOf("系统语音服务", "腾讯云语音识别（默认）", "兼容接口云端转写"))
            setSelection(
                if (!config.cloudSpeech) {
                    0
                } else if (config.speechProvider == AiSpeechProvider.TENCENT_FLASH) {
                    1
                } else {
                    2
                },
            )
        }
        voiceSection.addView(
            speechMode.apply {
                background = AiUiStyle.panel(this@AiSettingsActivity)
                minimumHeight = dp(48)
            },
        )
        val systemSpeechSettings = Button(this).apply {
            text = "系统语音服务设置"
            AiUiStyle.button(this)
            setOnClickListener {
                try {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this@AiSettingsActivity, "此系统未提供语音设置入口，请到系统设置搜索语音输入", Toast.LENGTH_LONG).show()
                }
            }
        }
        voiceSection.addView(systemSpeechSettings, LinearLayout.LayoutParams(-1, -2))
        val tencentFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        voiceSection.addView(tencentFields)
        val appId = field(tencentFields, "腾讯云 AppID", config.tencentSpeech.appId)
        val secretId = secretField(tencentFields, "腾讯云 SecretId", config.tencentSpeech.secretId)
        val secretKey = secretField(tencentFields, "腾讯云 SecretKey", config.tencentSpeech.secretKey)
        val tencentModel = field(tencentFields, "腾讯云识别模型", config.tencentSpeech.model)
        tencentFields.addView(
            TextView(this).apply {
                text = "16k_zh_en 为中英大模型，16k_zh 为通用中文，英语时自动使用 16k_en。需具备极速版对应模型资源包或后付费额度。内测默认凭证加密保存，随 APK 分发仍可被提取。"
                textSize = 13f
                setTextColor(AiUiStyle.secondary)
            },
        )
        tencentFields.addView(
            Button(this).apply {
                text = "恢复腾讯云语音默认配置"
                AiUiStyle.button(this)
                isEnabled = TencentSpeechDefaults.config.isReady
                setOnClickListener {
                    val defaults = TencentSpeechDefaults.config
                    appId.setText(defaults.appId)
                    secretId.setText(defaults.secretId)
                    secretKey.setText(defaults.secretKey)
                    tencentModel.setText(defaults.model)
                    speechMode.setSelection(1)
                    Toast.makeText(this@AiSettingsActivity, "已填入默认语音配置，保存后生效", Toast.LENGTH_LONG).show()
                }
            },
        )
        val compatibleFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        voiceSection.addView(compatibleFields)
        val speechEndpoint = field(compatibleFields, "云端转写 HTTPS 地址", config.speechEndpoint)
        val speechModel = field(compatibleFields, "云端语音模型", config.speechModel)
        val speechApiKey = secretField(compatibleFields, "语音 API Key（留空沿用文字 Key）", config.speechApiKey)
        speechMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                systemSpeechSettings.visibility = if (position == 0) View.VISIBLE else View.GONE
                tencentFields.visibility = if (position == 1) View.VISIBLE else View.GONE
                compatibleFields.visibility = if (position == 2) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        systemSpeechSettings.visibility = if (!config.cloudSpeech) View.VISIBLE else View.GONE
        tencentFields.visibility = if (config.cloudSpeech && config.speechProvider == AiSpeechProvider.TENCENT_FLASH) View.VISIBLE else View.GONE
        compatibleFields.visibility = if (config.cloudSpeech && config.speechProvider == AiSpeechProvider.OPENAI_COMPATIBLE) View.VISIBLE else View.GONE
        voiceSection.addView(
            TextView(this).apply {
                text = "云端语音在松开空格后上传录音，最长 60 秒；转写结果会由文字模型整理。录音、文字和截图会发送到对应服务商，可能产生费用。"
                textSize = 13f
                setTextColor(AiUiStyle.secondary)
                setPadding(0, dp(12), 0, dp(8))
            },
        )
        modelSection.addView(
            Button(this).apply {
                text = "恢复默认 DeepSeek 配置"
                AiUiStyle.button(this)
                isEnabled = AiBundledDefaults.apiKey.isNotEmpty()
                setOnClickListener {
                    endpoint.setText(AiConfigStore.DEFAULT_ENDPOINT)
                    model.setText(AiConfigStore.DEFAULT_MODEL)
                    visionModel.setText(AiConfigStore.DEFAULT_MODEL)
                    apiKey.setText(AiBundledDefaults.apiKey)
                    Toast.makeText(this@AiSettingsActivity, "已填入默认配置，点击保存设置后生效", Toast.LENGTH_LONG).show()
                }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        val saveButton = Button(this).apply {
            text = "保存设置"
            AiUiStyle.button(this, primary = true)
            setOnClickListener {
                val next = AiConfig(
                    endpoint.text.toString(),
                    model.text.toString(),
                    visionModel.text.toString(),
                    apiKey.text.toString(),
                    if (speechLanguage.selectedItemPosition == 1) "en-US" else "zh-CN",
                    cloudSpeech = speechMode.selectedItemPosition != 0,
                    speechEndpoint = speechEndpoint.text.toString(),
                    speechModel = speechModel.text.toString(),
                    speechProvider = if (speechMode.selectedItemPosition == 1) AiSpeechProvider.TENCENT_FLASH else AiSpeechProvider.OPENAI_COMPATIBLE,
                    speechApiKey = speechApiKey.text.toString(),
                    tencentSpeech = TencentSpeechConfig(appId.text.toString().trim(), secretId.text.toString().trim(), secretKey.text.toString().trim(), tencentModel.text.toString().trim()),
                    replySystemPrompt = replySystemPrompt.text.toString(),
                    showReplyOptions = showReplyOptions.isChecked,
                )
                if (!next.hasValidEndpoint) {
                    Toast.makeText(this@AiSettingsActivity, "请填写有效的完整 HTTPS 服务地址", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (next.cloudSpeech && !next.isSpeechReady) {
                    Toast.makeText(this@AiSettingsActivity, "请检查所选语音服务的地址、模型和凭证", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                try {
                    AiConfigStore.save(this@AiSettingsActivity, next)
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(this@AiSettingsActivity, "无法安全保存 API Key：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
            }
        }
        AiUiStyle.content(
            this,
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(AiUiStyle.background)
                addView(ScrollView(this@AiSettingsActivity).apply { addView(column) }, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(
                    saveButton,
                    LinearLayout.LayoutParams(-1, -2).apply {
                        setMargins(dp(20), dp(8), dp(20), dp(12))
                    },
                )
            },
        )
    }

    private fun section(parent: LinearLayout, title: String, summary: String): LinearLayout {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        group.addView(View(this).apply { setBackgroundColor(0x22808080) }, LinearLayout.LayoutParams(-1, dp(1)))
        group.addView(
            TextView(this).apply {
                text = title
                textSize = 18f
                setPadding(0, dp(16), 0, 0)
            },
        )
        group.addView(
            TextView(this).apply {
                text = summary
                textSize = 13f
                setTextColor(AiUiStyle.secondary)
                setPadding(0, dp(4), 0, dp(4))
            },
        )
        parent.addView(group, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        return group
    }

    private fun label(parent: LinearLayout, text: String) {
        parent.addView(
            TextView(this).apply {
                this.text = text
                textSize = 13f
                setTextColor(AiUiStyle.secondary)
                setPadding(0, dp(12), 0, dp(4))
            },
        )
    }

    private fun addQuickCaptureSettings(column: LinearLayout) {
        val toggle = Switch(this).apply {
            text = "快捷截屏（免重复确认）"
            isEnabled = AiQuickCapture.isSupported
            setPadding(0, dp(16), 0, dp(8))
        }
        quickCaptureSwitch = toggle
        column.addView(toggle, LinearLayout.LayoutParams(-1, -2))
        quickCaptureStatus = TextView(this).also {
            it.textSize = 13f
            it.setTextColor(AiUiStyle.secondary)
            column.addView(it)
        }
        toggle.setOnCheckedChangeListener { _, checked ->
            if (refreshingQuickCapture) return@setOnCheckedChangeListener
            if (!checked) {
                AiQuickCapture.setRequested(this, false)
                refreshQuickCapture()
                return@setOnCheckedChangeListener
            }
            AlertDialog.Builder(this)
                .setTitle("开启快捷截屏")
                .setMessage("开启后，点击键盘上的“AI回复”会截取当前整个屏幕，并发送到你已配置的 AI 服务以生成回复建议。截图可能包含聊天、姓名及其他个人信息。\n\n短按截当前屏幕；按住并上拖时，若聊天页面支持无障碍滚动，就在按住期间滚动截图，松手后拼接发送，最多五屏。页面不支持时，会在松手后模拟手指下拉采集。点击停止可取消。只在本次操作中查询前台区域，不读取控件文字，不在后台截屏。需要你在接下来的系统无障碍设置中手动启用“会意快捷截屏”，可随时关闭。\n\n若系统拒绝授权或提示“受限制的设置”，请返回此页，点击“授权被系统拦截？”查看放行步骤。")
                .setPositiveButton("同意并去授权") { _, _ ->
                    AiQuickCapture.setRequested(this, true)
                    refreshQuickCapture()
                    openQuickCaptureAuthorization()
                }
                .setNegativeButton("取消") { _, _ -> refreshQuickCapture() }
                .setOnCancelListener { refreshQuickCapture() }
                .show()
        }
        column.addView(
            Button(this).apply {
                text = "打开快捷截屏授权设置"
                isEnabled = AiQuickCapture.isSupported
                AiUiStyle.button(this)
                setOnClickListener {
                    if (AiQuickCapture.isRequested(this@AiSettingsActivity)) {
                        openQuickCaptureAuthorization()
                    } else {
                        toggle.isChecked = true
                    }
                }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        column.addView(
            Button(this).apply {
                text = "授权被系统拦截？"
                isEnabled = AiQuickCapture.isSupported
                AiUiStyle.button(this)
                setOnClickListener { showQuickCaptureAuthorizationHelp() }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        refreshQuickCapture()
    }

    private fun showQuickCaptureAuthorizationHelp() {
        AlertDialog.Builder(this)
            .setTitle("放行受限设置")
            .setMessage(
                "Android 13 及部分厂商系统会限制通过 APK 安装的应用开启无障碍服务。若看到“系统已拒绝向此应用授予访问权限”或“受限制的设置”，请按以下步骤操作：\n\n" +
                    "1. 点击下方“应用信息”，打开会意输入法的应用详情。\n" +
                    "2. 点击右上角“⋮”，选择“允许受限制的设置”（也可能称为“允许受限设置”），并完成系统要求的身份验证。\n" +
                    "3. 返回无障碍设置，在已安装的服务／下载的应用中开启“会意快捷截屏”。\n" +
                    "4. 回到此页，确认显示“快捷截屏已授权”。\n\n" +
                    "各品牌入口可能不同。如果没有上述菜单，可能受厂商、设备管理或高级保护策略限制，可先关闭快捷截屏，使用每次确认的普通截屏。应用无法代替你完成系统授权。",
            )
            .setPositiveButton("应用信息") { _, _ ->
                runCatching { AiQuickCapture.openAppInfo(this) }.onFailure {
                    Toast.makeText(this, "请长按会意输入法图标，手动打开应用信息", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("无障碍设置") { _, _ -> openQuickCaptureAuthorization() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openQuickCaptureAuthorization() {
        runCatching { AiQuickCapture.openAuthorization(this) }.onFailure {
            Toast.makeText(this, "无法打开无障碍设置，请在系统设置中找到“会意快捷截屏”", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshQuickCapture() {
        refreshingQuickCapture = true
        quickCaptureSwitch?.isChecked = AiQuickCapture.isRequested(this)
        refreshingQuickCapture = false
        quickCaptureStatus?.text = when {
            !AiQuickCapture.isSupported -> "需要 Android 11 或更新版本；当前使用每次确认的普通截屏。"
            !AiQuickCapture.isRequested(this) -> "未开启。使用 AI 回复时，系统会要求确认截屏。"
            AiQuickCapture.isReady(this) -> "已授权。短按截当前屏幕；按住上拖截更多聊天，松手结束。"
            else -> "尚未授权。请打开下方授权设置；系统拦截时可查看放行方法。"
        }
    }

    override fun onResume() {
        super.onResume()
        refreshQuickCapture()
    }

    private fun secretField(parent: LinearLayout, label: String, value: String): EditText = field(parent, label, value).apply {
        isSaveEnabled = false
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }

    private fun field(parent: LinearLayout, label: String, value: String): EditText {
        parent.addView(
            TextView(this).apply {
                text = label
                gravity = Gravity.START
                textSize = 13f
                setTextColor(AiUiStyle.secondary)
                setPadding(0, dp(12), 0, 0)
            },
        )
        return EditText(this).also {
            AiUiStyle.input(it)
            it.setSingleLine(true)
            it.setText(value)
            parent.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
