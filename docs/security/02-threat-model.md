<!-- SPDX-License-Identifier: LGPL-2.1-or-later -->

# 02 威胁模型（安全剪贴板）

> 任务：T5。需求依据：项目需求文档第 1.3、4、5、6、7、9、10、17 节；架构依据：`docs/security/00-architecture-and-file-ownership.md` 与 `SecureClipboardContract.kt`。
> 事实依据：`01-source-audit.md` 中标注【S】的源码结论。本文不重复大段源码引用，关键点给路径。
> 快照：commit `eb5b502` + T0 工作树（见 01 第 0 节）。T1/T2/T3 实现未落地，涉及它们的控制措施在本模型中标注“设计/待实现”。

## 1. 资产（Asset）

| ID | 资产 | 敏感度 | 所有者 | 说明 |
| --- | --- | --- | --- | --- |
| A1 | 用户的剪贴板明文历史 | 高 | 用户 | 可能是密码、验证码、身份证号、聊天记录、地址等。旧库 `clbdb` 明文存放（`data/clipboard/db/ClipboardEntry.kt:20`） |
| A2 | 安全剪贴板历史（密文） | 中 | 用户 | AES-256-GCM 密文 + 元数据（`SecureClipboardContract.kt:129-178`） |
| A3 | Android Keystore 密钥 | 极高 | 系统 | 不可导出；在 Keystore 内使用（T1 设计） |
| A4 | 用户选中/正在输入的文本 | 高 | 用户 | 安全摘录、IME 选区读取、组合预编辑等 |
| A5 | 来源 App 的选中文本 | 高 | 其他 App/用户 | PROCESS_TEXT/SEND 传入，来源 App 本身已持有 |
| A6 | 剪贴板明文在内存中的副本 | 高 | 应用进程 | `lastEntry`、列表 Paging 数据、提交参数等（01 第 3.5 节） |
| A7 | 目标 App 输入框中的文本 | 高 | 用户/目标 App | 粘贴后由目标 App 持有，本应用不可撤回 |
| A8 | 安全配置与状态 | 低-中 | 用户 | “是否开启安全模式”本身可能是敏感元数据；`ClipboardSecurityStatus` 不含正文（`Contract.kt:248-272`） |
| A9 | 用户数据导出包（zip） | 高 | 用户 | 含 `databases/`、`shared_prefs/`（`data/UserDataManager.kt:54-81`） |
| A10 | 日志/logcat | 中 | 用户/系统 | 可被导出（`ui/main/LogActivity.kt:41-57`） |

## 2. 攻击者（Adversary）

| ID | 攻击者 | 能力 | 动机 | 现实性 |
| --- | --- | --- | --- | --- |
| E1 | 普通恶意 App（无 root） | 读取系统剪贴板（前台/获得焦点时）、发送显式/隐式 Intent、申请 PROCESS_TEXT/SEND 目标、监听自身能看到的广播 | 窃取剪贴板/输入内容 | 高 |
| E2 | 恶意 App（诱导用户设为输入法/无障碍，或利用漏洞） | 更强的输入上下文访问 | 窃取全部输入 | 中（需求禁止本应用走此路，但攻击者可针对用户） |
| E3 | 同签名信任域内的恶意/被攻破插件 | 通过 `permission.PLUGIN`/`IPC` 绑定，注册 transformer；自身可申请 INTERNET | 明文外传 | 低-中（取决于发布链） |
| E4 | 拿到已解锁设备的物理攻击者 | 安装 App、导出数据、Developer 选项、ADB（若开启） | 提取历史 | 中 |
| E5 | 拿到锁屏设备的物理攻击者 | 无法解锁；可利用 direct boot 应用、通知、Recents 缩略图 | 信息泄露 | 中 |
| E6 | 云备份/换机迁移通道的窃取者 | 获取 Google 备份/厂商迁移数据 | 提取历史 | 低-中 |
| E7 | 恶意目标 App（被用户粘贴） | 通过 InputConnection 接收文本、可记录文本 | 收集用户粘贴内容 | 高（用户主动选择） |
| E8 | 系统/ROM 厂商侧的剪贴板管理、输入法框架观测 | 系统服务记录剪贴板预览、日志 | 合规/遥测/漏洞 | 中（平台行为） |
| E9 | 供应链攻击者（依赖/构建/插件仓库） | 修改构建产物 | 全盘控制 | 低但影响大 |

