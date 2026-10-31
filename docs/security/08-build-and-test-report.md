# 08 真实构建与测试报告

> 本文件记录 维护者 在 **2026-10-31** 于本机（Windows + Android SDK）对本次改造工作树实际执行的构建与测试。
> 所有结果均来自真实命令输出，不含推测；未执行的项目在第 6 节明确列出。

## 1. 构建环境（实际值）

| 项目 | 值 |
| --- | --- |
| OS | Windows（x64，中文系统） |
| 仓库 | `<本仓库根目录>`（GitHub fcitx5-android master，commit `eb5b50216e70484a009933d9519db76005f1faf3`，含子模块） |
| JDK | Temurin 17（`<JDK 17 安装目录>`），`JAVA_HOME` 显式指定 |
| Gradle | 9.6.1（wrapper 自动下载） |
| AGP / Kotlin / KSP | AGP 9.4.1 / Kotlin 2.4.10 / KSP 2.3.10（`gradle/libs.versions.toml`） |
| Android SDK | compileSdk 36、buildTools 36.0.0（本机未装 36.1.0，故用 `-PbuildTools=36.0.0`） |
| NDK / CMake | NDK 28.0.13004108（构建时由 AGP 自动安装）+ CMake 3.31.6 |
| 原生构建额外依赖 | Windows 宿主需要 KDE ECM 与 gettext；本机使用最小化 ECM 包（`<本地 ECM 安装>`）与 gettext 静态发行版（`<本地 gettext>`），仅作为**本机构建环境**，未写入仓库源码 |

构建命令中的关键参数：`-PbuildABI=arm64-v8a`（只构建一个 ABI，节省时间）、`-PbuildTools=36.0.0`。原生构建通过环境变量获得 ECM/gettext：`ECM_DIR`、`PATH` 追加 gettext。

## 2. 基线验证（改动前）

在写代码之前，对未修改的仓库执行了一次 `:app:compileDebugKotlin`：

```
BUILD SUCCESSFUL in 5m 58s
66 actionable tasks: 66 executed
```

说明本机工具链可编译上游原始代码，后续所有失败都可归因到具体改动。

## 3. 改动后的构建结果

| # | 命令 | 结果 | 说明 |
| --- | --- | --- | --- |
| 1 | `:app:compileDebugKotlin` | **BUILD SUCCESSFUL** | 全部新增/修改 Kotlin 源码（含 Room KSP 生成）编译通过；最后记录 18s（增量） |
| 2 | `:app:testDebugUnitTest` | **81 个安全剪贴板相关 JVM 单测全部通过**（含新增 20 个加密备份测试）；更早的全量运行中 1 个上游既有失败另计 | 详见第 4 节 |
| 3 | `:app:compileDebugAndroidTestKotlin` | **BUILD SUCCESSFUL** | instrumented 测试源码编译通过（未运行） |
| 4 | `内部测试探针构建（已移除）` | **BUILD SUCCESSFUL** | 独立测试 App APK（零权限、无 INTERNET） |
| 4b | `内部红队工具构建（已移除）` | **BUILD SUCCESSFUL** | 红队攻击 App APK 982,453 字节；零权限、无 INTERNET；仅测试设备使用 |
| 5 | `:app:assembleDebug` | **BUILD SUCCESSFUL** | 含完整 NDK/CMake 原生构建（fcitx5/libime/chinese-addons，arm64-v8a），产出主 APK |
| 6 | `:app:assembleDebugAndroidTest` | **失败（上游既有资源配置问题，与本改动无关）** | 资源链接报缺 `mipmap/ic_launcher_debug`、`string/app_name_debug`：这些是 app 在 `buildTypes.debug` 里用 `resValue()` 生成的资源，androidTest 资源链接器不可见；属项目既有构建配置问题。instrumented **源码**编译（第 3 行）已通过 |

### 3.1 主 APK（真实产物）

