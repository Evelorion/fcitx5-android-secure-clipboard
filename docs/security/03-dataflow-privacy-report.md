<!-- SPDX-License-Identifier: LGPL-2.1-or-later -->

# 03 数据传输与隐私审计报告（需求 10.1 / 10.2）

> 任务：T5。对应需求文档第十部分。本文逐项分析 A–L 共 12 条数据路径，并给出 10.2 的保护目标与“诚实边界”。
> 快照：commit `eb5b502` + T0 工作树（2026-10-31）。**T1/T2/T3 的新实现文件在 T0 尚未落地**；凡涉及它们的路径，本文同时给出“设计态（契约/架构文档）”和“T0 实际状态（未实现/旧行为）”，并明确标注未验证。
> 证据标记沿用 `01-source-audit.md`：【S】源码确认、【P】潜在风险、【T】需测试（未验证）。

## 1. 判定口径（11 个维度）

| 维度 | 判定标准 |
| --- | --- |
| 是否存在明文 | 该路径上是否出现可读的剪贴板/选中文本内容（而非长度、哈希、密文） |
| 明文可能停留在哪里 | 进程内存、系统剪贴板、Binder 事务缓冲、Room 文件/WAL、日志、备份包、输入框等 |
| 是否跨进程 | 是否经过 Binder/系统服务/另一个 App 进程 |
| 是否经过 Android 系统剪贴板 | 是否调用/读取 `android.content.ClipboardManager` 的系统剪贴板 |
| 是否可能被其他 App 获取 | 普通 App 在系统允许的条件下（前台、获得焦点、系统默认 IME 等）能否读到 |
| 是否可能联网 | 主应用是否有网络能力；对端 App/插件是否可联网 |
| 是否使用加密 | 传输/存储是否使用加密（AES-GCM 或平台安全通道），以及加密发生在哪一段 |
| 加密保护的具体边界 | 加密保护到哪个实体为止：数据在磁盘上？Binder 上？内存中？目标 App 内？ |
| 已采取的防护 | 现有代码/设计中的控制 |
| 仍然存在的风险 | 控制之外仍然存在的暴露 |
| 如何测试该风险 | 具体、可执行的验证方法（设备/模拟器/静态审计） |

**三个全局事实（先写在前面，避免任何场景被误读）**：

1. **主应用没有 INTERNET 权限，也没有网络客户端依赖**【S】（01 第 1.3/1.4 节）。但“主应用不联网”不能推导“数据不离开设备”：插件 App、目标 App、系统备份、用户导出、其他 App 读取系统剪贴板都可以把数据带出去。
2. **Binder IPC 是明文传输**：同签名插件、目标 App 通过 Binder 收到的都是 `String`/Parcelable 明文；签名权限只限制“谁能连接”，**不是端到端加密**【S/P】。
3. **通道 B（普通复制）必然经过 Android 系统剪贴板**；安全摘录/私有粘贴在 T0 只有旧代码路径（面板粘贴已用 `commitText`），新的安全摘录入口尚未实现。

## 2. A–L 总览矩阵

> 列：明文位置（主要） / 跨进程 / 经系统剪贴板 / 其他 App 可获取 / 可联网 / 加密。
> “T0 实际”描述审计快照时真实可运行的行为；“设计态”描述 Contract/00 架构文档约定的目标行为。

