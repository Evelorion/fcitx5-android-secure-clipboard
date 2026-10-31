<!-- SPDX-License-Identifier: LGPL-2.1-or-later -->

# 01 源码审计报告（Fcitx5 for Android 剪贴板相关）

> 范围：审计与安全报告。本文只基于仓库真实源码与真实命令输出，不引用任何尚未落地的推测实现。
> 审计对象：`<本仓库根目录>`（真实 GitHub 仓库），基线 commit `eb5b50216e70484a009933d9519db76005f1faf3`，外加审计时工作树中尚未提交的改造。

## 0. 快照声明、方法与证据等级

**审计快照 T0（2026-10-31，Asia/Shanghai）**：

- `git rev-parse HEAD` = `eb5b50216e70484a009933d9519db76005f1faf3`，`git branch --show-current` = `master`，该 commit 时间为 2026-10-13 22:33:48 +0800。
- `git remote -v`：`origin https://github.com/fcitx5-android/fcitx5-android.git`。
- T0 时 `git status --porcelain` 显示：13 个已跟踪文件被修改（`AndroidManifest.xml`、`FcitxRemoteService.kt`、`core/Fcitx.kt`、`data/clipboard/ClipboardManager.kt`、`data/clipboard/db/{ClipboardDao,ClipboardEntry}.kt`、`data/prefs/AppPrefs.kt`、`ui/main/MainFragment.kt`、`ui/main/settings/SettingsRoute.kt`、`res/values/strings.xml`、`res/values-zh-rCN/strings.xml`、`res/xml/{data_extraction_rules,full_backup_content}.xml`），以及未跟踪目录/文件（`data/secureclipboard/`、`ui/main/settings/security/`、`res/drawable/ic_shield_24.xml`、`docs/`）。
- 本仓库工作树在审计期间仍被其他任务（T1/T2/T3/维护者）持续修改。**本文第 0–7 节及附录 A 中标注 T0 的“引用行号”均为对应快照的行号**；最终集成后的构建/测试证据见**附录 B**，实现状态以附录 A/B 与 `06` 第 10 节为准。凡快照时尚不存在的实现，本文一律标注“未落地/未验证”，不编造。

证据等级（全文统一使用）：

| 标记 | 含义 |
| --- | --- |
| 【S】 | 源码确认：已在 T0 源码中直接读到，附 `路径:行号`。 |
| 【P】 | 潜在风险：源码路径存在，但可利用性/实际效果取决于运行环境或尚未完成的实现，需进一步验证。 |
| 【T】 | 需测试：只能通过真机、模拟器、自动化测试或运行时观测确认；T0 **未执行**，一律标“未验证”。 |

## 1. 仓库与构建基线

### 1.1 子模块（`git submodule status --recursive` 的真实输出，节选）【S】

```text
1e00551899f9d0fa5418d899f468b6e421401adf lib/fcitx5/src/main/cpp/fcitx5
19f06898581419d3e4d37492f33a14e841e2680e lib/fcitx5-chinese-addons/src/main/cpp/fcitx5-chinese-addons
ecd23795ff7ea63a55a1b88fc4767946b999e102 lib/libime/src/main/cpp/libime
9bf8d3dd2c6240d8e3e194d09547acbfa6a81204 lib/fcitx5-lua/src/main/cpp/fcitx5-lua (5.0.18)
11086f40512774dcadef54079f1ba023bfacf940 plugin/clipboard-filter/ClearURLsRules
```

其余子模块（rime/anthy/chewing/hangul/jyutping/sayura/thai/unikey、prebuilt、yoga、kenlm、rime 相关数据仓库等）见同命令输出；本任务不修改原生子模块。

### 1.2 工具链与 SDK【S】

| 项 | 值 | 证据 |
| --- | --- | --- |
| Gradle | 9.6.1 | `gradle/wrapper/gradle-wrapper.properties`（`distributionUrl=...gradle-9.6.1-bin.zip`） |
| Android Gradle Plugin | 9.4.1 | `gradle/libs.versions.toml:2` |
| Kotlin | 2.4.10 | `gradle/libs.versions.toml:3` |
| KSP | 2.3.10 | `gradle/libs.versions.toml:4` |
| Room | 2.8.4 | `gradle/libs.versions.toml:7` |
| minSdk / targetSdk / compileSdk | 23 / 36 / 36 | `build-logic/convention/src/main/kotlin/Versions.kt:11-13` |
| Java | 11 | `build-logic/convention/src/main/kotlin/Versions.kt:10` |
| 应用版本 | 0.1.3（baseVersionCode 11，最终 versionCode 按 ABI 计算） | `build-logic/convention/src/main/kotlin/Versions.kt:19-35`；`app/src/main/java/org/fcitx/fcitx5/android/utils/Const.kt`（versionName 拼接 BUILD_TYPE） |
| 支持 ABI | armeabi-v7a、arm64-v8a、x86、x86_64 | `Versions.kt:23` |
| 签名/构建限制 | 不在此列（未执行任何构建） | — |

### 1.3 依赖审计（`app/build.gradle.kts:79-132`）【S】

- 全部依赖均为 AndroidX/Material/Kotlin 官方或通用 UI/工具库：coroutines、serialization-json、activity、appcompat、autofill、constraintlayout、coordinatorlayout、core-ktx、lifecycle、navigation、paging、preference、recyclerview、room(runtime/ktx/paging/compiler)、startup、viewpager2、material、arrow、imagecropper、flexbox、dependency、timber、splitties、aboutlibraries。
- **未发现任何 HTTP/网络客户端依赖**（无 OkHttp/Retrofit/Volley/Ktor 等）；工作树未新增依赖。
- 审计用测试依赖只有 `junit:junit:4.13.2`（`gradle/libs.versions.toml:59`）与 androidx test（`app/build.gradle.kts:127-131`）。
- **结论【S】**：到 T0 为止，“核心功能完全本地运行、无云端 API”与“无新增第三方依赖”在构建脚本层面成立；但“无网络依赖”不等于“无外传路径”，见第 3、4 节与 `03-dataflow-privacy-report.md`。

### 1.4 权限与导出组件（`app/src/main/AndroidManifest.xml`，T0 行号）【S】

| 项 | 行号 | 说明 |
| --- | --- | --- |
| `VIBRATE` | 14 | 震动反馈 |
| `POST_NOTIFICATIONS` | 15 | 通知 |
| `permission.IPC`（signature） | 29-31 | `FcitxRemoteService` 的绑定权限 |
| `permission.PLUGIN`（signature） | 33-35 | 插件服务的绑定权限 |
| `uses-permission.PLUGIN` | 37 | 主应用自己持有，用于绑定插件服务 |
| `allowBackup="true"` | 41 | 备份默认开启（备份内容见 3.9） |
| 安全摘录 `SecureCaptureActivity`（exported=true，PROCESS_TEXT） | 124-142 | T0 工作树新增 |
| 安全分享 `SecureShareReceiverActivity`（exported=true，SEND） | 144-160 | T0 工作树新增 |
| `SecureUnlockActivity`（exported=false） | 162-168 | T0 工作树新增 |
| `FcitxRemoteService`（exported=true，`permission.IPC`） | 185-193 | 插件 IPC 入口 |
| `FcitxDataProvider`（exported=true，`MANAGE_DOCUMENTS`） | 195-204 | 文档提供者，只映射外部私有目录 |
| IME 服务 `FcitxInputMethodService`（exported=true，`BIND_INPUT_METHOD`） | 170-183 | 系统输入法框架绑定 |

**无 `INTERNET` 权限**【S】：主清单中没有 INTERNET 声明；`lib/common` 清单中亦无（`Get-ChildItem -Recurse ... | Select-String INTERNET` 无匹配）。

## 2. 进程、组件与生命周期

### 2.1 单进程模型【S】

- 全仓库 `app/src/main` 的清单里**没有 `android:process`**（grep 无匹配），因此 `FcitxApplication`、`FcitxInputMethodService`、`FcitxRemoteService`、Room 数据库、`ClipboardManager` 单例、fcitx 原生引擎（JNI）都在**同一个应用进程**中运行。
- fcitx 引擎由 `FcitxDaemon`（`app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt:41-96`）持有 `Fcitx` 实例（JNI），不是独立 Android 进程。
- 因此本文中“跨进程”只发生在：① 与本应用签名的插件 App（Binder）；② 系统剪贴板（系统服务）；③ 目标 App（InputConnection Binder）；④ 系统备份/SAF 等系统组件。