| 项 | 值 |
| --- | --- |
| 路径 | `app/build/outputs/apk/debug/org.fcitx.fcitx5.android-eb5b502-arm64-v8a-debug.apk` |
| 大小 | 69,819,756 字节 |
| SHA-256 | `D7C880B1D150937E24CF3057DF8B0D458A035B516270735B069CE69740ADE86B`（最终构建，含搜索/导出/红队测试配套修复） |
| 版本 | versionCode 112 / versionName `eb5b502`（浅克隆无 tag，故为 commit 短哈希） |
| minSdk / targetSdk / compileSdk | 23 / 36 / 36 |
| 权限（aapt） | `VIBRATE`、`POST_NOTIFICATIONS`、`<app>.permission.PLUGIN`（signature 自定义）——**无 INTERNET** |
| 签名（apksigner） | 有效（`CN=Android Debug, O=Android, C=US`，测试用 debug keystore） |
| 新增组件（manifest） | `SecureCaptureActivity`（ACTION_PROCESS_TEXT）、`SecureShareReceiverActivity`（ACTION_SEND text/plain）、`SecureUnlockActivity`（未导出） |
| 原生库 | `lib/arm64-v8a/libFcitx5Core.so` 等 fcitx5/libime/前端库齐全 |

> 该 APK **仅验证了“可构建、可签名、清单/权限正确”**，未安装到任何真机或模拟器运行。

## 4. 单元测试结果（真实统计）

测试结果目录：`app/build/test-results/testDebugUnitTest/`（JUnit XML）。

| 测试类 | 用例数 | 失败 |
| --- | --- | --- |
| `ClipboardCryptoTest` | 17 | 0 |
| `ClipboardEventProcessorTest` | 15 | 0 |
| `SecureClipboardPolicyTest` | 17 | 0 |
| `SecureClipboardMigrationTest` | 10 | 0 |
| `CryptoPerformanceBenchmarkTest` | 2 | 0 |
| `SecureClipboardExportCryptoTest`（新增：加密备份格式） | 20 | 0 |
| **新增合计** | **81** | **0** |
| `StringEscapeTest`（上游） | 2 | 0 |
| `ThemeSerializationTest`（上游） | 3 | 1 |

### 4.1 关于唯一的失败（必须如实说明）

`ThemeSerializationTest.version2` 失败：

```
java.lang.AssertionError: Migration shouldn't happen expected:<false> but was:<true>
```

原因：该测试（`app/src/test/java/org/fcitx/fcitx5/android/ThemeSerializationTest.kt:68`）断言输入 version `2.0` 时**不应发生迁移**；但上游实现的 `CustomThemeSerializer.CURRENT_VERSION = "2.1"`（`app/src/main/java/org/fcitx/fcitx5/android/data/theme/CustomThemeSerializer.kt:90`），因此 version 2.0 属于“已知旧版本”，必然迁移。

该失败与剪贴板功能无关：本次改动没有触碰主题序列化代码。仓库为上游 master 快照，测试/实现版本不一致属于既有问题，本报告**不把它写成“全部通过”**。

### 4.2 JVM AES-256-GCM 参考基准（来自 `CryptoPerformanceBenchmarkTest` 的 system-out）

> 桌面 JVM（非 Android 设备），仅加密原语往返（encrypt+decrypt），**不含 Keystore、Room、Binder、清空剪贴板、UI 与内存峰值**。不能当作真机端到端指标。

```
=== AES-256-GCM JVM benchmark (encrypt+decrypt, desktop JVM provider) ===
length(UTF-16 units) | avg(us) | min(us) | max(us) | MiB/s(round-trip)
                  10 |   123.7 |     29 |     370 |      0.2
                 100 |    53.1 |     29 |     128 |      3.6
                1000 |   106.3 |     47 |     165 |     17.9
               10000 |   313.2 |    241 |     734 |     60.9
              100000 |  1799.9 |   1533 |    2270 |    106.0
=== oversized input rejection: TooLargeText in 586us ===
```

## 5. 编译期发现并修复的真实问题（集成记录）

