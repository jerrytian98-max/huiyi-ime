<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# 会意输入法隐私说明 / Privacy

更新日期：2026-11-23。此说明适用于基于 Trime/Rime 修改的会意输入法。

- 普通中英文键入使用本地 Rime；Rime 可在本机学习用户词频和保存用户词库。
- 按住「AI回复」向下滑动时，录制本次回复要求，最长60秒；松手后将音频发送到已配置的语音服务，获得转写文字，再主动截屏，将本次要求和聊天图片一起交给配置的视觉模型生成回复。语音要求本身不会作为正文输入聊天框。同图再次生成保留要求，新一轮普通截图清空要求。录音时左滑取消、输入目标变化或系统取消触摸会放弃本次处理；已发送的数据无法从服务商处撤回。
- 系统语音模式由 Android 识别服务处理麦克风音频，是否上传或离线由该服务决定。默认腾讯云转写模式由本应用在内存录制最长60秒语音，用户点击完成或达到上限后，将音频上传到用户配置的 HTTPS 转写地址。腾讯云使用独立 AppID、SecretId 和 SecretKey，兼容接口可单独配置语音 Key，留空时沿用文字 Key。取消、退出输入框或进程结束会停止录音并放弃待处理内容；取消已发出的网络请求无法撤回服务商已经收到的数据。录音不写入文件。
- 用户点击“整理”时，有选区只发送所选文字；没有选区时，尽量读取并发送当前输入框的完整草稿到用户配置的 HTTPS AI 服务。无法确认读取到完整草稿时会提示手动选中，不发送不完整内容；不会自动读取整个聊天记录。语音整理会发送最终转写文字。
- 用户主动点击AI回复时，采集一张屏幕图片并发送到当前配置的 AI 服务，整张屏幕可能包含姓名、头像、通知或其他个人信息。普通模式每个新会话请求系统截屏授权，采集一帧后结束；Android 11 及以上可选快捷模式，先阅读图片上传说明并由用户在系统无障碍设置手动启用“会意快捷截屏”，之后保持授权时点击即可直接截一帧。短按只截一帧；上拖AI回复并松手后，快捷服务根据实际截图、前台应用窗口和键盘位置确定可见区域，模拟手指向下拖动聊天内容，自动截取最多五屏并按从早到晚拼接上传，不读取控件文字。无可滚动控件时仍使用前台聊天可见区域；模拟下拉每次下拖420毫秒并停留180毫秒后松开，滚动采集最长12秒，点击停止可取消。仅在本次用户操作中模拟触摸，不订阅界面事件、不在后台自动截屏；界面或输入框变化、关闭键盘和撤销授权会取消本次长图。关闭快捷开关取消待处理的快捷分析，撤销权限后恢复普通截屏；取消不能撤回服务商已接收的数据。
- AI回复系统提示词由用户在 AI 设置中修改，保存在应用私有配置中。生成回复时会随截图一起发送给当前配置的 AI 服务；留空时使用内置默认提示词。此设置不改变语音识别或文字整理的请求。
- 服务商可能按自身政策记录请求与响应。使用功能前请确认配置的服务可信，了解其政策和账号费用。
- AI 模块不将截图、语音转写或 AI 结果写入文件。建议在进程内暂存，清除结果或进程结束后丢失；用户明确选择“复制”时会进入 Android 剪贴板，可能触发原有的剪贴板管理功能。
- “编辑后插入”的原文和修改稿只在进程内交接，页面 Intent 仅携带随机请求标识，不保存文本或自动填充数据。点击“AI 重新整理”只上传编辑框当前文字；取消编辑不改变原输入框。确认后重新核对原输入框和选区：选中文字整理的结果按原有规则插入；整段整理的结果仅在完整草稿仍一致时替换，变化时复制结果而不覆盖当前草稿。编辑页禁止系统截屏。
- API Key、腾讯云 SecretId 和 SecretKey 使用 Android Keystore AES-GCM 加密保存在应用私有配置中。应用关闭系统备份，网络仅使用 HTTPS。卸载应用会删除应用私有配置和 Key。
- Trime 原有词库、剪贴板历史、应用调试日志、导出及同步功能仍存在。可在应用设置中管理；用户主动导出的文件遵循所选存储位置的访问规则。
- 麦克风、通知与截屏均用于对应的用户主动操作。普通模式无需无障碍权限；只有用户主动开启快捷模式时才引导授予无障碍截屏权限。密码和声明不允许个性化学习的输入框禁用 AI 操作。
- 本项目没有内置开发者遥测、云端用户账号或收集输入历史的服务器。