## 3. 信任边界（Trust Boundary）

```text
用户/来源 App（不可信）
  E1/E2/E7：恶意 App、目标 App
        │ Intent(ACTION_PROCESS_TEXT/SEND)     │ InputConnection(Binder，目标 App 侧)
        ▼                                       ▼
本应用进程（org.fcitx.fcitx5.android，单进程）
  Capture Activity/IME → ClipboardManager 门面 → SecureClipboardVault(Room secure_clbdb)
                        → fcitx 原生引擎（JNI，同进程） / 旧库 clbdb（明文，兼容模式）
        │ Binder(IPC, signature)   │ 系统剪贴板(系统服务)   │ Binder(InputConnection)
        ▼                          ▼                        ▼
插件 App（同签名，E3：transformer 明文）   Android 系统/其他 App（E1）   目标 App（E7）
        │
        ▼
云备份/换机迁移（E6）   日志/logcat、Recents 缩略图、截屏（E5/E8）
```

**边界说明**：

1. **系统剪贴板是公开信道**：Android 系统剪贴板对前台应用/default IME 可读；任何经过它的数据都必须视为“可能被其他 App/系统读取”。
2. **Binder 不是加密边界**：主应用↔插件、主应用↔目标 App 的 Binder 传输是明文 Parcelable/String；同签名/同用户下可被对端读取。签名权限只做“谁能连接”，不做“连接后看不到明文”。
3. **Android Keystore 只保护密钥材料与使用过程**；它不阻止已解锁设备上以本应用身份运行的代码解密，也不保护内存里的明文。
4. **设备存储加密（FBE/FDE）保护静态数据**，不等于应用层加密：解锁后的系统服务、root、备份代理可能以受保护方式访问。

## 4. STRIDE 威胁表

