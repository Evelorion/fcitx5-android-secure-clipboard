# 模拟器功能实测证据（Fcitx5 Android 输入法内部实现）

> 测试对象：本次改造后的 **Fcitx5 Android（包名 `org.fcitx.fcitx5.android.debug`，debug 构建）**。
> 目的：证明需求中的安全剪贴板功能**全部位于小企鹅输入法 App 内部**，并验证改造后输入法仍能正常获取系统剪贴板。
> 设备：Android Studio AVD `Pixel_9_Pro_XL`，Android 16（API 36），x86_64；使用同一源码构建的 x86_64 debug APK（功能与 arm64 APK 一致）。
> 时间：2026-10-31 17:0x–17:2x（Asia/Shanghai）。
> 说明：测试文本均为合成字符串；真机/OxygenOS/ColorOS 仍未验证。

## 0. 测试准备

```bash
adb install -r app-...-x86_64-debug.apk
adb shell ime enable org.fcitx.fcitx5.android.debug/org.fcitx.fcitx5.android.input.FcitxInputMethodService
adb shell ime set   org.fcitx.fcitx5.android.debug/org.fcitx.fcitx5.android.input.FcitxInputMethodService
# 用小企鹅输入法内置设置开启"安全剪贴板"（也可直接改其私有 prefs，本测试为了自动化这样做）
```

## 1. 功能位置证明：设置页在小企鹅输入法内部

在主界面进入「隐私与安全」页（App 内导航，包名 `org.fcitx.fcitx5.android.debug`），页面标题为「隐私与安全」，包含：

- 启用安全剪贴板（已开）
- 普通复制保护（已开）
- 保存成功后立即清空系统剪贴板（已开）
- 隐藏安全历史内容（已开）
- 允许第三方插件处理剪贴板（默认关 = 严格模式）
- 兼容模式、粘贴前要求设备验证、安全历史上限 1000
- 保护状态区：加密存储=正常、密钥=正常、最近一次自动转存/清空

截图：`emulator-fcitx5-privacy-security-settings.png`（本目录）。

## 2. 通道 B（普通复制自动加密）实测

### 2.1 旧模式（安全剪贴板关闭，默认行为）

1. 用测试工具向系统剪贴板写入 `LEGACY_TEST_12345`；
2. 小企鹅输入法进程监听系统剪贴板；
3. 读取其私有数据库 `databases/clbdb`（Room 明文库，改造前的默认路径）：

```
rows: 1
(1, 0, 1793437494859, 'text/plain', 0, 0) text= 'LEGACY_TEST_12345'
```

**结论：改造后小企鹅输入法仍然可以正常读取系统剪贴板（旧模式行为保留）。**
同时系统剪贴板内容保持原样（旧模式不清空，符合"兼容/不误删"设计）。

### 2.2 安全模式（安全剪贴板开启）

开启后，App 首次启动时把上面的旧明文记录迁移进加密库并清空旧明文表：

```
secure rows: 1  (sourceType=system_clipboard, ciphertext=33B, IV=12B)
legacy rows（迁移后）: []
secure_clbdb 明文扫描: b"LEGACY_TEST_12345" 不存在
```

再次复制 `SECURE_TEST_67890`（App 在前台）：

```
secure rows: 2（新增 system_clipboard，ciphertext=33B, IV=12B）
系统剪贴板读取: accessible=false, item_count=0   ← 加密保存成功后已被立即清空
secure_clbdb 明文扫描: 测试明文不存在
```

**App 在后台、但仍是默认输入法时**复制 `BACKGROUND_IME_TEST_XYZ`（桌面在前台）：

```
secure rows: 5（新增 system_clipboard，ciphertext=39B = 23 字符 + 16B tag，IV=12B）
系统剪贴板读取: accessible=false, item_count=0   ← 已清空
```

**结论：安全模式下，普通复制会被小企鹅输入法读取、AES-256-GCM 加密落库，成功后立即清空系统剪贴板；即使输入法在后台（默认输入法）也能工作。**

## 3. 通道 A（安全摘录，不经过系统剪贴板）实测

用 `ACTION_PROCESS_TEXT` 明文启动小企鹅输入法的 `SecureCaptureActivity`（等价于用户在文字选择菜单点击"安全摘录"）：

