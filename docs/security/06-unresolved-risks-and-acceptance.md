<!-- SPDX-License-Identifier: LGPL-2.1-or-later -->

# 06 未解决风险与验收状态

> 范围：对应需求文档第十二部分（安全测试）、第十六部分（交付要求）、第十七部分（最终验收标准）。
> 快照：commit `eb5b502` + 工作树（2026-10-31）。**本任务自身未运行 Gradle；维护者 已完成集成并执行真实构建/单元测试（真实结果见第 10 节）。instrumented 测试仅编译通过、未执行；真机数据仍无。**
> 证据标记：【S】源码确认、【P】潜在风险、【T】需测试（未验证）。

## 0. 实现落地状态（验收的前提）

> 本表是“是否具备验收条件”的事实基础；文件存在不等于可编译、更不等于已验证。构建/测试结果以 维护者 统一执行的结果为准。

| 模块 | 文件（T1 快照） | 状态 |
| --- | --- | --- |
| 契约/模型 | `data/secureclipboard/SecureClipboardContract.kt` | 已落地【S】 |
| 加密 | `data/secureclipboard/crypto/ClipboardCrypto.kt`、`ClipboardKeyManager.kt` | 已落地【S】（T1 报完成） |
| 加密库 | `SecureClipboardDatabase.kt`、`SecureClipboardDao.kt`、`SecureClipboardMetadata.kt` | 已落地【S】 |
| 仓库 | `SecureClipboardRepositoryImpl.kt`、`SecureClipboardPolicy.kt` | 已落地【S】 |
| 迁移 | `SecureClipboardMigration.kt` + `ClipboardManager.migrateLegacyIfNeeded` | 已落地【S】 |
| 通道 B | `data/clipboard/SecureClipboardController.kt`、`data/secureclipboard/ClipboardEventProcessor.kt` | 控制器/状态机/面板/编辑页均已落地【S】（T3 完成） |
| 通道 A 外部入口 | `ui/securecapture/{BaseSecureCaptureActivity,SecureCaptureActivity,SecureShareReceiverActivity,SecureCaptureValidator,SecureUnlockActivity,SecureUnlockGate}.kt` | 已落地并编译通过【S】 |
| 通道 A IME 入口 | `input/secureclipboard/{SelectedTextPolicy,SelectedTextCaptureController}.kt` + `KawaiiBarComponent` 盾牌/安全建议接线 | 已落地【S】 |
| 粘贴 | `input/secureclipboard/{SecureClipboardPasteController,PasteSession}.kt` | 已落地【S】；已接入 `ClipboardWindow`（`ClipboardWindow.kt:98-121,220+`） |
| 设置页 | `ui/main/settings/security/SecureClipboardSettingsFragment.kt`、`AppPrefs.Security`、路由/入口 | 已落地【S】 |
| 测试 App | `tools/clipboard-probe/**`、`tools/rogue-clipboard-app/**` | 已落地【S】；两者 `assembleDebug` BUILD SUCCESSFUL，零权限，aapt 确认无 INTERNET（第 10 节） |
| instrumented 测试 | `app/src/androidTest/java/.../secureclipboard/{ClipboardSecurityInstrumentedTest,SecureClipboardStorageInstrumentedTest,SecureClipboardTestSupport,ClipboardProbeClient}.kt` | 已写入【S】；`:app:compileDebugAndroidTestKotlin` BUILD SUCCESSFUL，**未执行**（无设备） |
| 单元测试 | `app/src/test/java/.../secureclipboard/{ClipboardCryptoTest,ClipboardEventProcessorTest,SecureClipboardMigrationTest,SecureClipboardPolicyTest,CryptoPerformanceBenchmarkTest}.kt` | 已落地【S】；维护者 执行 `:app:testDebugUnitTest`：**81 个安全剪贴板单测全部通过**（含 `SecureClipboardExportCryptoTest`）；更早全量运行中另 1 个为上游既有失败（详见第 10 节） |

## 1. 需求 17 验收标准逐条状态

> 状态定义：实现 = 源码存在且逻辑覆盖需求；部分 = 源码存在但集成/边界未完成；未验证 = 未构建/未测试；未落地 = 文件不存在。