| # | 类别 | 威胁 | 受影响资产 | 现有/设计控制 | 剩余风险 | 验证方式 |
| --- | --- | --- | --- | --- | --- | --- |
| S1 | Spoofing | 任意 App 伪造 `ACTION_PROCESS_TEXT`/`ACTION_SEND` 调用安全摘录，批量写入垃圾/覆盖数据 | A2/A5 | 导出 Activity 必须校验 Intent/Extra、用户确认保存（`AndroidManifest.xml:124-160` 已注册；T2 实现校验+确认，**未落地**）；速率限制常量 `Contract.kt:193-196` | 恶意 App 可拉起确认框骚扰；用户可能误点确认 | 恶意 App 发送伪造 Intent 的 instrumented 测试（T4 计划） |
| S2 | Spoofing | 插件冒充/重放 transformer 注册 | A1/A6 | `permission.IPC`/`permission.PLUGIN` 均 signature（清单 29-37） | 同签名插件可注册任意 transformer | `adb shell dumpsys package` 检查权限保护级别；代码审计 |
| S3 | Spoofing | 锁屏/direct boot 下诱骗应用在未解锁时工作 | A2/A3 | direct boot 不初始化 Vault（`ClipboardManager.kt:152-158`、`SecureClipboardVault.kt:9-16`） | 旧明文 `clbdb` 在 DPS 下仍可写 | 真机锁屏/重启测试 |
| T1 | Tampering | 修改/替换 `secure_clbdb` 文件（root/备份恢复） | A2 | AES-GCM 认证标签 + AAD（T1 设计，`Contract.kt:122-128`）；备份排除（`data_extraction_rules.xml:16-34`） | 整库替换/回滚无法检测（无单调计数器）；Keystore 密钥丢失则无法解密 | 篡改密文/IV/AAD 的单测（T1 计划）；设备上替换文件测试 |
| T2 | Tampering | 竞态：A 加密期间用户复制 B，然后错误清空 B | A5/A7 | 快照+序号+串行队列+重读比较+清空回调去重（需求 4.4；T3 状态机设计，**未落地**） | Android 无原子 compare-and-clear，TOCTOU 窗口存在 | 并发复制 instrumented 测试（T4 计划） |
| T3 | Tampering | 修改 `clbdb`/`secure_clbdb` 绕过迁移校验 | A1/A2 | 迁移成功后校验完成才清旧表（`ClipboardManager.kt:417-433`）；失败保留（`434-442`） | 中途进程死亡留下的半迁移状态需恢复策略 | 迁移中断测试（杀进程） |
| T4 | Tampering | 通过用户数据导入注入明文库/伪造迁移标记 | A1/A9 | 导入仅接受本包 zip（`UserDataManager.kt:100-101` 包名校验） | 导入可恢复 `clbdb` 与 `secure_clipboard_legacy_migrated` 偏好（`AppPrefs.kt:42`），可能跳过迁移 | 导出→导入→再开安全模式测试 |
| R1 | Repudiation | 无法证明“何时捕获/清空成功”，界面自证安全 | A8 | `ClipboardSecurityStatus` 记录计数/时间/错误（`Contract.kt:248-272`）；设置页展示（`SecureClipboardSettingsFragment.kt:149-200`） | 状态是进程内 `StateFlow`，重启丢失；不能作为事后审计证据 | 源码审计；测试状态展示 |
| I1 | Info Disclosure | 旧库明文 `clbdb` 被其他 App/备份/导出读取 | A1 | 应用私有目录 + 备份排除（T0 已加）+ 迁移后清空（设计） | root/ADB/物理镜像可读；迁移前明文仍在；WAL/页残留 | 真机 `run-as`/备份抓取测试；见 03 文档 L 场景 |
| I2 | Info Disclosure | 系统剪贴板短暂保留明文，其他 App 可能读取 | A5 | 加密成功后才清空；TOCTOU 重读比较；不承诺 0 暴露（需求 4.4/8.2） | 清空前窗口、清空失败、非文本内容 | 独立探针 App 记录（T4 计划） |
| I3 | Info Disclosure | 插件 transformer 读取明文并外传 | A1/A6 | 严格模式 `transformer=null`（`FcitxRemoteService.kt:62-75`、`ClipboardManager.kt:213-221`）；通道 A 不经插件 | 兼容模式/允许插件开关下明文仍会交给同签名插件 | 插件 App 记录 transform 入参的测试（T4/T5 计划） |
| I4 | Info Disclosure | 日志/崩溃日志/LogActivity 导出泄露 | A4/A10 | 剪贴板代码不打印正文（01 3.8）；verbose 默认关闭（`AppPrefs.kt:36`） | verbose 下组合文本进 logcat（`FcitxInputMethodService.kt:939`）；logcat 可被系统/ADB 读 | 源码审计 + logcat 抓取 |
| I5 | Info Disclosure | Recents 缩略图/截屏暴露历史 | A1/A2 | 安全 Activity `excludeFromRecents`/`autoRemoveFromRecents`（清单 127-128、146-147）；`BaseSecureCaptureActivity.kt:61-70` 已设置 `FLAG_SECURE`（T2 落地） | 剪贴板面板是输入法窗口，不产生 Recents 缩略图；但设置页/编辑器 Inspector 可能被截屏 | 真机截屏/Recents 检查 |
| I6 | Info Disclosure | 备份/换机迁移带走历史 | A1/A2/A9 | T0 工作树排除 `database` 域（01 3.9） | 排除只在 Android 备份框架生效；用户手动导出另算 | `bmgr backupnow` + `dumpsys backup` 验证 |
| I7 | Info Disclosure | 安全摘录 Activity/分享把明文放进 Intent extras，被系统/第三方观测 | A5 | PROCESS_TEXT/SEND 本来由系统传递；Activity 只读取、不把原文写回 result（T2 设计） | 系统/发起方可见；`singleTop` 复用时的旧 Intent 需要处理 | instrumented 测试 Intent 内容 |
| I8 | Info Disclosure | 遮罩字符串被误当正文提交/写库 | A2/A7 | T0 门面用 `maskedLabel` 构造显示行（`ClipboardManager.kt:224-243`） | `KawaiiBarComponent.kt:310-325` 仍会 commit `it.text`；`ClipboardEditActivity` 仍 `get()` 后编辑遮罩（01 5.4）——T2/T3 未落地 | 集成后 T3/T2 代码审查 + 真机粘贴测试 |
| I9 | Info Disclosure | fcitx 原生剪贴板模块持有明文并暴露给引擎插件 | A6 | T0：`it.secure` 时不镜像（`core/Fcitx.kt:486-492`）；旧模式下仍镜像（兼容性） | 原生 addon/模块可读；无 Android 权限拦截 | 源码审计 + 引擎插件测试 |
| I10 | Info Disclosure | 无障碍/输入法框架/厂商 ROM 侧观测 | A4/A7 | 应用无法控制 | 输入法本身必须接收文本；平台可见 | 平台行为，注明限制 |
| D1 | DoS | 极长文本/连续复制/大历史导致 OOM 或卡顿 | 应用可用性 | 常量上限 `100_000`/`400_000`（`Contract.kt:181-196`）；历史限额；速率限制 | 上限仍可占约 400KB×多条；加密前明文内存峰值 | 文本长度梯度测试（见 04） |
| D2 | DoS | 数据库损坏/磁盘满/Keystore 不可用导致监听链崩溃 | 数据可用性 | 失败不删除原文（需求 4.3/12.2；T3 设计）；异常捕获（`ClipboardManager.kt:487-490`） | `NotReady` 时安全模式下既不加密也不存旧库（01 3.11） | 故障注入测试（T4） |
| E1 | Elevation | 导出 Activity/Provider 被恶意利用越权写/读 | A1/A2 | Activity 无权限但需用户确认；Provider 有 `MANAGE_DOCUMENTS`；数据库不导出 | 用户确认框被伪造/诱导点击；`SecureUnlockActivity` exported=false | instrumented 安全测试 |
| E2 | Elevation | 通过插件服务权限字串重定义获得权限 | A1/A2 | signature 权限由本应用声明；重定义会安装失败/被忽略（Android 平台行为） | 平台实现差异需实测 | 安装两个同权限名不同签名 APK 测试（T4/T5 计划） |