| 场景 | T0 实际（源码确认） | 设计态（契约/架构，未落地项标注） | 明文主要位置 | 跨进程 | 经系统剪贴板 | 其他 App 可获取 | 可联网 | 加密 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A 安全摘录：来源 App → 接收 Activity | **未实现**（Activity 已在清单注册，类不存在） | PROCESS_TEXT Activity 校验 Intent → 确认 → Vault.save；不写系统剪贴板 | 来源 App 内存 → 系统 Binder → 本应用进程内存 | 是（系统 + 来源 App） | 否（设计） | 来源 App 本来持有；系统可见 Intent | 主应用否；来源 App 自身可联网 | 落盘 AES-GCM；Binder 段不加密 |
| B 安全摘录：IME 读取用户选中文字 | **未实现**（T2） | 用户点盾牌 → getSelectedText → Vault.save；不改选区、不写剪贴板 | 目标 App 输入框 → InputConnection Binder → 本应用内存 | 是（目标 App） | 否（设计） | 目标 App 持有；输入法本身可见 | 主应用否 | 落盘 AES-GCM；Binder 段不加密 |
| C 普通复制：来源 App → 系统剪贴板 | 存在（平台行为） | 不变（平台行为） | 系统剪贴板服务内存、来源 App 内存 | 是（系统服务） | **是** | **是**（前台 App/default IME 可读；系统预览可见） | 来源 App/系统可联网 | 否（平台不加密） |
| D 普通复制：系统剪贴板 → 本应用进程 | 存在（旧监听器读 `primaryClip`） | T3 控制器读快照 → 校验 → 加密 → 事务 → 重读 → 清空 | 本应用进程内存（`ClipData`/`ClipboardEntry`） | 是（系统服务→本应用） | **是** | 读取瞬间系统仍持有明文，其他 App 可能同时读 | 主应用否 | 读入内存后加密落盘；内存段不加密 |
| E 本应用进程 → AES-GCM 加密模块 | **未实现**（T1） | 明文作为 `CaptureRequest.text` 传给加密模块，输出 ciphertext+iv+tag | 本应用进程内存（明文与密文短暂共存） | 否（同进程） | 否 | 否（除非内存被 dump/root） | 否 | **是**（AES-256-GCM，Keystore 密钥） |
| F 加密模块 → Room 数据库 | **未实现**（T1） | 只写 `ciphertext/iv/encryptedMetadata/keyVersion/...`，无明文列 | App 私有目录 `databases/secure_clbdb`（含 WAL/SHM） | 否（进程内 IO） | 否 | 其他 App 默认不可读；root/备份可及 | 否（若备份排除生效） | **是**（静态密文） |
| G 私有数据库 → 剪贴板面板 | 面板仍显示旧库明文（`ClipboardWindow.kt:85-87`）；安全模式下列表来自遮罩映射（`ClipboardManager.kt:284-293,531-554`），但**解密读取接口的 UI 集成未落地** | 列表只显示遮罩/元数据；单条按需解密 | 面板内存/TextView 片段；旧库模式为完整明文 | 否 | 否 | 面板是输入法窗口，其他 App 不能直接读；截屏/Recents 另算 | 否 | 安全模式：密文落盘、按需解密；旧模式：明文 |
| H 本应用 → 目标 App 的 InputConnection | 存在（旧面板 `ClipboardWindow.kt:126-129`、Kawaii `KawaiiBarComponent.kt:310-325`） | PasteController 解密→校验输入会话→`commitText`→丢弃引用 | 本应用内存 → Binder → 目标 App 输入框（长期） | 是（目标 App） | 否（设计；`commitText` 不写剪贴板） | 目标 App 必然可见；输入法框架可见 | 目标 App 可联网 | 传输段不加密；落盘密文 |
| I 本应用 → 插件进程 | 旧模式**必然**发生（transformer 在落库前调用）；严格模式设计为 `transformer=null` | 严格模式不向插件提供通道 A/B 明文；兼容开关例外 | 主应用内存 → Binder → 插件进程内存 | 是（插件 App） | 否（transformer 不经过系统剪贴板，但输入来自系统剪贴板） | 同签名插件可读；其他 App 不可接入 | **插件可自行申请 INTERNET** | 否（Binder 明文） |
| J 插件进程 → 互联网 | 官方 ClearURLs 插件清单无 INTERNET；但同签名信任域内插件可自行申请 | 主应用无法替插件撤销网络能力 | 插件进程内存 → 网络栈 | 是 | 否 | 网络对端 | **是**（若插件有 INTERNET） | 取决于插件；主应用不参与 |
| K 数据库 → Android 系统备份 | T0 工作树已排除；基线 commit **未排除** | `data_extraction_rules.xml:16-34`、`full_backup_content.xml:15-33` 排除两类库；用户手动导出仍包含 `databases/`（`UserDataManager.kt:60-81`） | 备份传输通道/备份服务器（若启用） | 是（备份代理） | 否 | 备份系统/云 | 备份通道是网络服务 | 数据库文件本身：明文（旧）/密文（新）；备份传输由系统 TLS 保护，不是应用层加密 |
| L 锁屏/截屏/系统任务预览/日志 | 旧 UI 无 `FLAG_SECURE`；Recents 由系统管理；logcat 可导出（`LogActivity.kt:41-57`） | 安全 Activity `excludeFromRecents`（清单 127/146）；T2 计划 `FLAG_SECURE`；日志不打印正文 | 屏幕缓冲区、Recents 快照、logcat 环形缓冲、用户导出文件 | 否 | 否 | 截屏/录屏 App、系统 UI、ADB/root | 日志可被分享/上传（用户行为） | 否 |

## 3. 逐场景分析

### A. 安全摘录：来源 App → 小企鹅接收 Activity

| 维度 | 分析 |
| --- | --- |
| 是否存在明文 | 是。来源 App 已持有用户选中的文本；`ACTION_PROCESS_TEXT` 把文本放入 `Intent.EXTRA_PROCESS_TEXT`（`CharSequence`）传给接收方。 |
| 明文停留位置 | 来源 App 内存、系统 `Intent`/`ActivityTaskManager` 事务、本应用 Activity 内存、确认 UI、加密模块内存（设计）。 |
| 是否跨进程 | 是：来源 App → system_server → 本应用（Binder + Activity 栈）。 |
| 是否经过系统剪贴板 | **否**（设计目标）。源码依据：`00-architecture-and-file-ownership.md` 第 26 行；T2 任务书明确“禁止 setPrimaryClip”。T0 Activity 类未实现，无法验证。 |
| 是否可能被其他 App 获取 | 来源 App 本来就持有；system_server 处理 Intent；其他 App 不能直接读本应用内存。恶意 App 也可以**冒充来源 App** 给本应用发 PROCESS_TEXT（导出 Activity），所以“来源”不可信，必须用户确认（设计）。 |
| 是否可能联网 | 本应用无 INTERNET；来源 App 自身可能联网（与本功能无关）。 |
| 是否使用加密 | 落盘阶段 AES-256-GCM（设计，T1）；Intent/Binder/内存段不加密。 |
| 加密保护的具体边界 | 保护“已落盘的保险库记录”；不保护来源 App、系统 Intent 事务、本应用内存。 |
| 已采取的防护 | ① 清单声明 `exported=true` + PROCESS_TEXT/text 过滤（`AndroidManifest.xml:124-142`）；② 设计上强制确认（T2）；③ 长度/UTF-8 上限常量（`Contract.kt:181-196`）；④ 速率限制常量（`Contract.kt:193-196`）。 |
| 仍然存在的风险 | 恶意来源 App 伪造 Intent；用户被诱导确认；确认 UI 的文本预览可能被截屏/录屏；来源 App 之后仍可继续操作原文。 |
| 如何测试该风险 | ① 用 `adb shell am start` 伪造 PROCESS_TEXT（携带 EXTRA_PROCESS_TEXT）验证校验与确认；② 恶意测试 App 批量发送 100 次验证限流；③ 用独立 probe App 监听系统剪贴板，确认安全摘录期间剪贴板内容不变；④ 截屏/Recents 检查。**T4 计划项，T0 未执行，未验证。** |

