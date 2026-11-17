<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Android 首版验证记录

验证日期：2026-11-05 至 2026-11-06。版本：0.1.5-ai，包名 `com.rimeai.keyboard.debug`。源代码基于 Trime develop，新增修改保留在此工作区。

## 交付文件

- `../交付/huiyi-android-arm64-debug.apk`：普通 ARM64 安卓手机，约 43 MB。
- `../交付/huiyi-android-x86_64-debug.apk`：x86_64 安卓模拟器。
- `../交付/SHA256SUMS.txt`：安装包校验值。
- 两个包均通过 APK 签名验证，使用开发调试签名。最低 Android 8.0（API 26），编译/目标 API 37。

## 0.1.5 DeepSeek 加密默认配置验证

默认deepseek-flash（DeepSeek V4.1 Flash）。按用户要求加密附带内测默认Key，首次使用导入Android Keystore；APK恢复材料可提取，不能作为公开分发的密钥保护。354项单测、构建、Lint与Spotless通过。API35已直连官方服务验证中英文流式整理和截图三条建议，并验证加密导入、清空保留、自定义配置和地址绑定。详见 [DEEPSEEK_DEFAULTS.md](DEEPSEEK_DEFAULTS.md)。下方历史测试的“Key不进入APK”仅适用于当时版本。

## 0.1.4 方案与主题验证

集成固定版本雾凇拼音，默认简体、中文，兼容首次安装及旧版配置迁移；三个内置主题的中文按钮与提示统一为简体。351项单测通过，包括4项迁移测试；最终安装包的 native 引擎复测验证默认方案、汉语/输入法候选、中英混输、日期和计算器Lua通过；屏幕点击得到“汉语输入法hello”，字母键盘和符号面板截图均检查。初次调试运行遇到逐条词库日志导致的超时，已限制日志并优化字典构建和拼写投影路径；完整词库仍需等待首次编译。详见 [RIME_ICE_INTEGRATION.md](RIME_ICE_INTEGRATION.md)。本轮没有调用真实 AI 接口；hy4数据保留自0.1.3。

## 0.1.3 接口接入验证

当前默认 Tokenlink / hy4。中英文流式整理与截图三条建议通过 Android 生产 AiClient 的真实请求验证；Key 仅在电脑临时转发内存中。本轮手机/模拟器直连未通过：模拟器无法解析该域名，成功请求经电脑转发完成。347项单测、Lint、Spotless与APK签名通过。详见 [TOKENLINK_HY4_VALIDATION.md](TOKENLINK_HY4_VALIDATION.md)。下方完整UI、音频和键盘结果保留对应历史版本的验证范围。

## 已执行的验证

| 范围 | 结果与证据 |
| --- | --- |
| 构建 | ARM64、x86_64 APK 均成功构建；另行生成独立 androidTest APK |
| 单元测试 | 347 项测试通过，0 失败、0 错误；覆盖响应解析、截断拒绝、截图状态隔离、PCM WAV 格式和静音拒绝，并运行原有测试 |
| 静态检查 | Android Lint、Spotless 通过；Lint 0 错误、120 条警告，包含原有及本地化/风格提示 |
| 安装与普通输入 | Android 15 / API 35、1080×2400 Pixel 6 模拟器，WHPX 加速；安装、向导、启用、切换成功；屏幕点击验证中文拼音候选和英文输入 |
| 界面与适配 | 新默认“轻简”主题：白色圆角字母键、灰色功能键、绿色回车与细线 AI 图标；设置/回复/编辑页统一样式，按 systemBars、displayCutout、IME insets 避让；保留自定义主题 |
| 修改稿编辑 | 预览→编辑→AI 重新整理当前修改稿→返回并替换原选区；取消保留原文、纯手改插入均由独立测试 APK 驱动，生产 APK 不包含编辑测试接收器 |
| 文字整理 | 选中“嗯明天下午三点那个开会”，经本机模拟 HTTPS 服务流式返回整理稿，预览并替换为“明天下午三点开会。” |
| 语音流程 | 独立测试 RecognitionService 提供临时/最终识别回调，真实 SpeechRecognizer 绑定、AI 请求、预览、选择原文插入均通过 |
| 看图流程 | 键盘点击 → Android 系统整屏授权 → MediaProjection 前台服务 → 单帧 JPEG → 本机 HTTPS 模拟服务 → 三条回复 → 点击插入，通过 |
| 重复与取消 | 下一次看图重新出现系统同意窗口；取消后没有展示上一次建议 |
| 网络错误 | 设备中验证 401、429、格式错误和截断响应均被拒绝，不当作可插入的成功结果 |
| API Key | 设备中验证 Keystore 加密保存/读取一致，配置中没有明文 Key 字段 |
| 主线程 | 输入框文本读取已移至后台并设置时限，异步返回后重新核对输入框、文本与选区，避免远端输入框等待阻塞键盘 |
| 真实模型 | Android 中的实际 AiClient 经本机仅测试用 TLS 转发调用真实 OpenAI gpt-4o-mini；中文、英文流式整理及真实 JPEG 图片请求通过；正式 Key 仅在电脑进程内存，模拟器只使用虚拟测试凭据 |
| 云端语音 | 中英文合成 WAV 经生产 AiSpeechClient 发送到真实 gpt-4o-mini-transcribe，再由 AiClient 整理；另在 API 30 模拟器通过 gRPC 注入 PCM，实际 AudioRecord 录音 → 转写 → 整理预览 → 插入，通过；取消和离开编辑器均释放麦克风 |
| 语音网络及配置 | 设备验证 multipart WAV 请求、401、空转写和错误类型的响应拒绝；配置沿用 Keystore 加密 Key，新录音方式默认关闭 |
| 冷启动 | 堆栈定位 WorkManager/Room 初始化阻塞主线程后，已把后台同步初始化移至 IO；后续录音界面流程通过 |
| 候选窗口 | 修复候选触摸窗口在视图未附着时使用空 token 的崩溃，设备中复现触发条件的回归检查通过；0.1.1 最终 APK 再次通过中文候选选择与英文键入 |

