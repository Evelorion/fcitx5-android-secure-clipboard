# Fcitx5 Android 隐私优先双通道安全剪贴板 —— 交付索引

> 上游基线 commit：eb5b50216e70484a009933d9519db76005f1faf3。
> 上游项目：https://github.com/fcitx5-android/fcitx5-android
> 本目录所有文档均为本次工程改造的实际交付物；**未跑真机/模拟器的项目一律标注“未验证”**，不宣称已通过。

## 实施状态一览

| 需求 | 实现 | 证据 |
| --- | --- | --- |
| 安全摘录（ACTION_PROCESS_TEXT，零系统剪贴板） | ✅ 已实现 | `ui/securecapture/SecureCaptureActivity.kt`、`BaseSecureCaptureActivity.kt` |
| 输入法读取选中文字（盾牌按钮） | ✅ 已实现 | `input/secureclipboard/SelectedTextCaptureController.kt`、`input/bar/KawaiiBarComponent.kt` |
| ACTION_SEND 安全分享接收 | ✅ 已实现 | `ui/securecapture/SecureShareReceiverActivity.kt` |
| 普通复制自动加密 + 成功后立即清空 | ✅ 已实现（真机未验证） | `data/clipboard/SecureClipboardController.kt`、`data/secureclipboard/ClipboardEventProcessor.kt` |
| AES-256-GCM + Android Keystore | ✅ 已实现 | `data/secureclipboard/crypto/*` |
| 加密 Room 库（无明文列）+ 旧明文迁移 | ✅ 已实现 | `SecureClipboardDatabase.kt`、`SecureClipboardMigration.kt`、`ClipboardManager.migrateLegacyIfNeeded` |
| 私有粘贴（InputConnection.commitText，不写剪贴板） | ✅ 已实现 | `input/secureclipboard/SecureClipboardPasteController.kt` |
| 插件明文隔离（严格模式） | ✅ 已实现 | `FcitxRemoteService.kt`、`core/Fcitx.kt` |
| 中文设置界面（隐私与安全 → 安全剪贴板） | ✅ 已实现 | `ui/main/settings/security/SecureClipboardSettingsFragment.kt`、`values-zh-rCN/strings.xml` |
| 独立测试 App（**仅测试用，不是功能交付**） | ✅ 可构建 APK，零权限 | `tools/clipboard-probe/`（需求 12.2 要求的独立测试 App） |
| 隐私历史搜索 / 记录导出 | ✅ 已实现：App 内“隐私历史”页支持按需本地解密搜索；导出/导入为 PBKDF2(210k)+AES-256-GCM 口令加密备份 | [09](09-rogue-app-red-team.md)、`SecureHistoryActivity.kt`、`SecureClipboardExportCrypto.kt` |
| 自动化测试 | ✅ 81 个安全剪贴板 JVM 单测全部通过（含 20 个加密备份测试）；更早全量运行中 1 个上游既有失败；instrumented 源码编译通过、未执行 | `app/src/test`、`app/src/androidTest`、08 |
| 真实构建 | ✅ `:app:assembleDebug` 成功产出 arm64-v8a debug APK（69,819,756 字节，无 INTERNET 权限） | 08 |
| 模拟器功能实测 | ✅ Android 16 x86_64：旧模式可读剪贴板；安全模式加密落库+立即清空；后台默认输入法可转存；安全摘录不写系统剪贴板；搜索/加密备份导出导入；迁移清空旧明文 | [evidence](evidence/emulator-functional-test-evidence.md)、08 §9–11 |
| 红队“流氓 App”实测 | ✅ 零权限攻击 App：私有库/IPC/Provider/日志/外部目录/伪造入口均被拦截；安全模式下前台读剪贴板为空；旧模式对照可读（平台行为） | [09](09-rogue-app-red-team.md)、`tools/rogue-clipboard-app/` |
| OxygenOS/ColorOS/真机性能 | ⚠️ 未验证（模拟器≠真机，无 OEM 设备） | 见 04、05 |

## 文档