### 2.2 启动路径【S】

`FcitxApplication.onCreate()`：`app/src/main/java/org/fcitx/fcitx5/android/FcitxApplication.kt:83-152`

- 124 行：`Timber.setupForest(verbose = sharedPrefs.getBoolean("verbose_log", false))`；
- 128 行：`AppPrefs.init(sharedPrefs)`；
- 136 行：`ClipboardManager.init(ctx)`（`ctx` 为 `directBootAwareContext`，见 2.4）；
- 139 行：注册 `ACTION_SHUTDOWN` 接收器；
- 144-151 行：注册 `ACTION_RESTART_FCITX_INSTANCE` 接收器，权限 `android.permission.READ_INPUT_STATE`（仅 shell 等持有，供 `adb shell am broadcast` 调试）。

### 2.3 IME 服务生命周期【S】

`class FcitxInputMethodService : LifecycleInputMethodService()`：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:83`

| 回调 | 行号 | 与剪贴板/提交相关的行为 |
| --- | --- | --- |
| `onCreate` | 205-230 | 连接 fcitx daemon；注册各偏好监听；`super.onCreate()` |
| `onBindInput` | 676-699 | 记录 uid/包名并 `activate(uid, pkgName)` |
| `onStartInput` | 727-756 | 重置选区，`CapabilityFlags.fromEditorInfo(attribute)` 记录密码/敏感标记 |
| `onStartInputView` | 758-778 | 输入视图绑定 EditorInfo |
| `onFinishInputView` | 1050-1064 | 取消光标监听、重置组合状态 |
| `onFinishInput` | 1066-1072 | 焦点退出，恢复默认 CapabilityFlags |
| `onUnbindInput` | 1074-1084 | `deactivate(uid)` |
| `onDestroy` | 1086-1095 | 与 fcitx daemon 断开 |
| `commitText(text, cursor)` | 423-454 | **唯一被复用的提交封装**：`currentInputConnection.commitText`；未做日志 |

### 2.4 Direct Boot（直接启动）与解锁【S】

- `FcitxApplication` 在用户未解锁时 `isDirectBootMode = true`（`FcitxApplication.kt:85-88`），并通过 `directBootAwareContext`（`FcitxApplication.kt:76-81`）改用 `createDeviceProtectedStorageContext()`（DPS，设备加密存储）暴露上下文。
- 解锁后 `unlockReceiver` 会调用 `AppUtil.exit()` 让应用退出并在普通模式重启（`FcitxApplication.kt:48-58`）。
- 该上下文一路传给 `ClipboardManager.init(ctx)`（`FcitxApplication.kt:136`），Room 库文件因此**在直接启动模式下位于 DPS、正常模式下位于 CES（凭据加密存储）**——两套文件路径不同（`ClipboardManager.kt:146-150` 用传入 context 建库）。
- 工作树的改造在 direct boot 模式**不初始化**加密保险库：`ClipboardManager.kt:152-158`（`Timber.i("Direct boot mode: secure clipboard vault is not initialized")`）；`SecureClipboardVault` 注释也声明不得在首次解锁前读取用户秘密（`data/secureclipboard/SecureClipboardVault.kt:9-16`）。
- **风险【P】**：直接启动期间普通剪贴板监听仍会启动（见 3.1），若在首次解锁前发生复制，明文写入的是 DPS 下的 `clbdb`；解锁重启后应用打开 CES 下的 `clbdb`，两条历史互不可见，且 DPS 明文是否被后续迁移/清理需要实测。

## 3. 剪贴板数据流（上游基线 + T0 工作树改造）

### 3.1 监听注册与生命周期

- 上游监听器是 `android.content.ClipboardManager.OnPrimaryClipChangedListener`：`ClipboardManager` 对象自身实现该接口（T0：`data/clipboard/ClipboardManager.kt:58`）。
- 监听开关偏好 `AppPrefs.getInstance().clipboard.clipboardListening`，键 `clipboard_enable`，**默认 true**：`data/prefs/AppPrefs.kt:339`。
- 注册点：`FcitxApplication.onCreate` → `ClipboardManager.init(ctx)`（`FcitxApplication.kt:136`）；`init()` 内注册偏好监听并在启动时调用一次 `updateListenerRegistration()`（T0：`ClipboardManager.kt:145-176`，`168-171`）。
- 注册策略（T0 工作树）：安全模式关闭时注册旧监听器，开启时改为 `SecureClipboardController.setListening(listening)`，**两者互斥、只注册一个**：`ClipboardManager.kt:182-195`。
- 偏好切换监听：`ManagedPreference.OnChangeListener`（`ClipboardManager.kt:119-122`）。上游同机制见 commit `eb5b502` 前的 `ClipboardManager.kt`（本次工作树已重构）。
- 进程被杀后：没有 WorkManager/前台服务/定时任务重新注册；监听器只在**进程重新启动**（系统绑定 IME、用户打开应用等）时恢复。**这是源码确认的限制【S】**（全仓库无定时/常驻注册逻辑）。

### 3.2 读取时机与快照【S】

旧路径 `onPrimaryClipChanged()`：`ClipboardManager.kt:452-493`（T0 行号；上游逻辑一致）

- 452-453：在系统回调线程立即读 `systemClipboard.primaryClip`；
- 458-468：按 `ClipDescription.timestamp`（API 26+）或时间戳+hash（API 23-25）去重；
- 469-491：`launch { mutex.withLock { ... } }`，把读取结果 `ClipData` 交给 `ClipboardEntry.fromClipData`，之后才做 Room 事务；
- **注意**：`ClipData` 对象本身在协程中仍被引用（捕获），即系统剪贴板快照的 `Item.text` 明文在内存中至少存活到协程执行完。这不是缺陷，但属于“明文内存窗口”。
- `ClipboardEntry.fromClipData`：`data/clipboard/db/ClipboardEntry.kt:48-67`；只取第一个 item 的 `item.text`（54-55），非文本（无 `item.text`）直接返回 null；敏感标记读 `ClipDescription.EXTRA_IS_SENSITIVE`（API 33，低版本用字符串常量，56-60）。

### 3.3 是否经过第三方插件（transformer）【S】

- 门面字段：`var transformer: ((String) -> String)?`：T0 `ClipboardManager.kt:97`；上游为 `ClipboardManager.kt:52`（commit 版本）。
- 应用点：`ClipboardEntry.fromClipData(clip, transformer)` 在**写入数据库前**执行 `text = transformer(str)`：`ClipboardEntry.kt:61-66`（`62` 行）。
- transformer 来源：`FcitxRemoteService` 通过 AIDL 接收插件注册：`app/src/main/java/org/fcitx/fcitx5/android/FcitxRemoteService.kt:96-114`；AIDL 接口：`lib/common/src/main/aidl/org/fcitx/fcitx5/android/common/ipc/IClipboardEntryTransformer.aidl:3-9`（`String transform(String clipboardText)`）。
- 结论【S】：**旧模式（安全模式关闭）下，所有被监听到的剪贴板明文都会先经 Binder 交给已注册插件处理，插件进程可读取完整明文。** 这是上游既有设计。
- T0 工作树新增严格模式控制：`FcitxRemoteService.kt:62-75` 的 `isStrictSecureMode()` 在“安全剪贴板开启 && 未允许插件”时把 `ClipboardManager.transformer` 置空（`68-75`）；偏好监听在 `135-141`、`153-160` 注册/注销。
- 通道 A（安全摘录/分享/IME 选区）**不经过** transformer：T0 门面注释与实现见 `ClipboardManager.kt:209-221`（`transformForSecureCaptureResult` 仅服务于通道 B 的插件兼容开关）。

### 3.4 Room 明文数据库【S】

| 项 | 证据 |
| --- | --- |
| 数据库名 `clbdb` | `ClipboardManager.kt:146`（`Room.databaseBuilder(context, ClipboardDatabase::class.java, "clbdb")`） |
| 版本 4，仅 `ClipboardEntry` 一张表 | `data/clipboard/db/ClipboardDatabase.kt:11-19` |
| 表名 `clipboard`，`text` 为明文 `String` 列 | `data/clipboard/db/ClipboardEntry.kt:16-36`（`20` 行 `val text: String`） |
| 敏感标记列 `sensitive` | `ClipboardEntry.kt:28-29`；仅用于 UI 遮罩（见 3.5），**不改变明文存储事实** |
| 迁移 | 1→2→3→4 为 Room AutoMigration（`ClipboardDatabase.kt:14-18`）；`fallbackToDestructiveMigrationOnDowngrade(dropAllTables=true)`（`ClipboardManager.kt:146-149`） |
| 旧库迁移入口 | T0 工作树：`ClipboardManager.migrateLegacyIfNeeded()`（`ClipboardManager.kt:396-445`）：读 `legacyDao.allActiveRows()` → 逐条交 `SecureClipboardVault.repository.migrateLegacy` → 成功后才 `PRAGMA secure_delete=ON; DELETE; VACUUM; wal_checkpoint(TRUNCATE)`（`420-428`） |
| 迁移失败 | 保留旧库，只更新状态与日志（`434-442`） |
| 数据库位置 | 应用内部私有目录 `/data/data/<pkg>/databases/clbdb`（由 `Room.databaseBuilder(context,...)` 决定，`ClipboardManager.kt:146`；`UserDataManager` 也以 `applicationInfo.dataDir/databases` 指代该目录，`data/UserDataManager.kt:55`） |

### 3.5 内存中的明文【S】

| 位置 | 证据 | 说明 |
| --- | --- | --- |
| `lastEntry: ClipboardEntry?` 持有完整明文 | `ClipboardManager.kt:111`（T0）/上游 `:80` | 最近一条记录在进程内常驻，直到下一条或清空 |
| 剪贴板面板 Paging 数据 | `ClipboardWindow.kt:85-87`（`Pager(PagingConfig(pageSize = 16)) { ClipboardManager.allEntries() }`） | `PagingDataAdapter` 当前页持有 `ClipboardEntry`（完整 `text`） |
| 列表展示只截断，不消除原文 | `ClipboardAdapter.kt:51-81`（`excerptText`），`91-131`（`onBindViewHolder` 调 `setEntry(excerptText(entry.text, ...))`） | 明文仍在 `ClipboardEntry` 对象里，只是 TextView 显示截断/遮罩 |
| 敏感内容 UI 遮罩 | `ClipboardAdapter.kt:94`；`input/bar/KawaiiBarComponent.kt:129-133`（`BULLET.repeat(min(42, it.text.length))`） | 遮罩只影响渲染，`ClipboardEntry.text` 仍是明文 |
| 复制建议气泡 | `KawaiiBarComponent.kt:122-139`（`idleUi.clipboardUi.text.text = it.text.take(42)`） | 42 字符明文副本进入 TextView |
| 编辑 Activity | `ui/main/ClipboardEditActivity.kt:58-61`（`editText.setText(entry.text)`） | 完整明文进入 EditText |
| 粘贴时 | `ClipboardWindow.kt:126-129`：`service.commitText(entry.text)` | 明文进入 InputConnection 调用参数，随后由 `commitText` 提交（见 3.6） |

### 3.6 用户选择记录后的提交路径【S】

- 面板点击：`ClipboardWindow.kt:90-131` 的匿名 `ClipboardAdapter` 子类，`onPaste` 在 `126-129` 调 `service.commitText(entry.text)`；未写系统剪贴板。
- 复制建议气泡点击：`KawaiiBarComponent.kt:310-325` 的 `suggestionView.setOnClickListener` → `service.commitText(it.text)`（`312-314`）；长按进入编辑（`320-325`）。
- 编辑页“复制”按钮：`ui/main/ClipboardEditActivity.kt:40`、`47-56`，`52` 行 `clipboardManager.setPrimaryClip(ClipData.newPlainText("", str))` —— **这是应用主动写系统剪贴板的路径之一**。
- 文本编辑窗口的剪贴板动作：`input/editing/TextEditingWindow.kt:83-90`，`copy/paste` 通过 `performContextMenuAction(android.R.id.copy/paste)` 由**目标 App** 执行（系统剪贴板由目标 App 使用，不是本应用直接写）；`cut` 同理（78-82）。
- 统一落点：`FcitxInputMethodService.commitText`（`input/FcitxInputMethodService.kt:423-454`），最终 `ic.commitText(text, 1)`（445 行）。
- **结论【S】**：私有粘贴在旧代码中已经走 `InputConnection`，不经过系统剪贴板；但应用仍有两条主动写系统剪贴板的旧路径（编辑页复制、开发者 EditorInfo Inspector，见 3.7）。

### 3.7 其他写系统剪贴板/外发路径【S】

| 路径 | 证据 | 风险 |
| --- | --- | --- |
| 编辑页“复制”按钮 | `ui/main/ClipboardEditActivity.kt:40,52` | 用户操作；把明文写回系统剪贴板，安全模式下需禁用（T3 范围） |
| 剪贴板长按“分享” | `ClipboardWindow.kt:108-117`：`ACTION_SEND` + `EXTRA_TEXT=entry.text` + chooser | 明文离开应用，进入用户选择的目标 App；安全模式下需禁用（T3 范围） |
| 开发者 EditorInfo Inspector 的“复制” | `input/editorinfo/EditorInfoWindow.kt:46-54`（`49-50` 行 `setPrimaryClip`），受开发者选项 `editor_info_inspector`（默认 false，`AppPrefs.kt:38`）与 `StatusAreaWindow.kt:200` 控制 | **【P】开启后会把 EditorInfo 全部字段（含周围的 `SurroundingText` 文本）复制到系统剪贴板**；`EditorInfoParser.kt:86-92` 会序列化 `mInitialSurroundingText.text`，`107-120` 反射导出全部字段 |
| fcitx 原生剪贴板模块 | `app/src/main/cpp/native-lib.cpp:94`（加载 `clipboard` addon）、`366-369`（`setClipboard` → `IClipboard::setClipboardV2`）、`997-999`（JNI `setFcitxClipboard`）；Kotlin 侧 `core/Fcitx.kt:163-164,345,486-492` | 明文进入 **fcitx 引擎内存**（同进程，非 Android 系统剪贴板）；不落盘、不跨进程，但会暴露给引擎内插件/模块。T0 工作树已加 `if (it.secure) return` 阻止安全记录镜像（`Fcitx.kt:486-492`） |

### 3.8 日志暴露面【S】

- 日志实现：Timber + logcat，**没有日志落盘 Tree**：`utils/Timber.kt:12-24`（`VerboseTree`/`ConciseTree` 均写 `android.util.Log`）；`setupForest` 在 release 且未开 verbose 时用 `ConciseTree`，只输出 `priority >= INFO`（第 21 行）；verbose 时用 `VerboseTree` 输出 DEBUG（`app/src/main/java/org/fcitx/fcitx5/android/utils/Timber.kt:26-31`，由 `FcitxApplication.kt:124` 与开发者选项 `verbose_log` 决定，默认 false：`AppPrefs.kt:36`）。
- 剪贴板明文日志：检索 `Timber.*(text|clip|Trans|entry|content)` 全部结果，**未发现打印剪贴板/剪贴板记录正文的代码**；`ClipboardManager` 的异常日志只打印异常类名（T0：`ClipboardManager.kt:487-490`，`Timber.w("Failed to update clipboard database: ${exception.javaClass.simpleName}")`），刻意避免异常消息可能携带内容。
- 但日志仍有明文风险【P】：`FcitxInputMethodService.kt:939` 在 DEBUG 级别打印 `updateComposingText: '$text'`（**用户正在输入的组合文本**）；verbose 模式开启后这些会写入 logcat。`ui/main/LogActivity.kt:41-57,96-107` 提供“实时日志/崩溃日志”的 logcat 查看与**导出为 text/plain 文件**功能，导出内容含当前 logcat 缓冲。因此：**剪贴板密文/明文不会专门写入日志，但输入文本 + 用户可选 verbose + 日志导出构成“输入内容可能进入文件”的旁路**。
- Crash handler：`FcitxApplication.kt:91-119` 把崩溃堆栈写入 `LogActivity`（最多 128000 字符，`167` 行常量），崩溃堆栈可能包含异常消息；不包含剪贴板明文（没有相关代码把明文塞进异常消息）。

### 3.9 备份暴露面【S】（T0 工作树已修改）

| 项 | T0 值 | 证据 |
| --- | --- | --- |
| `allowBackup` | true | `AndroidManifest.xml:41` |
| Android 12+ 备份规则 | 云备份 / 设备迁移均排除 `secure_clbdb*` 与 `clbdb*` 的 `database` 域 | `res/xml/data_extraction_rules.xml:16-34`（cloud）、`49-67`（device-transfer） |
| Android 11- 全量备份规则 | 同样排除两类数据库文件 | `res/xml/full_backup_content.xml:15-33` |
| 旧规则（基线 commit） | **没有**任何 `database` 域排除，只有 `file/usr` 等四项 | `git show eb5b502:app/src/main/res/xml/data_extraction_rules.xml` |

- **结论【S】**：基线 commit 的明文 `clbdb` 与（未来）加密库都会默认进入 Android 自动备份/换机迁移；T0 工作树已修复为显式排除。**该修复需要真机/模拟器 + `bmgr`/备份抓取验证，未验证【T】**。
- 注意：备份规则只能约束 Android 备份框架；用户手动“导出用户数据”是另一条路径（见 3.10）。

### 3.10 用户数据导出/导入【S】

- `data/UserDataManager.kt:54-57`：导出目录包括 `shared_prefs`、**整个 `databases` 目录**、`external`；
- `60-81`：`export()` 把上述目录打包为 zip；`65` 行 `writeFileTree(dataBasesDir, "databases", zipStream)`；
- `93-110`：`import()` 把 zip 中 `databases` 目录整体复制回应用（`103` 行）。
- **风险【P】**：① 用户主动导出会把明文 `clbdb`（以及密文 `secure_clbdb`，但密钥在 Keystore 内不随包导出）带出沙箱；② 在另一台设备导入用户数据时，`secure_clipboard_legacy_migrated` 偏好（`AppPrefs.kt:42`）也可能随 `shared_prefs` 一起恢复，导致旧明文库“看起来已迁移”而不再迁移/清理；③ 导入恢复 `clbdb` 明文后，需要重新执行迁移或清空。此路径真实存在，效果需测试【T】。

### 3.11 进程被杀与重启恢复【S】

- 监听器注册随进程存在；进程被杀 → 监听消失。恢复只依赖进程重启（系统绑定 IME、用户点开应用、系统重启后输入法被使用等）。**没有常驻、定时、轮询、无障碍兜底**（全仓库无相关代码，符合需求的“不滥用”要求）。
- 设备重启后：`FcitxApplication.onCreate` 重新执行 → `ClipboardManager.init` 重新注册（若是首次解锁前，见 2.4 的 DPS/CES 分叉）。
- IME 服务被系统重建时 `onCreate` 不重新初始化 `ClipboardManager`（它是 Application 级单例，Application 进程还在时不会重复 init）。若应用进程被系统回收后由 IME 服务重新拉起，`Application.onCreate` 会重新执行，因此会重新 init。
- **【P】**：安全模式依赖 `SecureClipboardVault` 初始化成功；direct boot 或 Keystore 不可用时（T1 未落地，无法确认其失败处理）门面会返回 `NotReady`（`ClipboardManager.kt:248-258`），旧监听器也不会被注册（`182-195`），此时复制既不会加密保存、也不会进入旧明文库——需要确认这是否符合“失败不丢数据”的目标（见 `06-unresolved-risks-and-acceptance.md`）。

## 4. 插件机制审计（真实源码）

### 4.1 插件发现与描述【S】

- 常量：`DataManager.PLUGIN_INTENT = "${BuildConfig.APPLICATION_ID}.plugin.MANIFEST"`（`app/src/main/java/org/fcitx/fcitx5/android/core/data/DataManager.kt:37`）。
- 发现：`queryIntentActivities(PLUGIN_INTENT, MATCH_ALL)`（`DataManager.kt:87-102`），然后从插件资源读 `xml/plugin` 描述（`DataManager.kt:106-136`），校验 `apiVersion == PluginDescriptor.pluginAPI`（`148-149` 起）。
- 插件清单需要声明 `<action android:name="${applicationId}.plugin.MANIFEST">` 的 Activity 与 `xml/plugin.xml`；官方示例 `plugin/clipboard-filter` 的 `plugin.xml`（`apiVersion 0.1`、`hasService=true`，第 3-5 行）。

### 4.2 权限边界【S】

- 主应用声明两个 **signature** 级权限：`${applicationId}.permission.IPC`（`AndroidManifest.xml:29-31`）、`${applicationId}.permission.PLUGIN`（`33-35`）。
- `FcitxRemoteService` 由 `permission.IPC` 保护（`AndroidManifest.xml:185-193`）；插件若要与主应用通信，必须在自己的清单里请求该权限（官方插件示例：`plugin/clipboard-filter/src/main/AndroidManifest.xml:6` `<uses-permission android:name="${mainApplicationId}.permission.IPC" />`）。
- 插件服务由 `permission.PLUGIN` 保护（插件示例清单 `10-18` 行：`android:permission="${mainApplicationId}.permission.PLUGIN"`），主应用持有该权限（主清单 37 行），因此只有**与主应用同签名**的插件 APK 才能完成绑定。
- **结论【S】**：插件体系不是“任意第三方 App 可接入”，而是同签名（signature）信任域。**同签名不等于零风险**：签名构建/发布链一旦被控制或插件进程被攻破，明文通路依然存在。

### 4.3 明文通路（transformer）【S】

链路：系统剪贴板 → `ClipboardManager.onPrimaryClipChanged`（`ClipboardManager.kt:452-493`）→ `ClipboardEntry.fromClipData(clip, transformer)`（`ClipboardEntry.kt:61-66`）→ `transformer(str)`（`ClipboardManager.kt:97` 的字段，由 `FcitxRemoteService.updateClipboardManager` 绑定为 `::transformClipboard`，`FcitxRemoteService.kt:68-75`）→ Binder `IClipboardEntryTransformer.transform(String)`（AIDL `IClipboardEntryTransformer.aidl:7`）→ 插件进程 `MainService`（`plugin/clipboard-filter/.../MainService.kt:17-24`，官方 ClearURLs 插件）。

- 该调用发生在**加密/落库之前**；插件看到的参数是原始剪贴板字符串（旧模式）。
- transformer 是 **同步 Binder 调用**，串行遍历所有插件（`FcitxRemoteService.kt:43-51`），单个插件卡顿会阻塞剪贴板事件协程（不是主线程，但会延迟落库/清空）。
- 插件异常被捕获并只记录类名/描述（`FcitxRemoteService.kt:43-51`），不会携带明文；插件进程崩溃通过 `linkToDeath` 注销（`96-114` 内 `transformer.asBinder().linkToDeath`，具体行号见 `FcitxRemoteService.kt:105-112`）。

### 4.4 插件网络能力【S/P】

- 主应用无 INTERNET 权限（见 1.4），插件是**独立 APK**；官方 `plugin/clipboard-filter` 的清单没有 INTERNET 权限（该目录清单仅含 uses-permission IPC 与 service）。
- 但签名信任域内的其他插件构建、或未来新增插件，**可以自行申请 INTERNET**；此时 transformer 收到的明文可被插件进程直接联网外传。这是“信任插件”的固有边界，不是主应用能通过权限撤销的（除非主应用拒绝向插件传明文，即严格模式）。
- T0 工作树严格模式：安全剪贴板开启且未显式允许插件时，`ClipboardManager.transformer = null`（`FcitxRemoteService.kt:62-75`），且通道 B 的门面层转换函数也先检查 `secureClipboardAllowPlugins`（`ClipboardManager.kt:213-221`）。**该控制是否覆盖所有通道 B 实现，取决于 T3 的 `SecureClipboardController`（T0 未落地）——未验证【T】。**

### 4.5 通知、广播、ContentProvider、WebView【S】

- 通知：全仓库通知内容均为固定字符串/文件名（`utils/AppUtil.kt:77-81`、`daemon/FcitxDaemon.kt:116-122`、`ui/setup/SetupActivity.kt:108-112` 等），不包含剪贴板内容。
- 广播：`FcitxApplication` 只注册 `ACTION_SHUTDOWN`、`ACTION_USER_UNLOCKED`、自定义 `ACTION_RESTART_FCITX_INSTANCE`（`FcitxApplication.kt:87,139,144-151`）；没有携带剪贴板内容的广播。
- ContentProvider：`FcitxDataProvider` 只把 `getExternalFilesDir(null)` 映射为 DocumentsProvider（`provider/FcitxDataProvider.kt:80-85`），由 `MANAGE_DOCUMENTS` 保护（清单 195-204）；**不暴露数据库/密钥**。
- WebView：`app/src` 内无 WebView 引用（grep 无匹配）；网络相关代码在 app 中无匹配（见 1.3）。

## 5. T0 工作树中的安全剪贴板改造（仅记录已存在的代码）

> 说明：本节记录 T0 快照时**已经存在**的代码。审计过程中 T1/T2/T3 的实现文件陆续落地（T1 已完成，T2/T3 部分完成），对实际实现的复核见 **附录 A（T1 快照增量）**；本节中“未落地”的描述仅代表 T0 时点。

### 5.1 新增/修改文件【S】

| 文件 | 状态 | 作用 |
| --- | --- | --- |
| `data/secureclipboard/SecureClipboardContract.kt` | 新增 270 行 | 共享模型/接口/常量/状态（`CaptureSource:31-48`、`CaptureRequest:51-60`、`SecureClipboardEntry:129-178`、`SecureClipboardLimits:181-196`、`SecureClipboardRepository:199-236`、`ClipboardSecurityStatus:248-272`） |
| `data/secureclipboard/SecureClipboardVault.kt` | 新增 40 行 | 进程级仓库单例；direct boot 不初始化（`9-16`） |
| `data/clipboard/ClipboardManager.kt` | 修改 561 行 | 双后端门面：旧库/新库、监听互斥、通道 A 保存入口、迁移、遮罩列表（`45-57` 类注释；`145-243`；`396-445`） |
| `data/clipboard/db/ClipboardEntry.kt` | 修改 69 行 | 增加 `@Ignore secure` 显示标记（`30-35`） |
| `data/clipboard/db/ClipboardDao.kt` | 修改 75 行 | 增加迁移用查询/清空（`65-74`） |
| `data/prefs/AppPrefs.kt` | 修改 | 新增 `Security` 偏好分类（`364-427`）、迁移标记（`42`）、随 DPS 同步（`488-497`） |
| `app/src/main/AndroidManifest.xml` | 修改 | 三个安全摘录 Activity（124-168） |
| `res/xml/data_extraction_rules.xml`、`full_backup_content.xml` | 修改 | 排除两类剪贴板数据库（见 3.9） |
| `FcitxRemoteService.kt` | 修改 | 严格模式禁用 transformer（62-75、96-124、135-160） |
| `core/Fcitx.kt` | 修改 | 安全记录不镜像进 fcitx 原生剪贴板（486-492） |
| `ui/main/settings/security/SecureClipboardSettingsFragment.kt` | 新增 215 行 | 设置页：开关、迁移、清空、状态展示（33-215） |
| `ui/main/MainFragment.kt`、`SettingsRoute.kt` | 修改 | 设置入口与路由（MainFragment 108-110；SettingsRoute 36、82、223-224） |
| `res/values*/strings.xml` | 修改 | 安全剪贴板文案（含中文翻译） |
| `res/drawable/ic_shield_24.xml` | 新增 | 盾牌图标 |

### 5.2 契约层关键设计（Contract.kt）【S】

- 密文实体只存 `ciphertext`、`iv`、`encryptedMetadata`、`keyVersion`、`createdAt`、`sourceType`、`isPinned`、`isDeleted`、`dedupTag`（`SecureClipboardEntry:129-178`，字段在 `130-149`）。**没有明文 text 列**；`dedupTag` 是带密钥 HMAC（`146-148` 注释）。
- 限制：`MAX_TEXT_LENGTH=100_000` UTF-16 code unit、`MAX_TEXT_UTF8_BYTES=400_000`、默认历史 1000、上限 5000、外部入口速率限制窗口 2s/3 次（`SecureClipboardLimits:181-196`）。
- `SecureClipboardRecord.toString()` 只输出长度、来源、pin，不含明文（`84-93`）——这是防日志泄漏的源码级设计【S】。
- 仓库接口：`allRows/itemCount/haveUnpinned/save/getRow/read/pin/replaceText/delete/deleteAll/undoDelete/realDelete/enforceLimit/migrateLegacy/clearAll/status`（`199-236`）。
- 状态：`ClipboardSecurityStatus` 只含计数/时间/错误枚举，不含明文（`248-272`）。

### 5.3 门面层（ClipboardManager.kt）【S】

- 双后端：安全模式开关 `securityPrefs.secureClipboardEnabled`（`100-101`）；旧库仍用 `clbdb`（`146`）；安全库经 `SecureClipboardVault.repository`（`84`、`128-132`、`253` 等）。
- 监听互斥：`182-195`（安全模式下移除旧 listener，仅由 `SecureClipboardController` 监听）。
- 安全模式保存入口：`saveSecureCapture()`（`248-258`）：未开启返回 `Disabled`、Vault 未初始化返回 `NotReady`，成功后调用 `onSecureEntryPersisted`。
- 安全记录对 UI 只暴露遮罩行：`onSecureEntryPersisted`（`224-243`）以 `maskedLabel`（`R.string.secure_entry_masked`，`77` 行）构造 `ClipboardEntry(secure=true)`；`allEntries()` 用 `SecureDisplayPagingSource` 把密文行映射为遮罩（`284-293`、`531-554`）。
- 单条读取：`readEntry/readText`（`262-280`）——只有显式调用才会解密一条记录。
- 迁移：`migrateLegacyIfNeeded`（`396-445`），成功后 `secure_delete=ON` + `DELETE` + `VACUUM` + WAL truncate（`420-428`），失败保留旧库并上报（`434-442`）。

### 5.4 已识别的集成缺口/风险（T0 快照）【P】

1. **T1/T2/T3 未落地**：`SecureClipboardRepositoryImpl`、`SecureClipboardController`、`ClipboardEventProcessor`、`SelectedTextCaptureController`、`SecureCaptureActivity` 等均不存在；`ClipboardManager` 对它们的引用意味着 **T0 工作树当前不可编译**（T1-T3 完成后才闭合）。
2. **遮罩字符串可能被当作正文提交**：`KawaiiBarComponent.kt:310-325` 的复制建议点击仍然 `service.commitText(it.text)`；安全模式下 `lastEntry.text` 是 `maskedLabel`（`ClipboardManager.kt:224-234`），若 T2/T3 未改造该路径，会把 “Encrypted secure record” 文字提交到输入框。**待 T2/T3 落地后复核【T】**。
3. **`ClipboardEditActivity` 安全模式路径**：`ClipboardManager.updateText` 在安全模式会把传入文本重新加密为 `CaptureSource.ImeSelection`（`321-337`），但编辑页仍会先 `ClipboardManager.get(id)`（旧 API，安全模式返回遮罩行，`262-269`），再 `setText(遮罩)`，用户编辑的起点是遮罩字符串；T3 计划修改该文件，未落地前**存在把遮罩文本写回保险库的风险**【P】。
4. **严格模式与 `EditorInfoInspector`**：开发者 Inspector 仍可主动把 EditorInfo（含 SurroundingText）写入系统剪贴板（见 3.7），严格模式没有覆盖该路径（安全剪贴板只控制自己的捕获链路）。【P】
5. **迁移后恢复用户数据**：见 3.10 的第 ② 点。【P】

## 6. 结论：分类清单

### 6.1 源码确认的安全机制【S】

1. 主应用无 INTERNET 权限，无网络客户端依赖（1.3/1.4）。
2. 插件 IPC 与插件服务均为 signature 级权限保护（4.2）。
3. 私有粘贴复用 `InputConnection.commitText`（3.6）；旧代码粘贴路径不写系统剪贴板。
4. 仓库基线没有把剪贴板正文写入日志的代码（3.8）。
5. 契约层密文实体无明文列，`toString` 不输出明文（5.2）。
6. 安全模式监听器互斥注册（`ClipboardManager.kt:182-195`）。
7. T0 工作树的备份规则排除两类剪贴板数据库（3.9）；迁移成功后才清旧表并 VACUUM（3.4）。
8. 安全记录不镜像进 fcitx 原生剪贴板模块（`core/Fcitx.kt:486-492`）。

### 6.2 源码确认的问题/风险（基线 commit 或 T0 工作树）【S/P】

1. 旧模式明文 `clbdb` 落盘，且**基线 commit 的备份规则没有排除数据库**（3.4/3.9）——T0 工作树已修复，需验证。
2. 旧模式下所有剪贴板明文先经 Binder 交给插件 transformer（3.3/4.3）；同签名信任域内插件可申请 INTERNET（4.4）。
3. 应用有两条主动写系统剪贴板的旧路径：编辑页复制、开发者 EditorInfo Inspector（3.7）。
4. `lastEntry` 与 Paging 列表在内存中持有完整明文（3.5）。
5. 用户数据导出会打包整个 `databases` 目录（3.10）。
6. Direct Boot 与普通模式的 `clbdb` 位于不同存储域（2.4）；安全保险库在 direct boot 不初始化。
7. 开发者 Inspector 会把 `SurroundingText` 复制到系统剪贴板（3.7）。
8. verbose 日志会记录组合文本，且 logcat 可导出（3.8）——与剪贴板正文无直接关系，但属于“输入内容外泄面”。
9. 时间戳去重/清空竞态依赖 `ClipDescription.timestamp` 与重新读取比较，Android 无原子 compare-and-clear（需求 4.4 已承认；实现见 T3 未落地）。

### 6.3 需要测试确认的项（T0 未验证）【T】

- 备份排除规则在真实备份/换机迁移中的效果（adb `bmgr`、Android 14/15/16）。
- 进程被杀、重启、锁屏、低电量、深度睡眠下普通复制的转存/清空行为（真机，OxygenOS/ColorOS）。
- 插件 transformer 在严格模式切换时的实际生效与竞态。
- Direct Boot 下 DPS/CES 两套数据库的历史可见性与残留。
- 用户数据导出/导入后迁移标记与明文库的状态。
- 所有 T1/T2/T3 新代码的加解密、状态机、粘贴会话校验（T0 未落地，不可能测试）。

## 7. 复现/复核命令（本报告使用，未运行 Gradle）

```powershell
git -C <本仓库根目录> rev-parse HEAD
git -C <本仓库根目录> status --porcelain=v1
git -C <本仓库根目录> submodule status --recursive
Get-ChildItem -Recurse -Include *.kt <本仓库根目录>\app\src\main\java | Select-String -Pattern 'setPrimaryClip|primaryClip|commitText\('
Select-String -Path <本仓库根目录>\app\src\main\AndroidManifest.xml -Pattern 'permission|exported|allowBackup'
```

> 说明：本文没有、也不能代替构建与真机测试。任何“已验证”“已通过”的结论都不在本文中；T0 未执行任何 Gradle 构建或 Android 测试。

## 附录 A：T1 快照增量（实现落地后的复核，2026-10-31）

> 审计期间 T1（加密存储）报告完成，T2（安全摘录）与 T3（通道 B/粘贴/面板）仍在进行，T4（测试）部分落地。本节只复核**已写入工作树**的代码，行号为本附录编写时的快照；仍以“文件存在 ≠ 可编译 ≠ 已验证”为前提。

### A.1 已落地实现清单【S】

| 模块 | 文件 | 规模 |
| --- | --- | --- |
| AES-256-GCM | `data/secureclipboard/crypto/ClipboardCrypto.kt` | 210 行 |
| Keystore 密钥 | `data/secureclipboard/crypto/ClipboardKeyManager.kt` | 173 行 |
| 加密库 | `SecureClipboardDatabase.kt`、`SecureClipboardDao.kt`、`SecureClipboardMetadata.kt` | 41 / 84 / 120 行 |
| 仓库/策略 | `SecureClipboardRepositoryImpl.kt`（443 行）、`SecureClipboardPolicy.kt`（103 行）、`SecureClipboardMigration.kt` | — |
| 通道 B 状态机 | `ClipboardEventProcessor.kt`（约 540 行） | — |
| 通道 B 控制器 | `data/clipboard/SecureClipboardController.kt`（365 行） | — |
| 通道 A 外部入口 | `ui/securecapture/{BaseSecureCaptureActivity,SecureCaptureActivity,SecureShareReceiverActivity,SecureCaptureValidator,SecureUnlockActivity,SecureUnlockGate}.kt` | 244 / 37 / 51 / 158 行等 |
| 通道 A IME 入口 | `input/secureclipboard/{SelectedTextPolicy,SelectedTextCaptureController}.kt` + `KawaiiBarComponent` 盾牌/安全建议（T2 修改） | 104 / 151 行 |
| 设置页 | `ui/main/settings/security/SecureClipboardSettingsFragment.kt` | 215 行 |
| 单测 | `app/src/test/java/.../secureclipboard/{ClipboardCryptoTest,SecureClipboardPolicyTest,SecureClipboardMigrationTest,CryptoPerformanceBenchmarkTest}.kt` | 未执行 |
| 测试 App | `内部测试探针**` | 未执行 |

### A.2 加密实现复核【S】

- 算法与参数：`AES/GCM/NoPadding`（`ClipboardCrypto.kt:99`）；密钥 256 bit（`:88`）；IV 12 字节（`:91`）；tag 128 bit（`:94`）；`cipher.init(ENCRYPT_MODE, key, secureRandom)` 由 provider 生成随机 IV（`:122-134`）；解密用 `GCMParameterSpec(TAG_SIZE_BITS, iv)`（`:160-163`）。
- IV 校验：解密前拒绝非 12 字节/全零 IV（`:149-158`）；加密后拒绝 provider 返回的异常 IV（`:129-134`）。
- AAD：`SecureClipboardAad.payload/metadata(keyVersion, sourceType)` 绑定 schema 版本、用途、密钥版本、来源（`:197-210`）；**不绑定 createdAt**（注释说明去重刷新时间不重新加密，`:191-192`）。
- 异常：类型化 sealed 异常，消息只含错误类别/长度，不含明文/密钥/IV（`:23-45`）；`EncryptedPayload.toString` 只输出长度（`:63-64`）。符合“日志/异常不得包含明文、密钥、IV”规则。
- Keystore：AES-256 KeyGenParameterSpec（GCM + NoPadding + `setRandomizedEncryptionRequired(true)`，`ClipboardKeyManager.kt:142-157`）；HMAC-SHA256 去重键（`:159-172`）；密钥错误/失效映射为 `KeyUnavailable/KeyInvalid`，不静默重建密钥（`:102-140`）；密钥不写 SP/DB/日志【S】。
- 发现【P】：`CURRENT_KEY_VERSION` 固定为 1，`obtain()` 对已存在密钥总是返回 `CURRENT_KEY_VERSION`（`ClipboardKeyManager.kt:117,120`）；当前 v1 无问题，但未来轮换密钥时无法从 Keystore 侧识别旧密钥版本，需要在库中记录并处理。

### A.3 仓库/数据库复核【S】

- 密文实体：`SecureClipboardEntry` 只有 ciphertext/iv/encryptedMetadata/keyVersion/createdAt/sourceType/isPinned/isDeleted/dedupTag，无明文列（`SecureClipboardContract.kt:129-178`）；库名 `secure_clbdb`（`SecureClipboardDatabase.kt:29`）。
- 保存顺序：校验 → 取加密/HMAC 密钥 → 去重 → 加密 → Room 事务插入 + 限额 → `SaveResult`（`SecureClipboardRepositoryImpl.kt:117-168`）；失败先于任何写入，不会删除已存在原文。
- 去重：HMAC-SHA256(dedupKey, plaintext) 十六进制（`SecureClipboardPolicy.kt:50-75`）；去重命中只刷新 `createdAt`（`RepositoryImpl.kt:133-139`），不重新加密（与 AAD 不绑定 createdAt 一致）。
- 读取：单条 `read(id)` 解密，失败（认证失败/密钥缺失/行不可读）返回 null（`RepositoryImpl.kt:172-194`）。
- 元数据：`encryptedMetadata` 由独立随机 IV 加密，IV 前缀拼接（`SecureClipboardMetadata.kt:105-119`）；避免与 payload IV 复用。
- 发现【P】R5：`save()` 构造默认 `historyLimit = SecureClipboardLimits.DEFAULT_HISTORY_LIMIT`（1000）（`RepositoryImpl.kt:45`），事务内按该值裁剪（`:153-157`）；而设置项允许到 5000（`AppPrefs.kt:418-426`）。若 Vault 未注入偏好值，设置 5000 实际会被 1000 截断——需 维护者/T1 确认并修复。
- 发现【P】R15：`realDelete()`/`clearAll()` 只执行 SQL DELETE，未 `VACUUM`/`secure_delete`（`RepositoryImpl.kt:281-283,311-313`），密文页可能残留（无密钥不可读，但“删除≠物理不存在”）。
- 发现【P】R14：安全库降级到旧 schema 使用 `fallbackToDestructiveMigrationOnDowngrade(dropAllTables=true)`（`SecureClipboardDatabase.kt:31-39`），会清空密文历史；这是“避免崩溃”的取舍，需在验收中声明。
- 发现【P】：`SecureClipboardPolicy.validate()` 默认拒绝 `sensitive=true` 的请求（`SecureClipboardPolicy.kt:31-47`）；IME 选区在 `IME_FLAG_NO_PERSONALIZED_LEARNING` 下会标记 sensitive（`SelectedTextPolicy.kt:93-94`），因此这条路径实际会返回 `SensitiveRejected`（按钮可用但保存失败）——符合“不自动收集敏感内容”，但 UI 提示与可用性需确认。

### A.4 通道 B（SecureClipboardController + ClipboardEventProcessor）复核【S】

- 监听：安全模式下由门面注册 `SecureClipboardController`（`ClipboardManager.kt:182-195`），控制器在 `onPrimaryClipChanged` 读取并投递信号（`SecureClipboardController.kt:112-124`）。
- 只接受单一 `text/plain`（`SecureClipboardController.kt:167-209`）；拒绝多 item、混合 mime、URI/图片/HTML、敏感标记、空白、超长；不会为不支持内容建立明文缓存（`:242-267` 映射为 Unsupported/Empty，不保留文本）。
- 快照/清空：落库成功后重读并比较文本 + 时间戳（API 26+），不一致返回 `Stale` 且不清空（`ClipboardEventProcessor.kt:442-468`）；清空后武装 self-clear 窗口避免自身回调被当成新复制（`:493-502`）。
- API 兼容：`clearPrimaryClip()` 仅 API 28+；低于 28 用 `setPrimaryClip(空文本)` 回退（`SecureClipboardController.kt:226-238`），符合 minSdk 23 的兼容要求。
- 兼容模式：`secureClipboardClearAfterSave` 与 `secureClipboardCompatMode` 共同决定是否清空（`SecureClipboardController.kt:269-277`）；兼容模式仍加密保存、但保留系统剪贴板（需求 9 的行为）。
- 发现【P】R4：敏感标记剪贴板在安全模式下判为 Unsupported（`SecureClipboardController.kt:188-190`），处理路径不清空（`ClipboardEventProcessor.kt:379-385`）→ 敏感内容既不保存也不清空，仍留在系统剪贴板。需产品决策并写入验收说明。
- 发现【P】：`SecureClipboardController.setListening` 注释要求主线程；门面在 `Application.onCreate` 与偏好回调中调用（`ClipboardManager.kt:168-171,182-195`），需确认所有调用点都在主线程。

### A.5 通道 A（T2）复核【S】

- 校验器：`SecureCaptureValidator` 无 Android 依赖、可 JVM 测试；校验 action/type/CharSequence 类型、长度与 UTF-8 字节、控制字符归一化（`SecureCaptureValidator.kt:65-143`）；预览只渲染遮罩（`:145-150`）。
- 确认页：`BaseSecureCaptureActivity` 设置 `FLAG_SECURE`（`:61-70`，第 64 行）、只显示遮罩/长度/来源（`:122-136`）、用户点“保存加密”后才写入（`:161-191`）、返回 `RESULT_OK` 不带数据（`:177-183`）、不写系统剪贴板；`onDestroy` 释放 `pendingRequest`（`:238-243`）。
- PROCESS_TEXT/SEND：两个子类分别只读 `EXTRA_PROCESS_TEXT`/`EXTRA_TEXT`，SEND 额外检查敏感标记（`SecureShareReceiverActivity.kt:24-50`）；不读取来源 App 其他数据。
- IME 选区：`SelectedTextCaptureController` 仅在用户点击盾牌时读取一次 `getSelectedText(0)`（`:92-100`），读取后核对 inputConnection 身份与 EditorInfo（`:111-118`），密码框拒绝（`SelectedTextPolicy.kt:82-92`）；不改变选区、不写剪贴板。
- 盾牌接线：`ButtonsBarUi.shieldButton` 默认 GONE（`ButtonsBarUi.kt` 末尾），`KawaiiBarComponent` 仅在安全模式 && 默认输入法时显示，并按选区/编辑器策略置灰（T2 diff）。
- 已修复 T0 时的集成缺口：安全建议点击不再 `commitText(遮罩)`，而是 `readText(id)` + 会话校验 + `commitText`（T2 diff；`KawaiiBarComponent` 的 `pasteSecureSuggestion/decryptAndCommitSuggestion`），并支持可选设备凭据（`SecureUnlockGate`）。仍需真机/自动化验证【T】。

### A.6 仍未完成的事项（截至本附录）【P】

1. T3 面板/编辑页改造未落地：`input/clipboard/ClipboardWindow.kt:126-129` 仍直接 `commitText(entry.text)`；`ClipboardEditActivity.kt:47-55` 仍可把编辑内容写系统剪贴板；长按分享（`ClipboardWindow.kt:108-117`）未按安全模式禁用；`SecureClipboardPasteController` 文件未见到。
2. T4 instrumented 测试未落地：安全测试用例未见到；probe App 代码存在但未运行。
3. 未构建、未跑任何测试：本任务不运行 Gradle；T1 单测存在但未执行。
4. 上述 R4/R5/R14/R15 与插件严格模式覆盖需 维护者 在最终集成时确认。

### A.7 复核结论

- T1 加密实现与契约一致，未发现明文/密钥/IV 进入日志或异常消息的代码路径；AAD/IV/tag/密钥失效处理符合需求 5.1。
- T2 通道 A 的实现满足“用户主动、确认、不写剪贴板、FLAG_SECURE”的关键要求（源码层面）。
- T3 通道 B 的控制器与状态机已实现竞态缓解与 API 23+ 清空回退；但面板/粘贴 UI 集成未完成，因此“安全粘贴不写系统剪贴板”的端到端结论仍不成立（旧路径存在）。
- 所有上述内容都**未经构建与测试**；对外的最终状态以 `06-unresolved-risks-and-acceptance.md` 为准。

### A.8 T2 快照增量（T3/T4 文件落地后，2026-10-31 15:58 前后）

> 此时 T3 的粘贴控制器与面板/编辑页改造、T4 的 instrumented 测试文件均已写入工作树（任务状态仍为 in_progress）。以下仅登记新增事实，**仍未构建/未测试**。

- T3 粘贴实现：`input/secureclipboard/{SecureClipboardPasteController.kt（107 行）,PasteSession.kt（42 行）}`；流程为捕获 `PasteSession` → 可选认证 → 单条 `readRecordText` → 再次校验会话 → `FcitxInputMethodService.commitText`；异常只记录类名（`SecureClipboardPasteController.kt:83-105`）。
- 面板集成：`ClipboardWindow.kt` 安全记录走 `pasteSecureController.paste(id)`（约 92-102、220-238 行）；安全记录禁用分享（`onShare` 分支，约 129-133 行）；新增一次性“显示”对话框（`revealSecureEntry`，约 244-271 行）仅展示单条、不提供复制。
- 编辑页：安全模式下禁用“复制到系统剪贴板”（`ClipboardEditActivity.kt:40,43,53-60`），并改为单条读取（约 86 行起）。
- **发现【P】R21（新增）**：`ClipboardWindow` 构造 `SecureClipboardPasteController` 时 `authenticate = null`（约 99-100 行，TODO 注释称待 维护者 接入 T2 `SecureUnlockGate`）。因此在面板中启用“粘贴前要求设备认证”后，粘贴会以 `AuthenticationUnavailable` **失败关闭**（不会绕过认证，但功能不可用）；KawaiiBar 建议路径已接 `SecureUnlockGate`，两条路径行为不一致。
- **发现【P】R22（新增）**：一次性“显示”对话框在 IME 窗口内展示完整明文（`revealSecureEntry`，约 244-271 行），未见 `FLAG_SECURE`；属用户主动显示，但截屏/录屏风险需在验收说明中披露。
- T4：`app/src/androidTest/java/org/fcitx/fcitx5/android/data/secureclipboard/{ClipboardSecurityInstrumentedTest,SecureClipboardStorageInstrumentedTest,SecureClipboardTestSupport,ClipboardProbeClient}.kt` 已写入，**未执行**；探针 App `内部测试探针**` 已写入，**未运行**。
- 因此 A.6/A.7 中“面板/粘贴 UI 未落地”的判断在 T2 快照已过时：改为“已落地（源码），未验证”；其余未完成事项（未构建、单测未跑、R4/R5/R14/R15 待确认）不变。
- 行号说明：T2 快照中 `ClipboardWindow.kt` 已扩展安全模式逻辑，旧 T0 行号不再适用；当前文件的关键行号：`onShare` 129 行（安全记录禁用分享 129-133）、安全记录点击 `pasteSecureEntry` 159 行、非安全记录 `service.commitText(entry.text)` 162 行、`pasteSecureEntry` 定义 220 行、`revealSecureEntry` 定义 244 行（“显示”对话框 244-271）。本报告其余 T0 行号引用仍以基线 commit 的 `ClipboardWindow.kt` 为准。

## 附录 B：最终集成与真实构建/测试证据（维护者 执行，2026-10-31）

> 本附录记录 维护者 在最终集成后执行的真实 Gradle 构建/测试结果（本任务未运行 Gradle；以下为 维护者 提供的原始结果）。它**取代附录 A.6/A.7 中“未构建/未测试/面板未落地”的时点性判断**。

### B.1 构建与测试结果

| 命令 | 结果 | 备注 |
| --- | --- | --- |
| `:app:compileDebugKotlin` | **BUILD SUCCESSFUL** | Gradle 9.6.1 + AGP 9.4.1 + Kotlin 2.4.10，JDK 17 |
| `:app:testDebugUnitTest` | **81 个安全剪贴板 JVM 单测全部通过**（含 20 个加密备份测试）；更早全量运行中 1 个失败 | 失败为上游既有 `ThemeSerializationTest.version2`（测试 `app/src/test/java/org/fcitx/fcitx5/android/ThemeSerializationTest.kt:68` 断言 v2.0 不迁移，而 `CustomThemeSerializer.CURRENT_VERSION="2.1"`：`app/src/main/java/org/fcitx/fcitx5/android/data/theme/CustomThemeSerializer.kt:90`）；**既有失败、与本改动无关，不得写成“全部通过”** |
| `:app:compileDebugAndroidTestKotlin` | **BUILD SUCCESSFUL** | instrumented 仅编译，**未执行**（无设备） |
| `内部测试探针构建（已移除）` | **BUILD SUCCESSFUL** | APK 962,872 字节；aapt 确认零权限（无 INTERNET） |
| `内部红队工具构建（已移除）` | **BUILD SUCCESSFUL** | 红队攻击 App 982,453 字节；零权限、无 INTERNET；仅测试设备使用 |
| `:app:assembleDebug` | **BUILD SUCCESSFUL** | 含 NDK/CMake 原生构建（仅 arm64-v8a，NDK 28.0.13004108 + CMake 3.31.6）；APK `app/build/outputs/apk/debug/org.fcitx.fcitx5.android-eb5b502-arm64-v8a-debug.apk`，69,819,756 字节，SHA-256 `D7C880B1D150937E24CF3057DF8B0D458A035B516270735B069CE69740ADE86B`（最终构建）；versionCode 112 / versionName eb5b502 / minSdk 23 / targetSdk 36；aapt 确认仅 VIBRATE、POST_NOTIFICATIONS 与两个 signature 自定义权限（**无 INTERNET**）；apksigner 验证签名有效（CN=Android Debug）；manifest 含 SecureCaptureActivity（PROCESS_TEXT）、SecureShareReceiverActivity（ACTION_SEND）、SecureUnlockActivity（未导出）；APK 内含 arm64-v8a fcitx5/libime 原生库。**真机未安装运行**；另用同一源码构建 x86_64 APK 在 Android 16 模拟器上做了功能实测（见 B.5）。 |

新增单测分布（61）：`ClipboardCryptoTest` 17、`ClipboardEventProcessorTest` 15、`SecureClipboardPolicyTest` 17、`SecureClipboardMigrationTest` 10、`CryptoPerformanceBenchmarkTest` 2。

### B.2 对附录 A 的状态更正

| A 中描述 | 最终状态（源码/构建复核） |
| --- | --- |
| A.6 “T3 面板/编辑页改造未落地” | 已落地：`ClipboardWindow.kt:98-121,220+`（安全粘贴 + 认证 gate + reveal FLAG_SECURE 279-280）、`ClipboardEditActivity.kt:43,53-60`（安全模式禁用复制）、安全记录分享禁用（`ClipboardWindow.kt:129-133`） |
| A.6 “T4 instrumented 未落地” | 已写入并编译通过：`app/src/androidTest/java/.../secureclipboard/{ClipboardSecurityInstrumentedTest,SecureClipboardStorageInstrumentedTest,SecureClipboardTestSupport,ClipboardProbeClient}.kt`；**未执行** |
| A.6 “未构建、未跑测试” | `:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`:app:compileDebugAndroidTestKotlin`、`内部测试探针构建（已移除）`、`:app:assembleDebug` 均已由 维护者 执行（结果见 B.1） |
| A.6 “R4/R5/R14/R15 待确认” | R5/R8/R10/R12/R14(部分)/R15(部分)/R16/R18/R21/R22 已修复（源码复核见 `06` 第 10.2 节）；R4 为产品决策保留 |

