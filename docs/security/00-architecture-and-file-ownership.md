# 安全剪贴板（双通道）——实现架构与文件所有权

> 本文描述本仓库的实现架构与文件所有权（不含本机路径与内部协作信息）。
> 上游基线 commit：eb5b50216e70484a009933d9519db76005f1faf3。

## 0. 基线事实（源码确认）

- 应用：`org.fcitx.fcitx5.android`，版本名 0.1.3（`build-logic/.../Versions.kt`），minSdk 23 / targetSdk 36 / compileSdk 36。
- 构建：Gradle 9.6.1、AGP 9.4.1、Kotlin 2.4.10、KSP 2.3.10、Room 2.8.4。
- 现有剪贴板实现：
  - `data/clipboard/ClipboardManager.kt`：单例 + `OnPrimaryClipChangedListener`，把明文 `text` 写入 Room 库 `clbdb`（`data/clipboard/db/*`，version 4，表 `clipboard`，字段含明文 `text`）。
  - 复制监听开关 `AppPrefs.clipboard.clipboardListening`；历史上限 `clipboardHistoryLimit`。
  - `FcitxRemoteService.kt`：第三方插件通过 `IClipboardEntryTransformer`（signature 级 `permission.PLUGIN` + AIDL）注册，`ClipboardManager.transformer` 在**写入数据库前**对明文做转换 → 插件可看到原始剪贴板明文。
  - `input/clipboard/ClipboardWindow.kt` + `ClipboardAdapter.kt`：剪贴板面板，点击 `service.commitText(entry.text)` 直接提交（不写系统剪贴板），长按菜单可 pin/edit/share/delete。
  - `ui/main/ClipboardEditActivity.kt`：编辑记录；"复制"按钮调用 `ClipboardManager.setPrimaryClip()` 写系统剪贴板。
  - `input/bar/KawaiiBarComponent.kt`：复制建议气泡，点击 `commitText(entry.text)`。
  - `input/editing/TextEditingWindow.kt`：文本编辑窗口有剪贴板按钮；`performContextMenuAction(android.R.id.copy)` 让目标 App 复制（会走系统剪贴板，属于系统行为，不由本应用写入）。
- 安全边界：应用**没有** INTERNET 权限（AndroidManifest 中仅 VIBRATE / POST_NOTIFICATIONS + 自定义签名权限）；`android:allowBackup="true"`，有 `data_extraction_rules.xml` / `full_backup_content.xml`；`FcitxRemoteService` 由 signature 级 `permission.IPC` 保护；插件服务由 signature 级 `permission.PLUGIN` 保护；`FcitxDataProvider` 由 `MANAGE_DOCUMENTS` 保护。
- 输入连接：`FcitxInputMethodService` 继承 `LifecycleInputMethodService`，已有 `commitText(text, cursor)` 封装、`currentInputConnection`、`onStartInput/onFinishInput`、敏感输入相关 `CapabilityFlags`（`input/editorinfo`）。新增代码必须复用这些，禁止自建提交路径。

## 1. 目标与模式

- **安全模式总开关** `secureClipboardEnabled`（默认 false）。关闭时保留原有明文剪贴板行为（兼容性），开启后：
  - 剪贴板历史只写入独立的加密库 `secure_clbdb`（AES-256-GCM，Android Keystore 密钥）。
  - 通道 B：普通复制 → 监听系统剪贴板 → 校验/加密/事务提交 → 重新校验系统剪贴板仍是同一快照 → `clearPrimaryClip()`。
  - 通道 A：`ACTION_PROCESS_TEXT` / `ACTION_SEND` / 输入法盾牌按钮读取选中文字 → 加密保存，**全程不写系统剪贴板**。
  - 粘贴：解密后经 `InputConnection.commitText` 直接提交，**不写系统剪贴板**。
- 首次开启安全模式时，把旧明文库 `clbdb` 的记录逐条加密迁移到 `secure_clbdb`，校验条数后清空并 VACUUM 旧表；失败则保留旧数据并报告。
- 模式切换由 `ClipboardManager`（门面）统一管理监听器注册。