| 文件 | 内容 |
| --- | --- |
| [00-architecture-and-file-ownership.md](00-architecture-and-file-ownership.md) | 实现架构、文件所有权、编码规则 |
| [01-source-audit.md](01-source-audit.md) | 真实源码安全审计（剪贴板全链路、插件机制、日志/备份/导出、进程生命周期） |
| [02-threat-model.md](02-threat-model.md) | 资产、攻击者、信任边界、STRIDE、需求→控制措施 |
| [03-dataflow-privacy-report.md](03-dataflow-privacy-report.md) | 需求 10.1 A–L 场景逐项隐私分析 + 诚实边界 |
| [04-performance-battery-evaluation.md](04-performance-battery-evaluation.md) | 性能/功耗评估方法、指标、JVM 参考数据、真机待测项 |
| [05-compatibility-notes.md](05-compatibility-notes.md) | Android 14/15/16、OxygenOS/ColorOS 兼容性与系统限制 |
| [06-unresolved-risks-and-acceptance.md](06-unresolved-risks-and-acceptance.md) | 需求 17 验收逐条状态、未解决风险 R1–R22 |
| [07-test-plan-and-instrumentation.md](07-test-plan-and-instrumentation.md) | 测试矩阵、instrumented 测试、adb 手工验证步骤 |
| [08-build-and-test-report.md](08-build-and-test-report.md) | 真实构建与测试结果（Gradle 输出、APK、单测统计、模拟器功能实测） |
| [09-rogue-app-red-team.md](09-rogue-app-red-team.md) | 隐私历史搜索/加密备份实测 + 流氓 App 红队 10 项攻击结果与残余风险 |
| [evidence/](evidence/emulator-functional-test-evidence.md) | 模拟器功能实测证据（命令、DB 行、剪贴板状态、搜索/导出、红队结果、截图） |

## 实际构建命令（本机已验证）

前置：Android SDK（platform 36 / build-tools 36.0.0）、NDK 28.0.13004108、CMake 3.31.6、JDK 17。
原生构建在 Windows 上还需要 **ECM**（KDE Extra CMake Modules）与 **gettext** 工具。本机用最小化 ECM 包 + gettext 静态发行版解决；Linux/macOS 上装系统包即可。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ECM_DIR='<本地 ECM 安装>\share\ECM\cmake'
$env:PATH="<本地 gettext>\bin;" + $env:PATH
Set-Location '<本仓库根目录>'

# 1) Kotlin 编译
.\gradlew.bat :app:compileDebugKotlin --console=plain -PbuildABI=arm64-v8a -PbuildTools=36.0.0
# 2) JVM 单测
.\gradlew.bat :app:testDebugUnitTest --console=plain -PbuildABI=arm64-v8a -PbuildTools=36.0.0
# 3) instrumented 测试编译（运行需要设备）
.\gradlew.bat :app:compileDebugAndroidTestKotlin --console=plain -PbuildABI=arm64-v8a -PbuildTools=36.0.0
# 4) 独立测试 App
.\gradlew.bat :tools:clipboard-probe:assembleDebug --console=plain -PbuildABI=arm64-v8a -PbuildTools=36.0.0
# 5) 完整 APK（含原生库，单 ABI）
.\gradlew.bat :app:assembleDebug --console=plain -PbuildABI=arm64-v8a -PbuildTools=36.0.0
```

产物（本机已实际构建，另复制到 `<本地发布目录>\`）：
- 主 APK：`app/build/outputs/apk/debug/org.fcitx.fcitx5.android-eb5b502-arm64-v8a-debug.apk`（69,819,756 字节，SHA-256 `D7C880B1…0ADE86B`）；模拟器功能实测使用同源码的 x86_64 构建
- 测试工具（**仅测试用，不属于功能交付**）：`tools/clipboard-probe/`（约 940KB 零权限）与 `tools/rogue-clipboard-app/`（982KB 零权限红队 App）；两者均不参与 :app 依赖

> 构建成功 + 模拟器功能/红队实测 ≠ 真机验证：instrumented 自动化、真机/OEM（OxygenOS/ColorOS）与性能功耗仍未验证，详见 [08](08-build-and-test-report.md) 第 6、9–11 节、[09](09-rogue-app-red-team.md) 与 [evidence](evidence/emulator-functional-test-evidence.md)。
