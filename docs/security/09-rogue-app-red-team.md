# 09 隐私历史搜索 / 加密备份 / 红队“流氓 App”实测

> 测试对象：改造后的小企鹅输入法（`org.fcitx.fcitx5.android.debug`）与零权限红队 App（`org.fcitx.fcitx5.android.rogue`）。
> 环境：Android Studio AVD `Pixel_9_Pro_XL`，Android 16（API 36），x86_64；主 APK 为同一源码构建的 x86_64 debug 变体。
> 时间：2026-10-31 17:2x–17:4x（Asia/Shanghai）。
> 所有测试文本均为合成字符串；真机/OxygenOS/ColorOS 未验证。

## 一、新增功能实测

### 1. 隐私历史搜索

- 入口：小企鹅输入法 → 隐私与安全 → 隐私历史（App 内 Activity，包名 `…debug`）。
- 初始列表：`recentRows(500)` 只读密文行，显示遮罩标签 + 来源 + 时间，不解密。
- 搜索：输入 `SECURE` 点击搜索 → 仓库逐条本地解密匹配（大小写不敏感、最多扫描 5000 条/返回 200 条），界面只显示遮罩结果。
- 实测输出（uiautomator）：

```
隐私历史
SECURE
搜索
2 条匹配记录
加密安全记录 / 来源：剪贴板 · 2026年10月31日 09:13
加密安全记录 / 来源：剪贴板 · 2026年10月31日 09:07
```

**结果：搜索功能正常，匹配到 2 条（SECURE_TEST_V2_XYZ、SECURE_TEST_67890），界面不显示明文。**
证据：`evidence/privacy-history-search-result.txt`；截图因 Activity FLAG_SECURE 全黑（`evidence/emulator-privacy-history-flag-secure.png`，属预期防护）。

### 2. 加密备份导出 / 导入

- 导出：菜单“导出加密备份” → 双密码框（≥8 位、两次一致）→ 逐条解密读取 → **PBKDF2-HMAC-SHA256(210k) + AES-256-GCM** 加密 → 保存到
  `/sdcard/Android/data/org.fcitx.fcitx5.android.debug/files/secure-exports/secure-clipboard-yyyyMMdd-HHmmss.fcsc`。
- 实测文件：`secure-clipboard-20261031-093516.fcsc`，**603 字节**（5 条记录）。
  - 文件头：magic `FCSC-EXPORT`、version 1、salt 16B、IV 12B、AES-256-GCM 密文（含 16B tag）；
  - 在文件中直接搜索 `SECURE_TEST`、`CHANNEL_A`、`LEGACY_TEST`、`ROGUE_` 均**不存在**（无明文）。
- 导入（先清除全部安全历史 → DB 0 行）：
  - **错误密码** `wrongpass1`：拒绝导入，DB 仍 0 行（界面仍为“还没有安全记录”）；
  - **正确密码** `testpass123`：成功恢复 **5 条**记录（DB 行数 0 → 5），列表恢复显示 5 条遮罩记录。
- 边界：备份目录为 app-specific external（Android 11+ 文件管理器直接浏览受限，但 **adb/root 可读取该目录**——调试环境已确认 shell 能列出文件；生产环境该目录仍是应用专属）。导出/导入均为用户主动操作，口令用后清零；文件格式有 AAD 保护 magic/version/salt。

**结果：搜索、导出、导入（含错误口令拒绝）全部按设计工作。**
证据：`evidence/export-import-verification.txt`、`evidence/secure-vault-rows-after-tests.txt`。

## 二、红队“流氓 App”攻击实测

测试工具：新增独立模块 内部红队工具（**零权限、无 INTERNET**，仅测试设备使用）。它从第三方 App 的视角尝试窃取剪贴板/私有数据/IPC。

### 10 个攻击与实测结果（安全模式，Fcitx 为默认输入法）

| # | 攻击 | 结果 | 说明 |
| --- | --- | --- | --- |
| 1 | 前台读系统剪贴板 | **BLOCKED** | 用户复制后小企鹅已加密保存并清空系统剪贴板，红队 App 读到空（accessible=false, itemCount=0） |
| 2 | 后台监听剪贴板回调 | INFO | 可收到回调但在 Android 10+ 后台读不到内容（本次窗口内 callbacks=0） |
| 3 | 直接读小企鹅私有目录数据库/偏好 | **BLOCKED** | `secure_clbdb`、`clbdb`、shared_prefs 均 FileNotFoundException（应用沙箱） |
| 4 | 绑定小企鹅 IPC 服务（FcitxRemoteService） | **BLOCKED** | `SecurityException: Not allowed to bind to service`（signature 级 `permission.IPC`） |
| 5 | 查询小企鹅 ContentProvider | **BLOCKED** | `SecurityException: Permission Denial`（`MANAGE_DOCUMENTS`） |
| 6 | 伪造 ACTION_PROCESS_TEXT 拉起安全摘录 | INFO（需用户确认） | startActivity 被接受（弹出确认框），但不会静默入库 |
| 7 | 连续 10 次伪造 PROCESS_TEXT（DoS） | INFO | 10 次调用被接受；小企鹅进程未崩溃、**数据库行数 8→8 不变**（无静默写入；另有 5/2 秒的确认框启动节流） |
| 8 | 伪造 ACTION_SEND 拉起安全分享 | INFO（需用户确认） | 同上，确认后才可保存 |
| 9 | 读取小企鹅 logcat | **BLOCKED** | target_pkg_lines=0（Android 限制只能读自身 UID 日志） |
| 10 | 列小企鹅外部目录 `Android/data/<pkg>/files` | **BLOCKED** | exists=false（scoped storage 拒绝跨应用访问） |