| 问题 | 修复 |
| --- | --- |
| Room KSP 不接受 data class 构造函数上的 `@Ignore` 参数（`ClipboardEntry`） | 改为类体内 `@Ignore var secure`（不参与 Room 列） |
| `SecureClipboardController` 调用未定义的 `updateStatus` | 补私有辅助函数（同步修复） |
| `SecureUnlockGate` 在 `object` 内使用 `companion object` | 改为对象内 `private const val` |
| `ClipboardManager.get()` 的重载解析少传 `label` | 传入遮罩标签 |
| Windows 原生构建缺 ECM | 提供最小化 ECMConfig（含 ECMConfigVersion，满足 libime `find_package(ECM 1.0)`） |
| Windows 原生构建缺 gettext | 安装 gettext 静态发行版并加入 PATH |
| 本机 `~/.android/debug.keystore` 密码不匹配 | 用 `keytool` 生成独立 debug keystore，`-Pandroid.injected.signing.*` 注入 |
| 迁移后旧明文表未清空（模拟器实测发现）：`execSQL("PRAGMA secure_delete=ON")`/`wal_checkpoint` 抛 `SQLiteException` | PRAGMA 改用 `query()` 执行；DELETE/VACUUM/checkpoint 各自独立容错并校验剩余行数（`ClipboardManager.kt`、`SecureClipboardRepositoryImpl.kt`）；重新实测旧表 0 行 |

## 6. 明确未执行的项目（不得声称通过）

| 项目 | 状态 | 原因 |
| --- | --- | --- |
| instrumented 测试（`app/src/androidTest`） | **未执行**（仅编译通过） | 已有模拟器，但 instrumented 测试需要把测试 APK 一并安装运行；见第 9 节手工实测替代 |
| 独立 probe App 与主 App 联合安全测试 | **已手工执行**（模拟器） | 用 内部测试探针 的 ContentProvider 设置剪贴板/读取长度，见第 9 节 |
| adb 手工验证矩阵（07 文档第 4 节 12 步） | **部分执行**（模拟器） | 已执行：旧模式读取、安全模式加密+清空、后台默认输入法转存、安全摘录不写剪贴板；其余（锁屏、重启、Direct Boot、OEM）未执行 |
| OxygenOS / ColorOS / Android 14/15/16 兼容性 | **未验证**（模拟器为 AOSP Android 16） | 无 OEM 设备 |
| 真机性能/功耗（P50/P95/P99、CPU、内存、Doze） | **未测量** | 无真机；04 文档只给出方法与桌面 JVM 参考值 |
| `:app:lintDebug` / release 构建 / 多 ABI 构建 | **未执行** | 时间与体积原因；本次只构建 arm64-v8a 与 x86_64 debug |
| APK 安装与运行 | **已在模拟器执行**（Android 16 x86_64） | 见第 9 节；真机未安装 |
| `:app:assembleDebugAndroidTest`（测试 APK 打包） | **失败（既有配置问题）** | 见第 3 节第 6 行：`resValue` 生成的 debug 资源在 androidTest 资源链接中不可见；instrumented 源码编译已通过 |

## 7. 复现命令（本机已实际执行）

```powershell
$env:JAVA_HOME='<JDK 17 安装目录>'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT="$env:LOCALAPPDATA\Android\Sdk"
$env:ECM_DIR='<本地 ECM 安装>\share\ECM\cmake'
$env:PATH="<本地 gettext>\bin;" + $env:PATH
Set-Location '<本仓库根目录>'

.\gradlew.bat :app:compileDebugKotlin --console=plain -PbuildABI=arm64-v8a -PbuildTools=36.0.0
.\gradlew.bat :app:testDebugUnitTest --console=plain -PbuildABI=arm64-v8a -PbuildTools=36.0.0
.\gradlew.bat :app:compileDebugAndroidTestKotlin --console=plain -PbuildABI=arm64-v8a -PbuildTools=36.0.0
.\gradlew.bat 内部测试探针构建（已移除） --console=plain -PbuildABI=arm64-v8a -PbuildTools=36.0.0
.\gradlew.bat :app:assembleDebug --console=plain -PbuildABI=arm64-v8a -PbuildTools=36.0.0 -Pandroid.injected.signing.store.file=<本地 debug.keystore> -Pandroid.injected.signing.store.password=android -Pandroid.injected.signing.key.alias=androiddebugkey -Pandroid.injected.signing.key.password=android
```

