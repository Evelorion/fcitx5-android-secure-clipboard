<!-- SPDX-License-Identifier: LGPL-2.1-or-later -->

# 05 兼容性说明（Android 14/15/16、OxygenOS/ColorOS 与系统限制）

> 任务：T5，对应需求文档第八部分（8.1 适配范围、8.2 重点场景）与第十六部分“兼容性说明”。
> 快照：commit `eb5b502` + T0 工作树（2026-10-31）。**没有真机、没有模拟器运行记录、没有运行 Gradle。** 下表中凡涉及设备行为的结论一律“未验证”；平台 API 行为只写 Android 官方框架语义，不冒充本项目实测。
> 证据标记：【S】源码确认、【L】Android 平台限制（框架语义，非本仓库源码）、【T】需真机测试（未验证）。

## 1. 适配范围与测试记录要求

| 项 | 要求 | 当前状态 |
| --- | --- | --- |
| 重点系统 | OxygenOS、ColorOS；Android 14 / 15 / 16 | **未测试** |
| 项目支持范围 | minSdk 23（`build-logic/convention/src/main/kotlin/Versions.kt:12`）；targetSdk/compileSdk 36（同文件 11/13） | 仅源码确认 |
| 必须记录 | 真实测试机型、系统版本、安全补丁日期、输入法是否默认、是否 root/开发者选项 | **无记录**（本报告不编造机型） |
| 结论口径 | 不得假定所有一加机型表现一致（需求 8.1） | 沿用 |

## 2. Android 版本行为矩阵

### 2.1 系统剪贴板 API 与限制

| 主题 | Android 版本 | 平台行为/限制 | 对本项目的影响 | 依据 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 剪贴板读取权限 | 10+（Q） | 只有**当前获得焦点的应用**或**默认输入法**可以读剪贴板；后台应用读取受限 | 通道 B 依赖“小企鹅是默认输入法且进程存活”；切换默认输入法/进程冻结后无法读取，不能承诺常驻 | 【L】需求 8.2 也要求明确该限制 | 未验证 |
| 剪贴板内容预览 | 12/12L 有复制提示；13+ 系统剪贴板预览浮层 | 系统 UI 会展示复制的文本片段 | 属于“系统剪贴板的短暂暴露”，应用无法阻止；清空后预览可能短暂残留 | 【L】 | 未验证 |
| `ClipboardManager.clearPrimaryClip()` | API 28（P）起提供 | minSdk 23 设备上**没有**该 API | 通道 B 的“立即清空”在 API 23-27 需要降级策略（例如写入空 ClipData），效果与平台行为不同；T3 实现必须处理，否则在旧设备上编译不通过或行为缺失 | 【S】`Versions.kt:12`；【L】API 级别 | **未实现/未验证** |
| `ClipDescription.timestamp` | API 26（O）起 | 旧版本无时间戳 | 旧监听器已有降级（时间戳+hash）：`utils/ClipData.kt:11-15`、`ClipboardManager.kt:458-468` | 【S】 | 源码确认 |
| `ClipDescription.EXTRA_IS_SENSITIVE` | API 33（TIRAMISU） | 旧版本用字符串常量 | `ClipboardEntry.kt:42-46` 已兼容 | 【S】 | 源码确认 |
| 剪贴板历史（OEM） | 各 OEM 不同 | 厂商剪贴板管理器可能保留历史/云同步 | `clearPrimaryClip()` 不保证清除 OEM 历史；这是重要的“清空不等于擦除”边界 | 【L】/【P】 | 未验证 |
| 多用户/工作资料 | 各版本 | 剪贴板按用户隔离（工作资料独立） | 需在“安装在工作资料的输入法”场景测试；本应用单用户假设 | 【L】 | 未验证 |

### 2.2 Android 14 / 15 / 16 重点变化（与本功能相关）

