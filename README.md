# 小企鹅输入法（Fcitx5 Android）· 隐私优先双通道安全剪贴板

本仓库是 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 的一个**修改版（fork）**：
在保留原有输入法、词库、插件能力的前提下，由本人完成「隐私优先双通道安全剪贴板」的源码审计、实现、测试与文档。

- 上游基线：eb5b50216e70484a009933d9519db76005f1faf3（master）
- 许可证：LGPL-2.1-or-later（与上游一致）
- 构建产物：Release 中提供 **release 签名发行包**（arm64-v8a，R8 混淆）与 debug 测试包；均为本机自签，签名密钥不随仓库分发。

> 本仓库文档与代码只描述**相对上游的修改**；上游原有的输入法功能不在这里重复介绍。

## 一、我做的修改（相对上游）

### 1. 新增：安全摘录（通道 A —— 不经过系统剪贴板）

通过 ACTION_PROCESS_TEXT、ACTION_SEND、输入法工具栏盾牌按钮读取选中文字，用户确认后直接 AES-256-GCM 加密落库，**全程不调用 setPrimaryClip**。

| 文件 | 作用 |
| --- | --- |
| app/src/main/java/.../ui/securecapture/{BaseSecureCaptureActivity,SecureCaptureActivity,SecureShareReceiverActivity}.kt | 三个安全入口的确认 UI（FLAG_SECURE、遮罩、启动限流 5 次/2 秒） |
| .../ui/securecapture/{SecureCaptureValidator,SecureUnlockGate,SecureUnlockActivity}.kt | 参数校验、设备凭据解锁 |
| .../input/secureclipboard/{SelectedTextCaptureController,SelectedTextPolicy}.kt | 输入法内“读取选中文字”按钮的策略与采集 |
| .../AndroidManifest.xml | 注册 PROCESS_TEXT / SEND 入口（未导出组件、无 INTERNET） |

### 2. 新增：普通复制保护（通道 B —— 加密后立即清空）

监听系统剪贴板；收到文本后校验、加密、事务写库，重读快照一致后立即清空系统剪贴板。串行队列处理并发复制；保存失败不误删原文；自身清空事件去重。

| 文件 | 作用 |
| --- | --- |
| .../data/clipboard/SecureClipboardController.kt | 系统剪贴板监听与清空编排 |
| .../data/secureclipboard/ClipboardEventProcessor.kt | 并发/竞态状态机、快照验证、失败处理 |
| .../data/clipboard/ClipboardManager.kt | 旧模式/安全模式统一门面；迁移；安全条目遮罩展示 |

### 3. 新增：加密存储与密钥

- 每条记录 AES-256-GCM（96-bit IV、128-bit tag、AAD 绑定数据库/记录 ID），密钥由 Android Keystore 生成且不可导出；
- 独立 HMAC-SHA256 去重键，不把明文哈希写库；
- Room 库 secure_clbdb **无明文列**；
- 旧明文库 clbdb 迁移后执行 DELETE + secure_delete + VACUUM + WAL checkpoint 并校验 0 行；
- 记录条数上限、去重、固定、删除、撤销删除、全部清除。

| 文件 | 作用 |
| --- | --- |
| .../data/secureclipboard/crypto/{ClipboardCrypto,ClipboardKeyManager}.kt | AES-GCM、Keystore、HMAC 去重 |
| .../data/secureclipboard/{SecureClipboardContract,SecureClipboardDao,SecureClipboardDatabase,SecureClipboardRepositoryImpl,SecureClipboardVault,SecureClipboardMetadata}.kt | 数据层 |
| .../data/secureclipboard/{SecureClipboardPolicy,SecureClipboardMigration}.kt | 策略/限制/迁移 |

### 4. 新增：私有粘贴与界面

历史面板只显示遮罩；用户点“查看/粘贴”时才单条解密。安全粘贴通过 InputConnection.commitText 直接上屏，**不重新填充系统剪贴板**；安全模式下列表/详情页禁止“复制到系统剪贴板”。

