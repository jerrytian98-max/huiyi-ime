<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# 会意输入法

中文名称：会意输入法。英文名称：Huiyi Keyboard。标语：听懂话，看懂意。

0.1.7-ai 将应用、桌面入口、Android 输入法列表、初次使用向导、主页面、AI 设置、部署与截屏通知统一为会意名称。内测包显示“会意输入法（内测）”；英文及繁体资源同步更新。安装包使用 `huiyi-android-arm64-debug.apk` 和 `huiyi-android-x86_64-debug.apk`。

保留 `com.rimeai.keyboard.debug` 包名、原签名、组件名、存储路径、配置键和加密协议，以便直接覆盖安装并沿用原有配置、词库及设备 Keystore 数据。默认使用雾凇拼音、应用专属存储，以及已配置的 DeepSeek 服务。

Trime、Rime、OpenCC 和雾凇拼音是上游项目名称，其署名、来源链接和许可证继续保留。源码目录 `trime-ai` 和主题标识 `rime_ai` 属于现有工程与配置标识。

运行界面检查：`python script/brand_ui_smoke.py`。该脚本检查简体、英文和繁体引导文案、主页面名称和标语、AI 设置入口与页面，以及 Android 系统输入法列表；不调用 AI 接口。截图和结果位于 `build/test-evidence/huiyi-*`。