### B. 安全摘录：输入法获取用户主动选择的文字

| 维度 | 分析 |
| --- | --- |
| 是否存在明文 | 是。`InputConnection.getSelectedText(0)` 返回目标 App 选中文本；或 `EditorInfo.getInitialSelectedText()`。 |
| 明文停留位置 | 目标 App 输入框/其进程 → Binder → 本应用 IME 进程内存 → 确认 UI/保险库（设计）。 |
| 是否跨进程 | 是（目标 App ↔ 输入法与 system_server 的输入法框架）。 |
| 是否经过系统剪贴板 | **否**（设计）。 |
| 是否可能被其他 App 获取 | 目标 App 持有原文；输入法框架/system_server 参与连接管理；其他 App 不可读 IME 进程内存。 |
| 是否可能联网 | 本应用无 INTERNET。 |
| 是否使用加密 | 落盘 AES-GCM；Binder/内存不加密。 |
| 加密保护的具体边界 | 仅保护落盘后的记录；不保护输入框和输入法内存。 |
| 已采取的防护 | ① 只在用户点击时读取（设计/T2）；② 密码框/`IME_FLAG_NO_PERSONALIZED_LEARNING` 拒绝（基础判定已存在：`core/CapabilityFlag.kt:97-180`：103-105 行把该 flag 映射为 Sensitive，144-151 行识别 password variation）；③ 复用 `FcitxInputMethodService.currentInputConnection`（`input/FcitxInputMethodService.kt:727-756,1066-1072`）。 |
| 仍然存在的风险 | 目标 App 可能不实现 `getSelectedText`；输入框切换/连接失效时的过期连接（需 T2 的会话校验）；密码框判断依赖 EditorInfo 不可伪造保证；选中文本可能同时对系统可见。 |
| 如何测试该风险 | ① 在多个第三方输入框（浏览器、聊天、密码框）实测；② 切换输入框后点击盾牌验证会话校验；③ 用 `adb shell input` 制造选区；④ 检查密码框是否被拒绝。**T2 未落地，无法测试，未验证。** |

### C. 普通复制：来源 App → Android 系统剪贴板

| 维度 | 分析 |
| --- | --- |
| 是否存在明文 | 是。来源 App 调 `ClipboardManager.setPrimaryClip()`，明文进入 system_server 的系统剪贴板。 |
| 明文停留位置 | 来源 App 内存、system_server 剪贴板服务、可能的系统剪贴板 UI/历史（OEM 特性）。 |
| 是否跨进程 | 是（来源 App → system_server）。 |
| 是否经过系统剪贴板 | **是，无法避免**。 |
| 是否可能被其他 App 获取 | **是**：Android 10+ 对后台读剪贴板有限制（前台/默认 IME 可读），但剪贴板预览、OEM 剪贴板管理器、其他被授予权限的系统组件仍可能看到；这是平台公开面。 |
| 是否可能联网 | 系统/ROM 剪贴板服务可能联网（平台行为）；来源 App 可能联网。 |
| 是否使用加密 | 否。系统剪贴板不加密（内存数据）。 |
| 加密保护的具体边界 | 无。该段完全在平台控制之下。 |
| 已采取的防护 | 应用只能“尽快读取并清空”（通道 B），无法阻止来源 App 写入和系统短暂保留。 |
| 仍然存在的风险 | 清空前的窗口期；清空 API 可用性（`clearPrimaryClip` 需 API 28+，本项目 minSdk 23）；OEM 剪贴板历史可能在 system_server 或厂商服务中保留副本；Android 13+ 的复制预览 UI 会显示内容。 |
| 如何测试该风险 | ① 独立 probe App 记录 `OnPrimaryClipChangedListener` 事件与内容长度/哈希；② 在 Android 14/15/16 真机观察复制预览；③ 用厂商剪贴板管理器验证清空后是否仍有历史；④ 测试 API 23-27 设备的清空替代方案。**未验证。** |

### D. 普通复制：Android 剪贴板 → 小企鹅进程