最终界面回归的原始结果位于 `build/test-evidence/ui-result.json`，七条完整流程均通过。截图位于同目录的 `polish-preview.png`、`voice-preview.png`、`reply-preview.png` 和 `keyboard-tested.png`；内容全部为专用测试聊天。

0.1.1 最终安装版本的普通键盘证据另见 `build/test-evidence/native-input-result.json`、`native-candidates-final.png` 和 `native-input-final.png`：屏幕点击 nihao 显示中文候选，点击第二候选提交“你好”，切换西文后屏幕点击 hello，输入框最终为“你好hello”。同一安装包的四项 AI 界面流程也重新通过，测试后新日志未见崩溃。

0.1.2 的界面回归覆盖选区整理、系统语音回调预览、修改稿重整、取消编辑、纯手改插入、单帧截图回复和再次授权取消。中英文键入另行在新版安装包进行屏幕点击验证。真实模型及真实语音结果保留自 0.1.1 的同一 AI/音频客户端测试，本次界面更新没有重跑付费模型请求。

本次七项 UI 检查分批执行。首次整套运行的测试进程十分钟窗口耗尽；已延长为二十分钟。重试期间模拟器出现应用启动/触摸分发超时和 System UI 无响应，保留了原始日志与 ANR 堆栈，执行测试安装预编译并重启该模拟器后继续。截图授权返回时键盘可能收起，点击原输入框后展示待处理建议；脚本已覆盖这一操作。取消检查排除了原输入框中已插入的同一句文字，核对输入内容不变、建议弹窗关闭。通过项分别见 `../ui-polish-ui-012.log` 的前五项、`../ui-polish-capture-ui-finished.log` 的截图插入项和 `../ui-polish-cancel-final.log`；本记录不把首次失败运行算为整套通过。上述模拟器经历不能证明其他设备不存在启动或兼容性问题。

## 测试边界

系统识别模式的键盘 UI 流程使用测试服务回调和模拟 HTTPS 响应；云端模式已通过合成语音的真实声学转写及模拟器实际录音、预览插入验证。截图授权与插入 UI 使用模拟服务，截图理解另用真实模型验证。**没有录制真人声音，也没有验证物理手机麦克风、不同口音噪声、厂商识别服务及真实语音端到端延迟分布。** 没有系统识别服务的手机可以选择云端转写。AI 功能需要用户自行配置可用的 HTTPS 接口、模型和 API Key，请在手机设置中填写，不要在聊天中发送 Key。

API 35 与 API 30 模拟器是本次实际运行环境，分别覆盖现代截屏权限流程与真实音频录入链路；Android 8 至其他版本及不同厂商手机仍需兼容性测试。受保护窗口可能无法截屏。当前为可安装开发版，尚未完成应用商店上架、正式签名、桌面端或 iOS 端。

## 复现方法