| # | 验收标准（需求 17） | 源码状态 | 测试状态 | 证据 | 结论 |
| --- | --- | --- | --- | --- | --- |
| 1 | 安全摘录：选中文字直接保存到内部加密库，并通过测试确认没有使用系统剪贴板作为中转 | 实现：三个入口类与确认 UI 已落地（`ui/securecapture/*`、`input/secureclipboard/SelectedTextCaptureController.kt:64-143`）；入口均不调用 `setPrimaryClip`（grep 可证） | **模拟器实测通过**：ACTION_PROCESS_TEXT → 确认页 → 加密落库，系统剪贴板全程为空（08 §9、09）；instrumented 未跑、真机未测 | 清单 `AndroidManifest.xml:124-160`；`BaseSecureCaptureActivity.kt:61-70,161-191`；`SelectedTextCaptureController.kt:92-143` | 不能判定通过 |
| 2 | 普通复制：能收到复制事件且加密存储可用时，成功转存后立即尝试清空系统副本 | 实现：监听/状态机/清空已实现（`SecureClipboardController.kt:86-124,226-238`；`ClipboardEventProcessor.kt:387-503`） | **模拟器实测通过**：复制后加密库 +1 行、系统剪贴板立即被清空（红队随后读到空）；instrumented/真机未跑 | `SecureClipboardController.kt:226-238`（API 28+ `clearPrimaryClip`，API<28 用空 ClipData 回退） | 不能判定通过 |
| 3 | 加密存储：历史以 AES-256-GCM 认证密文保存在应用内部私有区域，数据库不能直接读取原文 | 实现（源码）：AES/GCM/NoPadding、96-bit IV、128-bit tag、Keystore AES-256 + HMAC 去重键、无明文列（`ClipboardCrypto.kt:82-184`、`ClipboardKeyManager.kt:50-172`、`SecureClipboardRepositoryImpl.kt:117-168,365-407`） | **JVM 单测通过**：ClipboardCryptoTest 17/17、SecureClipboardMigrationTest 10/10、SecureClipboardPolicyTest 17/17、SecureClipboardExportCryptoTest 20/20（81 全过）；**模拟器侧数据库文件明文扫描已做**：secure_clbdb + WAL 内无测试明文 | 见 `01` 附录 A/B；第 10 节 | 源码+JVM 单测通过；设备侧验证缺失，不能判定整体通过 |
| 4 | 安全粘贴：私有历史通过 InputConnection 直接提交，不重新填充系统剪贴板 | 实现（源码）：面板安全记录走 `SecureClipboardPasteController` + `PasteSession`（`ClipboardWindow.kt:98-121,220+`、`SecureClipboardPasteController.kt:65-106`），编辑页安全模式禁用复制；`commitText` 统一封装 | 未验证：无 probe/真机实测 | 第 10 节；T3 代码 | 不能判定通过（需设备验证“剪贴板无变化”） |
| 5 | 隐私隔离：不必要的插件、日志、Intent、导出组件不能获取原始敏感文字 | 实现（源码）：严格模式 `transformer=null`（`FcitxRemoteService.kt:62-75`）、日志只打异常类名、通道 A 不经插件（`ClipboardManager.kt:209-221`）；EditorInfo Inspector 在安全模式下已阻止写系统剪贴板（`EditorInfoWindow.kt:50-58` + `strings.xml:391`） | **部分实测**：零权限红队 App 伪造 PROCESS_TEXT/SEND 只能拉起确认框、攻击前后 DB 行数不变；日志/Provider/外部目录/IPC 均被拦截（09）。**插件 transformer 隔离未单独实测** | 第 10 节、09 | 部分满足（插件路径仍需测试） |
| 6 | 可靠性：数据库失败不应导致唯一原文被无提示删除 | 部分：`save` 先加密后写库、失败返回错误且不清除系统剪贴板（`ClipboardEventProcessor.kt:411-503`）；迁移失败保留旧库（`ClipboardManager.kt:417-442`） | 未验证：无故障注入测试 | 同上 | 不能判定通过 |
| 7 | 并发复制不会被当成普通单线程流程处理 | 实现（源码）：序号、串行队列、快照重读比较、清空去重（`ClipboardEventProcessor.kt:288-339,354-503`） | **JVM 单测通过**：ClipboardEventProcessorTest 15/15（81 全过之一）；instrumented/真机竞态未执行 | 第 10 节 | 源码+JVM 单测通过；设备竞态未验证 |
| 8 | 兼容性：原有输入能力不受明显影响 | 待评估：安全模式默认关闭（`AppPrefs.kt:366-371`），旧路径保留；新增监听/状态机/设置页的影响未跑回归 | 未验证：无真机回归 | `AppPrefs.kt:364-427`；`MainFragment.kt:108-110` | 不能判定通过 |
| 9 | 真实性：安全效果必须通过源码审计、自动化测试或真实设备测试证明，不能只靠设置页显示“已保护” | 源码审计（01/02/03/05/06）已完成；JVM 自动化测试已执行 | **部分满足**：源码审计 + JVM 单测有证据；instrumented/真机未执行 | 第 10 节 | 部分满足，不能宣称整体已证明 |

**结论（更新）：构建已成功、81 个安全剪贴板 JVM 单测全部通过；第 3/7 项获得源码 + JVM 单测证据；第 9 项的搜索/导出已实现并在模拟器实测。第 1/2/4/5/6/8 项仍缺 instrumented 测试与真机证据——因此不能判定项目整体验收通过。详见第 10 节。**

## 2. 需求 12 测试执行状态