| 维度 | 分析 |
| --- | --- |
| 是否存在明文 | 是。旧监听器读 `primaryClip` 并构造 `ClipboardEntry(text=...)`（`data/clipboard/db/ClipboardEntry.kt:48-67`）。 |
| 明文停留位置 | 本应用进程内存（`ClipData` 快照、`ClipboardEntry`、`lastEntry`）；旧模式最终进入 Room 明文库；安全模式设计为加密后清空。 |
| 是否跨进程 | 是（system_server → 本应用 Binder 回调）。 |
| 是否经过系统剪贴板 | **是**（数据来源）。 |
| 是否可能被其他 App 获取 | 读取瞬间系统剪贴板仍持有明文，其他前台 App/系统组件可能并行读取；本应用落盘后（旧模式明文、新模式密文）其他 App 不可直接读。 |
| 是否可能联网 | 本应用无 INTERNET；旧模式会把明文交给插件 transformer（见 I）。 |
| 是否使用加密 | 旧模式：**否**（明文 Room）。安全模式：设计为读入后尽快 AES-GCM 加密落盘；内存段不加密。 |
| 加密保护的具体边界 | 安全模式只保护落盘后的历史；不保护读取窗口和进程内存。 |
| 已采取的防护 | ① 旧监听器去重（`ClipboardManager.kt:458-468`）；② 旧库明文 + 应用私有目录；③ 安全模式设计：快照→加密→事务→重读→清空（T3）；④ 备份排除（T0）。 |
| 仍然存在的风险 | 旧模式明文库；安全模式在 T0 未实现；`NotReady` 时既不加密也不落旧库；进程被杀导致事件丢失；插件 transformer 暴露窗口（I）；TOCTOU。 |
| 如何测试该风险 | ① 复制测试文本后用 `adb`/probe 检查剪贴板是否被清空；② `run-as`/备份提取检查数据库内是否出现明文（T1 后应为密文）；③ 竞态测试：A/B/C 连续复制；④ 杀进程/重启后检查漏捕获。**T3 未落地，未验证。** |

### E. 小企鹅进程 → AES-GCM 加密模块

| 维度 | 分析 |
| --- | --- |
| 是否存在明文 | 是。`CaptureRequest.text` 明文传入加密模块（`Contract.kt:51-60`）。 |
| 明文停留位置 | 本应用进程内存（Kotlin String，不可控地可能被 GC 前驻留；无内存擦除保证）。 |
| 是否跨进程 | 否（同进程函数调用；Keystore 操作通过系统服务，但密钥不出 Keystore）。 |
| 是否经过系统剪贴板 | 否。 |
| 是否可能被其他 App 获取 | 否（除非 root/调试/内存 dump）。 |
| 是否可能联网 | 否（主应用无 INTERNET）。 |
| 是否使用加密 | **是**：设计为 AES-256-GCM，96-bit 随机 IV、128-bit tag、AAD 绑定元数据（`Contract.kt:122-128`；需求 5.1）。 |
| 加密保护的具体边界 | 只保护加密输出（密文/标签）；明文在进程内存期间不受保护；JVM 无法可靠擦除 String。 |
| 已采取的防护 | 契约层限制（长度/空/敏感/速率，`Contract.kt:181-196`）；T1 设计可注入 key 的纯 JVM 实现用于单测；不打印明文（规则见 `Contract.kt:24-28` 注释）。 |
| 仍然存在的风险 | 明文内存窗口；T1 实现未落地，无法确认 IV 随机性/不重复/AAD/tag 校验/异常不泄露明文；Keystore 首次使用延迟。 |
| 如何测试该风险 | ① JVM 单测：加解密往返、密文/IV/tag 篡改必须失败、AAD 不匹配失败、IV 不重复（T1 计划）；② 源码审计确认无 `Log`/异常消息带明文；③ 静态扫描 `String` 生命周期。**T1 未落地，未验证。** |

### F. 加密模块 → Room 数据库

| 维度 | 分析 |
| --- | --- |
| 是否存在明文 | 否（设计）。`SecureClipboardEntry` 无明文列（`Contract.kt:129-178`）。 |
| 明文停留位置 | 若实现正确，仅密文 + 元数据落盘；WAL/SHM 中同样只有密文行。 |
| 是否跨进程 | 否（Room/SQLite 在应用进程内）。 |
| 是否经过系统剪贴板 | 否。 |
| 是否可能被其他 App 获取 | 默认否（应用私有目录、FBE 保护）；root/ADB/备份可及。 |
| 是否可能联网 | 仅当备份/导出路径未排除时（见 K）。 |
| 是否使用加密 | **是**：AES-256-GCM 密文 + Keystore 密钥；密钥不落库（需求 5.1）。 |
| 加密保护的具体边界 | 保护静态数据；不保护内存中的明文、不阻止已解锁设备上以本应用身份解密。 |
| 已采取的防护 | ① 独立库 `secure_clbdb`（设计，T1）；② 备份排除（T0）；③ 迁移后清旧表并 VACUUM（`ClipboardManager.kt:417-433`）；④ 去重标签用 HMAC 而非明文（`Contract.kt:146-148`）。 |
| 仍然存在的风险 | SQLite 删除/覆写后的页与 WAL、SSD/UFS 物理残留；Keystore 密钥丢失后无法解密；备份/导出的边缘路径；数据库文件可被 root 复制做离线分析（无密钥则不可读）。 |
| 如何测试该风险 | ① 真机/模拟器导出 `secure_clbdb`，用 `strings`/`grep` 搜索测试明文（应无匹配）；② 同法测旧 `clbdb`；③ 备份包抓取；④ 删除后测试 `secure_delete`/VACUUM 效果（注意：物理层无法保证）。**未验证。** |

### G. 私有数据库 → 小企鹅剪贴板面板