工具准备与构建见 README_AI.md。额外的设备测试依赖 `python -m pip install adb-shell cryptography`。

1. 运行 `python script/mock_ai_server.py --prepare-only`，生成本机测试 TLS Key 与测试 APK 的公开证书；再构建 `:app:assembleDebugAndroidTest`。
2. 在另一个终端运行 `python script/mock_ai_server.py`。服务仅监听本机 9443，不连接外部 AI。
3. 启动 API 35 的 1080×2400 Pixel 6 模拟器，端口为 5556/5557；启用软件键盘并安装应用与 androidTest APK。`script/device.py` 可直接连接该本地模拟器，不依赖 adb server。
4. 为应用和测试 APK 授予麦克风权限，将模拟器的 `voice_recognition_service` 设置为 `com.rimeai.keyboard.debug.test/com.osfans.trime.ai.TestRecognitionService`。
5. 运行 `python script/device.py --timeout 600 shell "am instrument -w -r -e interactive true com.rimeai.keyboard.debug.test/com.osfans.trime.ai.AiIntegrationInstrumentation"`。等待专用聊天界面与键盘显示。测试进程仅信任测试证书；正式应用仍使用系统 TLS 校验。
6. 第二个终端设置 `$env:RIME_AI_STATUS_FILE='ready'` 后运行 `python script/ui_smoke.py`。此脚本使用该模拟器的固定键盘坐标，不适用于任意尺寸的真机。脚本完成后通知测试进程恢复原配置。
7. 测试结束执行 `python script/device.py shell "settings delete secure voice_recognition_service"`，移除模拟器的测试语音服务设置。

以上测试使用合成文本、合成服务和公开的虚拟 Key，不涉及真实聊天记录或付费请求。

## 真实模型实测

仅下面的单独测试会调用真实服务并消耗其 API 额度；默认构建和模拟测试不会。测试输入为合成文字和专用合成聊天截图，使用 [Chat Completions 接口](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)。

| 样例 | 首段文字 | 完整结果 | 人工核对 |
| --- | --- | --- | --- |
| 中文整理 | 3.386 秒 | 3.628 秒 | 去除口头禅，保留 Alex、1500 元及“还未确定” |
| 英文整理 | 1.731 秒 | 2.524 秒 | 输出仍为英文，保留 Alex、$1,500 及 not confirmed yet |
| 聊天截图 | 不适用 | 5.204 秒 | 最后对方消息为“方便的话请提前发我一下提纲”；三条建议均回应准备或发送提纲 |

这是单次样例结果，包含模拟器和本机转发开销，不是性能分位数或真机端到端语音速度。测试没有覆盖所有语言、聊天布局和服务商，AI 输出仍需用户核对。实际模型生成具有变化性。

完整输入、输出和耗时见 `build/test-evidence/live-ai-result.json`。真实测试先发现英文意外被翻译、截图建议受草稿干扰，已调整生产提示词并重跑以上用例。中英文整理均保留原意、姓名、数值和不确定性；英文金额显示为 `$1,500`，语义未改变。最终截图三条建议为：“好的，我会提前准备好提纲。”“没问题，我会尽快发给你。”“好的，等我整理好再发给你。”

复现：先按前节准备测试证书、构建并安装 APK 和 androidTest APK。在本机终端设置自己的 `OPENAI_API_KEY` 环境变量后运行 `python script/live_ai_relay.py`，再在另一终端运行：

```powershell
python script/device.py --timeout 180 shell "am instrument -w -r -e live true com.rimeai.keyboard.debug.test/com.osfans.trime.ai.AiIntegrationInstrumentation"
```

转发器固定指向 `https://api.openai.com/v1/chat/completions`，固定模型 `gpt-4o-mini`，最多转发三个请求后退出。真实 Key 不进入手机，不写入设置、源码、日志或 APK；仅合成模型输出写入测试证据文件。测试证书信任只发生在测试进程，正式应用使用系统 TLS。该测试不依赖 mock 服务运行，端口为本机 9444。

## 真实音频转写与录音 UI

语音文件由 Windows 本机 TTS 合成，测试 APK 内的 `speech-zh.wav` 和 `speech-en.wav` 为 16 kHz、单声道、PCM16。没有使用真人麦克风，也没有将预设文字当作真实识别回调。