> 在 Linux/macOS 或已安装 MSYS2/gettext 的机器上，只需把 ECM 与 gettext 的系统包装好，无需上述本机路径注入。

## 8. 结论

- **构建维度：通过**。Kotlin 编译、Room KSP、instrumented 测试编译、独立测试 App、含原生库的完整 debug APK 全部构建成功。
- **JVM 自动化测试维度：通过（新增 81/81）**；更早的全量运行中存在 1 个上游既有失败，已如实记录。
- **设备/仪器与 OEM 兼容维度：未验证**。APK 未安装运行，instrumented 测试与 adb 手工矩阵均未执行。
- 因此本次交付的准确表述是：**“可构建、新增单测通过、模拟器功能实测通过、真机/OEM 未验证”**，而不是“功能已在真机验证通过”。

## 9. 模拟器功能实测（2026-10-31 17:00–17:25 补充）

在 Android Studio AVD `Pixel_9_Pro_XL`（Android 16 / API 36 / x86_64）上安装**同一份源码**构建的 x86_64 debug APK，并用 adb + uiautomator + 独立 probe App（零权限测试工具）实际驱动小企鹅输入法。完整证据见 `evidence/emulator-functional-test-evidence.md`。

### 9.1 关键结论

| 场景 | 实测结果 |
| --- | --- |
| 旧模式（安全剪贴板关闭）能否获取系统剪贴板 | ✅ 能。复制 `LEGACY_TEST_12345` 后，小企鹅输入法私有 Room 库 `clbdb.clipboard` 出现该行（旧行为保留） |
| 安全模式普通复制 | ✅ 复制被读取、AES-256-GCM 加密写入 `secure_clbdb`（每行 12B IV + 密文，含 16B tag），**系统剪贴板随后立即被清空**（probe 读回 accessible=false / item_count=0） |
| 安全模式后台转存（输入法进程在后台、仍为默认输入法） | ✅ 桌面在前台时复制，仍成功加密落库（新增 1 行），并清空系统剪贴板 |
| 旧明文迁移 | ✅ 迁移后加密库有记录、旧明文表 **0 行**；加密库主文件与 WAL 中均扫描不到测试明文 |
| 安全摘录（ACTION_PROCESS_TEXT） | ✅ 打开小企鹅输入法的确认页（遮罩预览 + 来源 + 字数），点击「Save encrypted」后加密库新增 `sourceType=process_text` 行；系统剪贴板始终保持为空（全程未使用） |
| FLAG_SECURE | ✅ 安全摘录窗口在系统截屏中为黑块（预期防护，见 evidence 截图） |
| 缺陷发现与修复 | 实测发现迁移后旧明文表未被清空：`execSQL(PRAGMA ...)` 抛 `SQLiteException` 阻断后续 DELETE；改为 `query()` 执行 PRAGMA 并逐步容错后复测为 0 行 |

### 9.2 与真机验证的边界

- 模拟器证明「功能位于小企鹅输入法内部、代码路径可运行、剪贴板读写与加密落库行为正确」；
- **不代表** OxygenOS/ColorOS 真机的剪贴板后台限制、进程存活、锁屏/Direct Boot、OEM 权限策略与性能功耗已验证；这些仍是第 6 节列出的未验证项。
- instrumented 自动化测试仍未执行（第 9–11 节是 adb/uiautomator 手工实测，非 `androidTest`）。

## 10. 隐私历史搜索与加密备份导出/导入实测（2026-10-31 17:2x 补充）