## 5. 需求 → 控制措施映射

> 需求编号沿用需求文档章节号。状态列：设计=架构/契约已定；源码=已存在代码；未落地=T0 尚不存在实现；未验证=需测试。

| 需求 | 威胁/资产 | 控制措施 | 代码/文档位置 | 状态 |
| --- | --- | --- | --- | --- |
| 2.1 通道 A 不经系统剪贴板 | I2/I7 | 专用 Activity 接收 PROCESS_TEXT/SEND；IME 选区读取；保存走 Vault | 清单 124-160；`Contract.kt:31-48`；T2 未落地 | 设计+清单已落地，实现未落地 |
| 2.1 通道 B 自动加密后清空 | I2/T2 | 监听→校验→加密→事务→重读→clearPrimaryClip | `ClipboardManager.kt:182-195,452-493`（旧监听器）；T3 状态机未落地 | 部分 |
| 2.1 私有粘贴不写剪贴板 | I2 | `FcitxInputMethodService.commitText`（`input/FcitxInputMethodService.kt:423-454`） | T3 PasteController 未落地；旧面板已用 commitText | 部分 |
| 3.1 PROCESS_TEXT 校验与确认 | S1/I7 | T2 校验器 + 确认 UI；exported=true 但需用户确认 | 清单 124-142；T2 未落地 | 未落地 |
| 3.2 IME 选区读取限制 | I10/S1 | getSelectedText 仅在用户点击时、密码框禁止、尊重 NO_PERSONALIZED_LEARNING | `core/CapabilityFlag.kt:97-180`（密码/敏感判定基础）；T2 未落地 | 未落地 |
| 3.3 ACTION_SEND 校验 | S1 | 仅 text/plain、大小限制、确认 | 清单 144-160；T2 未落地 | 未落地 |
| 4.3 成功后清空、失败不删原文 | I2/D2 | 锁内顺序执行；失败保留；状态上报 | `Contract.kt:62-81`（错误类型）；T3 未落地 | 未落地 |
| 4.4 竞态保护 | T2 | 序号/快照/串行/Mutex/重读比较/去重 | 需求 4.4；T3 ClipboardEventProcessor（设计） | 未落地 |
| 4.5 非文本拒绝 | D1/I2 | 只取 item.text，其余 null（`ClipboardEntry.kt:48-67`）；安全控制器策略（T3） | 部分源码 | 部分 |
| 5.1 AES-256-GCM + Keystore | A1/A2/A3 | T1 crypto/keymanager；`Contract.kt:122-128` | 未落地 | 未落地 |
| 5.2 密文库无明文列 | A2 | SecureClipboardEntry（`Contract.kt:129-178`） | 契约源码 | 设计 |
| 5.3 内部私有存储/CES vs DPS | A2/A3 | 应用私有目录；direct boot 不初始化 Vault（`ClipboardManager.kt:152-158`） | `SecureClipboardVault.kt:9-16` | 源码（部分） |
| 5.4 迁移不丢数据 | A1/T3 | migrateLegacyIfNeeded：读→加密→校验→清旧表（`ClipboardManager.kt:396-445`） | 源码（依赖 T1 实现） | 门面源码；T1 未落地 |
| 5.5 密钥丢失处理 | A2/A3 | 错误类型 KeyUnavailable/KeyInvalid（`Contract.kt:68-81`）；不静默重建密钥 | T1 未落地 | 未落地 |
| 6.1 直接提交 | A7 | commitText 封装 + 会话校验 | `input/FcitxInputMethodService.kt:423-454`；T3 未落地 | 部分 |
| 6.2 输入框变化 | A7/T2 | 绑定输入会话、提交前核验 | T3 设计 | 未落地 |
| 6.3 密码框策略 | A4/I10 | CapabilityFlags.fromEditorInfo（`core/CapabilityFlag.kt:97-180`） | T2 未落地 | 部分基础 |
| 7.1 插件隔离 | A1/I3 | 严格模式移除 transformer；通道 A 不经插件 | `FcitxRemoteService.kt:62-75,135-160`；`ClipboardManager.kt:209-221` | 源码（门面/服务），T3 未落地 |
| 7.2 无 INTERNET | I3/I6 | 清单无 INTERNET；无网络依赖 | 01 1.3/1.4 | 源码确认 |
| 9 设置页 | A8/R1 | SecureClipboardSettingsFragment、AppPrefs.Security、路由 | `AppPrefs.kt:364-427`；`SecureClipboardSettingsFragment.kt`；`SettingsRoute.kt:82,223-224` | 源码（状态展示依赖 T1/T3 状态上报） |
| 10.1/10.2 隐私报告 | 全部 | `03-dataflow-privacy-report.md` | 本文档目录 | 文档 |
| 11 性能 | D1 | 事件驱动/异步/限额/不批量解密 | `04-performance-battery-evaluation.md`；门面 allEntries 不解密（`ClipboardManager.kt:284-293,531-554`） | 设计+部分源码 |
| 12 测试 | 全部 | T4 instrumented + probe；T5 本文档 | T4 未落地 | 未落地 |
| 13 原功能兼容 | — | 安全模式默认关闭；旧路径保留（`AppPrefs.kt:366-371`） | 源码 | 需回归测试 |
| 17 验收 | 全部 | `06-unresolved-risks-and-acceptance.md` | 本文档目录 | 文档 |

