# 插件兼容性实测（clipboard-filter 插件）

> 时间：2026-10-31 18:13–18:17（Asia/Shanghai）
> 环境：Android 16 x86_64 模拟器；主 APK 为同源码 x86_64 debug 构建（org.fcitx.fcitx5.android.debug）
> 插件：从同一仓库构建的官方 plugin/clipboard-filter（ClearURLs 剪贴板 URL 追踪参数清除），debug 构建

## 1. 签名与安装

- 插件 APK：org.fcitx.fcitx5.android.plugin.clipboard_filter.debug，2,435,371 字节
- App 与插件签名 SHA-256 一致：dd26a87fd...a24c3602（CN=Android Debug）
- adb install Success；小企鹅「插件」页显示 **已加载** / clipboard_filter / "Apply ClearURLs on clipboard"

## 2. 绑定日志（无 SecurityException）

    DataManager: Detected plugin packages: org.fcitx.fcitx5.android.plugin.clipboard_filter.debug
    FcitxPluginServices: Bind to plugin: clipboard_filter
    FcitxPluginServices$PluginServiceConnection: Plugin connected: clipboard_filter

插件服务进程正常启动（Start proc ... MainService）。

## 3. 三种模式下的行为

测试 URL：https://example.com/watch?id=789&utm_source=foo&utm_medium=bar&fbclid=zzz（73 字符）
通过 probe ContentProvider 写入系统剪贴板（设备 shell 中该 URL 已用单引号保护，避免 & 被截断）。

| 模式 | 日志 transformer 状态 | 存储结果 | 系统剪贴板 |
| --- | --- | --- | --- |
| 旧模式（安全剪贴板关） | Clipboard transformers: 1, strictSecureMode=false | clbdb 存 https://example.com/watch?id=789（**插件过滤生效**，追踪参数被清除） | rogue 读取仍为原始 73 字符 URL（旧模式按设计保留系统剪贴板） |
| 安全模式 + 严格（默认） | Clipboard transformers: 1, strictSecureMode=true | secure_clbdb 密文 89 字节 = 73 字符原文 + 16B tag（**未过滤**，transformer 被移除） | rogue 读取 blocked（剪贴板已清空） |
| 安全模式 + 允许插件 | Clipboard transformers: 1, strictSecureMode=false | secure_clbdb 密文 48 字节 = 32 字符过滤后 URL + 16B tag（**过滤后加密**） | rogue 读取 blocked（剪贴板已清空） |

结论：插件在三种模式下均能正常绑定/加载；严格模式仅移除 clipboard transformer hook，其他功能不受影响；用户打开“允许插件”后，插件可继续处理剪贴板文本（加密前）。

## 4. 内置附加组件

模拟器「附加组件」页 16 项全部加载：Android 输入法前端、Android 英文键盘、简繁转换、剪贴板、全角字符、Lua IME API、输入法选择器、Lua 附加组件加载器、Android 弹出提示与通知、拼音、额外拼音功能、标点、快速输入、拼写、码表、Unicode。

## 5. 限制

- 只验证了 clipboard-filter 一个第三方插件；Rime/Anthy/Chewing 等含原生库插件未构建测试；
- 官方渠道插件 APK 使用官方 release 签名，无法绑定到本 debug 包（signature 权限），需用同一密钥自行构建；
- 多插件并存、插件崩溃/重连、插件配置页深度功能未测试。