| 测试类别 | 计划/文件 | 执行状态 |
| --- | --- | --- |
| 12.1 功能测试 | T4 instrumented + 手工清单（见 05 第 6 节） | **未执行**（instrumented 仅编译通过；无设备） |
| 12.2 安全测试（独立 probe App） | `tools/clipboard-probe/**` 代码存在且 APK 构建成功（零权限）；instrumented 测试已写入 | **已手工执行（模拟器）**：用 probe + adb 实测旧模式读取、安全模式加密+清空、后台转存、安全摘录不写剪贴板（见 08 §9 与 evidence/）；instrumented 自动化仍未跑 |
| 12.3 竞态测试 | JVM：`ClipboardEventProcessorTest`（15 个）；instrumented/手工仍未执行 | **JVM 部分已执行并通过**（15/15）；设备竞态未执行 |
| 单元测试（加密/策略/迁移/事件处理/加密备份） | 上述测试类 + `SecureClipboardExportCryptoTest` | **已执行（维护者）**：81 个全部通过；更早全量运行中 1 个上游既有失败（ThemeSerializationTest.version2，与本改动无关） |
| 构建 | 维护者 统一执行 | `:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`、`:tools:clipboard-probe:assembleDebug`、`:app:assembleDebug` 均 BUILD SUCCESSFUL（APK 细节见第 10.1 节） |

## 3. 未解决风险清单

> 等级：高 = 可能直接导致明文外泄/数据不可恢复；中 = 降低保护效果或可用性；低 = 体验/边界问题。
> 状态：接受 = 平台限制无法解决但已披露；待缓解 = 有明确修复方向；待验证 = 需测试确认。