### B.3 审计结论的最终状态

- **基线 commit 的风险结论不变**：旧库明文、插件 Binder 明文通路、系统剪贴板短暂暴露、目标 App 收到粘贴明文、物理残留、进程不能常驻——这些是平台/架构层面的边界，已修复项不能消除它们。
- **新增实现的安全结论**：加密/状态机/通道 A/B/设置/迁移在源码层面与契约一致（附录 A + B.2）；JVM 单测提供加密、策略、迁移、事件状态机的自动化证据；**已有 Android 16 x86_64 模拟器上的功能实测**（B.5），但**没有真机/OEM 与 instrumented 自动化证据**。
- **仍然未验证**：instrumented 自动化测试、真机（OxygenOS/ColorOS）、锁屏/Direct Boot/重启/低电量场景、设备侧性能功耗、备份/导出真机行为、OEM 剪贴板历史。
- **仍未实现**：隐私历史搜索、剪贴板记录导出（用户数据导出明确排除剪贴板库，见 `06` 第 10.3 节）。
- **不得对外宣称**：全量单测全部通过、真机/OEM 安全效果已验证、任何情况下都不写系统剪贴板、OxygenOS/ColorOS 兼容性已验证。
- **可以宣称**：`:app:assembleDebug` 已成功产出 arm64-v8a debug APK（aapt/apksigner 检查通过）；同一源码的 x86_64 构建已在 Android 16 模拟器上实测通道 A/B、加密落库、立即清空系统剪贴板与旧明文迁移清理（B.5）；**真机仍未经运行验证**。