- 数据库行数：攻击前 8 → 攻击后 8（伪造 Intent 未产生任何记录）；小企鹅进程存活。
- 攻击日志原文：`evidence/rogue-app-results-secure-mode.txt`。

### 安全模式 vs 旧模式（重要对比）

| 场景 | 红队 App 读系统剪贴板 |
| --- | --- |
| **安全模式**（Fcitx 默认输入法，复制后加密并立即清空） | **BLOCKED（空）** |
| **旧模式**（关闭安全剪贴板；系统剪贴板按兼容设计保留） | **SUCCESS**：读到 `ROGUE_LEGACY_CLIP_1000`（22 字符，preview 完整），同时旧库 `clbdb` 也出现了该明文行 |

结论：**安全模式能阻止用户在别处复制后的剪贴板被前台流氓 App 拿走；旧模式（兼容开关）本身就会保留系统剪贴板，任何前台 App（含流氓 App）都可读取——这是平台行为，正是默认推荐开启安全模式的原因。**

证据：`evidence/rogue-app-results-legacy-mode.txt`。

## 三、已确认的防护与残余风险

### 已确认防护
1. 应用沙箱：跨应用读不到 `/data/data/<pkg>` 下数据库/偏好。
2. signature 级权限：IPC 服务与 PLUGIN 服务无法被第三方 App 绑定。
3. `MANAGE_DOCUMENTS`：数据 Provider 无法被普通 App 查询。
4. logcat 按 UID 隔离：第三方看不到小企鹅日志。
5. scoped storage：第三方无法进入小企鹅 `Android/data` 外部目录。
6. 安全模式：加密保存成功后立即清空系统剪贴板 → 前台流氓 App 之后读到空。
7. 外部入口必须用户确认，无法静默入库；另有 2 秒内最多 5 次确认框启动节流。
8. 安全模式下列表只显示遮罩，解密仅在用户主动“查看/搜索/导出”时逐条进行。

### 残余风险（必须如实说明）
1. **平台层面**：Android 只限制“后台应用”读剪贴板；**当前获得焦点的应用**与**默认输入法**仍可读。若用户把内容复制到某个恶意 App 内，该 App 本来就有内容；安全模式的作用是让“在别处复制的内容”尽快从系统剪贴板消失，并缩短窗口，而不是消除所有暴露。
2. **旧模式/兼容模式**：保留系统剪贴板，前台流氓 App 可读（实测 SUCCESS）；需要用户自行开启安全模式。
3. **默认输入法限制**：若小企鹅不是默认输入法且进程在后台，普通复制无法自动转存/清空（Android 剪贴板读取限制）；实测该状态下系统剪贴板会保留内容，红队可读。设置页“保护状态”会显示默认输入法是否生效。
4. **伪造 Intent DoS**：任何 App 都能拉起安全摘录确认框；已加启动节流，但仍可能造成打扰（无法完全阻止，除非给入口加签名权限，但那样第三方“文字选择菜单/分享”就不可用）。
5. **TOCTOU**：复制到清空之间仍存在极短窗口，平台无原子 compare-and-clear API。
6. **调试环境**：导出目录在 app-specific external，adb/root 可读；生产构建无 root 时第三方 App 仍无法访问。
7. **真机/OEM/性能**：以上均在模拟器完成；一加 OxygenOS/ColorOS、锁屏/Direct Boot/重启、性能功耗仍未验证。

## 四、证据文件

| 文件 | 内容 |
| --- | --- |
| `evidence/privacy-history-search-result.txt` | 搜索 SECURE 的 uiautomator 结果（2 条匹配、仅遮罩） |
| `evidence/export-import-verification.txt` | 导出文件头/无明文检查、错误口令拒绝、正确口令恢复 5 条 |
| `evidence/secure-vault-rows-after-tests.txt` | 加密库行（id/来源/密文长度/12B IV/去重标签） |
| `evidence/rogue-app-results-secure-mode.txt` | 安全模式 10 个攻击的 RESULT 原始行 |
| `evidence/rogue-app-results-legacy-mode.txt` | 旧模式红队读到剪贴板原文的 RESULT 原始行 |
| `evidence/emulator-privacy-history-flag-secure.png` | 隐私历史界面截图（FLAG_SECURE 全黑，预期） |
