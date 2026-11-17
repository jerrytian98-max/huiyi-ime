<!-- SPDX-FileCopyrightText: 2015 - 2026 Rime community and Huiyi contributors -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# 会意输入法

**看懂屏幕内容，帮你组织语言并直接回复。** 会意输入法是一款面向 Android 的中英文输入法，基于 [Trime](https://github.com/osfans/trime) 和 [Rime](https://rime.im)，加入雾凇拼音、语音转写、AI 文字整理和聊天截图回复等功能。

## 下载

**最新 Android APK：**[会意输入法 v0.1.41（arm64，适用于大多数安卓手机）](https://github.com/jerrytian98-max/huiyi-ime/releases/latest/download/huiyi-android-v0.1.41-arm64-debug.apk)

也可前往[全部版本与更新记录](https://github.com/jerrytian98-max/huiyi-ime/releases)查看 APK、发布日期和校验值。此为 Android 调试签名版本；卸载重装或更换签名版本时，系统可能要求先卸载旧版本，应用内设置也可能随卸载清除。Android 8.0 及以上可安装。桌面端和 iOS 版本目前尚未提供。

## 能做什么

- **中英文输入：**内置雾凇拼音简体方案，提供全键盘和九宫格布局；可切换浅色、深色主题，并支持常用符号及剪贴板输入。
- **按住空格说话：**长按空格开始录音，松开后转写并把文字放到当前输入光标。云端转写需要在 AI 设置里配置服务；录音期间键盘显示音频律动反馈。
- **整理输入框文字：**点击工具栏「整理」，尽量读取当前编辑框里的完整草稿，交给 AI 调整条理和表达，再核对后写回原输入框。
- **看聊天截图并拟回复：**点击「AI回复」截取当前屏幕，由视觉模型理解可见聊天上下文并生成建议。也可以按住按钮向上拖动以尝试连续滚动截图，松手后生成回复。默认直接填入第一条建议；开启「提供多个回复选项」后可从三条建议中选择。输入法不会替你点击发送。
- **快捷截图：**Android 支持时，可在系统授权无障碍服务后免去每次截图确认；授权由用户在系统页面明确开启，可随时关闭。某些应用会遮挡或禁止截图，系统限制无法绕过。
- **记住回复输入位置：**截图前保存输入框和光标状态；返回时尽可能将生成的文字写入原位置。如果目标应用或系统不允许恢复焦点，会提供复制或手动插入的后备方式。
- **整理回复风格：**可在 AI 设置里自定义系统提示词，让聊天建议更符合自己的语气。

## 安装与开始使用

1. 从上面的链接下载 `arm64` APK 并安装。大多数近年的 Android 手机选择此包；`x86_64` 主要用于模拟器。
2. 打开「会意输入法」，按引导在系统设置中启用它，并将其切换为当前键盘。首次启动会在应用专属存储中部署方案和词库。
3. 在输入法设置里选择全键盘或九宫格；进入「AI 设置」，填写服务地址、模型名称和自己的 API Key，然后保存。首次公开版不会附带开发者的 AI 或语音服务凭证。
4. 回到任意可输入文字的应用，使用键盘工具栏上的「语音」「整理」「AI回复」。语音输入需授予麦克风权限；整理和截图回复需要网络以及兼容的 AI 服务。
5. 若使用免重复确认的快捷截图，在「AI 设置」查看说明并前往 Android 系统设置，手动开启会意的无障碍服务。系统首次截图授权仍需按 Android 提示完成。

### AI 服务配置

应用使用 OpenAI 兼容的 Chat Completions 接口。文字整理需要支持聊天补全的模型；截图回复还需要支持图片输入的视觉模型。将服务商提供的**完整 HTTPS 接口地址**、模型名和 API Key 填入 AI 设置。语音可选腾讯云识别或兼容的音频转写接口，按服务商要求填写对应凭证。不同服务商的接口格式、模型能力、费用和数据保留策略不同，请在使用前确认。

API Key 会由 Android Keystore 加密后保存在本应用的专属配置中。请求会把用户提交的文字、录音或屏幕图像发送到所选服务商；请勿在敏感内容上使用不可信服务。快捷截图只在用户主动触发后采集屏幕。

## 项目结构与开发

本项目优先开发 Android 端。桌面端和 iOS 尚未实现。代码主要位于：

- `app/src/main/java/com/osfans/trime/ai/`：AI 请求、语音、截图、回复选择和光标回填。
- `app/src/main/assets/shared/`：Rime 方案、雾凇拼音词库和主题配置。
- `app/src/main/java/com/osfans/trime/ui/main/settings/`：输入法设置页面。
- `script/`：Android 构建、APK 发布和界面回归辅助脚本。

### 构建 Android APK

开发环境需要 JDK 17、Android SDK/NDK、Python 3 和 Git 子模块。克隆仓库并初始化子模块：

```powershell
git clone https://github.com/jerrytian98-max/huiyi-ime.git
cd huiyi-ime
git submodule update --init --recursive
```

在 Windows 配置 `JAVA_HOME`、`ANDROID_HOME` 后构建调试包：

```powershell
./gradlew.bat :app:assembleDebug
```

也可以运行 `script/Build-Android.ps1`。不提供 AI 密钥时，普通中英文输入仍可使用；AI 功能需由安装者自行配置服务。正式发布请使用自己的签名密钥，并妥善保存。

## 许可证与致谢

本项目基于 GPL-3.0-or-later 的 Trime，并包含雾凇拼音及其他采用各自许可的资源，因此项目整体保留 **GPL-3.0-or-later**，不能将整个衍生项目重新标成 MIT。各组件许可见仓库中的 `LICENSE` 文件和 `app/src/main/assets/shared/licenses/`。使用、修改或再分发时请遵守对应组件的许可证及署名要求。

感谢 [Trime](https://github.com/osfans/trime)、[Rime](https://rime.im)、[雾凇拼音](https://github.com/iDvel/rime-ice)、OpenCC 及相关开源社区。
