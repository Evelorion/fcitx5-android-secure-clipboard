# 07 测试方案与 instrumented 测试

> 作者：T4 test-harness（安全剪贴板双通道项目）
> 仓库：D:\\KEYBORD\\src\\fcitx5-android（master eb5b502）
> 关联实现：app/src/main/java/org/fcitx/fcitx5/android/data/secureclipboard/**、data/clipboard/**、ui/securecapture/**、tools/clipboard-probe/**、app/src/androidTest/**
> 关联文件：docs/security/00-architecture-and-file-ownership.md

## 0. 诚实声明（必读）

本文件中的**“实现状态”只表示测试代码/工具代码已经提交到仓库**，不代表测试已经执行过。
T4 受项目铁律约束**没有运行 Gradle**，本次会话内**也没有连接真机或模拟器执行任何 instrumented 测试**。
因此：

- 所有构建结论（能否编译通过）由 Lead 统一构建时确认，T4 不声称“已编译通过”；
- 所有 instrumented 测试、adb 手工步骤的执行结果一律标记为“未执行”，不得在验收报告里写成通过；
- 只有 T1/T3 在本地 JVM 上实际跑过的单测，才能在验收时引用其结果（以他们的报告为准）。

## 1. 测试资产清单

### 1.1 独立测试 App：tools/clipboard-probe

| 文件 | 作用 |
| --- | --- |
| tools/clipboard-probe/build.gradle.kts | 独立 Android application 模块（alias(libs.plugins.android.application)，不依赖 build-logic convention 插件），零第三方依赖、纯 Java、compileSdk 36 / minSdk 23 / targetSdk 36，applicationId 与 namespace 均为 org.fcitx.fcitx5.android.probe |
| tools/clipboard-probe/src/main/AndroidManifest.xml | 不声明任何权限（尤其没有 INTERNET）；一个 Launcher Activity + 一个只读 ContentProvider |
| .../probe/ProbeLog.java | 内存事件环形缓冲（最多 256 条）：时间戳、正文长度、MIME flags、事件发生时 probe 是否在前台；**不保存正文** |
| .../probe/ProbeProvider.java | 导出的只读 ContentProvider（authority org.fcitx.fcitx5.android.probe.events），提供 status / events 查询与 clear / ensureListening / readCurrentClipboard 方法 |
| .../probe/MainActivity.java | 手工测试 UI：a) 后台静默读取剪贴板长度；b) 显示最近 N 次事件；c) 判断最近是否有前台 App 写剪贴板 |
| tools/clipboard-probe/README.md | 构建/安装/查询命令 |

设计约束：probe 与 :app **没有模块依赖关系**，需要单独安装；它只记录“剪贴板发生过变化”这一事实和时间/长度，
不记录正文，因此即使被其他 App 读取 provider 也不会泄露剪贴板内容。probe 仅用于测试设备，不进入发布包。

### 1.2 instrumented 测试：app/src/androidTest/java/org/fcitx/fcitx5/android/data/secureclipboard/

| 文件 | 覆盖内容 |
| --- | --- |
| ClipboardProbeClient.kt | 通过 ContentProvider 读取 probe 状态/事件；probe 未安装时自动 assume 跳过；提供 readCurrentClipboard 与前台拉起 probe 的辅助函数 |
| SecureClipboardTestSupport.kt | 目标 Context、唯一标记生成、轮询等待、vault 初始化、默认输入法判断、私有目录明文扫描、logcat 明文扫描、SecureMode 偏好开关（opt-in + 还原） |
| SecureClipboardStorageInstrumentedTest.kt | 加密存储层：仅密文落盘、IV=12 字节、解密往返、去重、空/超长/控制字符/孤立代理项/敏感内容拒绝、私有目录无明文、logcat 无明文、存储路径不写系统剪贴板、缺失记录读取返回 null |
| ClipboardSecurityInstrumentedTest.kt | 外部通道与剪贴板隔离：安全摘录(facade)不写系统剪贴板、解密读取不写剪贴板、PROCESS_TEXT Activity 不写剪贴板、ACTION_SEND Activity 不写剪贴板、非法 Intent 被拒绝且不写剪贴板、普通复制→加密→立即清空、连续 3 次复制后清空 |

补充说明：

- 测试**不新增任何依赖**：只使用 app 已有的 androidTest 依赖（androidx.test:runner / rules、junit4）和 Android 平台 API；
- 测试**不修改 app/src/main**（只读取 Lead 已固定的 Contract.kt / SecureClipboardVault.kt / ClipboardManager.kt 门面 API）；
- 测试运行在 **app 进程内**（标准 AndroidJUnitRunner 行为），因此无法自动点击确认对话框，也无法模拟真实输入法会话；这两类场景见第 4 节手工步骤；
- 所有依赖设备状态的用例使用 JUnit assume()：probe 未安装、vault 不可用（direct boot/Keystore 不可用）、安全模式关闭且未 opt-in、app 不是默认输入法时自动跳过，不判失败。

### 1.3 构建入口

settings.gradle.kts 末尾只追加了一行：

    include(":tools:clipboard-probe")

该模块**不是** :app 的依赖，不参与 app 打包；Lead 需要单独构建/安装它。

## 2. 测试分层

| 层 | 内容 | 执行者 | 本文件是否覆盖 |
| --- | --- | --- | --- |
| JVM 单元测试（app/src/test） | 加密算法、策略校验、迁移计数（T1）；事件状态机/竞态（T3，计划中） | T1 / T3（本地跑，不依赖设备） | 仅登记，不重复实现 |
| Instrumented 测试（app/src/androidTest） | 真实 Room/Keystore/剪贴板/Activity 行为 | Lead 在模拟器/真机执行（T4 提供代码） | 是，见第 3/4 节 |
| 手工 adb + probe | 端到端用户路径、IME 盾牌按钮、设备凭据、进程死亡、OEM 行为 | 测试人员按第 5 节执行 | 是，见第 5 节 |
| 审计/静态检查 | 源码数据流、日志、备份、插件通路 | T5 报告 | 否（见 docs/security/01..06） |

## 3. 测试矩阵

状态列含义：**已实现-未执行** = 测试代码已提交但本次会话未执行；**JVM-未执行** = 由 T1/T3 单测覆盖；
**手工-未执行** = 只能按第 5 节人工验证；**不覆盖** = 当前无自动化或需专用硬件。

### 3.1 功能

| ID | 场景 | 层级 | 自动化用例 / 手工步骤 | 预期 | 状态 |
| --- | --- | --- | --- | --- | --- |
| F-01 | 安全摘录（PROCESS_TEXT）保存到加密库，不写系统剪贴板 | instrumented | ClipboardSecurityInstrumentedTest.secureCaptureActivityDoesNotWriteSystemClipboard | probe 事件数不变；确认后 vault 条数 +1（确认点击为手工） | 已实现-未执行 |
| F-02 | 安全分享（ACTION_SEND text/plain）同上 | instrumented | ClipboardSecurityInstrumentedTest.secureShareIntentDoesNotWriteSystemClipboard | probe 事件数不变 | 已实现-未执行 |
| F-03 | 输入法选中文字（盾牌按钮）→ 加密保存 | 手工 | 第 5 节步骤 4 | 无转写剪贴板；无授权/密码框时拒绝 | 手工-未执行 |
| F-04 | facade 通道 A 保存不碰系统剪贴板 | instrumented | secureCapturePathDoesNotWriteSystemClipboard | probe 事件数不变 | 已实现-未执行 |
| F-05 | 加密存储只存密文、IV=96bit、可解密往返 | instrumented | SecureClipboardStorageInstrumentedTest.captureIsStoredAsCiphertextOnlyAndRoundTrips | ciphertext/encryptedMetadata 均不含明文；read 返回原文 | 已实现-未执行 |
| F-06 | 相同内容去重 | instrumented | duplicateCaptureIsDeduplicated | 两次 save 返回同一 id | 已实现-未执行 |
| F-07 | 异常 Unicode（emoji/ZWJ/组合字符/RTL）往返 | instrumented | unusualUnicodeRoundTrips | 解密结果与原文一致 | 已实现-未执行 |
| F-08 | 普通复制 → 加密保存 → 系统剪贴板立即清空 | instrumented | plainCopyIsEncryptedAndSystemClipboardIsCleared | vault 条数增加；probe 读到剪贴板为空；事件被记录 | 已实现-未执行 |
| F-09 | 私有粘贴不写系统剪贴板 | instrumented（部分）/手工 | encryptedReadDoesNotWriteSystemClipboard + 第 5 节步骤 6 | 解密读取不产生剪贴板事件；真实 commitText 粘贴后其他 App 粘不出内容 | 已实现-未执行 |
| F-10 | 剪贴板面板显示遮罩、pin/delete、分享禁用 | 手工 | 第 5 节步骤 7 | 列表显示遮罩；安全记录无“分享”；删除/置顶正常 | 手工-未执行 |
| F-11 | 旧明文库迁移：逐条加密、校验条数后清空并 VACUUM | instrumented（数据准备复杂）/手工 | 第 5 节步骤 8；T1 SecureClipboardMigrationTest | MigrationResult 计数一致；clbdb 表为空或文件被清空 | JVM-未执行 / 手工-未执行 |
| F-12 | 历史条数上限、清空安全历史 | 手工 | 第 5 节步骤 9 | 超过上限的最旧未 pin 记录被删除；清空保留 pinned | 手工-未执行 |

### 3.2 安全

| ID | 场景 | 层级 | 自动化用例 / 手工步骤 | 预期 | 状态 |
| --- | --- | --- | --- | --- | --- |
| S-01 | app 私有目录（db/wal/shm/shared_prefs/files/cache）搜不到明文 | instrumented | SecureClipboardStorageInstrumentedTest.captureIsStoredAsCiphertextOnlyAndRoundTrips（assertNoPlaintextInAppStorage） | 全文扫描无明文标记 | 已实现-未执行 |
| S-02 | logcat 无明文 | instrumented | noPlaintextLeaksIntoLogcat | logcat 最近 4000 行无明文标记 | 已实现-未执行 |
| S-03 | 安全摘录/私有粘贴期间 probe 记录不变（绕过系统剪贴板） | instrumented | secureCaptureActivityDoesNotWriteSystemClipboard / encryptedReadDoesNotWriteSystemClipboard | probe 事件计数不变 | 已实现-未执行 |
| S-04 | 恶意输入：空/全空白/超长/控制字符/孤立代理项/敏感标记 | instrumented | emptyOversizedMalformedAndSensitiveCapturesAreRejected | 分别返回 EmptyText/TooLargeText/UnsupportedFormat/SensitiveRejected，且不落库 | 已实现-未执行 |
| S-05 | 恶意/非法 Intent：无 ACTION_PROCESS_TEXT、缺 EXTRA、错误 action | instrumented | invalidSecureCaptureIntentIsRejectedWithoutClipboardWrite | Activity 结束（拒绝）且不写剪贴板、不崩溃 | 已实现-未执行 |
| S-06 | 连续复制（3 次快速）不崩溃、最终清空 | instrumented | burstCopiesAreHandledAndSystemClipboardEndsCleared | 至少 1 条入库；剪贴板最终为空 | 已实现-未执行 |
| S-07 | 插件 strict 模式：transformer 收不到明文 | 手工/审计 | 第 5 节步骤 10；T5 源码审计 | secureClipboardAllowPlugins=false 时插件回调不触发（或不可见明文） | 手工-未执行 |
| S-08 | 备份/恢复不含加密库明文、dataExtractionRules 排除 | 审计/手工 | T5 报告；第 5 节步骤 11 | 备份 XML 不含 secure_clbdb 与明文 | 手工-未执行 |
| S-09 | 失败路径不破坏原文：加密/写入失败不清除旧记录、不清空剪贴板 | JVM + 手工 | T3 ClipboardEventProcessorTest（计划）；第 5 节步骤 12 | 保存失败时记录仍在、系统剪贴板不被误清 | JVM-未执行 / 手工-未执行 |
| S-10 | 竞态：A→B→C 连续复制、保存中 B 更新、自身 clear 去重、快照不一致 STALE_CLIP | JVM | T3 ClipboardEventProcessorTest（计划） | 见 T3 单测断言 | JVM-未执行 |

### 3.3 兼容性

| ID | 场景 | 层级 | 方式 | 预期 | 状态 |
| --- | --- | --- | --- | --- | --- |
| C-01 | Android 14 / 15 / 16 模拟器基本路径 | 手工 | 第 5 节全流程 | F/S 矩阵通过 | 未执行 |
| C-02 | Android 10+ 后台剪贴板读取限制下的降级 | 审计/手工 | 第 5 节步骤 5 的说明 | app 不是默认 IME 时不自动转写，不崩溃；设置页可解释 | 未执行 |
| C-03 | direct boot（首次解锁前）不初始化 vault、不崩溃 | 手工 | 解锁前调用设置/剪贴板面板 | 面板为空，状态显示未初始化 | 未执行 |
| C-04 | 进程被杀/后台限制：普通复制不转写 | 手工 | 杀进程后复制，再打开 app | 不崩溃；无记录；报告已知限制 | 未执行 |
| C-05 | 锁屏/重启后 Keystore 失效路径（KeyPermanentlyInvalidatedException） | 手工 | 改锁屏密码后读取旧记录 | 返回不可读/拒绝，不崩溃 | 未执行 |
| C-06 | OEM（OxygenOS/ColorOS）后台存活与剪贴板回调差异 | 手工 | 真机 | 行为差异记录，不承诺常驻 | 未执行 |

## 4. Instrumented 测试运行方法（Lead 执行）

### 4.1 构建

    .\\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest "-PndkVersion=28.2.13676358" "-PcmakeVersion=3.22.1" "-PbuildTools=36.0.0" "-PbuildABI=arm64-v8a"
    .\\gradlew.bat :tools:clipboard-probe:assembleDebug

（T4 未执行以上命令，构建是否通过以 Lead 的结果为准。）

### 4.2 安装到测试机

    adb install -r app\\build\\outputs\\apk\\debug\\app-debug.apk
    adb install -r tools\\clipboard-probe\\build\\outputs\\apk\\debug\\clipboard-probe-debug.apk
    adb install -r app\\build\\outputs\\apk\\androidTest\\debug\\app-debug-androidTest.apk

实际 APK 文件名以构建输出为准（app 模块 archivesName 可能带版本号）。确认测试包：

    adb shell pm list instrumentation

### 4.3 设备准备（channel B 自动测试必需）

    adb shell ime enable org.fcitx.fcitx5.android.debug/org.fcitx.fcitx5.android.input.FcitxInputMethodService
    adb shell ime set org.fcitx.fcitx5.android.debug/org.fcitx.fcitx5.android.input.FcitxInputMethodService

说明：

- channel B 的自动化用例要求被测 app 是**默认输入法**（Android 10+ 只有前台 App/默认 IME 能读剪贴板）；
- 若使用 release/debug 包名不同，替换为实际 applicationId（debug 默认带 .debug 后缀）。

### 4.4 执行

    adb shell am instrument -w -e enableSecureMode true \
        org.fcitx.fcitx5.android.debug.test/androidx.test.runner.AndroidJUnitRunner

只跑某个类：

    adb shell am instrument -w -e enableSecureMode true \
        -e class org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardStorageInstrumentedTest \
        org.fcitx.fcitx5.android.debug.test/androidx.test.runner.AndroidJUnitRunner

安全说明：

- **enableSecureMode=true 表示允许测试修改安全剪贴板偏好**，其中开启安全模式会触发一次性迁移并清空旧明文剪贴板库 clbdb；只能在**一次性模拟器/测试机**上使用该参数；
- 不传该参数时，安全模式关闭的用例会 assume 跳过，不会修改用户配置；
- 测试结束后 @After 会还原安全模式与 channel B 相关偏好到测试前的值；
- 已知跳过条件：probe 未安装、vault 未初始化（direct boot / Keystore 不可用）、安全模式关闭且未 opt-in、app 不是默认 IME。

## 5. adb 手工验证步骤（在模拟器/真机上执行）

以下步骤中 pkg = org.fcitx.fcitx5.android.debug（debug 包；release 用 org.fcitx.fcitx5.android）。

### 步骤 0：准备

1. 安装 app debug、androidTest 与 probe APK（见 4.2）。
2. 设置默认输入法（见 4.3），确认 Fcitx5 输入法可用。
3. 确认 probe 无网络/无权限（期望只看到系统默认权限，没有 INTERNET）：

       adb shell dumpsys package org.fcitx.fcitx5.android.probe | findstr /I permission

4. 记录 probe 初始状态（应为 listening=1，event_count 为当前累计）：

       adb shell content query --uri content://org.fcitx.fcitx5.android.probe.events/status

### 步骤 1：开启安全剪贴板

UI 路径：打开 app → 设置 → 隐私与安全（Privacy and security）→ 安全剪贴板（Secure clipboard）→ 打开“启用安全剪贴板”。
或直接打开设置页：

    adb shell am start -a android.intent.action.APPLICATION_PREFERENCES -n org.fcitx.fcitx5.android.debug/org.fcitx.fcitx5.android.ui.main.MainActivity

预期：设置页出现安全剪贴板状态区；首次开启会触发旧库迁移（旧库为空时应显示 0 条或直接完成）。

### 步骤 2：安全摘录（PROCESS_TEXT）不写系统剪贴板

1. 清空 probe 事件：

       adb shell content call --uri content://org.fcitx.fcitx5.android.probe.events --method clear

2. 在浏览器/笔记应用中选中一段文字 → 系统选择菜单 → 更多/三点 → “安全摘录”。
3. 确认对话框显示来源与字数（不显示完整明文可接受）→ 点“保存加密”。
4. 立即查询 probe 状态，event_count 必须仍为 0（或与步骤 1 结束后一致，不增加）：

       adb shell content query --uri content://org.fcitx.fcitx5.android.probe.events/status

5. 打开 app 剪贴板面板，应出现一条“已加密的安全记录”遮罩条目。

### 步骤 3：安全分享（ACTION_SEND）

1. 清空 probe 事件。
2. 在任意 App 分享一段 text/plain 文本 → 选择“安全摘录/安全分享”。
3. 确认保存后再次查询 probe：event_count 不增加。

### 步骤 4：输入法盾牌按钮（选中文字）

1. 清空 probe 事件。
2. 打开一个可编辑文本框，输入并选中一段文字，调出 Fcitx5 键盘。
3. 点击盾牌按钮 → 期望提示已加密保存，或按策略拒绝（密码框、无选中、非默认输入法）。
4. probe event_count 不增加；系统剪贴板内容不变。
5. 在密码框（inputType=textPassword）重复：不应保存、不应写剪贴板。

### 步骤 5：普通复制 → 加密 → 立即清空（channel B）

1. 打开 probe App（保持前台，便于读剪贴板长度）：adb shell am start -n org.fcitx.fcitx5.android.probe/.MainActivity
2. 切换到另一个 App（如浏览器），长按复制一段带唯一标记的文字（例如 “FCITX-PRIV-<随机串>”）。
3. 切回 probe，点击 “a) Read clipboard length silently”，预期显示不可读/空（已被清空）。
4. 查询 probe 事件列表，应能看到本次复制与清空对应的时间戳/长度事件（不含正文）：

       adb shell content query --uri content://org.fcitx.fcitx5.android.probe.events/events

5. 打开 app 剪贴板面板，应新增一条遮罩的安全记录；普通模式历史（旧库）不应新增明文记录。
6. 注意：若 app 不是默认输入法且不在前台，系统禁止读取剪贴板，本例预期无法自动转写（属已知限制，不是失败）。

### 步骤 6：私有粘贴不写系统剪贴板

1. 清空 probe 事件。
2. 打开 app 剪贴板面板 → 点击安全记录 → 应通过 InputConnection.commitText 粘贴到当前输入框；或长按 → 编辑（编辑页不得有“复制到系统剪贴板”按钮）。
3. probe event_count 不增加；系统剪贴板内容不变（在另一个 App 中粘贴应无该文本）。
4. 若开启“粘贴前需要设备认证”，应弹出系统锁屏验证；取消后不粘贴。

### 步骤 7：面板与编辑页

1. 安全记录列表显示遮罩文本（不显示正文）；长按菜单无“分享”。
2. pin/取消 pin、删除、撤销删除、清空安全历史（保留 pinned）功能正常。
3. 编辑安全记录并保存后，重新打开应显示新内容（密文已更新），且全程无明文日志/剪贴板写入。

### 步骤 8：旧库迁移

1. 在**未开启**安全模式时，用普通模式复制 3–5 条不同文本，确认旧库（普通模式历史）有记录。
2. 记录旧库条数（普通模式面板可见）。
3. 开启安全模式，等待迁移完成。
4. 预期：安全历史条数 = 旧库条数；旧库记录被清空；设置页/日志（非敏感）显示迁移结果。
5. 在 host 侧验证旧库无明文（步骤 11 同理，扫描 databases/clbdb*）。

### 步骤 9：历史上限与清空

1. 设置安全历史上限为最小值（设置页可选 10）。
2. 连续复制超过上限数量的文本（每次确认 probe 事件），预期最旧的未 pin 记录被删除、pinned 保留。
3. 使用“清空安全历史”，预期仅删除未 pin 记录。

### 步骤 10：插件 strict 模式

1. 安装带剪贴板 transformer 的插件（如有），默认 strict（secureClipboardAllowPlugins=false）。
2. 普通复制触发 channel B，预期插件收不到明文（插件侧日志/行为确认）。
3. 打开“允许第三方剪贴板转换器”后再复制，插件应能收到（这是显式 opt-in 的兼容路径）。

### 步骤 11：私有目录与日志明文检查

1. 记录一个唯一标记（例如 FCITX-PRIV-<随机 UUID>），按步骤 2 保存到加密库。
2. 拉取私有数据库与偏好文件（debug 包可 run-as）：

       adb exec-out run-as org.fcitx.fcitx5.android.debug cat databases/secure_clbdb > secure_clbdb.bin
       adb exec-out run-as org.fcitx.fcitx5.android.debug cat databases/secure_clbdb-wal > secure_clbdb-wal.bin
       adb exec-out run-as org.fcitx.fcitx5.android.debug cat shared_prefs/org.fcitx.fcitx5.android.debug_preferences.xml > prefs.xml

   如果 clbdb 存在也一并拉取：

       adb exec-out run-as org.fcitx.fcitx5.android.debug cat databases/clbdb > clbdb.bin

3. 在 host 上搜索标记（不得出现）：

       python -c "import sys; m=sys.argv[1].encode(); import pathlib; [print('HIT', p) for p in pathlib.Path('.').glob('*.bin*') if m in p.read_bytes()]" FCITX-PRIV-<随机 UUID>
       findstr /C:"FCITX-PRIV-<随机 UUID>" prefs.xml

4. 检查 logcat（不得出现标记）：

       adb logcat -d -v threadtime > logcat.txt
       findstr /C:"FCITX-PRIV-<随机 UUID>" logcat.txt

   说明：logcat 可能包含系统组件自己的日志，本项只检查被测 app 是否打印明文；测试前先 adb logcat -c 清空缓冲区更准确。

### 步骤 12：失败路径与竞态人工抽查

1. 在复制后立即切换进程/锁屏，观察是否出现旧记录被清空、系统剪贴板被误清；预期不清除已有记录。
2. 连续快速复制 A→B→C，观察最终只保留能确认快照的条目，剪贴板最终为空，应用不崩溃。
3. 恢复安全模式开关，确认偏好还原、监听器切回普通模式不崩溃。

## 6. 未执行项清单（本次交付的诚实边界）

| # | 未执行项 | 原因 | 影响 |
| --- | --- | --- | --- |
| 1 | 运行 Gradle 构建（app / androidTest / :tools:clipboard-probe） | 项目铁律：T4 不运行 Gradle，由 Lead 统一构建 | 测试代码与 probe 模块是否编译通过未经 T4 验证 |
| 2 | 执行任何 instrumented 测试 | 本次会话未连接真机/模拟器 | 第 3 节所有 instrumented 行为只有代码级保证 |
| 3 | 执行第 5 节全部 adb 手工步骤 | 同上 | 端到端用户路径未验证 |
| 4 | Android 14/15/16 多版本与 OxygenOS/ColorOS 真机 | 无设备 | 兼容性结论为空 |
| 5 | IME 盾牌按钮、选中文字、密码框的自动化 | 需要真实输入法会话，无 UiAutomator 依赖且不能新增依赖 | 仅手工覆盖 |
| 6 | 粘贴前设备凭据（KeyguardManager）流程 | 需要锁屏交互 | 仅手工覆盖 |
| 7 | 旧库大规模迁移（数千条）耗时/失败恢复 | 数据准备与设备时间成本 | 仅 T1 JVM 小规模单测 |
| 8 | 性能/功耗/启动时间 | 需要专用工具与真机 | 见 docs/security/04 |
| 9 | 进程死亡、后台限制、direct boot、Keystore 失效路径 | 设备状态难自动化 | 手工/审计覆盖 |
| 10 | 插件 transformer 实际调用链 | 需要签名级插件 | T5 审计 + 手工 |

## 7. 测试代码已知限制与风险

1. **测试运行在 app 进程内**：instrumented 测试与被测代码同进程，无法模拟“其他 App 读剪贴板”的攻击者视角；该视角由 probe（另一 UID）部分补偿，但 probe 也不能证明系统剪贴板对第三方完全不可见。
2. **Android 10+ 剪贴板读取限制**：后台 App（包括 probe）能收到剪贴板变化回调，但读不到内容；因此 probe 事件里的 length 常为 -1，自动化断言主要依赖“事件计数不变/剪贴板最终不可读”。
3. **assume() 跳过可能掩盖失败**：设备未配置（probe 未装、非默认 IME、安全模式未 opt-in）时用例会跳过；CI 必须检查跳过数量，不能把“0 failure”当成全覆盖。
4. **偏好开关的副作用**：enableSecureMode=true 会触发旧库迁移与清空，只能用于一次性测试设备；测试结束会还原偏好，但不会还原已迁移/已删除的数据。
5. **私有目录扫描**：assertNoPlaintextInAppStorage 逐文件扫描 app dataDir（跳过 lib/code_cache，单文件上限 32MB），可能漏掉系统页缓存/已释放但未覆盖的页；不能替代 T5 的静态审计，也不能证明 SSD/UFS 物理擦除。
6. **logcat 检查**：不同 ROM 的日志缓冲区大小/过滤策略不同，最近 4000 行可能覆盖不到；测试前清空缓冲区并重复是关键。
7. **probe 的 provider 无权限保护**：只暴露时间戳/长度/flags，不含正文；但仍是 exported 组件，仅应安装在测试设备（已在 README 与本文件注明）。
8. **未覆盖的竞态**：A/B/C 连续复制的细粒度状态机断言在 T3 的 JVM 单测（ClipboardEventProcessorTest）中覆盖；instrumented 层只做冒烟（不崩溃、最终清空）。
9. **APK 文件名与包名**：debug applicationId 带 .debug 后缀，测试包为 .debug.test；脚本中如与实际构建输出不一致需按 Lead 构建结果修正（本文件未实际执行验证）。

## 8. 给 Lead 的验收 checklist

- [ ] :app:assembleDebug 与 :app:assembleDebugAndroidTest 编译通过（包含本文件第 1.2 节 4 个测试文件）
- [ ] :tools:clipboard-probe:assembleDebug 独立编译通过（不依赖 build-logic convention 插件）
- [ ] 模拟器安装 probe 后，运行第 4.4 节命令，确认 assumption 跳过数量与失败数量均被记录
- [ ] 按第 5 节至少执行步骤 2/5/6/11 四项（安全摘录、普通复制清空、私有粘贴、明文落盘）
- [ ] 将实际执行结果回填到第 3 节状态列；未执行项保持“未执行”，不得默认写成通过
- [ ] 确认 probe APK 不在发布产物中（settings.gradle.kts 只是 include，不是 :app 依赖）

## 9. Lead 执行回填（2026-10-31）

> 本节由 Lead 在模拟器实测后补充；第 1–8 节中 instrumented 的“未执行”状态保持不变。

| checklist | 结果 |
| --- | --- |
| `:app:assembleDebug` | ✅ BUILD SUCCESSFUL（arm64-v8a；另构建 x86_64 用于模拟器） |
| `:app:assembleDebugAndroidTest` | ❌ 上游既有构建配置问题（`resValue` 的 debug 资源在 androidTest 资源链接不可见）；`:app:compileDebugAndroidTestKotlin` ✅ 已通过 |
| `:tools:clipboard-probe:assembleDebug` | ✅ BUILD SUCCESSFUL，APK 962,872 字节，零权限 |
| probe 是否被 :app 依赖 | 否（仅 `settings.gradle.kts` include 一行；`app/build.gradle.kts` 无引用） |
| 第 5 节手工步骤 | 部分执行（模拟器，adb/uiautomator）：安全摘录 ✅、普通复制加密+立即清空 ✅、旧明文迁移清理 ✅、后台默认输入法转存 ✅；私有粘贴面板 UI 与锁屏/重启/Direct Boot 未执行 |
| instrumented 运行 | ❌ 未执行（测试 APK 未安装运行） |

执行结果详细记录：`08-build-and-test-report.md` 第 9 节；证据：`evidence/emulator-functional-test-evidence.md`。

**结论：本文件中 instrumented 测试的“未执行”标注仍然有效；模拟器上的功能验证是 adb/uiautomator 手工实测，不冒充 androidTest 自动化结果。**
