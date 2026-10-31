<!--
  SPDX-License-Identifier: LGPL-2.1-or-later
  SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
-->

# Rogue Clipboard App（红队测试工具）

安全剪贴板/双通道隐私改造项目的**红队测试 App**：从一个普通第三方 App 的视角，尝试窃取小企鹅
输入法（Fcitx5 for Android）的系统剪贴板、加密剪贴板库、私有文件、ContentProvider、IPC 服务、
日志与外部目录，并把结果以机器可读格式输出，供 Android 16 模拟器/真机验收使用。

## 0. 警告（仅测试设备）

- 仅限专用测试设备/一次性模拟器使用，**不得**用于真实攻击或真实用户数据。
- read_clipboard 会输出剪贴板**前 80 字符**与内容 SHA-256 前缀（需求要求的能力证明）。因此运行前
  必须把系统剪贴板内容替换为**合成测试字符串**（例如 ROGUE_TEST_ABC123），禁止在有真实隐私内容的
  设备上运行该攻击。
- 该 App 的 MainActivity 是导出的（便于 adb 触发攻击），任何应用都能触发这些测试攻击；测试完毕后
  请卸载：adb uninstall org.fcitx.fcitx5.android.rogue。
- 它不声明任何权限、不声明 INTERNET、不包含第三方依赖，能做的事就是一个普通第三方 App 能做的事。

## 1. 模块信息

| 项 | 值 |
| --- | --- |
| 模块路径 | tools/rogue-clipboard-app |
| applicationId / namespace | org.fcitx.fcitx5.android.rogue |
| 语言 | 纯 Java（Java 11 编译选项） |
| 第三方依赖 | 无（仅 Android SDK） |
| 权限 | 无（无 INTERNET；仅 Manifest 中声明 package visibility 的 queries，不是权限） |
| SDK | compileSdk 36 / minSdk 23 / targetSdk 36（与 build-logic Versions.kt 一致） |
| 构建方式 | 独立 application 模块，不使用 build-logic convention 插件；可单独 assembleDebug |
| 与 :app 关系 | 不是 :app 的依赖，不进入产品 APK；settings.gradle.kts 末尾仅 include 一行 |

## 2. 构建与安装

Windows cmd / PowerShell（构建由 Lead 统一执行）：

    gradlew.bat :tools:rogue-clipboard-app:assembleDebug
    adb install -r tools/rogue-clipboard-app/build/outputs/apk/debug/rogue-clipboard-app-debug.apk

APK 实际文件名以构建输出为准（AGP 可能附加版本信息）。

## 3. 触发方式

### 3.1 UI

启动 App 后每个攻击一个按钮，按钮文字就是攻击名（便于 uiautomator），另有 all（跑全套）与
clear_results（清空结果列表）。每条结果单独一个 TextView，供 uiautomator dump 读取。

### 3.2 adb intent（推荐自动化）

    adb shell am start -n org.fcitx.fcitx5.android.rogue/.MainActivity --es attack read_clipboard
    adb shell am start -n org.fcitx.fcitx5.android.rogue/.MainActivity --es attack all

指定目标包（默认自动发现 debug 包）：

    adb shell am start -n org.fcitx.fcitx5.android.rogue/.MainActivity --es attack read_fcitx_private --es pkg org.fcitx.fcitx5.android

### 3.3 读取结果

    adb logcat -d -s ROGUE:I
    adb shell uiautomator dump /sdcard/rogue-dump.xml
    adb pull /sdcard/rogue-dump.xml

### 3.4 输出格式

    RESULT|<attack>|<status>|<detail>

- detail 已压缩为单行、去除竖线、最长 400 字符；除 read_clipboard 的 preview 外不含剪贴板明文。
- status 语义：

| status | 含义 |
| --- | --- |
| SUCCESS | 攻击达成目的，说明存在真实风险/漏洞 |
| BLOCKED | 目标、Android 平台或权限拒绝攻击（期望的安全结果） |
| INFO | 中性观测/能力基线，需要结合 detail 判断（例如伪造入口打开了确认界面） |
| ERROR | 测试工具自身失败（进程执行错误等），不代表目标安全 |
| DONE | 仅用于套件结束标记（RESULT|suite|DONE|...） |

## 4. 目标包解析