Ordinary typing is processed locally by Rime. System speech mode uses Android's recognition provider, which may process audio online. Tencent cloud speech is the default and records up to 60 seconds in memory and uploads audio to the configured HTTPS transcription provider when the user finishes or the limit is reached; cancelling or leaving the editor stops capture and pending processing. Cancellation cannot retract data already received by a provider. No audio file is written. Tapping text cleanup sends the selected text, or the full current editor draft when nothing is selected, to the configured text provider. If a complete draft cannot be confirmed, no partial draft is sent. Voice cleanup sends the transcript. Tap-based screenshot analysis sends one full-screen image; long capture sends a stitched image after release. Providers' retention and billing policies apply. Tencent speech uses separate credentials. Compatible transcription endpoints support a separate speech key and use the text key when it is left blank. AI content is held in memory; explicit copying uses the system clipboard. API keys are encrypted with Android Keystore; app backup is disabled. Existing Trime dictionary learning, clipboard history, logs, exports and synchronization can store local data and remain configurable. Optional quick screenshot mode on Android 11+ requires separate in-app consent and a user-enabled AccessibilityService. A tap captures one screen. An upward flick followed by release starts bounded injected downward touch swipes in the visible foreground chat area, captures up to five screens, and uploads an in-memory stitched image. Each swipe drags for 420ms and pauses for 180ms before lifting, collection is bounded to 12 seconds, and Stop cancels the operation. No scrollable accessibility node is required. It does not read node text, subscribe to screen events, or capture in the background. Leaving the editor or revoking authorization cancels long capture. Disabling it cancels pending analysis; revocation restores ordinary projection consent. No developer telemetry backend is added.

The review editor keeps original and edited text in memory only; its Intent carries an opaque request ID. Re-polishing sends only the current edited text. Cancel leaves the original input unchanged. Confirmation checks the original editor and selection. If the cursor or editor changed, insertion at the new position requires a fresh user confirmation; a rejected editor submission falls back to a clearly indicated copy. Review content is not saved for view restoration or autofill, and the review window prevents screenshots.

0.1.4 默认方案改为本地雾凇拼音。升级前的输入方案配置备份保存在原用户目录（`.before-ice-v1`），不读取或包含 AI 模块的 API Key；原有导出和同步规则仍适用。日期与计算器扩展在本地执行。

0.1.5内测版按用户明确要求随APK附带DeepSeek共享默认Key的AES-GCM封装和恢复材料，因此持有APK的人可能提取共享Key。首次使用自动导入设备Keystore加密配置，清空并保存后不自动恢复，可在设置恢复默认。内置Key仅用于官方DeepSeek HTTPS聊天地址。卸载不吊销服务端Key，重装会再次提供默认Key。默认模型改为deepseek-flash（当前V4.1 Flash），文字与截图发送到DeepSeek；语音仍默认使用系统识别。

0.1.9 AI回复页的“插入到光标”使用内存中的目标摘要将已选择文字交回输入法，在返回输入应用时调用标准 InputConnection；成功时不经过剪贴板，不自动发送消息。目标变化需确认，重复或旧页面提交被拒绝。

0.1.19 按用户要求加密预填腾讯云语音内测凭证，并默认启用云端录音转写。内测 APK 中的恢复材料可以被逆向提取，此分发方式仅用于内测。录音和语音结果不写入应用文件，服务商数据处理与费用遵循其政策。

0.1.21 新增长按空格云端语音直输。长按开始在内存录音，松开后上传本次音频到已配置的语音服务，返回的识别原文直接通过 InputConnection 输入原输入框。录满60秒时先停止麦克风，等待松开后才上传。上滑取消、光标或输入框变化、继续按键及关闭键盘会停止录音并取消待处理结果。取消已发出的请求无法撤回服务商已收到的数据。原文直输使用语音服务；工具栏的语音整理流程继续使用文字服务。

Version 0.1.21 adds hold-space dictation. Holding starts in-memory recording. Releasing uploads the audio to the configured speech provider and inserts the returned raw transcript at the original editor location through InputConnection. Recording stops at 60 seconds and upload waits for release. Sliding out, changing selection or editor, pressing another key, or hiding the keyboard cancels capture and pending processing. Cancellation cannot retract data already received by the provider.