| 维度 | 分析 |
| --- | --- |
| 是否存在明文 | 旧模式：是（`ClipboardWindow.kt:85-87` 的 Pager 来自旧库 `allEntries()`，列表项含全文）；安全模式设计：列表只显示遮罩，单条按需解密（`ClipboardManager.kt:284-293,531-554`）。 |
| 明文停留位置 | 旧模式：Paging 数据、`lastEntry`、TextView 片段；安全模式：只在使用时短暂进入内存（设计）。 |
| 是否跨进程 | 否（输入法窗口在本应用进程）。 |
| 是否经过系统剪贴板 | 否。 |
| 是否可能被其他 App 获取 | 不能直接读窗口内容；截屏/录屏/Recents/无障碍服务可能获取（见 L）。 |
| 是否可能联网 | 否。 |
| 是否使用加密 | 安全模式：库内密文，展示遮罩；旧模式：明文。 |
| 加密保护的具体边界 | 安全模式保护静态数据；UI 内存中的明文不加密。 |
| 已采取的防护 | ① 安全模式遮罩映射（`ClipboardManager.kt:224-243,511-554`）；② 敏感标记旧路径遮罩（`ClipboardAdapter.kt:94`、`KawaiiBarComponent.kt:129-133`）；③ 不整库解密（`ClipboardManager.kt:45-57` 注释与实现）。 |
| 仍然存在的风险 | **I8 遮罩误用**：`KawaiiBarComponent.kt:310-325` 仍 `commitText(it.text)`、`ClipboardEditActivity` 仍以 `get()` 初始化编辑框（T2/T3 未落地）；旧模式面板直接显示明文；截屏。 |
| 如何测试该风险 | ① 安全模式下打开面板确认只显示遮罩；② 点击/长按/编辑/分享逐项验证（安全模式应禁用分享、编辑不得把遮罩写库）；③ 截屏/Recents 检查。**T2/T3 未落地，未验证。** |

### H. 小企鹅 → 目标 App 的 InputConnection

| 维度 | 分析 |
| --- | --- |
| 是否存在明文 | 是。粘贴时明文经 `InputConnection.commitText` 提交给目标 App（`input/FcitxInputMethodService.kt:423-454`）。 |
| 明文停留位置 | 本应用内存（提交参数）→ Binder 事务 → 目标 App 输入框（由目标 App 长期持有）。 |
| 是否跨进程 | 是。 |
| 是否经过系统剪贴板 | **否**（`commitText` 不写系统剪贴板；旧面板与建议气泡路径均如此，源码确认）。 |
| 是否可能被其他 App 获取 | 目标 App 必然可见；输入法框架/system_server 参与连接；其他普通 App 不可直接读。 |
| 是否可能联网 | 目标 App 可联网（用户选择粘贴即授权其接收）。 |
| 是否使用加密 | 传输段不加密（Binder 明文）；落盘历史为 AES-GCM（设计）。 |
| 加密保护的具体边界 | 只保护“库里的历史”；一旦提交给目标 App，内容归目标 App 控制，本应用无法撤回。 |
| 已采取的防护 | ① 复用唯一 `commitText` 封装；② 设计上粘贴前校验输入会话（T3）；③ 不写系统剪贴板。 |
| 仍然存在的风险 | 目标 App 记录/上传；输入框切换导致误提交到 App B（T3 会话校验未落地）；提交后明文在目标 App 生命周期内不可控；输入法框架可能记录。 |
| 如何测试该风险 | ① 用 probe/日志确认粘贴后系统剪贴板无内容变化；② 在 App A 打开面板后切到 App B，验证不会误提交；③ 用测试目标 App 记录收到文本并验证会话绑定。**T3 未落地，未验证。** |

### I. 小企鹅 → 插件进程

| 维度 | 分析 |
| --- | --- |
| 是否存在明文 | **旧模式必然：是**。`ClipboardEntry.fromClipData` 在落库前调用 `transformer(str)`（`ClipboardEntry.kt:61-66`）；transformer 由 `FcitxRemoteService` 绑定（`FcitxRemoteService.kt:43-51,68-75`）；AIDL 直传 `String`（`IClipboardEntryTransformer.aidl:7`）。 |
| 明文停留位置 | 主应用内存、Binder 事务缓冲（system_server 中转）、插件进程内存。 |
| 是否跨进程 | **是**（Binder）。 |
| 是否经过系统剪贴板 | 通道 B 的输入来自系统剪贴板（是）；但 transformer 调用本身不经过剪贴板。通道 A 设计为不经过插件。 |
| 是否可能被其他 App 获取 | 只有同签名插件能注册（signature 权限，01 4.2）；普通 App 不能。 |
| 是否可能联网 | **插件可自行申请 INTERNET**；官方 ClearURLs 插件当前无 INTERNET，但信任域内插件可添加。 |
| 是否使用加密 | 否（Binder 明文 + 插件内明文）。 |
| 加密保护的具体边界 | 无应用层保护；签名权限只限制接入方，不限制接入后数据使用。 |
| 已采取的防护 | ① signature 级 `IPC`/`PLUGIN` 权限（`AndroidManifest.xml:29-37,185-193`）；② 严格模式（安全剪贴板开启且未允许插件）时 `transformer=null`（`FcitxRemoteService.kt:62-75`）；③ 门面层 transform 先检查 `secureClipboardAllowPlugins`（`ClipboardManager.kt:213-221`）；④ 插件异常被捕获不打印明文（`FcitxRemoteService.kt:43-51`）。 |
| 仍然存在的风险 | 兼容模式/允许插件开关下明文仍会交给插件；同签名插件供应链风险；插件进程崩溃不改变“已看到明文”的事实；严格模式覆盖范围依赖 T3/T2 实现（未落地）。 |
| 如何测试该风险 | ① 构建一个记录 `transform` 入参长度的测试插件（同签名）验证严格模式确实不调用；② `adb shell dumpsys` 检查服务权限；③ 代码审计确认无其他插件明文接口（当前只有 transformer 一条）。**T4/T5 计划，未执行，未验证。** |

