<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# 快捷AI回复：授权后直接截屏

0.1.8-ai 增加可选的快捷AI回复，支持 Android 11 及以上。默认仍使用普通系统截屏。快捷模式利用安卓无障碍服务的单帧 `takeScreenshot` 接口，不复用已结束的 MediaProjection 会话，也不需要 Root。

## 手机开启步骤

1. 覆盖安装新版会意输入法，在设置首页点击“AI 设置”（也可长按键盘上的“AI回复”）。
2. 打开“快捷AI回复（授权后免重复确认）”，阅读截屏与 AI 上传说明，点击“同意并去授权”。
3. 在系统无障碍设置中的已安装服务/下载的应用里，手动启用“会意快捷截屏”，完成系统确认。
4. 返回聊天输入框，点击“AI回复”。服务保持授权且应用开关打开时，直接截取一张当前屏幕，生成最多三条回复建议，由你选择插入；不会自动发送聊天消息。

系统服务未启用、权限被撤销或快捷模式关闭时，自动改用普通截屏授权。Android 10 及以下的系统服务组件禁用，仅提供普通模式。部分厂商可能限制侧载应用启用无障碍服务或回收后台服务，以手机系统实际提示为准。

## 系统拒绝授权或受限设置

如果系统提示“系统已拒绝向此应用授予访问权限”或“受限制的设置”，先关闭该提示，再按下面步骤操作：

1. 长按会意输入法图标，进入**应用信息**；也可在 0.1.13 起的 AI 设置中点击“授权被系统拦截？”→“应用信息”。
2. 点击应用信息右上角“⋮”，选择**允许受限制的设置**／**允许受限设置**，按系统提示验证身份。
3. 返回**无障碍 → 已安装的服务／下载的应用**，手动开启“会意快捷截屏”。
4. 回到会意 AI 设置，确认显示“快捷截屏已授权”。

这里的应用信息指的是“会意输入法”的应用详情，不是无障碍服务列表的菜单。入口名称随手机品牌和系统版本变化。若没有该选项，可能还存在厂商、设备管理或高级保护策略限制，需要根据手机型号确认；可先关闭快捷AI回复并使用普通截屏。应用不能自行解除系统限制，也不会自动授予权限。

依据：[Google Android 官方受限设置说明](https://support.google.com/android/answer/12623953?hl=zh-Hans)。该说明主要适用于 Android 13 及更高版本，厂商系统可能另有调整。

## 截屏与取消规则

截图发送到当前配置的 AI 服务，可能包含整个屏幕上的聊天、姓名、头像、通知等个人信息。短按采集一帧；上拖后松手会在前台聊天区域模拟手指下拉，最多自动截取五屏并拼接。点击停止可取消，不读取控件文字，不在后台采集。

系统服务只声明 `canTakeScreenshot` 能力，不声明事件类型、读取窗口内容或手势操作能力；`isAccessibilityTool=false`，没有把它声明为帮助残障人士的辅助工具。服务连接或系统事件不会自行触发截图。

关闭应用内快捷开关会取消正在进行的快捷分析并阻止下一次快捷截图；权限本身可在系统无障碍设置中撤销。AI回复进度页和键盘进度对话框提供取消入口。取消无法撤回服务商已经收到的请求，但不会再将迟到结果作为本次建议显示。锁屏时不发起快捷截屏，受保护内容由系统遮蔽或拒绝采集。

截图与结果只在内存中处理，继续沿用生产 AiClient 和既有设备 Key 加密保存机制。没有新增外部图片存储或独立服务器。

## 官方接口依据

- [AccessibilityService.takeScreenshot：Android 11/API 30 起提供](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))
- [普通 MediaProjection 每个新会话都需要用户同意](https://developer.android.com/media/grow/media-projection#user-consent)

## 测试入口

`script/quick_capture_ui_smoke.py` 配合 `AiIntegrationInstrumentation -e quick true`，使用本地模拟 HTTPS 和合成聊天画面验证。测试脚本在本机模拟器授予并恢复无障碍设置，实际手机仍须由用户按上述步骤授权。测试不调用真实 AI 服务，不使用用户提供的聊天截图。