| 文件 | 作用 |
| --- | --- |
| .../input/secureclipboard/{SecureClipboardPasteController,PasteSession}.kt | 私有粘贴 |
| .../input/clipboard/{ClipboardWindow,ClipboardAdapter,ClipboardInstructionUi}.kt | 面板、查看、认证、分享禁用 |
| .../ui/main/ClipboardEditActivity.kt、.../input/editorinfo/EditorInfoWindow.kt | 编辑页/选词页的安全限制 |

### 5. 新增：中文设置页、隐私历史与加密备份

- 设置页入口：**隐私与安全 → 安全剪贴板**（开关、保护状态、历史条数、迁移、清除、兼容模式、允许插件）；
- 新增功能支持**简体中文、繁体中文、日语、英语**（跟随系统语言切换）；上游原有界面同样跟随系统语言；
- **隐私历史**：查看/搜索/固定/删除/全部清除；搜索按需本地解密（≤5000 条扫描、≤200 条返回），列表只显示遮罩；
- **加密备份导出/导入**：PBKDF2-HMAC-SHA256(210k) + AES-256-GCM 口令加密文件；错误口令拒绝；文件不含明文。

| 文件 | 作用 |
| --- | --- |
| .../ui/main/settings/security/SecureClipboardSettingsFragment.kt | 设置页 |
| .../ui/main/settings/security/SecureHistoryActivity.kt | 隐私历史 + 搜索 + 导出/导入 |
| .../data/secureclipboard/export/SecureClipboardExportCrypto.kt | 加密备份格式 |
| .../ui/main/{MainFragment,settings/SettingsRoute}.kt、res/layout/*secure*、res/drawable/ic_shield_24.xml | 入口/路由/布局 |

### 6. 修改：插件与引擎边界（隐私隔离，唯一触碰插件的地方）

| 修改 | 位置 | 说明 |
| --- | --- | --- |
| 严格模式下移除 clipboard transformer | FcitxRemoteService.kt | 当“安全模式开 && 允许插件关”时，第三方插件注册的剪贴板内容转换器不再接收剪贴板文本；其他插件 IPC 接口不变 |
| 安全条目不镜像到 fcitx 引擎剪贴板 | core/Fcitx.kt | 安全摘录/安全复制的内容不进入 native 剪贴板模块，避免引擎 addon 读到明文；私有粘贴走 InputConnection |
| 剪贴板库不参与备份/导出 | data/UserDataManager.kt、res/xml/{full_backup_content,data_extraction_rules}.xml | clbdb* / secure_clbdb* 从 Android 备份、用户数据导出中排除 |
| 安全模式禁止面板复制到系统剪贴板 | input/clipboard/*、EditorInfoWindow.kt | 防止绕过 |
| 新增设置开关/状态 | data/prefs/AppPrefs.kt | secure_clipboard_* 偏好（安全模式默认关闭，旧行为不变） |

### 7. 新增：测试、工具与文档（测试工具不属于功能）

- **81 个 JVM 单测**：crypto 17、事件状态机 15、策略 17、迁移 10、加密备份 20、桌面 AES 基准 2；
- **androidTest 源码** 4 个文件（编译通过，未执行）；
- 测试探针与红队 App 已从公开仓库与 Release 移除，不随分发提供。
- docs/security/：源码审计、威胁模型、A–L 数据流隐私报告、性能/兼容说明、构建测试报告、红队报告、证据文件。

### 8. 构建/清单/配置变更

- settings.gradle.kts 不再注册测试工具模块（已移除）；
- AndroidManifest.xml 新增 4 个 Activity（SecureCapture/SecureShareReceiver/SecureUnlock/SecureHistory）；
- 中英文 strings.xml 新增安全剪贴板文案；
- Room schema app/schemas/...SecureClipboardDatabase/1.json。

## 二、我没有改的部分（保持上游行为）

- **词库与输入引擎**：libime、拼音/双拼/五笔/码表/自然码、用户词典、语言模型、输入法配置页——未改一行；
- **插件加载协议**：FcitxRemoteService binder 接口、permission.IPC / permission.PLUGIN 签名权限、插件包扫描与加载逻辑；
- **原生库与构建**：CMake、NDK、fcitx5/libime 原生代码；
- **主题、键盘布局、候选窗口、表情/符号**等；
- 默认关闭安全模式，普通用户不开启时行为与上游一致。

词库回归实测（模拟器）：输入 nihao → 候选 1. 你好 / 2. 👋 / 3. 拟好 …，提交得到“你好”；用户词库 user.dict/user.history 自动生成更新；16 项内置附加组件全部加载。详见 docs/security/evidence/emulator-dictionary-function-check.md。

## 三、插件兼容性

**结论：插件协议兼容；严格模式下只有一个有意的行为差异——剪贴板内容转换器（clipboard transformer）不生效，其他插件能力不受影响。**

### 3.1 未改动的插件接口

- 插件通过 org.fcitx.fcitx5.android.debug.permission.IPC（signature 级）绑定 FcitxRemoteService，接口与协议未改；
- 插件服务权限 permission.PLUGIN、插件包扫描、native lib 挂载、data 合并逻辑均未改；
- 插件仍能正常注册输入法/码表/词典/转换器等。

### 3.2 有意的行为差异（严格模式）

严格模式 = 安全剪贴板开启 且 “允许插件” 关闭（默认）。

| 场景 | 插件 transformer 是否收到剪贴板文本 | 说明 |
| --- | --- | --- |
| 旧模式（安全剪贴板关闭） | **收到**（与上游一致） | 插件功能不变 |
| 安全模式 + 严格模式 | **不收到**（hook 被移除） | 避免插件拿到明文 |
| 安全模式 + 允许插件（用户主动打开） | **收到** | 用户自担风险，转换结果在加密前应用 |

另外：安全摘录/安全复制的条目不会镜像到 fcitx 引擎剪贴板，因此**读引擎剪贴板的插件**看不到这些敏感内容（设计如此）；普通复制仍照旧同步给引擎。

### 3.3 实测（Android 16 x86_64 模拟器）

用**同一份源码、同一个 debug 签名**构建官方插件 plugin/clipboard-filter（ClearURLs URL 追踪参数清除）并安装测试：

- 签名一致：App 与插件 SHA-256 均为 dd26a87f…a24c3602；
- 插件在小企鹅「插件」页显示**已加载**；日志 Bind to plugin: clipboard_filter → Plugin connected: clipboard_filter，无 SecurityException；
- 复制 https://example.com/watch?id=789&utm_source=foo&utm_medium=bar&fbclid=zzz ：
  - 旧模式：strictSecureMode=false，clbdb 存 https://example.com/watch?id=789（**插件过滤生效**）；系统剪贴板仍为原始 URL；
  - 安全模式严格：strictSecureMode=true，secure_clbdb 密文 89 字节（73 字符原文 + 16B tag，**未过滤**）；系统剪贴板已被清空；
  - 安全模式 + 允许插件：strictSecureMode=false，secure_clbdb 密文 48 字节（32 字符过滤后 URL + 16B tag，**过滤后加密**）；系统剪贴板已清空。
- 内置 16 项附加组件全部加载（拼音、码表、拼写、标点、剪贴板等）。

详见 docs/security/evidence/plugin-compatibility-test.md。

### 3.4 签名限制（重要）

官方发布的插件 APK 用**官方 release 密钥**签名；本仓库构建的是 **debug 包**。Android 的 signature 级权限要求插件与主应用同签名，因此：

- 官方渠道下载的插件 APK **无法**绑定到本 debug 包（permission denial，属预期）；
- 要测试插件兼容性，必须用**同一密钥**从本仓库构建插件（本次就是这样验证的）；
- 正式发布时应保持主应用与插件同签名（官方发布的机制本身如此）；本次已用同一 release 密钥构建 clipboard-filter 发行版插件并在 release 主包上验证可加载。

### 3.5 未验证的插件

- 官方商店/官方 Release 插件 APK（签名不同，无法绑定到 debug 包）；
- Rime、Anthy、Chewing 等带原生库的插件（未构建测试）；
- 插件配置页的深度功能、多插件并存、插件崩溃/重连场景。

## 四、实测与交付

- 构建：:app:assembleDebug 成功（arm64-v8a 69,819,756 字节，无 INTERNET 权限）；:app:assembleRelease 成功产出 release 签名发行包（arm64-v8a 54,651,647 字节，R8，无 INTERNET 权限，apksigner v1+v2 通过）；
- 单测：81 个安全剪贴板 JVM 单测全部通过；
- 模拟器功能实测：通道 A/B、立即清空、旧明文迁移清库、隐私历史搜索、加密备份导出/导入；
- 红队：零权限流氓 App 10 项攻击，私有库/IPC/Provider/日志/外部目录/伪造入口均被拦截；安全模式复制后剪贴板读空；
- 词库/插件回归：见上文第二节、第三节；
- 真机/OEM（一加 OxygenOS/ColorOS）、instrumented 自动化、性能功耗：**未验证**。

## 五、构建、签名与发布

    $env:JAVA_HOME='<JDK 17 安装目录>'
    $env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
    $env:ECM_DIR='<本地 ECM 安装>\share\ECM\cmake'
    $env:PATH="<本地 gettext>\bin;" + $env:PATH
    Set-Location '<本仓库根目录>'
    gradlew.bat :app:assembleDebug --console=plain -PbuildABI=arm64-v8a -PbuildTools=36.0.0

Release 中同时提供 **release 签名发行包**（R8）与 **debug 测试包**；仓库不含任何密钥。发行包使用本机 release 密钥签名（与官方 Fcitx5 不同签名，不能覆盖官方版），插件需用同一密钥构建。

## 六、文档

| 文档 | 内容 |
| --- | --- |
| [docs/security/00-architecture-and-file-ownership.md](docs/security/00-architecture-and-file-ownership.md) | 实现架构与文件所有权 |
| [docs/security/01-source-audit.md](docs/security/01-source-audit.md) | 上游源码安全审计 |
| [docs/security/02-threat-model.md](docs/security/02-threat-model.md) | 威胁模型 |
| [docs/security/03-dataflow-privacy-report.md](docs/security/03-dataflow-privacy-report.md) | A–L 数据流隐私报告 |
| [docs/security/04-performance-battery-evaluation.md](docs/security/04-performance-battery-evaluation.md) | 性能/功耗评估 |
| [docs/security/05-compatibility-notes.md](docs/security/05-compatibility-notes.md) | 兼容性说明 |
| [docs/security/06-unresolved-risks-and-acceptance.md](docs/security/06-unresolved-risks-and-acceptance.md) | 验收与残余风险 |
| [docs/security/07-test-plan-and-instrumentation.md](docs/security/07-test-plan-and-instrumentation.md) | 测试计划 |
| [docs/security/08-build-and-test-report.md](docs/security/08-build-and-test-report.md) | 构建与测试报告 |
| [docs/security/09-rogue-app-red-team.md](docs/security/09-rogue-app-red-team.md) | 红队实测 |
| [docs/security/evidence/](docs/security/evidence/) | 全部实测证据 |

## 七、已知限制

- 真机/OEM（一加 OxygenOS/ColorOS）、锁屏/Direct Boot、重启、性能功耗**未验证**；
- instrumented 自动化未执行（仅编译通过）；
- 平台限制：焦点应用/默认输入法仍可读系统剪贴板；安全模式普通复制保护依赖小企鹅为默认输入法；TOCTOU 无法完全消除；旧模式/兼容模式保留系统剪贴板；
- 插件：仅测试了 clipboard-filter 一个插件；官方签名插件 APK 无法绑定到 debug 包；原生插件未测。