| ID | 风险 | 等级 | 状态 | 说明/证据 | 缓解方向 |
| --- | --- | --- | --- | --- | --- |
| R1 | TOCTOU：加密落库与清空之间系统剪贴板可能已被改写；无法原子 compare-and-clear | 高 | 接受（平台限制） | 需求 4.4/8.2；实现用落库后重读比较 + 序号 + 去重把窗口缩到最小（`ClipboardEventProcessor.kt:438-503`） | 保持重读比较；测试 A/B/C 连续复制；不承诺零窗口 |
| R2 | 进程被杀/未解锁/后台限制时通道 B 无法转存或清空 | 高 | 接受（平台限制） | 需求 8.2；监听依赖进程存活（01 第 3.11 节） | 明确 UI 状态；不做常驻/轮询/无障碍 |
| R3 | 系统剪贴板短暂明文 + OEM 剪贴板历史/预览可能保留副本 | 高 | 接受（平台限制） | 需求 10.1/10.2；`clearPrimaryClip()` API 28+（`SecureClipboardController.kt:226-238`） | 加密成功后立刻清空（已实现）；兼容模式明确提示 |
| R4 | 敏感标记内容（`EXTRA_IS_SENSITIVE`）在安全模式下既不保存也不清空 | 中 | **产品决策保留（非缺陷）** | `SecureClipboardController.kt:188-190,211-224`：敏感剪贴板被判 Unsupported，处理路径不清空（`ClipboardEventProcessor.kt:379-385`）；维护者 决策：不保存也不清空，避免删除唯一原文 | 文档写明该取舍；如需改为“尝试清空”需产品重新决策 |
| R5 | 历史上限 1000（旧默认）与设置上限 5000 不一致 | 中 | **已修复（源码复核）** | `SecureClipboardVault.kt:31-41` 注入 `historyLimitProvider = { AppPrefs...secureClipboardHistoryLimit }`；`SecureClipboardRepositoryImpl.kt:45,156` 每次 save 读取 `historyLimitProvider()` 并按 `MAX_HISTORY_LIMIT` 收敛 | 已无固定 1000 截断；JVM 单测覆盖策略，设备行为未验证 |
| R6 | 旧明文库/密文库删除后存在 SQLite 页/WAL/闪存物理残留 | 高 | 接受（物理限制） | 迁移用 `secure_delete=ON + DELETE + VACUUM + wal_checkpoint(TRUNCATE)`（`ClipboardManager.kt:420-428`）；安全库 `realDelete/clearAll` 未执行 VACUUM（`SecureClipboardRepositoryImpl.kt:281-283,311-313`） | 安全库删除后可加 `PRAGMA secure_delete`/VACUUM；SSD/UFS 物理残留无法保证 |
| R7 | Keystore 密钥丢失/失效后历史永久不可读；不静默重建密钥 | 高 | 接受（设计） | `ClipboardKeyManager.kt:102-140`（KeyInvalid/KeyUnavailable）；需求 5.5 | UI 明确报告不可解密；提供清空/重建入口 |
| R8 | 备份/换机与用户数据导出可能带走明文库或导致迁移标记失效 | 高 | **已修复（源码复核）** | 备份规则已排除（`data_extraction_rules.xml:16-34`、`full_backup_content.xml:15-33`）；`UserDataManager.kt:40-47` 新增 `isClipboardDatabaseFile()`，`export`（`:79-80`）与 `import`（`:132-133`）均排除 `clbdb*`/`secure_clbdb*` | 用户数据导出不再携带剪贴板库；备份框架规则仍需真机验证 |
| R9 | 同签名插件可读取明文（兼容模式/允许插件时）且插件可申请 INTERNET | 高 | 部分缓解（信任边界） | `FcitxRemoteService.kt:62-75` 严格模式默认禁用；官方 ClearURLs 插件见 `plugin/clipboard-filter/src/main/java/org/fcitx/fcitx5/android/plugin/clipboard_filter/MainService.kt:17-35` | 默认严格模式；文档明确插件信任边界；不把 Binder 说成加密 |
| R10 | 开发者 EditorInfo Inspector 可把 EditorInfo（含 SurroundingText）写入系统剪贴板 | 中 | **已修复（源码复核）** | `EditorInfoWindow.kt:50-58`：安全模式下阻止并 toast `secure_system_clipboard_blocked`（`res/values/strings.xml:391`、`values-zh-rCN/strings.xml:391`）；非安全模式行为不变；开关默认 false（`AppPrefs.kt:38`） | 安全模式已覆盖；非安全模式属开发者工具既有行为 |
| R11 | verbose 日志记录组合文本；日志可导出 | 中 | 接受（可配置） | `FcitxInputMethodService.kt:939`；`LogActivity.kt:41-57`；verbose 默认 false（`AppPrefs.kt:36`） | 文档提示；验收时关闭 verbose；后续可对输入文本脱敏 |
| R12 | 安全模式下列表面板/编辑页旧代码路径 | 高 | **已修复（源码复核，T3 完成）** | `ClipboardWindow.kt:98-121,220+` 安全记录走 paste controller；`ClipboardEditActivity.kt:43,53-60` 安全模式禁用复制；`onShare` 安全记录禁用（约 129-133 行） | 待 instrumented/真机验证 |
| R13 | 遮罩字符串误提交风险（原 KawaiiBar 路径） | 中 | 已修复（源码） | T2 已改为 `readText(id)` + 会话校验（`KawaiiBarComponent.kt` 安全建议部分） | 需测试确认 |
| R14 | 安全库降级使用 `fallbackToDestructiveMigrationOnDowngrade(dropAllTables=true)` | 中 | 接受（避免崩溃；删除/清空已加 VACUUM） | `SecureClipboardDatabase.kt:31-39`：数据库降级会清空密文历史（未改变）；删除/清空后的页清理已补（见 R15） | 文档声明降级会丢历史；升级路径需兼容 |
| R15 | `realDelete`/`clearAll` 不重建数据库文件，删除的密文页仍可能残留 | 中 | **已修复（源码复核，部分缓解）** | `SecureClipboardRepositoryImpl.kt:283-286,314-325`：realDelete/clearAll 后执行 `PRAGMA secure_delete=ON` + `VACUUM`；legacy 库 init 设 secure_delete（`ClipboardManager.kt:154-156`），realDelete/nukeTable 后 VACUUM（`:375-382,388-396`） | SQLite 逻辑页已尽量覆写；SSD/UFS 物理残留仍无法保证 |
| R16 | 用户主动分享路径（旧面板长按分享）会把明文交给其他 App | 中 | **已修复（源码复核，T3 完成）** | `ClipboardWindow.kt:129-133`：安全记录分享被禁用并提示 `secure_entry_share_disabled` | 待测试确认 |
| R17 | Direct boot 下 DPS/CES 两套旧库分叉 | 中 | 待验证 | 01 第 2.4 节；`FcitxApplication.kt:76-88,136`；`ClipboardManager.kt:152-158` | 真机验证；必要时在解锁后合并/清理 DPS 旧库 |
| R18 | 迁移标记与旧库清理在导入用户数据后不一致 | 中 | **已修复（源码复核）** | `ClipboardManager.migrateLegacyIfNeeded`（`ClipboardManager.kt:410-431`）不再信任 `secure_clipboard_legacy_migrated`，先查 `legacyDao.allActiveRows()` 实际行数并幂等迁移；R8 的导入排除也消除了来源 | 依赖设备导入/恢复测试确认 |
| R19 | 输入法不是默认输入法/被切换时通道 B 失效 | 中 | 接受（平台） | 需求 8.2；Android 10+ 剪贴板读取限制 | UI 状态提示；文档说明 |
| R20 | 未做设备/系统版本实测（OxygenOS/ColorOS、Android 14/15/16） | 高 | 待验证 | 05 全部未验证 | 按 05 第 6 节执行 |

## 4. 物理擦除残留（必须如实声明）