## 6. 假设与不在范围

### 6.1 假设

1. 设备未 root、未开启 ADB root；系统未安装可读取应用私有目录的恶意组件；OEM 未把输入法列入特殊监视名单。
2. 应用安装为设备上的默认输入法（否则系统剪贴板的读取权限受限，通道 B 无法工作）。
3. Android Keystore 在本机可用且密钥未失效；用户未在安全模式开启后清除应用数据。
4. 官方插件与主应用同签名；插件更新由用户/应用商店控制。
5. 用户理解“普通复制必然经过系统剪贴板”这一平台事实。

### 6.2 不在本威胁模型范围

1. 已被 root/物理控制的设备上的攻击（无法防御）。
2. 用户主动把内容粘贴到恶意 App（目标 App 必然收到明文）。
3. 厂商 ROM/系统输入法框架对输入内容的记录（应用无法控制）。
4. 屏幕录制、截屏、肩窥。
5. 供应链（构建系统、依赖仓库、插件发布链）被整体攻破后的最终影响——只能给出边界说明。
6. 侧信道（功耗、缓存计时）级别的攻击。

## 7. 风险接受摘要（供验收文档引用）

- **不可消除**：系统剪贴板在通道 B 中的短暂明文窗口；目标 App 收到粘贴明文；来源 App 本来持有选中文本；Binder 对端可见明文；SSD/UFS 物理残留；进程无法常驻。
- **可缓解但需实现验证**：TOCTOU 窗口（重读比较把风险降到极小窗口）；插件明文（严格模式默认关闭）；备份/导出（排除规则 + 迁移）；日志（不打印正文）；遮罩误提交（待 T2/T3）。
- **本模型不接受**任何“端到端加密/绝对零暴露/绝对不丢数据”的表述；详见 `03-dataflow-privacy-report.md` 第 4 节。