1. adb 传入的 --es pkg X（若已安装）；
2. 默认 org.fcitx.fcitx5.android.debug（debug 构建）；
3. 回退 org.fcitx.fcitx5.android（release 构建）；
4. 都没有则 installed=false，相关攻击输出 BLOCKED。

App 顶部 header 显示目标包、版本、debuggable、allowBackup、当前默认输入法。类名使用命名空间
（namespace）下的稳定类名：debug 包的 applicationId 后缀不影响类名。

## 5. 攻击清单与预期安全结果

| # | attack | 行为 | 预期安全结果 | 说明 |
| --- | --- | --- | --- | --- |
| 1 | read_clipboard | 前台读取系统剪贴板，输出 accessible/hasText/length/sha256_8/preview(<=80)/itemCount | 安全模式下系统剪贴板应为空：accessible=false → BLOCKED | 证明“安全摘录/私有粘贴不使用系统剪贴板”；也是攻击者能力基线（前台本就能读剪贴板） |
| 2 | listen_clipboard | 注册剪贴板监听 3 秒，输出回调次数、可读次数、最后长度/hash | 回调可能发生但后台读不到正文；安全模式下剪贴板内容不应是明文 → INFO/BLOCKED | 验证 Android 10+ 后台读取限制与“普通复制加密后立即清空” |
| 3 | read_fcitx_private | 用 FileInputStream 直接读 /data/data/<pkg>/databases/secure_clbdb、clbdb、-wal、shared_prefs 等 | 全部 FileNotFoundException/SecurityException → BLOCKED | 若任意字节可读 = SUCCESS，说明第三方可读加密库/明文库，严重漏洞 |
| 4 | bind_fcitx_ipc | bindService 到 action <pkg>.IPC 与显式 FcitxRemoteService | SecurityException（signature 级 permission.IPC）→ BLOCKED | 验证插件 IPC 权限边界 |
| 5 | query_fcitx_provider | contentResolver.query content://<pkg>.provider/ | SecurityException（MANAGE_DOCUMENTS）→ BLOCKED | 验证导出 provider 的权限保护 |
| 6 | forged_process_text | startActivity 伪造 ACTION_PROCESS_TEXT 到 SecureCaptureActivity，EXTRA_PROCESS_TEXT=ROGUE_INJECT_1 | started=true 但只弹确认框，clipboard_callbacks=0 → INFO | 外部入口必须用户确认；隐私不能静默入库（vault 侧最终验证见 docs/security/07 与 instrumented 测试） |
| 7 | spam_process_text | 连续 10 次伪造 PROCESS_TEXT（ROGUE_INJECT_0..9） | 不崩溃、不静默入库、clipboard_callbacks=0；Android 10+ 后台启动限制可能使 started 仅 1 → 平台行为 | 验证批量恶意 Intent 的稳定性与限流/确认策略 |
| 8 | forged_send | 伪造 ACTION_SEND text/plain 到 SecureShareReceiverActivity，EXTRA_TEXT=ROGUE_SEND_1 | 同 6：只弹确认框，无剪贴板事件 | 验证分享入口不静默入库 |
| 9 | read_logs | Runtime.exec("logcat -d -t 400")，统计含 fcitx/Clipboard 的行与含目标包名的行（排除 ROGUE 自身行） | target_pkg_lines=0（现代 Android 默认只允许读自身 UID 日志）→ BLOCKED | 验证目标日志不向第三方泄露明文 |
| 10 | read_fcitx_external | 尝试列 /sdcard/Android/data/<pkg>/files 与 files/secure-exports（detail 中 p0..p3） | exists=false 或 listable=false（scoped storage）→ BLOCKED | 验证导出的 secure-exports 等外部目录不暴露给第三方 |

## 6. 平台限制（红队结论必须考虑）

- **后台剪贴板读取**：Android 10+ 只有前台 App/默认输入法能读剪贴板内容；后台监听仍会收到“变化
  回调”但读不到正文。因此 listen_clipboard 的 readable 可能为 0，这属于平台保护，不代表目标失败。
- **后台启动 Activity（BAL）**：Android 10+ 限制 App 在后台启动 Activity。forged_process_text /
  forged_send 在 rogue 仍处于前台时发起才能弹出确认框；一旦 rogue 退到后台，后续 startActivity 可能
  被系统静默拦截（不抛异常）。spam_process_text 的 started 计数因此常为 1，详情字段已注明。