### J. 插件进程 → 互联网

| 维度 | 分析 |
| --- | --- |
| 是否存在明文 | 插件进程内是；插件自行决定是否外发。 |
| 明文停留位置 | 插件进程内存/其网络栈/其服务器。 |
| 是否跨进程 | 是（插件 → 网络）。 |
| 是否经过系统剪贴板 | 否。 |
| 是否可能被其他 App 获取 | 网络对端与插件自身。 |
| 是否可能联网 | **是**，完全取决于插件 APK 是否申请 INTERNET；主应用不能撤销插件权限（也不应代插件做网络代理）。 |
| 是否使用加密 | 取决于插件（不是本项目的加密边界）。 |
| 加密保护的具体边界 | 无：主应用对插件的网络行为没有任何强制约束。 |
| 已采取的防护 | 严格模式默认不向插件提供明文（设计）；主应用自身无网络权限。 |
| 仍然存在的风险 | 同签名插件被攻破/恶意更新；用户开启“允许第三方剪贴板转换器”；插件供应链。 |
| 如何测试该风险 | ① 安装带 INTERNET 的测试插件（同签名）验证严格模式是否阻止明文到达；② 抓包（mitmproxy/PCAP）观察插件流量；③ `dumpsys package` 检查插件权限。**未验证。** |

### K. 数据库 → Android 系统备份 / 用户数据导出

| 维度 | 分析 |
| --- | --- |
| 是否存在明文 | 基线 commit：`clbdb` 明文会进入备份（规则未排除任何数据库）；T0 工作树：两类库都被 `database` 域排除。安全库本身是密文（设计），但一旦备份到另一台设备也无法解密（密钥在 Keystore，不随备份迁移）。 |
| 明文停留位置 | 备份代理、Google 备份/厂商迁移通道、备份包；用户数据导出 zip。 |
| 是否跨进程 | 是（系统备份代理）。 |
| 是否经过系统剪贴板 | 否。 |
| 是否可能被其他 App 获取 | 备份系统/云服务可及；普通 App 不可。 |
| 是否可能联网 | 云备份是网络服务（由系统管理）；用户导出文件由用户决定去向。 |
| 是否使用加密 | Android 备份通道由系统加密（传输/服务端），但**不是本项目应用层加密**；数据库文件本身：旧明文/新密文。 |
| 加密保护的具体边界 | 系统备份的加密不改变“明文库内容进入备份”的事实（基线）；加密库即使被备份也无法在无 Keystore 密钥的设备解密（但换机恢复失败本身是可用性问题，见 06）。 |
| 已采取的防护 | T0 工作树：`data_extraction_rules.xml:16-34,49-67`、`full_backup_content.xml:15-33` 排除 `clbdb*`/`secure_clbdb*`。 |
| 仍然存在的风险 | 规则未在真机上验证；用户数据导出仍打包整个 `databases/`（`UserDataManager.kt:60-81`）；导入可能恢复明文库并伪造迁移标记（`AppPrefs.kt:42`）。 |
| 如何测试该风险 | ① `adb shell bmgr backupnow org.fcitx.fcitx5.android` + `dumpsys backup`；② 用 `bmgr`/备份抓取工具检查包内是否还有 `clbdb`；③ 导出用户数据 zip 并解包检查；④ 在另一台设备导入验证迁移标记行为。**未验证。** |

### L. 锁屏、截屏、系统任务预览与日志

| 维度 | 分析 |
| --- | --- |
| 是否存在明文 | 剪贴板面板/编辑页显示明文（或遮罩）；截屏/录屏可捕获；logcat 在 verbose 下含组合文本（`FcitxInputMethodService.kt:939`），不含专门的剪贴板正文打印。 |
| 明文停留位置 | 屏幕缓冲、截屏文件、Recents 快照、logcat 环形缓冲、LogActivity 导出文件。 |
| 是否跨进程 | 系统 UI/截屏服务、ADB、日志服务。 |
| 是否经过系统剪贴板 | 否。 |
| 是否可能被其他 App 获取 | 截屏/录屏 App（读取屏幕/媒体库）、无障碍服务、系统 UI；普通 App 不能读其他 App 窗口内容。 |
| 是否可能联网 | 截屏/日志文件可能被用户/App 上传。 |
| 是否使用加密 | 否（屏幕/日志层面无加密）。 |
| 加密保护的具体边界 | 不适用（系统级采集面）。 |
| 已采取的防护 | 安全 Activity `excludeFromRecents`/`autoRemoveFromRecents`（清单 127-128、146-147）；`BaseSecureCaptureActivity.kt:61-70` 已设置 `FLAG_SECURE`（T2 落地）；剪贴板正文不写日志（01 3.8）。 |
| 仍然存在的风险 | 旧面板/编辑页无 `FLAG_SECURE`；输入法窗口截屏；verbose 日志 + 日志导出；崩溃日志；Recents 缩略图（编辑页 `excludeFromRecents` 已有，但安全 Activity 的 `FLAG_SECURE` 未实现）；锁屏通知（本功能无通知）。 |
| 如何测试该风险 | ① 真机截屏/录屏测试面板与确认页；② 查看 Recents；③ `adb logcat -d | grep` 搜索测试明文；④ 全量日志导出检查。**未验证。** |