| 版本 | 变化/限制 | 影响 | 状态 |
| --- | --- | --- | --- |
| Android 14（API 34） | 前台服务类型/权限收紧；隐式 Intent 限制；默认输入法相关 API 变化（如 `InputMethodManager.currentInputMethodSubtype`，本仓库已有处理：`input/FcitxInputMethodService.kt:691-697,710-725`） | 不新增前台服务，影响小；IME 子类型逻辑已有适配 | 源码确认已有部分适配；真机未验证 |
| Android 15（API 35） | targetSdk 35+ 强制 edge-to-edge；预测性返回（`OnBackInvokedCallback`）成为默认 | 安全摘录/设置页需要 edge-to-edge 适配；返回手势行为需测试（仓库现有 `OnBackPressedCallback` 用法：`ui/common/DynamicListAdapter.kt:159`、`ui/main/settings/FcitxPreferenceFragment.kt:56`） | 未验证（新 Activity 未落地） |
| Android 16（API 36） | 以实际 SDK 行为为准；本仓库 compileSdk/targetSdk 36（`Versions.kt:13`） | 需在 Android 16 真机验证剪贴板/后台/输入法行为；本报告不臆测未确认的行为 | **待测试** |
| 所有版本 | 输入法进程可被系统随时回收；IME 不是前台服务 | 通道 B 不能承诺常驻（需求 8.2） | 【L】 | 未验证 |

### 2.3 Direct Boot / 锁屏 / 重启

| 场景 | 源码行为 | 限制/风险 | 状态 |
| --- | --- | --- | --- |
| 首次解锁前（direct boot） | `FcitxApplication` 进入 direct boot 模式（`FcitxApplication.kt:85-88`）；`ClipboardManager.init` 使用 DPS context（`FcitxApplication.kt:136`）；安全 Vault **不初始化**（`ClipboardManager.kt:152-158`） | ① 旧 `clbdb` 在 DPS 下与正常模式 CES 的库不是同一个文件；② 安全模式在解锁前不可用（`NotReady`），普通复制既不入加密库也不入旧库（安全模式分支已移除旧监听器，`ClipboardManager.kt:182-195`） | 源码确认；真机行为未验证 |
| 解锁 | `unlockReceiver` 让应用退出并普通模式重启（`FcitxApplication.kt:48-58`） | 重启后 Vault 初始化；DPS 中历史是否被迁移/忽略需测试 | 未验证 |
| 锁屏状态 | 输入法在锁屏下一般不可用；安全 Activity 可能被锁屏页遮挡 | 无法承诺锁屏时捕获/清空；恶意应用从锁屏拉起 PROCESS_TEXT 的可能性需实测 | 未验证 |
| 手机重启 | `FcitxApplication.onCreate` 重新初始化；首次解锁前见上 | 重启后监听是否恢复取决于进程启动时机（系统绑定 IME 时） | 未验证 |
| 切换默认输入法 | 非默认输入法时剪贴板读取受限【L】 | 通道 B 停止工作（依平台版本） | 未验证 |

### 2.4 低电量 / 深度睡眠 / 后台限制

| 场景 | 平台行为 | 对功能影响 | 状态 |
| --- | --- | --- | --- |
| Doze / 深度睡眠 | 后台 CPU/网络/唤醒受抑制 | 输入法进程可能被冻结，剪贴板回调延迟或丢失；**不能承诺实时转存** | 【L】未验证 |
| 低电量模式 | 系统可能限制后台/降低调度 | 同上 | 未验证 |
| 后台限制/应用待机 | 应用进入 restricted bucket | 进程可能被杀；监听消失 | 未验证 |
| OEM 电池优化/自启动管理 | OxygenOS/ColorOS 有额外后台清理策略（具体以机型/版本为准） | 需要指导用户把输入法加入电池优化白名单/允许自启动；**不能通过轮询/常驻服务绕过**（需求 8.2 明确禁止） | 未验证 |
| 用户“清理最近任务” | OEM 可能杀进程 | 监听消失直到进程重启 | 未验证 |

## 3. OxygenOS / ColorOS 场景矩阵（需求 8.2 逐项）

> 当前**全部未验证**。列出每项的预期行为、验证方法与风险点，供 T4 真机测试直接执行。