```bash
adb shell am start -a android.intent.action.PROCESS_TEXT -t text/plain \
  --es android.intent.extra.PROCESS_TEXT "CHANNEL_A_SECRET_V2" \
  -n org.fcitx.fcitx5.android.debug/org.fcitx.fcitx5.android.ui.securecapture.SecureCaptureActivity
```

UI 层级（uiautomator）显示的确认页内容：

```
Secure capture
Save selected text to the encrypted vault?
••••••••••••••••••••            ← 只显示遮罩，不显示明文
Source: PROCESS_TEXT · com.android.shell
19 characters
Cancel | Save encrypted
```

截图 `emulator-secure-capture-dialog-flag-secure.png` 中该对话框区域为**全黑**：因为 Activity 设置了 `FLAG_SECURE`，系统截屏被正确阻止（这是预期安全行为，也证明功能在输入法 App 内）。

点击「Save encrypted」后：

```
secure rows: 4
(4, 'process_text', 1793438048373, ciphertext=35B = 19 字符 + 16B tag, IV=12B)
当前前台: 回到小企鹅输入法 MainActivity（RESULT_OK 且不回传原文）
系统剪贴板: accessible=false, item_count=0   ← 全程未使用系统剪贴板
secure_clbdb 明文扫描: b"CHANNEL_A_SECRET_V2" 不存在
```

**结论：安全摘录功能在小企鹅输入法内部完成确认、加密、落库，全程未写系统剪贴板，也不把原文返回给来源 App。**

## 4. 测试中发现并修复的真实缺陷

测试安全模式迁移时发现：迁移已成功写入加密库，但旧明文表未被清空。排查日志：

```
W [main] ClipboardManager: Cannot enable secure_delete on legacy clipboard db: SQLiteException
W [...worker-1] ClipboardManager$migrateLegacyIfNeeded: Legacy plaintext table wipe incomplete: SQLiteException
```

原因：`SQLiteDatabase.execSQL()` 不能执行会返回结果行的 PRAGMA（`PRAGMA secure_delete=ON`、`PRAGMA wal_checkpoint(...)` 会返回行），抛异常后导致后续 `DELETE`/`VACUUM` 未执行。

修复：PRAGMA 改为 `query()` 执行；删除/压缩/checkpoint 各自独立容错并校验剩余行数。重新测试后：

```
legacy rows（迁移后）: []
```

该问题已复测确认修复，相关代码在 `data/clipboard/ClipboardManager.kt` 与
`data/secureclipboard/SecureClipboardRepositoryImpl.kt`。

## 5. 证据文件

| 文件 | 内容 |
| --- | --- |
| `emulator-fcitx5-privacy-security-settings.png` | 小企鹅输入法内「隐私与安全」设置页（中文 UI，安全剪贴板开关/状态） |
| `emulator-secure-capture-dialog-flag-secure.png` | 安全摘录确认对话框的窗口区域（FLAG_SECURE 导致截屏为黑色） |
| 本文 | 命令、DB 行、剪贴板状态、缺陷与修复记录 |

## 5.1 后续补充功能与红队证据（2026-10-31 17:2x–17:4x）

隐私历史搜索、加密备份导出/导入、零权限流氓 App 红队 10 项攻击的完整结果见 `docs/security/09-rogue-app-red-team.md`，原始证据见本目录：

- `privacy-history-search-result.txt`（搜索 SECURE → 2 条匹配）
- `export-import-verification.txt`（加密文件无明文、错误口令拒绝、正确口令恢复 5 条）
- `rogue-app-results-secure-mode.txt` / `rogue-app-results-legacy-mode.txt`（红队原始 RESULT 行）

## 6. 边界（不得夸大）

- 以上是 **Android 16 x86_64 模拟器**上的功能实测，**不是**一加/OxygenOS/ColorOS 真机验证。
- 模拟器默认输入法权限模型与真实 ColorOS/OxygenOS 可能有差异；后台进程存活、锁屏、Direct Boot、深度睡眠等场景仍需真机验证。
- instrumented 自动化测试仍未执行（本测试为 adb + uiautomator 手工驱动）。