### B.4 复核命令（维护者 已执行；本报告未自行运行）

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat 内部测试探针构建（已移除）
.\gradlew.bat :app:assembleDebug -PbuildABI=arm64-v8a -PbuildTools=36.0.0
```

### B.5 模拟器功能实测（维护者，2026-10-31 17:00 后）

- 设备：AVD `Pixel_9_Pro_XL`，Android 16（API 36），x86_64；安装同源码构建的 x86_64 debug APK。
- 结果：旧模式仍能读取系统剪贴板；安全模式普通复制加密落库后**立即清空系统剪贴板**；输入法在后台（仍为默认输入法）时也能转存；`ACTION_PROCESS_TEXT` 安全摘录确认→加密→落库且**全程不写系统剪贴板**；迁移后旧明文表 0 行；加密库主文件与 WAL 扫描不到明文。
- 实测发现并修复：迁移后旧表未清空（`execSQL(PRAGMA ...)` 抛 `SQLiteException`），改用 `query()` 执行 PRAGMA 并逐步容错，复测旧表 0 行。
- 证据：`docs/security/evidence/emulator-functional-test-evidence.md`、`08` 第 9 节。
- 边界：模拟器 ≠ 一加/OxygenOS/ColorOS 真机；instrumented 自动化仍未运行；真机性能功耗未测。