| 输入音频 | 真实转写耗时 | 转写加整理 | 结果 |
| --- | --- | --- | --- |
| 中文，约9.5秒 | 3.479 秒 | 6.114 秒 | 会议时间、提纲、1500元和未确定均保留；开头的合成口头禅“嗯”误识别成“阿伦” |
| 英文，约10.8秒 | 1.854 秒 | 3.616 秒 | 正确识别会议、outline、$1500、not confirmed yet；整理保持英文 |

以上时间从已准备的 WAV 请求开始计量，不含说话时长。语音模型为真实 `gpt-4o-mini-transcribe`，接口格式见 [官方转写文档](https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create)。中文误识别说明云端转写仍需核对，AI 整理会保留识别稿中的姓名，不能假设它能纠正声学错误。

另在 Android 11 / API 30 模拟器中，将相同中文音频以48 kHz立体声 PCM 实时注入模拟麦克风，再由生产 `AiAudioRecorder` 以16 kHz单声道实际采集。完整流程“录音 → 真实转写 → 真实 AI 整理 → 预览 → 点击插入”通过。随后验证取消录音不改文字、重新录音可用、退出输入框后麦克风停止。`cmd appops get ... RECORD_AUDIO` 最终无 running 状态。电脑真实麦克风始终关闭。

证据文件：`build/test-evidence/live-audio-result.json`、`cloud-voice-ui-result.json`、`cloud-voice-preview.png`。其中语音文件的直接请求在 API 35 完成，麦克风注入及 UI 在 API 30 完成。API 35 / Emulator 37.2.13 的默认声卡注入得到静音，切换旧声卡后得到测试正弦波，未将这些失败记为成功；API 30 使用旧声卡后完成实际链路。

复现需安装 `grpcio-tools` 和 API 30 AOSP x86_64 系统镜像，使用1080×2400 Pixel 6配置启动测试模拟器，附加参数 `-grpc 8554 -grpc-use-token -feature -VirtioSndCard`。先按安装说明完成首次存储和输入法配置，授予应用麦克风权限。准备并安装 androidTest APK 后：

1. 在终端本地设置自己的 `OPENAI_API_KEY`，运行 `python script/live_ai_relay.py --audio`。此模式最多转发六个合成请求到真实服务，包括两种语言的转写及整理和一次录音 UI 的转写及整理，会消耗账号额度。
2. 另一个终端运行 `python script/device.py --timeout 360 shell "am instrument -w -r -e audio true com.rimeai.keyboard.debug.test/com.osfans.trime.ai.AiIntegrationInstrumentation"`。
3. 第三个终端运行 `python script/audio_ui_smoke.py`。脚本读取本机模拟器的临时 gRPC 凭据但不输出，关闭电脑麦克风，注入合成音频，检查预览插入、取消和离开输入框释放；结束后通知测试进程恢复原配置。

默认不保存录音文件；`live_ai_relay.py --capture-audio` 只用于合成样例诊断，可将合成的上传 WAV 写入构建证据目录，生产 APK 无此功能。测试 fixture 与测试证书只进入 androidTest APK。

## 0.1.7 会意命名验证

名称统一为会意输入法 / Huiyi Keyboard，标语为“听懂话，看懂意”。更新简体、繁体和英文资源、引导、主页面、AI 设置、后台部署与看图通知，以及源码首页、隐私和安装说明。上游署名和现有应用标识保留。

Android API 35 覆盖安装成功；`script/brand_ui_smoke.py` 验证了两步中文向导、主标题与标语、AI 设置入口与页面、英文与繁体向导，以及系统输入法列表。初次界面检查受部署通知遮挡影响，增加包名过滤并等待通知消失后通过。截图已检查。354 项单元测试通过，构建、Spotless、Lint 通过（0 错误、135 警告）。真实 AI 与音频沿用既有版本证据，本轮界面测试不调用 AI API。

新安装文件名为 `huiyi-android-arm64-debug.apk` 和 `huiyi-android-x86_64-debug.apk`，可直接覆盖旧包。结果见 `huiyi-brand-ui-result.json`，日志见 `验证日志/huiyi-brand-final-build.log` 与 `验证日志/huiyi-brand-ui.log`。

## 0.1.8 可选快捷截屏

Android API 35 完成 8 组设备检查与 3 组实际界面检查：应用内说明及取消、两次独立真实截屏、生产客户端请求本地 HTTPS 模型并返回三条建议、立即取消、关闭开关取消网络分析、实际键盘看图后插入、撤销无障碍权限以及普通截屏授权回退和取消。仅用合成聊天画面，未调用外部 AI，未使用用户附件。354 项单测、构建、Spotless 与 Lint 通过（0 错误、141 警告）。