新增功能在 Android 16 x86_64 模拟器上的实际结果（完整记录见 [09-rogue-app-red-team.md](09-rogue-app-red-team.md) 第一节）：

| 功能 | 实测结果 |
| --- | --- |
| 隐私历史列表 | `recentRows(500)` 只读密文行，显示遮罩 + 来源 + 时间，列表不解密（隐私历史 Activity 自身 FLAG_SECURE） |
| 搜索 | 查询 `SECURE` → “2 条匹配记录”，结果列表仅遮罩；逐条本地解密匹配，界面不显示明文 |
| 导出加密备份 | 生成 `secure-clipboard-20261031-093516.fcsc`（603 字节，5 条记录）：magic `FCSC-EXPORT` + version + 16B salt + 12B IV + AES-256-GCM 密文；文件内搜索测试明文均不存在 |
| 导入（错误口令） | 拒绝导入，数据库保持 0 行 |
| 导入（正确口令） | 成功恢复 5 条记录（DB 0 → 5），列表恢复显示 |
| 证据 | `evidence/privacy-history-search-result.txt`、`evidence/export-import-verification.txt`、`evidence/secure-vault-rows-after-tests.txt` |

## 11. 红队“流氓 App”实测（2026-10-31 17:4x 补充）

新增零权限测试模块 内部红队工具（仅测试设备使用），从第三方 App 视角发起 10 种攻击；完整结果见 [09-rogue-app-red-team.md](09-rogue-app-red-team.md)。

| 攻击 | 安全模式结果 |
| --- | --- |
| 前台读系统剪贴板（复制后） | **BLOCKED**（Fcitx 已加密保存并清空系统剪贴板，读到空） |
| 直接读私有数据库/偏好 | **BLOCKED**（应用沙箱） |
| 绑定 FcitxRemoteService（IPC） | **BLOCKED**（signature 权限 → SecurityException） |
| 查询 FcitxDataProvider | **BLOCKED**（MANAGE_DOCUMENTS → SecurityException） |
| 读取小企鹅 logcat | **BLOCKED**（UID 隔离，target_pkg_lines=0） |
| 访问小企鹅 `Android/data` 外部目录 | **BLOCKED**（scoped storage） |
| 伪造 PROCESS_TEXT / SEND 入口 | 可拉起确认框，但**无法静默入库**；连续 10 次伪造后数据库行数 8→8 不变，进程存活 |
| 旧模式对照 | 关闭安全剪贴板后，前台红队 App **可读取**系统剪贴板（SUCCESS，读到测试明文）——旧模式按兼容设计保留系统剪贴板，这是默认推荐开启安全模式的原因 |

结论：在**安全模式 + 小企鹅为默认输入法**时，第三方应用无法通过系统剪贴板、私有文件、IPC、Provider、日志或伪造入口直接拿到小企鹅的敏感剪贴板内容；旧模式/兼容模式和“小企鹅非默认输入法”状态仍受平台限制，已在 09 的残余风险中列明。


## 13. 词库 / 输入功能回归实测（2026-10-31 10:00 补充）

安全剪贴板改造没有触碰词库/输入引擎代码。为排除集成回归，在模拟器上实测：

- **内置输入法齐全**：English、拼音、双拼、五笔字型、五笔拼音、自然码、电报码（添加/删除/拖拽排序正常）。
- **附加组件全部加载**：拼音、额外拼音功能、码表、拼写、标点、快速输入、简繁转换、剪贴板、Unicode 等 16 项。
- **拼音输入成功**：输入 `nihao` → 预编辑 `ni hao`，候选 `1. 你好 / 2. 👋 / 3. 拟好 / 4. 你 / 5. 拟 / 6. 泥 / 7. 逆`；提交后文本为“你好”，说明 `sc.dict` + `zh_CN.lm` 词典与语言模型正常。
- **用户词库正常**：`external/data/pinyin/user.dict`（339B）与 `user.history`（57B）在输入后自动创建/更新。
- **词库管理页正常**：“拼音词库”“管理码表输入法”页面均可打开（新安装无自定义词库，列表为空属预期）。
- **用户数据导出回归**：导出 zip 7,592 字节 / 24 条目，包含拼音词典配置与 `user.dict/user.history`，**不包含** `clbdb/secure_clbdb`（安全改造的排除逻辑生效）。
- 全流程无 FATAL/崩溃，进程存活。