| # | 场景 | 预期行为（设计/源码） | 关键限制/风险 | 验证方法 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 1 | 键盘显示时复制 | 通道 B 监听触发→加密→清空 | 清空竞态；OEM 剪贴板预览 | probe App + 真机复制 | 未验证 |
| 2 | 键盘隐藏（进程存活）时复制 | 旧监听器/安全控制器在进程内仍可收到回调（若未被冻结） | 进程是否仍活跃；系统是否冻结 | 后台复制 + logcat/probe | 未验证 |
| 3 | 小企鹅进程存活但无界面时复制 | 同上 | 同上 | 同上 | 未验证 |
| 4 | 小企鹅被系统杀死后复制 | 静默：无监听、不转存、不清空 | **不能承诺常驻**（需求 8.2） | `am force-stop`/OEM 清理后复制，检查 probe 与后续历史 | 未验证 |
| 5 | 锁屏状态 | 输入法通常不可用；剪贴板读取受限 | 无解；不能承诺 | 锁屏复制测试 | 未验证 |
| 6 | 解锁之后 | 应用重启后 Vault 初始化；监听恢复 | 重启期间复制的数据可能丢失 | 解锁前后对比 | 未验证 |
| 7 | 刚重启且未首次解锁 | direct boot：Vault 不初始化；旧库用 DPS | 安全模式不可用/数据分叉 | 重启→锁屏→复制→解锁→检查 | 未验证 |
| 8 | 低电量模式 | 可能延迟/丢失回调 | 不能承诺实时 | 低电量模式下复制 | 未验证 |
| 9 | 深度睡眠 | 同上 | 同上 | 整晚后检查 | 未验证 |
| 10 | 系统后台限制 | 进程被杀/冻结 | 同上 | 开发者选项“后台进程限制”测试 | 未验证 |
| 11 | 手机重启 | 应用/输入法重新启动后恢复监听 | 重启窗口期数据丢失 | 重启后复制 | 未验证 |
| 12 | 切换默认输入法 | 非默认时通道 B 受限 | 功能失效属平台限制 | 切换默认输入法后复制 | 未验证 |
| 13 | 安全摘录（PROCESS_TEXT） | 文字选择菜单出现“Secure capture”条目并弹出确认 | OEM 菜单实现差异；Activity 主题/居中 | 在浏览器/聊天 App 选中文字验证 | 未验证（T2 未落地） |
| 14 | 安全分享（ACTION_SEND） | 分享菜单出现入口 | OEM 分享面板差异 | 从文本 App 分享 | 未验证（T2 未落地） |
| 15 | 私有粘贴到目标 App | 面板点击→解密→commitText；不写剪贴板 | 输入框切换/目标 App 拒绝 | 多 App 测试 | 未验证（T3 未落地） |
| 16 | 兼容模式 | 用户临时保留系统剪贴板，供依赖原生粘贴的 App 使用 | 兼容模式下通道 B 防护减弱，必须明确提示（已有偏好与文案：`AppPrefs.kt:404-410`、strings `secure_clipboard_compat_mode*`） | 开关兼容模式后测试原生粘贴 App | 未验证（语义未实现） |

## 4. 输入类型/敏感场景兼容性（需求 6.3）

| 输入类型/标记 | 现成判定（源码） | 安全摘录/粘贴策略（设计/T2） | 状态 |
| --- | --- | --- | --- |
| 文本密码框 `TYPE_TEXT_VARIATION_PASSWORD` / `WEB_PASSWORD` | `core/CapabilityFlag.kt:144-148` 映射为 `Password` | 不自动摘录；用户主动粘贴可允许（需求 6.3 允许“不读取原文的前提下粘贴”） | 判定基础源码确认；策略未落地 |
| 可见密码 `VISIBLE_PASSWORD` | `CapabilityFlag.kt:149-152` → Sensitive + NoSpellCheck | 同上 | 同上 |
| `IME_FLAG_NO_PERSONALIZED_LEARNING` | `CapabilityFlag.kt:103-105` → Sensitive | 不自动记录/不自动摘录 | 同上 |
| 其他 `Sensitive` capability | `CapabilityFlag.Sensitive`（`CapabilityFlag.kt:54`） | 保守处理 | 同上 |
| 未设置任何标记的普通输入框 | 正常 | 允许用户主动摘录/粘贴 | — |

## 5. 已知系统限制（必须如实对外说明）