测试期间模拟器出现服务绑定停滞，停止构建后台并重启模拟器后完成整条流程；测试授权在 instrumentation 进程就绪后设置。最后设备与 UI 检查全部通过，主机结果解析最初误要求原始 instrumentation code 标记，而 stream 模式只输出内容；已修正并对照本轮设备 JSON 和完整日志验证。结果见 `quick-capture-result.json`，图片见 `测试截图/quick-capture-*`，日志见 `验证日志/quick-capture-*`。手机仍需用户手动授权，厂商兼容性及真人设备未验证。

## 0.1.9 AI 文字插入到光标

回复页增加直接插入按钮，编辑确认后返回输入应用，通过标准 InputConnection 提交。光标变化时先确认新的插入位置，提交失败给出复制提示；只输入文字，不发送聊天消息。356 项单元测试通过，新增旧结果与重复选择的插入防护测试。

Android API 35 在独立测试应用中完成四组实际验证：回复插入光标中间且前后文字保留、成功插入不改变剪贴板、光标变化后取消保留原文、确认新光标插入，以及编辑 AI 草稿后只替换原选区。以上合并为四组设备检查，全部通过；不调用模型 API。测试聊天程序新增的广播驱动最初依赖未打入独立测试 APK 的 Kotlin runtime，已将该独立夹具改为纯 Java 后重新通过整条测试。

证据：`cursor-insertion-result.json`、`测试截图/cursor-*`、`验证日志/cursor-insertion-device.log` 与 `验证日志/cursor-insertion-ui.log`。快捷截图的实际 Android 截图证据版本为 0.1.8；真实 DeepSeek 接口证据仍为 0.1.5，本轮只验证插入流程。真机和厂商编辑器兼容性仍待手机验证。

最终源码 Lint 和 Spotless 复核通过（0 错误、141 警告），记录见 `验证日志/cursor-insertion-release-checks.log`。独立测试夹具 Android 32 以下的受签名权限保护广播注册保留兼容写法并局部说明；Android 33 及以上显式设置导出标志。两种 ABI 安装包签名、SHA256、内置默认密钥加密封装及明文密钥排除检查通过；未包含测试广播驱动、合成 Key 或测试私钥。

## 0.1.10 工具栏图标与文字居中

三个 AI 按钮使用同一个横向组合，图标 18dp、间距 4dp，整体在原 80dp 按钮内居中。保留全按钮点击与长按监听、语音按钮底色和主题刷新。Android API 35 实际键盘的 1.0 / 1.3 字体截图已人工检查，无文字截断或相互重叠；长按麦克风图标能打开会意 AI 设置。

构建、Lint（0 错误、141 警告）及 Spotless 通过。此次仅修改低影响布局，未新增或重跑单元测试；356 项单测证据仍来自 0.1.9。此次没有请求 AI 接口。证据见 `toolbar-alignment-result.json`、`测试截图/toolbar-alignment-*.png` 与 `验证日志/toolbar-alignment-build.log`。

## 0.1.11 九宫格与长按功能清理

三个内置主题移除命名功能的长按绑定和提示，保留符号长按。设置增加26键／9键，九宫格独立使用 `rime_ice_t9` 索引并共用雾凇词库。升级只追加方案并备份配置；新增方案触发原生部署，设置切换可重试中断的部署。数字面板返回时恢复此前的中文或英文布局。

358 项单元测试通过（新增两项迁移检查），构建与 Spotless 通过。Android API 35 完成五组原生候选验证：九宫格的“你好、汉语、输入法”及全拼的“汉语、输入法”；删除仅九宫格编译缓存并重新触发迁移后，自动部署及候选验证通过。六组 UI 检查分段完成：设置切9键、九宫格点选“你好”上屏、英文全键盘 hello、数字面板123及返回、密码／数字／普通输入框切换、进程重启保存、切回26键输入 hanyu 并用空格确认“汉语”，以及长按空格不隐藏输入法。部分动作合并为一组，分组见结果 JSON。

保留失败记录：初次原生测试发现继承方案缺少部署前必需的根级方案 ID，已修正；启动线程 ready 早于异步部署完成，测试改为等待部署。模拟器长时间运行出现页面点按丢失、输入连接更新延迟，重启、导航重试及等待实际提交后完成上述验证。另一次26键候选点击出现原生已提交但测试编辑框没有文字，根因未确定，未记为点选上屏成功；26键最终验证采用空格确认，九宫格候选点选已通过。候选点击的偶发问题仍需后续单独排查及真机验证。