## 2. 包与文件（新增）

```
data/secureclipboard/
  SecureClipboardContract.kt          [维护者 已写] 共享模型/接口/常量/状态
  crypto/ClipboardCrypto.kt           [T1] AES-256-GCM（可注入 SecretKey，纯 JVM 可测）
  crypto/ClipboardKeyManager.kt       [T1] Android Keystore 密钥、keyVersion、失效处理
  SecureClipboardDatabase.kt          [T1] Room 加密库 secure_clbdb（v1）
  SecureClipboardDao.kt               [T1]
  SecureClipboardRepositoryImpl.kt    [T1] 校验/加密/事务/解密/限额/迁移/状态
  SecureClipboardMigration.kt         [T1] 旧库读取与迁移（可注入假实现做单测）
  data/secureclipboard/SecureClipboardVault.kt  [维护者] 单例持有仓库 + init/close
input/secureclipboard/
  SelectedTextCaptureController.kt    [T2] 盾牌按钮 → InputConnection.getSelectedText
  SelectedTextPolicy.kt               [T2] 密码框/敏感输入/IME_FLAG 判定（纯 Kotlin）
  SecureClipboardPasteController.kt   [T3] 解密 + 当前会话校验 + commitText
ui/securecapture/
  SecureCaptureActivity.kt            [T2] ACTION_PROCESS_TEXT
  SecureShareReceiverActivity.kt      [T2] ACTION_SEND
  SecureCaptureValidator.kt           [T2] Intent/文本校验（纯 Kotlin 可测）
  SecureUnlockActivity.kt             [T2] 可选设备凭据验证（KeyguardManager）
data/clipboard/
  SecureClipboardController.kt        [T3] 通道 B 监听/状态机/去重/竞态
  ClipboardEventProcessor.kt          [T3] 状态机 + 串行队列 + 快照比较（纯 Kotlin 可测）
ui/main/settings/security/
  SecureClipboardSettingsFragment.kt  [维护者] 设置页
```

## 3. 共享接口（已固定在 Contract.kt，不得改名）

- `CaptureSource`, `CaptureRequest`, `SaveResult`, `SecureClipboardError`, `SecureClipboardRecord`, `StorageStatus`, `LegacyClipboardRow`, `MigrationResult`, `SecureClipboardLimits`, `SecureClipboardEntry`, `SecureClipboardRepository`, `ClipboardSecurityStatus( Holder)`。
- 仓库实现类必须命名为 `SecureClipboardRepositoryImpl(context: Context)`，并实现 `SecureClipboardRepository`；由 `SecureClipboardVault` 初始化。
- 通道 A 的统一入口：`SecureClipboardVault.repository.save(CaptureRequest(...))`。
- 通道 B 的统一入口：`SecureClipboardController`（T3 实现），由 `ClipboardManager` 注册为 `ClipboardManager.OnPrimaryClipChangedListener`。
- 面板/编辑页读取明文：`ClipboardManager.readText(id)`（维护者 在门面实现，内部调仓库 `read`）。

## 4. 文件所有权（禁止改别人的文件）