证据：`evidence/emulator-dictionary-function-check.md`、`evidence/emulator-pinyin-candidates.png`。

> 限制：本轮用硬件按键事件驱动 Fcitx 拼音引擎（该模拟器对 Fcitx 软键盘窗口的触摸注入不生效），真机软键盘打字手感、OEM 输入法切换、插件扩展词库、导入用户数据均未验证。

## 14. 插件兼容性实测（2026-10-31 18:1x 补充）

用**同一份源码、同一个 debug 签名**构建官方插件 `plugin/clipboard-filter` 并在模拟器安装测试：

- 签名一致（App/插件 SHA-256 均为 `dd26a87f…a24c3602`）；插件「插件」页显示**已加载**，日志 `Bind to plugin: clipboard_filter` → `Plugin connected: clipboard_filter`，无 SecurityException；
- 三种模式的剪贴板插件行为（测试 URL 含 utm_source/utm_medium/fbclid）：

| 模式 | transformer 日志状态 | 存储结果 | 系统剪贴板 |
| --- | --- | --- | --- |
| 旧模式 | `Clipboard transformers: 1, strictSecureMode=false` | clbdb 存 `https://example.com/watch?id=789`（过滤生效） | 保留原始 URL |
| 安全模式 + 严格 | `Clipboard transformers: 1, strictSecureMode=true` | secure_clbdb 密文 89B = 73 字符原文 + 16B tag（未过滤） | 已清空（红队读取 blocked） |
| 安全模式 + 允许插件 | `Clipboard transformers: 1, strictSecureMode=false` | secure_clbdb 密文 48B = 32 字符过滤后 URL + 16B tag | 已清空（红队读取 blocked） |

结论：插件协议兼容；严格模式只移除 clipboard transformer（有意隐私差异），其他插件能力不受影响；官方签名插件 APK 无法绑定 debug 包，需同密钥构建。

证据：`evidence/plugin-compatibility-test.md`。

## 15. 发行版构建与冒烟测试（2026-10-31 18:3x 补充）

- `:app:assembleRelease` 成功（arm64-v8a）：54,674,383 字节，versionCode 112 / versionName 0.1.3-26-g1f0d11a8，R8 混淆 + 资源压缩，无 INTERNET 权限；apksigner v1+v2 验证通过，证书为本机 release key（SHA-256 3E:E0:29:DE:…:04:F5）。
- 新增功能已提供简体中文 / 繁体中文（zh-rTW、zh-rHK）/ 日语本地化，跟随系统语言。
- 同源码 x86_64 release 包在 Android 16 模拟器冒烟：安装启动正常；隐私设置页正常；启用安全模式后“加密存储/密钥”显示 OK、默认输入法 Active；测试内容复制 → 系统剪贴板被清空（前台应用读取为空）；隐私历史显示 2 条遮罩记录（无明文）。
- 词库回归（release 变体）：输入 nihao → 候选 1.你好 / 2.👋 / 3.拟好 / 4.你…，提交得到“你好”，R8 未破坏词典/语言模型。
- 插件回归（release 变体）：用同一 release 密钥构建官方 plugin/clipboard-filter 发行版，release 主包「插件」页显示 Loaded（签名权限匹配）；官方签名插件仍无法绑定自签发行包。
- 证据/发行包：GitHub Release `secure-clipboard-v1.1`；调试期证据见 `evidence/`。

## 16. 最终状态一句话

**“可构建（debug + release 签名）、81 个 JVM 单测通过、模拟器上搜索/加密备份/双通道/红队攻击/词库/插件兼容性实测完成；真机/OEM 与 instrumented 自动化仍未验证。”**