1. **没有原子“比较剪贴板版本并清空”的公开 API**；TOCTOU 窗口无法完全消除（需求 4.4/8.2）。
2. **输入法进程不能保证常驻**；进程被杀/未解锁/后台限制时，普通复制无法转存/清空（需求 8.2/11.3）。
3. **通道 B 必然经过系统剪贴板**，清空发生在其后；系统复制预览、OEM 剪贴板历史可能保留展示（平台行为）。
4. **目标 App 必然收到用户主动粘贴的明文**（需求 10.2 已承认）。
5. **SSD/UFS 物理残留**：`PRAGMA secure_delete` + `VACUUM` 只能尽量覆写 SQLite 逻辑页，不能保证闪存物理块/磨损均衡副本被擦除（需求 5.4）。
6. **Android Keystore 密钥不可导出**：卸载/清数据/换机会导致无法解密历史；备份恢复的 `secure_clbdb` 在新设备无密钥时不可读（需求 5.5）。
7. **`clearPrimaryClip()` 需要 API 28+**；项目 minSdk 23 的旧设备需降级方案（T3 必须处理）。
8. **OEM/系统输入法框架可能记录输入内容**；应用无法控制（需求 10.1 特别禁止把平台暴露说成不存在）。
9. **direct boot 与正常模式的存储域不同**（DPS vs CES），旧明文库可能分叉（本报告 01 第 2.4 节）。
10. **严格模式默认禁用插件转换**：依赖 ClearURLs 等 transformer 的用户在安全模式下会失去 URL 清洗能力（功能取舍，需在设置页说明；已有 `secure_clipboard_allow_plugins_summary` 文案）。

## 6. 兼容性测试执行清单（供 T4/Lead 使用）

| 步骤 | 命令/操作 | 记录 |
| --- | --- | --- |
| 记录设备信息 | `adb shell getprop ro.product.model`、`ro.build.version.release`、`ro.build.version.security_patch`、OEM 版本 | 机型/系统/补丁日期 |
| 安装 debug 包 | 由 Lead 统一构建（本任务不运行 Gradle） | 版本号、commit |
| 设置默认输入法 | 系统设置/`adb shell ime set org.fcitx.fcitx5.android/.input.FcitxInputMethodService` | 是否成功 |
| 基线回归 | 打字/候选/切换输入法/主题/剪贴板面板/编辑/分享 | 原有功能无破坏 |
| 安全模式开 | 设置→隐私与安全→安全剪贴板 | 首次提示是否出现 |
| 场景 1-12 | 见第 3 节矩阵 | 每项结果（成功/失败/异常） |
| 抓取日志/崩溃 | `adb logcat -d`、`adb shell dumpsys activity processes` | 是否有异常/崩溃（注意日志可能含输入文本，妥善保管） |
| 备份/导出 | 见 03 文档 K 场景 | 是否含数据库文件 |

## 7. 结论

- 本功能的**设计考虑**了 Android 14/15/16 与 OxygenOS/ColorOS 的主要限制，并在源码层做了 direct boot、API 差异（timestamp/敏感标记）等适配；
- 但 **T0 没有任何真机/模拟器兼容性数据**，`05` 中所有“未验证”项都不能视为通过；**OxygenOS/ColorOS 的进程存活、剪贴板预览/OEM 历史、兼容模式行为**是最高优先级的待测项。
- 任何对外表述都必须保留第 5 节的系统限制，不得把“设计上避免”写成“实测无问题”。

## 附录：T1 快照增量（实现层面对限制的落实）

- API<28 的剪贴板清空回退已实现：`SecureClipboardController.kt:226-238`（`Build.VERSION_CODES.P` 判断 + `setPrimaryClip(空文本)`），对应第 2.1/5 节的平台限制。
- 安全摘录 Activity 已设置 `FLAG_SECURE`（`BaseSecureCaptureActivity.kt:64`），并在清单中 `excludeFromRecents`/`autoRemoveFromRecents`（`AndroidManifest.xml:127-128,146-147`）。
- Android 14+ 的 IME 子类型适配在既有代码中（`input/FcitxInputMethodService.kt:691-697,710-725`），T1 快照未改动该逻辑。
- 其他兼容性结论（OxygenOS/ColorOS、Android 14/15/16 真机）仍**全部未验证**，第 3 节矩阵与第 5 节限制不变。
