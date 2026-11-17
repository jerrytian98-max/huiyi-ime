# Tokenlink / hy4 接入验证

日期：2026-11-06。开发版：0.1.3-ai。本轮只调用 hy4，没有调用 hy3 或 OpenAI 模型。

默认聊天地址为 `https://tokenlink.woa.com/openapi/v1/chat/completions`，文字整理与看图模型均为 `hy4`。用户提供的 Key 只在电脑测试进程内存中使用，不写源码、安装包、测试报告或模拟器配置。手机需在 AI 设置保存自己的 Key；升级保留已有配置，必要时将地址和两个模型切换为上述值。

## 请求兼容性修复

直接沿用原输入法参数时，中文整理和看图请求出现 `finish_reason=length`、正文为空；流式英文也曾耗时约19秒。Tokenlink 此路由未按标准 `thinking.type=disabled` 关闭思考。实际验证有效的组合是：

```json
{
  "model": "hy4",
  "reasoning_effort": "no_think",
  "chat_template_kwargs": {"reasoning_effort": "no_think"}
}
```

生产 AiClient 在模型为 hy4 时加入上述参数。仍拒绝截断或空结果，不把失败响应当成可以插入的文字；不会把隐藏推理字段显示给用户。

## 实际结果

所有输入均为合成文字和专用测试聊天截图。耗时是单次样例，不是稳定性能指标，不包括录音和语音识别。

| 用例 | 电脑直连接口 | Android 生产 AiClient 经电脑测试转发 |
| --- | --- | --- |
| 中文流式整理 | 首段1.029秒，完成1.381秒 | 首段2.027秒，完成2.358秒 |
| 英文流式整理 | 首段0.937秒，完成1.655秒 | 首段1.172秒，完成1.498秒 |
| 截图理解与三条建议 | 2.556秒 | 4.365秒 |

中文保留 Alex、1500元和未确定的含义；英文保持英文并保留姓名、金额与不确定性。截图三条建议均回应最后一条“提前发提纲”的请求。Android 中文结果仍保留了开头一个“嗯”，表明去口头禅效果并非每次完全一致，插入前仍需核对或编辑。

证据：`build/test-evidence/tokenlink-hy4-host-result.json`（原参数）、`tokenlink-hy4-no-thinking-result.json`（无效参数尝试）、`tokenlink-hy4-native-no-think-result.json`（有效电脑请求）和 `tokenlink-hy4-android-result.json`（Android实际客户端请求）。Android 测试转发仅监听本机9444，最多三次请求，转发至用户提供的原地址后自动退出。模拟器只持有虚拟测试凭据，测试证书不会进入生产 APK。

## 网络与语音边界

电脑可以访问该服务。模拟器使用系统 TLS、以无效虚拟凭据进行直连探测时，遇到 `UnknownHostException` 对应的“无法连接 AI 服务，请检查网络和地址”，尚未完成到服务端的鉴权。这轮成功的 Android 请求通过电脑临时测试转发完成，**不证明手机能直连该地址**。手机需具备能解析并访问该地址的网络；是否需要企业网络或 VPN，应以手机实测为准。生产应用没有内置测试转发。

用户仅提供聊天接口，没有提供语音转写接口。本轮未测试或推断 Tokenlink 的语音转写能力；语音默认继续使用 Android 系统识别。新安装的云端转写地址和模型留空，避免把当前 Key 误发到另一家服务。此前 OpenAI 合成语音结果是0.1.1的独立历史测试。

0.1.3构建、347项单测、Lint、Spotless及两个APK签名验证通过。完整键盘/编辑/截图授权UI七项与屏幕中英文输入的证据来自0.1.2；本轮没有声称重新完成整套真机或UI验收。

## 复现

在源码目录运行 `python script/probe_ai_provider.py --endpoint https://tokenlink.woa.com/openapi/v1/chat/completions --model hy4 --no-think --cases zh en vision`，在本机隐藏输入提示中填写自己的 Key。此命令会实际调用服务并使用额度。

Android 端先构建并安装主APK与测试APK，再运行 `python script/live_ai_relay.py --endpoint https://tokenlink.woa.com/openapi/v1/chat/completions --model hy4 --key-prompt --requests 3`，隐藏输入 Key。启动 API35 模拟器后运行 `python script/device.py --timeout 180 shell "am instrument -w -r -e live true -e liveModel hy4 -e liveProvider Tokenlink com.rimeai.keyboard.debug.test/com.osfans.trime.ai.AiIntegrationInstrumentation"`。仅合成测试数据会发送到服务。

参数参考：[腾讯云深度思考说明](https://cloud.tencent.cn/document/product/1823/131208)、[腾讯混元 Hy4 preview 官方部署示例](https://github.com/Tencent-Hunyuan/Hy4-preview)。Tokenlink 上实际生效的参数以本报告中的实测为准。