- 迁移成功后的旧库清理：`PRAGMA secure_delete=ON` → `DELETE FROM clipboard` → `VACUUM` → `PRAGMA wal_checkpoint(TRUNCATE)`（`ClipboardManager.kt:420-428`）。这能把 SQLite 逻辑页尽量覆写并收缩文件，但不能保证 SSD/UFS 的物理块、FTL 重映射副本、文件系统日志、厂商备份/快照被擦除。
- 安全库 `realDelete()`（`DELETE ... WHERE isDeleted=1`）与 `clearAll()`（`DELETE FROM secure_clipboard`）在此前版本没有 VACUUM 调用；现在已补 `PRAGMA secure_delete=ON` + `VACUUM`（`SecureClipboardRepositoryImpl.kt:283-286,314-325`），旧库也加了 secure_delete/VACUUM（`ClipboardManager.kt:154-156,375-382,388-396`）。这能减少 SQLite 逻辑页残留，但“已删除”仍不等于“闪存物理不存在”。
- 系统备份、OEM 剪贴板历史、云同步、日志文件、用户数据导出 zip 都可能保留副本；这些不在应用控制内。
- 结论：可以声明“应用层已尽量覆写并清理”，不能声明“物理上已不可恢复”；需求 5.4/16 已要求该表述。

## 5. 插件兼容策略

| 场景 | 行为 | 依据 | 风险/说明 |
| --- | --- | --- | --- |
| 安全模式关闭（默认） | 旧版插件 transformer 正常工作，剪贴板明文先经插件处理再入旧明文库 | `FcitxRemoteService.kt:68-75`；`ClipboardEntry.kt:61-66` | 与上游一致；明文插件通路存在（已披露） |
| 安全模式开启 + 严格模式（允许插件=关，默认） | `transformer` 置空；通道 B 捕获不再经过插件；通道 A 从不经过插件 | `FcitxRemoteService.kt:62-75,135-160`；`ClipboardManager.kt:209-221` | ClearURLs 等 URL 清洗能力失效（功能取舍）；仍需验证 T3 捕获路径没有旁路 |
| 安全模式开启 + 允许插件=开 | 通道 B 的文本会经插件 transformer 后再加密 | `ClipboardManager.kt:213-221`；`SecureClipboardController.kt:128-139` | 同签名插件可读明文并可能联网；用户显式开启需提示 |
| 插件未安装/绑定失败 | `transformer=null`，正常捕获 | `FcitxRemoteService.kt:68-75`；`FcitxPluginServices.kt:59-77` | 无影响 |
| 插件崩溃/死亡 | `linkToDeath` 注销；异常只记录描述/类名 | `FcitxRemoteService.kt:96-114` | 不残留明文日志；下次注册恢复 |
| 如何验证 | 构建同签名测试插件记录 transform 入参长度（不记内容）；在严格模式/兼容模式/关闭安全模式三种状态实测 | T4/T5 计划 | 未执行 |

## 6. 验收必需的后续动作（交给 维护者/T4）

1. ~~构建：维护者 统一执行 compileDebugKotlin 与单测~~ **已完成（维护者）**：见第 10 节；`:app:assembleDebug` 亦已成功产出 arm64-v8a APK（未安装运行）。
2. ~~单元测试~~ **已完成（维护者）**：81 个安全剪贴板单测全部通过；更早全量运行中 1 个上游既有失败（ThemeSerializationTest.version2，与本改动无关），不得写成“全量全部通过”。
3. T3 收尾：面板/编辑页安全模式改造、PasteController 落地、分享禁用；否则第 4 项验收（安全粘贴）不完整。
4. T4 仪器测试/真机：按 05 矩阵与需求 12.2/12.3 执行；probe App 验证“安全摘录/私有粘贴不写剪贴板”。
5. 数据库明文扫描：设备上导出 `secure_clbdb` 并搜索测试明文；旧 `clbdb` 迁移后同样检查。
6. 备份/导出验证：`bmgr`、用户数据导出 zip。
7. ~~修复/决策~~ **已处理**：R5/R8/R10/R12/R16/R18/R21/R22 已修复（源码复核，见第 10 节）；R4 为产品决策保留；R14 降级清空历史仍为已知取舍。

## 7. 可以/不可以对外宣称的内容

| 可以宣称（有源码依据） | 不可以宣称（无依据/被需求禁止） |
| --- | --- |
| 应用自身无 INTERNET 权限、无网络客户端依赖 | 数据绝不会联网 |
| 落盘历史设计为 AES-256-GCM 认证加密，无明文列 | 端到端加密 |
| 安全摘录/私有粘贴的设计路径不写系统剪贴板 | 系统剪贴板完全不会被使用/不会短暂暴露 |
| 严格模式默认不把明文交给插件 | 插件绝对无法获取明文 |
| 迁移成功后才清理旧明文库并尽量覆写 | 物理删除不可恢复 |
| 输入法进程被杀后无法保证转存（平台限制） | 始终实时监控系统剪贴板 |
| 备份规则排除剪贴板库（配置） | 备份中一定没有剪贴板数据（未验证） |
| 目标 App 必然收到用户主动粘贴的明文 | 目标 App 收不到明文 |

## 8. 文档交付核对（T5）