- **包可见性**：Manifest 中 queries 只声明“可见性”，不是授权；Android 11+ 不加 queries 看不到目标包。
- **Scoped storage**：Android 11+ 普通 App 无法列/读其他 App 的 /sdcard/Android/data 目录。
- **Logcat**：Android 4.1 起默认仅允许读取自身 UID 的日志；read_logs 的 total_lines 可能为 0 或仅
  含自己。
- **File.exists()**：对无权访问的路径可能返回 false 而不抛异常，所以 read_fcitx_private 同时输出
  exists 与异常类型，避免把“无权限”误判成“文件不存在”。

## 7. 与验收需求的对应关系

| 验收点 | 建议攻击 | 期望判定 |
| --- | --- | --- |
| 安全摘录/私有粘贴后系统剪贴板无变化 | 先 read_clipboard，执行安全摘录/粘贴后再 read_clipboard | 前后都 accessible=false（或执行后无明文长度/hash 变化） |
| 加密库中搜不到明文，且第三方不可读 | read_fcitx_private | 全部 BLOCKED；任何可读=漏洞 |
| 普通复制加密后立即清空 | listen_clipboard + read_clipboard（在目标侧普通复制后切回 rogue） | 剪贴板回调可发生，但最终 accessible=false |
| 恶意输入/非法 Intent 不静默入库 | forged_process_text / spam_process_text / forged_send | started=true 且 clipboard_callbacks=0；目标侧确认后才入库 |
| IPC/Provider 权限边界 | bind_fcitx_ipc / query_fcitx_provider | BLOCKED（SecurityException） |
| 日志不含明文 | read_logs | target_pkg_lines=0 |
| 外部目录不泄露 | read_fcitx_external | BLOCKED |

与 tools/clipboard-probe（T4）、app/src/androidTest 的 instrumented 测试互补：probe 用于“系统剪贴板
是否被写入”的精细观测，rogue app 用于“第三方视角能窃取什么”的攻击面验收。

## 8. 结果判读示例（安全模式生效时）

    RESULT|read_clipboard|BLOCKED|accessible=false;hasText=false;length=-1;sha256_8=-;preview="";itemCount=0;...
    RESULT|read_fcitx_private|BLOCKED|target_pkg=org.fcitx.fcitx5.android.debug;secure_clbdb=FileNotFoundException;...
    RESULT|bind_fcitx_ipc|BLOCKED|...;action_result=bound=false;exception=SecurityException:...
    RESULT|query_fcitx_provider|BLOCKED|...;exception=SecurityException:...
    RESULT|forged_process_text|INFO|...;started=true;exception=none;clipboard_callbacks=0;...
    RESULT|read_logs|BLOCKED|...;target_pkg_lines=0;...
    RESULT|read_fcitx_external|BLOCKED|...;exists=false;...
    RESULT|suite|DONE|attacks=10;...

注意：若 read_clipboard 显示 SUCCESS 且长度>0，先确认当时剪贴板里是否是测试字符串（可能是你/其他
App 刚复制的普通文本），再结合目标侧安全模式是否开启、兼容模式是否启用来判断。

## 9. 快速红队验收流程（Android 16 模拟器）

1. 安装小企鹅输入法 debug APK，按 docs/security/07-test-plan-and-instrumentation.md 开启安全模式；
2. 安装本 App；
3. 在目标 App 中执行一次安全摘录、一次普通复制（安全模式 channel B，等待清空）、一次私有粘贴；
4. 启动红队套件：

       adb shell am start -n org.fcitx.fcitx5.android.rogue/.MainActivity --es attack all

5. 等待约 10 秒（listen_clipboard 需要 3 秒、activity 攻击各需约 1 秒），读取结果：

       adb logcat -d -s ROGUE:I

6. 按第 5 节预期逐条核对；forked/伪造攻击会打开目标确认界面，属正常现象（测试工具不点击确认，
   确认后的入库行为由 instrumented 测试/手工验证覆盖）；
7. 如实记录结果；未执行/无法执行的项目按 docs/security/07 的未执行项清单标注，不得写成通过。