- **T1 crypto-storage**：`data/secureclipboard/crypto/*`、`SecureClipboardDatabase.kt`、`SecureClipboardDao.kt`、`SecureClipboardRepositoryImpl.kt`、`SecureClipboardMigration.kt`、`app/src/test/java/.../secureclipboard/{ClipboardCryptoTest,SecureClipboardPolicyTest,SecureClipboardMigrationTest}.kt`。
- **T2 capture-entries**：`input/secureclipboard/{SelectedTextCaptureController,SelectedTextPolicy}.kt`、`ui/securecapture/*`、`res/layout/activity_secure_capture.xml`；允许**仅追加**修改 `input/bar/ui/idle/ButtonsBarUi.kt`、`input/bar/KawaiiBarComponent.kt`（见 T2 任务书）。
- **T3 clipboard-runtime**：`data/clipboard/{SecureClipboardController,ClipboardEventProcessor}.kt`、`input/secureclipboard/{SecureClipboardPasteController,PasteSession}.kt`；允许修改 `input/clipboard/ClipboardWindow.kt`、`ClipboardAdapter.kt`、`ClipboardEntryUi.kt`、`ui/main/ClipboardEditActivity.kt`；测试 `app/src/test/java/.../secureclipboard/ClipboardEventProcessorTest.kt`。
- **T4 test-harness**：`tools/clipboard-probe/**`（独立测试 App 模块）、`app/src/androidTest/**`、`settings.gradle.kts` 增加 `:tools:clipboard-probe` 一行、`docs/security/07-instrumentation-tests.md`。
- **T5 audit-docs**：`docs/security/{01..06}*.md`（只写文档，不改源码）。
- **维护者**：Contract.kt、`data/secureclipboard/SecureClipboardVault.kt`、`data/clipboard/ClipboardManager.kt`、`data/clipboard/db/ClipboardEntry.kt`、`data/prefs/AppPrefs.kt`、`FcitxApplication.kt`、`FcitxRemoteService.kt`、`AndroidManifest.xml`、`res/values*/strings.xml`、`res/drawable/ic_shield_24.xml`、`ui/main/settings/**`、`ui/main/MainFragment.kt`、`ui/main/settings/SettingsRoute.kt`、`core/Fcitx.kt`、`ui/main/DeveloperFragment.kt`、`res/xml/data_extraction_rules.xml`、`res/xml/full_backup_content.xml`、构建与最终集成。

## 5. 编码与安全规则

1. 所有新文件带 LGPL-2.1-or-later SPDX 头（与仓库现有一致）。
2. **禁止**打印/日志明文、IV、密钥、AAD；异常信息不得包含明文（只允许长度、错误类型）。
3. 加解密一律 AES-256-GCM：96-bit IV 每次随机（SecureRandom）、128-bit tag、AAD 绑定元数据。
4. 不允许新增任何依赖；不允许新增 INTERNET 权限；不写 SharedPreferences 明文。
5. 明文只在内存短暂存在：提交后不再持有；列表展示用遮罩/元数据，不做全量解密缓存。
6. 所有外部入口（PROCESS_TEXT / SEND）默认弹出确认，限制长度，校验 Intent；外部调用不得直接改库。
7. `commitText` 必须走 `FcitxInputMethodService.commitText` 或等价 `currentInputConnection` 封装；粘贴前核对输入会话（`EditorInfo`/InputConnection 有效性）。
8. 新增代码不应修改 Fcitx5/libime 原生引擎；不动现有输入法功能。
9. 注释/日志用英文；用户可见字符串由 维护者 统一加入 `strings.xml` 与 `values-zh-rCN/strings.xml`，代码里只引用 `R.string.xxx`。
10. 不要运行 Gradle（维护者 统一构建），不要改动 `build.gradle.kts`（除 T4 的 settings.gradle.kts 一行）。

## 6. 验收命令（维护者 执行）

```
.\\gradlew.bat :app:compileDebugKotlin "-PndkVersion=28.2.13676358" "-PcmakeVersion=3.22.1" "-PbuildTools=36.0.0" "-PbuildABI=arm64-v8a"
.\\gradlew.bat :app:testDebugUnitTest ...
```

## 7. 已知系统限制（必须在报告中如实说明）

- Android 公开 API 无"比较剪贴板版本并原子清空"，TOCTOU 竞态窗口无法完全消除。
- 普通复制依赖输入法进程存活；进程被杀/未解锁/后台限制时无法转存，不能承诺常驻。
- 安全摘录绕过系统剪贴板，但来源 App 和目标 App 必然接触明文；不是端到端加密。
- 已删除明文在 SSD/UFS 上可能有物理残留，无法保证立即擦除。
- 插件为 signature 级权限体系，但严格模式仍需主动阻断 transformer 明文通路。