| 文件 | 内容 | 状态 |
| --- | --- | --- |
| `docs/security/01-source-audit.md` | 真实源码审计、数据流、插件机制、分类结论 | 已完成（T0 基线 + T1 增量见附录） |
| `docs/security/02-threat-model.md` | 资产/攻击者/边界/STRIDE/需求映射 | 已完成 |
| `docs/security/03-dataflow-privacy-report.md` | 需求 10.1 A–L、10.2 目标与诚实边界 | 已完成（T1 增量见附录） |
| `docs/security/04-performance-battery-evaluation.md` | 指标/优化/方法/风险（无假数据） | 已完成 |
| `docs/security/05-compatibility-notes.md` | 版本/OEM/场景矩阵/系统限制 | 已完成 |
| `docs/security/06-unresolved-risks-and-acceptance.md` | 需求 17 验收逐条、风险、擦除残留、插件策略 | 已完成 |

最终声明（更新）：构建与 JVM 单测已有真实证据（第 10 节），但 instrumented 测试与真机验证仍未执行；凡本目录未明确标注“维护者 构建/测试记录”的安全效果，仍只能标记为“设计/实现/未验证”。

## 9. T2 快照增量（T3 粘贴/面板与 T4 测试文件落地后）

> 时间：T1 快照之后、本任务完成前。任务状态仍为 in_progress；以下更新不改变“未构建/未测试”的总判断。

| 项 | T1 快照 | T2 快照 |
| --- | --- | --- |
| 粘贴控制器 | 未见 | 已落地：`input/secureclipboard/{SecureClipboardPasteController,PasteSession}.kt` |
| 面板安全模式 | 旧路径 | 已改造：安全记录走 paste controller；分享禁用；一次性显示对话框（`ClipboardWindow.kt`） |
| 编辑页 | 仍可复制到系统剪贴板 | 安全模式禁用复制、单条读取（`ClipboardEditActivity.kt:43,53-60,86+`） |
| instrumented 测试 | 未见 | 已写入 `ClipboardSecurityInstrumentedTest`、`SecureClipboardStorageInstrumentedTest`、`SecureClipboardTestSupport`、`ClipboardProbeClient`，**未执行** |
| 认证 gate | — | **已修复（源码复核）**：`ClipboardWindow.kt:98-121` 接入 `SecureUnlockGate`（`requireAuth` 才触发；gate 不可用→`AuthenticationUnavailable` fail-closed；取消/失败/120s 超时→`AuthenticationFailed`，超时常量见 `:425`） |

### 9.1 验收状态更新

- 第 4 项“安全粘贴”：源码状态由“部分”改为“**实现（源码）**”，测试仍“未验证”。
- 第 1/2/5/6/7 项：源码状态不变（部分/实现），测试仍“未验证”。
- R12（面板旧路径）、R16（分享明文）：**源码层面已修复**，待测试确认；R13 保持已修复。
- **新增风险**：
  - R21：**已修复（源码复核）**——`ClipboardWindow.kt:98-121` 已接 `SecureUnlockGate`（120s 超时、fail-closed）；`SecureClipboardPasteController.kt:65-106` 在认证后重新捕获会话再解密。
  - R22：**已修复（源码复核）**——`ClipboardWindow.kt:279-280` 给 reveal 对话框加 `FLAG_SECURE`；仍属用户主动显示明文的操作，需在验收说明中披露。
- **仍需 维护者/T4 执行**：构建、单测、instrumented 测试、真机矩阵、数据库明文扫描、备份/导出验证（同第 6 节）。

## 10. 最终集成与真实构建/测试证据（维护者 执行）

> 证据来源：维护者 在 2026-10-31 集成后执行的真实 Gradle 构建与测试记录（本任务未运行 Gradle；以下数字为 维护者 提供，原样引用）。

### 10.1 构建结果

| 命令 | 结果 | 说明 |
| --- | --- | --- |
| `:app:compileDebugKotlin` | **BUILD SUCCESSFUL** | Gradle 9.6.1 + AGP 9.4.1 + Kotlin 2.4.10，JDK 17 |
| `:app:testDebugUnitTest` | **81 个安全剪贴板 JVM 单测全部通过**（含 20 个加密备份测试）；更早全量运行中 1 个失败 | 失败项为上游既有测试 `ThemeSerializationTest.version2`：它断言 version 2.0 不迁移，而 `CustomThemeSerializer.CURRENT_VERSION = "2.1"`（`app/src/main/java/org/fcitx/fcitx5/android/data/theme/CustomThemeSerializer.kt:90`；测试见 `app/src/test/java/org/fcitx/fcitx5/android/ThemeSerializationTest.kt:68`）。**既有失败、与本改动无关，不得写成“全部通过”** |
| `:app:compileDebugAndroidTestKotlin` | **BUILD SUCCESSFUL** | instrumented 测试仅编译通过，**未执行**（无设备） |
| `:tools:clipboard-probe:assembleDebug` | **BUILD SUCCESSFUL** | APK 962,872 字节；aapt 确认零权限（无 INTERNET） |
| `:tools:rogue-clipboard-app:assembleDebug` | **BUILD SUCCESSFUL** | 红队攻击 App 982,453 字节；零权限、无 INTERNET |
| `:app:assembleDebug` | **BUILD SUCCESSFUL** | APK `app/build/outputs/apk/debug/org.fcitx.fcitx5.android-eb5b502-arm64-v8a-debug.apk`，69,819,756 字节，SHA-256 `D7C880B1D150937E24CF3057DF8B0D458A035B516270735B069CE69740ADE86B`；versionCode 112 / versionName eb5b502；aapt 确认仅 VIBRATE、POST_NOTIFICATIONS 与两个 signature 自定义权限（**无 INTERNET**）；apksigner 签名有效（CN=Android Debug）；manifest 含 SecureCaptureActivity / SecureShareReceiverActivity / SecureUnlockActivity；含 arm64-v8a 原生库。**未安装运行**。 |