## 4. 需求 10.2：保护目标与诚实边界

### 4.1 保护目标（逐条）

| 目标 | 状态（T0） | 依据/说明 |
| --- | --- | --- |
| 安全摘录模式：在小企鹅自身控制的整个操作链路中，不读取、不写入 Android 系统剪贴板 | 设计目标；**未实现未验证** | T2 未落地；只能从架构/清单确认方向（清单 124-160；0 架构文档第 26 行） |
| 普通复制模式：尽量缩短系统剪贴板明文存留时间 | 设计目标；旧代码无此能力，T3 未落地 | 设计：加密+事务成功+重读一致后 `clearPrimaryClip`；受 TOCTOU 与进程存活限制 |
| 私有粘贴：不重新写入系统剪贴板 | 旧面板路径源码确认成立（`ClipboardWindow.kt:126-129`）；安全模式粘贴控制器未落地 | 统一走 `commitText`（`input/FcitxInputMethodService.kt:423-454`） |
| 插件严格模式：不向第三方插件提供原始摘录/剪贴板文字 | 门面/服务层已实现开关（`FcitxRemoteService.kt:62-75`、`ClipboardManager.kt:213-221`）；通道 B 控制器未落地 | 需 T3 复核所有通道 B 入口都经过该开关 |
| 日志不含剪贴板正文 | 源码审计未发现打印正文；verbose 下组合文本仍会进 logcat | 不能宣称“日志绝对无明文” |
| 备份不含剪贴板库 | T0 工作树已配置排除；未验证 | 见 K |

### 4.2 诚实边界（必须如实披露，禁止的表述）

> 以下四条是需求 10.1 末尾的“特别禁止”。本节逐条给出**正确表述**与**依据**，后续对外报告/验收必须沿用正确表述。

| 禁止的表述 | 正确表述 | 依据 |
| --- | --- | --- |
| “Binder IPC 是端到端加密” | **Binder 传输不加密**。主应用↔插件、主应用↔目标 App 传输的是明文 String/Parcelable；签名权限只限制连接方，不保护已连接后的明文。AES-GCM 只保护落盘的历史。 | `IClipboardEntryTransformer.aidl:3-9`；`FcitxRemoteService.kt:43-51`；`input/FcitxInputMethodService.kt:423-454` |
| “设备存储加密等于完整的应用层加密” | **不等于**。FBE/FDE 只保护静态数据；解锁后系统服务、root、备份代理可能以受保护方式访问；应用层加密仅覆盖 `secure_clbdb` 内的密文。 | 需求 5.3；`SecureClipboardVault.kt:9-16` 注释 |
| “系统剪贴板的短暂暴露完全不存在” | **必然存在**。通道 B 的明文先进入系统剪贴板，清空发生在加密落盘之后，且清空可能失败/被竞态影响。 | 需求 2.1/4.3/4.4；01 第 3.2 节 |
| “目标 App 收不到用户主动粘贴的明文” | **目标 App 必然收到明文**。InputConnection 提交后内容归目标 App；用户主动粘贴即授权其接收。 | 需求 6.1/10.2；`input/FcitxInputMethodService.kt:423-454` |

补充边界（同样必须披露）：

1. 来源 App 本来就持有被选中的文本；Android 组件（system_server、输入法框架）参与跨应用文本传递。
2. 普通文本输入不可能具备“端到端加密”语义：输入法、目标 App、系统框架都能看到明文。
3. `clearPrimaryClip()` 的可用性/行为受 Android 版本与 OEM 影响（API 28+ 才有该 API；本项目 minSdk 23 需要降级策略）。
4. SSD/UFS 上已删除明文的物理残留无法保证立即擦除（需求 5.4/16 已承认）。
5. 输入法进程不能保证常驻：进程被杀/未解锁/后台限制时通道 B 无法转存（需求 8.2）。
6. 严格模式关闭插件通路只对“本应用主动给插件的数据”有效；插件若通过其他系统能力（如自己的前台服务读剪贴板）仍然可以获取系统剪贴板内容，这不是本应用能阻止的。

## 5. 测试方法汇总（全部未验证）

