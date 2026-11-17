# DeepSeek 默认配置与验证

版本：0.1.5-ai，含用户授权的共享默认 Key，仅用于内测。

默认地址为 `https://api.deepseek.com/chat/completions`，文字和看图模型均为 `deepseek-flash`。官方说明此名称当前对应 DeepSeek V4.1 Flash（V4.1-Flash-1017），支持原生图片理解。短文本和截图回复使用 `thinking: {type: disabled}`。语音仍默认使用系统识别，没有推断 DeepSeek ASR 地址。

来源：[官方首次调用说明](https://api-docs.deepseek.com/quick_start/)、[视觉理解](https://api-docs.deepseek.com/guides/vision/)、[思考开关](https://api-docs.deepseek.com/guides/thinking_mode/)。

## 默认 Key 的存储边界

用户明确要求将提供的 Key 加密预填入程序。内测构建使用 AES-256-GCM 封装，由忽略版本控制的 `local-ai-defaults.properties` 提供构建输入。源码、APK和测试报告中均未发现该 Key 的明文。

**APK同时包含恢复默认Key所需的材料，这种分发封装不能抵抗客户端逆向提取，不等于服务端保密。** 持有内测APK的人可能提取并使用共享Key。正式公开分发应改为用户自行配置Key或服务端托管。

首次读取配置时，默认Key自动导入，由当前设备的Android Keystore AES-GCM重新加密保存。新装不需要再填写。旧版默认Tokenlink配置会先保存本机加密备份再切换；未填Key的旧版OpenAI示例配置也会切换。其他自定义服务保留，可在设置点击“恢复默认DeepSeek配置”后保存。

清空Key并保存后不会自动重新填入，可以通过恢复默认按钮恢复。Key输入框使用密码显示，禁止视图状态保存和自动填充，设置窗口禁止截屏。内置Key仅允许用于DeepSeek官方HTTPS聊天地址，其他主机、端口或非聊天路径被拒绝；仍拒绝HTTP重定向。

卸载会删除本机私有配置和设备Keystore Key，不会吊销服务端共享Key；重新安装此内测包会再次提供默认Key。

## 验证结果

354项单元测试，0失败、0错误；包括跨Python/Android加密向量、篡改/错误包装密钥拒绝和官方地址绑定。构建、Lint、Spotless与两个APK签名验证通过。

Android API35使用生产AiClient和设备系统TLS直接调用官方接口，未使用电脑转发。额外验证了自动导入、Keystore读写、配置无明文Key、清空后不自动恢复、保留自定义配置与跨地址绑定拒绝。报告不包含Key或可恢复默认Key的封装材料。

| 合成样例 | 首段正文 | 完整结果 |
| --- | --- | --- |
| 中文整理 | 1.299秒 | 1.470秒 |
| 英文整理 | 0.716秒 | 0.871秒 |
| 聊天截图三条建议 | 不适用 | 1.492秒 |

中文保留Alex、1500元和未确定的含义，去除了口头禅；英文保持英文并保留姓名、金额和不确定性。三条截图建议回应最后的“提前发提纲”请求。每项为单次测量，不包括录音、语音识别和真机延迟分布。

证据：`build/test-evidence/deepseek-flash-host-result.json`、`deepseek-bundled-android-result.json`和`../deepseek-android-bundled.log`。电脑和安卓各调用三次真实模型请求，均只使用本轮提供的DeepSeek Key与合成输入。雾凇、简体主题和键盘UI沿用0.1.4的验证，本轮未声称重新完成全部真机和麦克风测试。

## 构建复现

不提供本地加密输入文件构建时，仍默认DeepSeek地址和模型，但不附带共享Key。生成自己的内测默认配置：

```powershell
python script/probe_ai_provider.py --endpoint https://api.deepseek.com/chat/completions --model deepseek-flash --prepare-bundled-default --prepare-only
.\script\Build-Android.ps1
```

在隐藏提示中输入自己的Key。准备命令只生成加密构建输入，不调用模型。真实测试时移除`--prepare-only`并添加`--disable-thinking --cases zh en vision`。不要提交本地封装文件或公开分发含共享Key的构建。