新增单测分布（合计 61）：`ClipboardCryptoTest` 17、`ClipboardEventProcessorTest` 15、`SecureClipboardPolicyTest` 17、`SecureClipboardMigrationTest` 10、`CryptoPerformanceBenchmarkTest` 2。

### 10.2 维护者 修复复核（源码确认）

| 风险 | 状态 | 源码证据 |
| --- | --- | --- |
| R5 历史上限 | **已修复** | `SecureClipboardVault.kt:31-41` 注入 `historyLimitProvider`；`SecureClipboardRepositoryImpl.kt:45,156` 每次 save 读取偏好并收敛到 `MAX_HISTORY_LIMIT` |
| R8 用户数据导出/导入 | **已修复** | `UserDataManager.kt:40-47` `isClipboardDatabaseFile()`；导出 `:79-80`、导入 `:132-133` 均排除 `clbdb*`/`secure_clbdb*` |
| R18 迁移标记 | **已修复** | `ClipboardManager.kt:410-431`：不再信任 `secure_clipboard_legacy_migrated`，以 `legacyDao.allActiveRows()` 实际行数幂等迁移 |
| R10 EditorInfo Inspector | **已修复** | `input/editorinfo/EditorInfoWindow.kt:50-58` 安全模式阻止并 toast `secure_system_clipboard_blocked`（`res/values/strings.xml:391`、`res/values-zh-rCN/strings.xml:391`） |
| R14/R15 删除残留 | **部分修复** | `SecureClipboardRepositoryImpl.kt:283-286,314-325`（realDelete/clearAll 后 `secure_delete=ON`+`VACUUM`）；legacy 库 `ClipboardManager.kt:154-156,375-382,388-396`；**SSD/UFS 物理残留声明不变** |
| R21 面板认证 gate | **已修复** | `input/clipboard/ClipboardWindow.kt:98-121` 接入 `SecureUnlockGate`：gate 不可用→`AuthenticationUnavailable`（fail-closed）；取消/失败/120s 超时→`AuthenticationFailed`（超时常量 `:425`）；认证后 `SecureClipboardPasteController.kt:65-106` 重新捕获会话再解密 |
| R22 reveal 对话框 | **已修复** | `ClipboardWindow.kt:279-280` 给一次性显示明文的对话框加 `FLAG_SECURE` |
| R4 敏感剪贴板 | **产品决策保留（非缺陷）** | 决策：`EXTRA_IS_SENSITIVE` 内容不保存、也不清空，避免删除唯一原文（`SecureClipboardController.kt:188-190`；`ClipboardEventProcessor.kt:379-385`） |

### 10.3 仍未实现 / 仍未验证

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 隐私历史搜索 | **已实现（模拟器实测）** | 新增 `SecureHistoryActivity`：按需本地解密搜索（≤5000 条扫描/200 条返回，列表仍只显示遮罩）；2026-10-31 模拟器实测查询 `SECURE` → 2 条匹配（见 09） |
| 剪贴板记录导出 | **已实现（模拟器实测）** | 导出为 PBKDF2(210k)+AES-256-GCM 口令加密文件（`.fcsc`），导入支持错误口令拒绝与恢复；用户数据导出（`UserDataManager`）仍明确排除剪贴板库（两回事） |
| instrumented 测试 | **未执行**（已有模拟器，但测试 APK 未运行） | 3 个安全 instrumented 测试类已编译通过；模拟器上的功能验证由 adb/uiautomator 手工实测完成（见 08 §9） |
| adb 手工矩阵 | **未执行** | 备份/导出、Recents/截屏、竞态、OxygenOS/ColorOS 等 |
| 真机/OEM 验证 | **未验证** | Android 14/15/16、OxygenOS/ColorOS 全部未测（见 05） |
| TOCTOU 窗口 | **仍存在** | 平台无原子 compare-and-clear（R1） |
| API<28 清空 | **降级方案** | 写空 `ClipData` 而非 `clearPrimaryClip()`（`SecureClipboardController.kt:226-238`） |
| API<26 时间戳 | **退化为文本+500ms** | `ClipboardEventProcessor` 的 duplicateWindowMs 逻辑；无 `ClipDescription.timestamp` 时的近似去重 |
| 通道 B 可用性 | **平台限制** | 依赖进程存活与默认输入法（R2/R19） |