| 场景 | 自动化/命令 | 判据 |
| --- | --- | --- |
| A/B 安全摘录不碰剪贴板 | 独立 probe App（T4 计划）注册 `OnPrimaryClipChangedListener`；执行安全摘录；比对事件前后剪贴板内容/时间戳 | 事件数与内容不变 |
| C/D 普通复制转存与清空 | probe App 记录事件；真机复制已知文本；观察剪贴板内容/长度变化 | 转存后剪贴板被清空或变为空，加密库出现记录 |
| E/F 加密与落库 | JVM 单测（T1 计划）：往返/篡改/AAD/IV；设备导出 `secure_clbdb` 搜索明文 | 篡改必失败；库文件无明文匹配 |
| G 面板遮罩与按需解密 | 真机 UI 操作 + 源码审计 | 列表无全文；分享禁用；编辑不写遮罩 |
| H 私有粘贴 | probe App 观察剪贴板；在 A/B 两个 App 间切换 | 粘贴后剪贴板无变化；不误提交到 B |
| I/J 插件明文 | 同签名测试插件记录 `transform` 参数长度；严格模式开关对比；抓包 | 严格模式下不调用；插件无网络则无外传 |
| K 备份/导出 | `bmgr`/`dumpsys backup`、导出 zip 解包 | 不含 `clbdb*`/`secure_clbdb*` |
| L 截屏/日志 | `adb exec-out screencap`、`adb logcat -d`、日志导出 | 无测试明文；Recents 无缩略图 |

**真实执行状态**：以上测试在 T0 **均未执行**（无真机、未运行 Gradle/instrumented 测试）。任何“已通过”的说法都必须等 T4/T5 后续实测后由 Lead 统一更新。

## 6. 结论（可直接引用的表述）

1. 本项目能保证的：**落盘历史使用认证加密**（设计/T1）、**安全摘录/私有粘贴的设计不写入系统剪贴板**（设计/T2/T3）、**应用自身无网络权限**（源码确认）、**严格模式默认不向插件提供明文**（设计+门面源码）、**备份规则排除剪贴板库**（配置，未验证）。
2. 本项目不能保证的：系统剪贴板的短暂暴露、目标 App 收到粘贴明文、来源 App 持有原文、Binder 对端可见明文、物理残留、进程常驻、OEM/系统剪贴板管理器的行为。
3. **在 T1/T2/T3 落地并通过 12 节测试前，上述“能保证”的项目都只能标记为“设计/未验证”，不得作为已实现的安全效果对外宣称。**

## 附录 B：T1 快照增量（实现落地后对 10.1 场景状态的更新）

> 审计期间 T1 完成、T2/T3 部分落地。下面只更新“状态”结论；第 2 节矩阵中标注 T0 的列仍代表当时时点。所有新增实现**均未构建/未测试**，状态为“实现/部分”，不是“已验证”。

| 场景 | T1 快照状态 | 依据 |
| --- | --- | --- |
| A 安全摘录 PROCESS_TEXT | 实现（源码），未测试 | `ui/securecapture/SecureCaptureActivity.kt`、`BaseSecureCaptureActivity.kt:61-191`、`SecureCaptureValidator.kt:65-74` |
| B 安全摘录 IME 选区 | 实现（源码），未测试 | `input/secureclipboard/SelectedTextCaptureController.kt:64-143`、`SelectedTextPolicy.kt:68-103`；盾牌接线见 `KawaiiBarComponent` T2 diff |
| E 加密模块 | 实现（源码），未测试 | `ClipboardCrypto.kt:82-184`、`ClipboardKeyManager.kt:50-172` |
| F Room 密文库 | 实现（源码），未测试 | `SecureClipboardDatabase.kt`、`SecureClipboardDao.kt`、`SecureClipboardRepositoryImpl.kt:117-168` |
| G 私有数据库→面板 | 部分：安全模式列表映射已实现；单条解密读取已在门面/仓库实现；**面板 UI 改造（T3）未完成** | `ClipboardManager.kt:262-293,511-554`；`ClipboardWindow.kt` 仍旧路径 |
| H → 目标 App InputConnection | 部分：KawaiiBar 安全建议已改 `readText(id)` + 会话校验 + `commitText`；**面板旧路径与 PasteController 未落地** | `KawaiiBarComponent.kt` T2 diff；`ClipboardWindow.kt:126-129` 旧 |
| I → 插件 | 部分：严格模式默认移除 transformer（`FcitxRemoteService.kt:62-75`）；通道 B 控制器调用 transformer 前经门面开关（`ClipboardManager.kt:213-221`、`SecureClipboardController.kt:128-139`） | 源码；未测试 |
| J 插件→互联网 | 不变：主应用无 INTERNET；插件自身能力不受控 | 01 第 1.3/1.4 节 |
| K 数据库→备份 | 配置已排除（`data_extraction_rules.xml:16-34`、`full_backup_content.xml:15-33`）；未验证 | 同上 |
| L 锁屏/截屏/日志 | 部分：安全 Activity `FLAG_SECURE` 已实现（`BaseSecureCaptureActivity.kt:64`）；日志仍无正文打印；verbose 组合文本风险不变 | 同 01 第 3.8 节 |

**结论**：10.1 各场景的“设计目标”大部分已有实现对应物，但 10.2 的保护目标仍需**测试证据**才能成立；本附录不改变第 4.2 节的诚实边界，也不改变“未验证”的总判断。

**T2 快照补充（15:58 前后）**：`G` 项面板安全模式已接入 `ClipboardWindow`/`ClipboardAdapter`（安全记录遮罩显示、分享禁用、一次性显示对话框）；`H` 项已接入 `SecureClipboardPasteController` + `PasteSession`（`ClipboardWindow.kt` 约 92-102、220-238 行），旧 `commitText(entry.text)` 路径仅保留给非安全记录。上述均为源码状态，未构建/未测试；面板的设备认证 gate 尚未接线（`authenticate = null`，fail-closed）。
