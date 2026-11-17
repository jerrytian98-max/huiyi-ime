# Android 首版验收核对

核对日期：2026-11-06。交付版本为 0.1.5-ai，使用开发调试签名。验收范围是本轮 Android 首版和可执行测试，不包含正式上架及所有厂商真机覆盖。

| 要求 | 当前证据 | 结论 |
| --- | --- | --- |
| 基于 Trime/Rime 的安卓输入法 | Trime 源码、Rime native 构建；独立包名 `com.rimeai.keyboard.debug` | 已实现 |
| 中文和英文键入 | API 35 模拟器中安装、向导、启用、拼音候选与英文键入验证；`build/test-evidence/native-input-result.json`；最终0.1.4雾凇及轻简·简体主题中，屏幕点击 hanyu、shurufa 并选择简体候选，切换英文后键入 hello，得到“汉语输入法hello” | 已验证模拟器 |
| 语音转文字后 AI 整理、预览插入 | 系统 SpeechRecognizer 绑定测试 RecognitionService，临时/最终回调、AI 预览、插入原文；`build/test-evidence/ui-result.json`；真实模型另行验证转写文本整理 | 系统服务链路已验证；另通过云端模式验证实际 AudioRecord、真实转写、整理、预览插入及取消释放 |
| 中英文 AI 文字整理 | Android 生产 AiClient 调用真实 gpt-4o-mini；完整中英文输出保留语言、姓名、数值和不确定性；`build/test-evidence/live-ai-result.json` | 已验证合成样例 |
| 点击后系统授权单帧截屏 | 完整 UI 流程中的 MediaProjection 整屏授权、前台服务、JPEG、采集停止；下一次重新授权、取消不显示旧建议 | 已验证 API 35 |
| AI 理解聊天并生成回复建议 | 同一客户端发送测试聊天截图到真实模型，三条回复针对最后一条“发提纲”请求；生产提示词明确忽略草稿与键盘 | 已验证合成样例 |
| 用户选择建议后插入 | 模拟 HTTPS 端到端 UI 测试选择建议并核对输入框；目标变化时复制及生命周期限制的代码实现 | 插入流程已验证；模型内容仍需用户核对 |
| 微信输入法方向的外观 | “轻简”主题、白色圆角键、绿色主要操作、细线图标、统一 AI 页面；实际模拟器截图 | 已实现 |
| 整理稿编辑与重整 | 原文核对、手动修改、当前稿重新整理、确认替换与取消；七项 AI UI 流程及 review 状态单测 | 已验证模拟器 |
| 可安装 APK | `../交付/` 内 ARM64、x86_64 开发版；两包签名校验成功，x86_64 更新安装成功；`SHA256SUMS.txt` | 已交付 |
| 可执行验证 | 351 项单元测试，0 失败、0 错误；Lint、Spotless、构建成功；模拟测试及真实模型测试脚本可复现 | 已交付 |
| 安装配置文档与源码 | `README_AI.md`、`ANDROID_VALIDATION.md`、`PRIVACY.md`，交付目录中文文档及本工作区对应源码 | 已交付 |

云端语音已经使用中英文合成音频调用真实识别模型，且 API 30 中的真实 AudioRecord 采集注入音频并完成全部 UI 流程；见 `build/test-evidence/live-audio-result.json` 和 `cloud-voice-ui-result.json`。API 35 的系统模式仍使用测试回调，二者没有混淆。物理手机麦克风、真人口音和不同厂商兼容性尚未覆盖；用户本轮选择继续完善模拟器可验证部分。桌面端和 iOS 端不属于本轮安卓首版交付。

真实模型输出并非确定性结果。记录中的速度是合成输入的单次测量，不能证明真实语音端到端速度或所有聊天布局的理解质量。详见 [验证记录](ANDROID_VALIDATION.md)。

0.1.1 的真实云端语音和真实模型测试结果保留为已有证据；本次 0.1.2 主要调整界面、主题和修改稿编辑，重新完成构建、347 项单测、Lint、Spotless、APK 签名与新版模拟器 UI 回归。生产 APK 不包含测试证书、测试音频、测试图片或编辑测试驱动。

0.1.3 新增 Tokenlink / hy4 默认配置及快速模式参数，中英文整理和截图回复经过当前 Android AiClient 真实请求验证。模拟器直连域名解析失败，成功请求经电脑临时测试转发；手机直连仍待验证。完整七项 UI 与普通按键输入的记录仍为0.1.2；详见 [TOKENLINK_HY4_VALIDATION.md](TOKENLINK_HY4_VALIDATION.md)。

0.1.4集成固定版本雾凇拼音并设为默认方案，三个内置主题按钮统一为简体。新增4项配置迁移测试，当前351项单测通过；真实 native 引擎的简体候选、中英混输、日期和计算器扩展通过。完整词库首次编译耗时较长，调试热路径已优化。此前 hy4 与 AI 界面流程记录分别保留0.1.3和0.1.2的范围。见 [RIME_ICE_INTEGRATION.md](RIME_ICE_INTEGRATION.md)。

0.1.5按用户要求加密附带DeepSeek内测默认Key，默认模型为deepseek-flash（当前DeepSeek V4.1 Flash）。354项单测通过，API35直连官方接口完成中英文整理和截图回复；加密默认导入、清空保留、自定义配置和地址绑定通过。分发封装可逆向提取，详见 [DEEPSEEK_DEFAULTS.md](DEEPSEEK_DEFAULTS.md)。