新增风险登记：

| ID | 风险 | 等级 | 状态 |
| --- | --- | --- | --- |
| R23 | 隐私历史**搜索**：实现但大历史（>5000 条）下仅扫描上限内记录，性能未在真机测 | 中 | 已实现（模拟器实测），真机性能待测 |
| R24 | 剪贴板**导出**：文件为口令加密的 app-external 文件；调试环境 adb 可读该目录，生产无 root 时第三方不可访问；口令强度依赖用户 | 中 | 已实现（模拟器实测），残余风险见 09/下节 |
| R26 | 红队测试发现：旧模式/兼容模式保留系统剪贴板，前台流氓 App 可读（平台行为，非本应用缺陷） | 中 | 已实测并文档化；默认推荐安全模式 |
| R27 | 伪造 PROCESS_TEXT/SEND 可拉起确认框（已加 5 次/2 秒启动节流）；无法完全阻止第三方打扰，除非牺牲系统入口 | 低 | 已缓解，残余 DoS 可接受 |
| R28 | 安全模式普通复制依赖小企鹅为默认输入法；非默认/后台时 Android 不允许读取剪贴板，系统内容不会被清空（模拟器已复现） | 高 | 平台限制；设置页显示默认输入法状态 |
| R25 | instrumented/真机测试未执行，OS/OEM 兼容性无数据 | 高 | 待验证（R20 同源，保留） |

### 10.4 最终验收结论（更新）

- **已有真实证据的**：行级源码审计（本目录 01/02/03/05）、`:app:compileDebugKotlin` 成功、**81 个安全剪贴板 JVM 单测通过**（加密 17、事件状态机 15、策略 17、迁移 10、JVM 基准 2、加密备份 20）、androidTest 编译通过、probe APK 构建成功且零权限、`:app:assembleDebug` 成功产出 arm64-v8a debug APK（aapt 无 INTERNET、apksigner 签名有效）、**Android 16 x86_64 模拟器上的功能实测**（旧模式读取、安全模式加密+立即清空、后台默认输入法转存、安全摘录不写系统剪贴板、迁移清空旧明文、隐私历史搜索、加密备份导出/导入；见 08 §9–11）、**零权限流氓 App 红队测试**（私有库/IPC/Provider/日志/外部目录/伪造入口均被拦截；安全模式复制后红队读剪贴板为空；见 09）。
- **不能宣称的**：全量单测“全部通过”（有 1 个上游既有失败）、instrumented 自动化通过、真机/OxygenOS/ColorOS 验证通过、真机性能功耗数据、端到端“任何情况下都不写系统剪贴板”（平台限制见 R1/R2）。
- **结论**：需求 17 的验收在“构建 + JVM 自动化 + 模拟器功能实测 + 红队实测”维度取得实质证据；但**真机/OEM 与 instrumented 自动化维度仍不满足**，因此项目整体应标记为“构建通过、JVM 单测通过、模拟器功能/红队实测通过、真机未验证”，不能判定为全部验收通过。

### 10.5 模拟器功能实测补充（2026-10-31 17:00 后）

> 后续 17:2x–17:4x 又完成：隐私历史搜索、加密备份导出/导入、零权限流氓 App 红队 10 项攻击（详见 [09-rogue-app-red-team.md](09-rogue-app-red-team.md) 与 08 §10–11）。结论：安全模式下私有库/IPC/Provider/日志/外部目录/伪造入口均被拦截，复制后系统剪贴板被清空（红队读空）；旧模式对照下前台 App 可读系统剪贴板（平台行为，需开启安全模式）。

- 设备：Android Studio AVD `Pixel_9_Pro_XL`，Android 16（API 36），x86_64；安装同源码 x86_64 debug APK。
- 实测通过：旧模式仍能读取系统剪贴板；安全模式普通复制加密落库并立即清空系统剪贴板；输入法在后台（仍是默认输入法）时也能转存；`ACTION_PROCESS_TEXT` 安全摘录确认→加密→落库且全程不写系统剪贴板；迁移成功并清空旧明文表。
- 实测发现并修复：迁移后旧表未清空（`execSQL(PRAGMA ...)` 抛 `SQLiteException`），改 `query()` 执行 PRAGMA + 逐步容错后复测旧表 0 行（详见 08 §9 与 evidence 文档）。
- 新增功能实测：隐私历史搜索 `SECURE` → 2 条匹配（仅遮罩）；加密备份导出 603B（无明文）/错误口令拒绝/正确口令恢复 5 条；零权限红队 App 10 项攻击中私有库/IPC/Provider/日志/外部目录/剪贴板读取均被拦截，伪造入口只能拉起确认框、攻击前后数据库行数 8→8 不变（详见 09 与 08 §10–11）。
- 仍然未验证：instrumented 自动化、真机/OEM（OxygenOS/ColorOS）、锁屏/Direct Boot/重启/低电量、真机性能与功耗。