结果见 `t9-native-result.json`、`t9-layout-ui-result.json`、`t9-package-result.json`。本轮不调用 AI 接口，真实 AI／语音仍沿用既有版本证据。签名保持不变，两个 ABI 包的九宫格资源、长按绑定清理、测试驱动排除和明文 Key 特征排除检查通过。

最终源码静态复核通过：Lint 0 错误、141 警告，Spotless 通过；见 `验证日志/t9-final-static-checks.log`。手机 ARM64 包已覆盖指定同步盘目录并核对 SHA256，本地复制已确认，云端同步状态未查询。

## 0.1.12 精简数字和符号页

三个内置主题的数字／符号页移除全部次级长按、滑动绑定及键帽上方提示；符号页删除“数字”“迷你”快捷键。轻简／预设主题空格宽度从30%调整为50%，标准主题对应行重新均分，避免删除按键后留下空位。

358 项单元测试通过，构建、Spotless 与 Lint 通过（0 错误、141 警告）。两种 ABI 的打包资源检查通过，三个内置主题的两个页面均无次级功能绑定和被删除的快捷键。Android 11 / API 30 独立测试编辑器完成三组验证：符号页输入12、返回主键盘、数字输入123并退格为12。数字页、符号页及返回截图已检查。本轮不调用 AI 接口。

API 35 模拟器首次测试未绑定输入法，随后出现 System UI 无响应及空白输入区域，未计为成功；改用已有 API 30 模拟器完成本次界面验收。失败日志保留在验证日志目录。雾凇候选与 AI 完整流程沿用此前版本证据，本次不声称重新验证这些流程。

证据：`number-cleanup-ui-result.json`、`number-cleanup-package-result.json`，截图 `测试截图/number-cleanup-*.png`，日志 `验证日志/number-cleanup-build.log` 与 `验证日志/number-cleanup-ui-api30.log`。

## 0.1.13 系统拒绝授权时的操作指引

AI 设置新增“授权被系统拦截？”帮助入口，说明 Android 13 及部分厂商对侧载应用的受限设置机制，提供本应用的应用信息及无障碍设置跳转。授权未连接时提示此入口，首次开启说明也补上拦截后的处理路径。没有修改无障碍服务能力、自动授予权限或更改系统保护策略；具体手机上的放行仍由用户在系统页面确认。

358 项单元测试通过，构建、Spotless 和 Lint 通过（0 错误、141 警告）。API 30 模拟器验证三组实际界面行为：帮助内容及取消不改开关、应用信息链接定位会意输入法、无障碍设置链接打开系统页面而不启用服务。AI 设置保留 FLAG_SECURE，本次使用 UI 层级断言而非截取含配置的页面图片。首次 UI 树未就绪导致测试解析失败，改为等待有效 UI 树后完整通过，失败日志保留。

API 30 不具备 Android 13+ 的这项受限设置流程，本次没有声称复现或解除用户手机的系统拦截；受限设置放行步骤依据 Android 官方说明，具体厂商入口和手机授权结果仍需用户操作确认。本轮无 AI 请求。

证据：`restricted-settings-ui-result.json`、`restricted-settings-package-result.json`；日志见 `验证日志/restricted-settings-*.log`。手机操作见 `快捷看图使用说明.md`。

## 0.1.14 设置首页的 AI 入口

将 AI 设置入口移到设置首页列表，在“常规”上方显示带24dp图标的独立“AI 设置”行，沿用现有列表的行高、文字和主题着色。右上角溢出菜单移除原 AI 设置项。中文简体、繁体和英文均提供对应标题，点击打开原 AI 设置页面。

构建、Spotless 和 Lint 通过（0 错误、141 警告）。本次为低影响入口调整，未新增或重跑单元测试，358 项单测证据仍来自 0.1.13。API 30 模拟器四组实际验证通过：首页行位于常规上方、点击打开 AI 设置、返回首页、溢出菜单不再包含该项。首页和菜单截图已检查，模拟器为英文环境；中文标题已随资源编译。没有调用 AI 接口，也没有重新验证截图授权或模型请求。

证据：`ai-settings-home-ui-result.json`、`测试截图/ai-settings-home.png`、`测试截图/ai-settings-overflow.png`，日志见 `验证日志/ai-settings-home-build.log` 和 `验证日志/ai-settings-home-ui.log`。
