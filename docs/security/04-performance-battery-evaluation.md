<!-- SPDX-License-Identifier: LGPL-2.1-or-later -->

# 04 性能与耗电评估

> 范围：对应需求文档第十一部分（11.1 优化、11.2 指标、11.3 验收）。
> 快照：commit `eb5b502` + 工作树（2026-10-31）。**本任务未运行 Gradle/benchmark；维护者 已执行构建与 JVM 单测，其中桌面 JVM AES-GCM 参考基准见附录 B（非设备）。没有任何真机数据。** 本文指标定义与设备测量方法中的数值一律标注“未验证/待测”，不编造延迟。
> 证据标记：【S】源码确认、【D】设计目标、【T】需测试（未验证）。

## 1. 现状与结论先行

1. **没有设备端性能数据**：实现已落地且 维护者 执行了 JVM 基准（附录 B），但加密→事务→清空→端到端的设备耗时、CPU、内存、功耗、启动时间全部未测；本报告不提供任何设备端实测数字。
2. **现有基线是“事件驱动、无轮询”**：剪贴板监听来自系统 `OnPrimaryClipChangedListener` 回调（`data/clipboard/ClipboardManager.kt:452-493`），没有定时任务、没有前台服务、没有后台轮询【S】。
3. **现有实现已避免在系统回调线程做重活**：旧监听器在回调里只取 `primaryClip`，随后 `launch { mutex.withLock { ... } }` 把解析/DB 放到协程（`ClipboardManager.kt:452-493`）【S】。
4. **列表不做整库解密**：安全模式列表通过 `SecureDisplayPagingSource` 映射密文行、不调用解密（`ClipboardManager.kt:284-293,531-554`）【S（门面层）】。
5. **验收目标“短文本 1 秒内完成转存+清空”是设计目标，不是承诺**（需求 11.3），必须以真机 P50/P95/P99 证明。

## 2. 指标定义（对应需求 11.2）

| 指标 ID | 指标 | 定义（测点） | 采集方式 | 目标 | 状态 |
| --- | --- | --- | --- | --- | --- |
| P1 | 复制事件通知延迟 | 系统写入剪贴板（`ClipDescription.timestamp`）→ 本应用 `onPrimaryClipChanged` 被调用的时间差 | 回调内记录 `SystemClock.elapsedRealtimeNanos()` 与 `clip.description.timestamp`（API 26+）对比 | 越小越好；不设硬指标 | 未验证 |
| P2 | 文本加密耗时 | `ClipboardCrypto.encrypt` 入口→出口（含 IV 生成、tag） | 在加密模块入口/出口 `System.nanoTime()`；仅记录耗时与字节长度，禁止记录正文 | 设计目标：短文本 < 10ms 量级（待测，非承诺） | 未验证（T1 未落地） |
| P3 | 数据库事务耗时 | `save()` 开始→Room 事务提交成功（含加密、去重、limit 执行） | 仓库 `save()` 内部计时（`SaveResult.Success.durationMs` 已在契约中预留，`Contract.kt:62-65`） | 待测 | 未验证（T1 未落地） |
| P4 | 保存完成→清空系统剪贴板延迟 | 事务成功返回 → `clearPrimaryClip()` 返回 | T3 状态机 VERIFYING→CLEARING 计时 | 设计目标：尽量小；受 Binder/系统调度影响 | 未验证（T3 未落地） |
| P5 | 端到端转存耗时 | 事件通知（P1 起点）→ 清空完成（P4 终点） | 由 T3 状态机统一计时（只记耗时/长度） | 需求 11.3：短文本尽量 < 1s（设计目标） | 未验证 |
| P6 | P50/P95/P99 | 对 P1–P5 分别统计分位数 | 至少 N≥30 次/长度档；剔除首次冷启 | 待测 | 未验证 |
| P7 | CPU 使用率 | 转存期间应用进程 CPU 占用；空闲 1 小时基线 | `adb shell top -H -p <pid>`、`dumpsys cpuinfo`、Perfetto | 待测 | 未验证 |
| P8 | 内存使用量 | PSS/Java heap 峰值（加密大文本时） | `adb shell dumpsys meminfo <pkg>`、Android Studio Memory Profiler | 待测 | 未验证 |
| P9 | 后台功耗 | 屏幕关闭、输入法空闲 8 小时的耗电增量 | `dumpsys batterystats --reset` + `dumpsys batterystats`、Perfetto battery counters、系统电池统计 | 待测 | 未验证 |
| P10 | 键盘首次启动时间 | 冷启动输入法（进程不存在→键盘可见） | 输入法无独立 launcher；用 Perfetto/`dumpsys gfxinfo` + 用户可见首帧；对比安全模式开/关 | 相对基线不显著退化 | 未验证 |
| P11 | 输入候选词响应速度 | 按键→候选更新（现有路径） | 现有基准 + 安全模式开/关对比 | 不显著退化 | 未验证 |
| P12 | 文本长度梯度 | 10/100/1000/10000/100000 字符 | 每档独立统计 P2/P3/P5/P8 | 待测 | 未验证（实现未落地） |

## 3. 已/应采取的优化手段

### 3.1 已存在（源码确认）【S】

| 手段 | 证据 |
| --- | --- |
| 事件驱动、无轮询 | 监听来自系统回调；全仓库无 Timer/Alarm/轮询剪贴板代码（01 3.1/3.11） |
| 系统回调线程不做重活 | 旧监听器仅读取快照，DB 操作在 `launch` 协程 + `Mutex`（`ClipboardManager.kt:469-492`） |
| 串行化 | `mutex.withLock`（`ClipboardManager.kt:74,470`）；迁移另有 `migrationMutex`（`ClipboardManager.kt:75,401`） |
| 历史限额 | `clipboardHistoryLimit`（旧）与 `secureClipboardHistoryLimit`（新，`AppPrefs.kt:418-426`），门面 `enforceLimit`（`ClipboardManager.kt:125-138,235-242`） |
| 列表不做全量解密 | `SecureDisplayPagingSource` 只映射密文行（`ClipboardManager.kt:531-554`）；面板 Paging 每页 16（`ClipboardWindow.kt:85-87`） |
| 避免重复初始化 | `SecureClipboardVault.init` 幂等（`SecureClipboardVault.kt:30-34`）；`AppPrefs`/`ThemeManager` 单例 |
| 明文上限 | `MAX_TEXT_LENGTH=100_000`、`MAX_TEXT_UTF8_BYTES=400_000`（`Contract.kt:181-187`），防止无限制分配 |
| 外部入口速率限制常量 | `RATE_LIMIT_WINDOW_MS=2_000`、`MAX_CAPTURES_PER_WINDOW=3`（`Contract.kt:193-196`） |
| 安全记录不镜像 fcitx 引擎 | `core/Fcitx.kt:486-492`（避免额外内存/引擎处理） |

### 3.2 设计上要求但 T1/T2/T3 未落地（需在实现中确认）【D】

1. 加解密、Room 事务全部在 `Dispatchers.IO`/后台协程，**禁止主线程**。
2. 单次只解密一条记录（`read(id)`），不批量解密；界面用遮罩行（`ClipboardManager.kt:262-293` 已有门面语义）。
3. 加密模块/KeyManager 单例、Keystore 密钥只加载一次（T1）。
4. 通道 B 状态机串行队列、不并发加密；失败快速返回（T3）。
5. 外部入口速率限制实际执行（T2/T3；常量已有）。
6. 迁移分批/事务、失败可恢复（门面按整批 `migrateLegacy` 调用，`ClipboardManager.kt:417`；T1 决定批大小）。
7. 不新增常驻服务/定时器/唤醒锁；不申请电池优化白名单（需求 8.2 禁止“常驻”手段）。

## 4. 测量方法（可执行清单，全部尚未执行）

### 4.1 仪器化埋点（实现落地后添加，日志禁止含明文）

- 在 `ClipboardCrypto.encrypt`（T1）、`Repository.save`（T1）、`ClipboardEventProcessor`（T3）使用 `System.nanoTime()` 记录耗时；事件日志只允许 `length`、`durationMs`、`source`、`result`（`SecureClipboardError` 枚举），不得包含文本。
- 复用 `SaveResult.Success.durationMs`（`Contract.kt:63`）与 `ClipboardSecurityStatus` 的计数（`Contract.kt:248-272`）做统计，不新增敏感字段。
- 临时性能日志建议只在 debug/开发者 verbose 下输出耗时数字（不含内容），验收后移除或降级。

### 4.2 设备侧命令

```powershell
# 进程与 CPU
adb shell pidof org.fcitx.fcitx5.android
adb shell top -H -p <pid> -d 1 -n 30
adb shell dumpsys cpuinfo | Select-String fcitx
# 内存
adb shell dumpsys meminfo org.fcitx.fcitx5.android
# 启动（输入法无独立 launcher；让输入框唤起后看首帧/渲染统计）
adb shell dumpsys gfxinfo org.fcitx.fcitx5.android
# 电量（8h 空闲前后对比）
adb shell dumpsys batterystats --reset
adb shell dumpsys batterystats org.fcitx.fcitx5.android
adb shell dumpsys power | Select-String Wake
# 系统级跟踪（无需新增应用依赖）
adb shell perfetto -o /data/misc/perfetto-traces/clipboard.pftrace -t 10s sched freq idle am wm gfx view binder_driver
```

### 4.3 实验设计

| 实验 | 变量 | 重复 | 记录 |
| --- | --- | --- | --- |
| 加密/事务延迟 | 文本长度 10/100/1000/10000/100000；敏感标记开/关 | 每档 ≥30 次，丢弃首次（冷 Keystore/冷数据库） | P2/P3/P5、P50/P95/P99、Java heap 峰值 |
| 连续复制 | 单次 vs 1 秒内 3 次 vs 5 次 | ≥20 组 | 是否有 STALE_CLIP、清空次数、CPU |
| 模式对比 | 安全模式开/关、严格模式开/关 | 交替 | P10/P11（键盘首帧、候选响应）、CPU、内存 |
| 空闲功耗 | 屏幕关闭、输入法未使用 8h；对照组关闭安全模式 | 各 ≥3 次整晚 | 电池统计、唤醒次数、Perfetto 频率/唤醒 |
| 大历史 | 0/100/1000/5000 条记录 | 各测一次 | 面板首屏加载、内存、itemCount 查询 |

### 4.4 结果记录模板（T0 为空，禁止填假数据）

| 指标 | 10 | 100 | 1000 | 10000 | 100000 |
| --- | --- | --- | --- | --- | --- |
| P2 加密耗时 P50/P95/P99 (ms) | 未验证 | 未验证 | 未验证 | 未验证 | 未验证 |
| P3 事务耗时 P50/P95/P99 (ms) | 未验证 | 未验证 | 未验证 | 未验证 | 未验证 |
| P5 端到端 P50/P95/P99 (ms) | 未验证 | 未验证 | 未验证 | 未验证 | 未验证 |
| Java heap 峰值 (MB) | 未验证 | 未验证 | 未验证 | 未验证 | 未验证 |

## 5. 预期性能风险（需实测确认）

| # | 风险 | 说明 | 缓解 | 验证 |
| --- | --- | --- | --- | --- |
| R1 | Keystore 首次加解密慢 | 首次访问 Keystore 可能需要建立/加载密钥，明显慢于后续；具体数值依赖设备，未测 | 预热（可选的启动后异步 warm-up 操作，不阻塞 UI）；不在主线程 | 冷启动后首次 vs 第 2 次对比（P2） |
| R2 | Room 事务 fsync 慢 | SQLite 事务提交涉及文件系统；WAL 模式下通常较快，但低端 UFS 设备可能变慢 | 单事务批量写；避免多次小事务；WAL 默认 | P3、P5 分位数 |
| R3 | 大文本内存峰值 | Kotlin String（UTF-16）+ UTF-8 字节数组 + 密文可能 2-4 倍；无限制会 OOM | 100k 字符/400k 字节上限；避免不必要拷贝；不把大文本放进 UI 列表（遮罩/分页） | P8、大文本梯度 |
| R4 | 同步 Binder transformer 延迟 | 旧路径在剪贴板协程内同步调用插件（`FcitxRemoteService.kt:43-51`）；插件卡顿会推迟落库/清空 | 严格模式默认禁用；超时/异常处理（T3 需保证不阻塞清空链） | 插件慢响应测试（T4） |
| R5 | 列表滚动触发多次 DB 查询 | Paging 每页 16（`ClipboardWindow.kt:85-87`），大量记录时滚动查询 | 索引/limit；不整库解密 | 大历史（5000 条）滚动测试 |
| R6 | 迁移一次性解密/加密大量历史 | 旧库 1000 条 × 平均长度；迁移在 IO 协程但可能占内存/CPU | 分批 + 进度状态；迁移失败可恢复（门面已有失败保留逻辑 `ClipboardManager.kt:434-442`） | 1000 条旧数据迁移耗时/内存 |
| R7 | 事件风暴（连续复制） | 高频复制导致排队/加密多次 | Mutex 串行 + 序号 + 去重（T3）；限速常量 | 1 秒 5 次复制测试 |
| R8 | 后台功耗 | 事件驱动本身几乎无后台成本；风险在于误加轮询/唤醒锁 | 明确禁止 | P9 |
| R9 | 输入法首帧退化 | 安全模式初始化（Vault、Keystore）若在 `Application.onCreate` 同步执行会拖慢进程启动 | 门面 `init` 中 `SecureClipboardVault.init(context)` 为同步调用（`ClipboardManager.kt:152-158`）——T1 实现需保证构造轻量、密钥延后加载 | P10 冷启动对比 |

## 6. 验收建议（与 06 文档一致）

1. 需求 11.3 的“短文本 1 秒内”只能作为**设计目标**；验收时至少给出 P50/P95/P99 与测试机型/系统版本（见 05）。
2. 若没有真机数据，验收文档必须写“未验证”，**不得声称延迟已达标**。
3. 性能回归的门槛建议：安全模式开关前后，键盘首帧与候选响应差异在可接受范围（具体阈值由 维护者 与实测基线确定，本报告不预设数字）。
4. 功耗结论必须基于整晚对照实验；仅凭“无轮询”不能证明省电。

## 附录：T1 快照增量

- T1 已提交 JVM 性能基准测试 `app/src/test/java/.../CryptoPerformanceBenchmarkTest.kt`（**维护者 已执行，2 个基准测试通过**，数字见附录 B；只代表桌面 JVM，不能替代真机端到端数据）。
- 仓库 `save()` 的 historyLimit 已改为每次读取偏好（`SecureClipboardVault.kt:31-41` 注入 `historyLimitProvider`；`SecureClipboardRepositoryImpl.kt:45,156`），`06` R5 已修复；性能测试时仍需记录该设置值，避免不同配置导致数据不可比。
- 通道 B 控制器/状态机已落地（`SecureClipboardController.kt`、`ClipboardEventProcessor.kt`），P4/P5 埋点位置可确定；JVM 侧仅有附录 B 的加密原语参考值，**没有任何设备端实测数字**。

## 附录 B：桌面 JVM AES-256-GCM 参考基准（维护者 实测，非设备）

> 来源：维护者 执行 `:app:testDebugUnitTest` 时 `CryptoPerformanceBenchmarkTest` 的 system-out（新增 JVM 单测中的 2 个，均通过；该批次 61 个，后续加密备份测试加入后共 81 个）。本任务未运行 Gradle，以下数字原样引用，**仅代表桌面 JVM**。
> **重要**：这不是设备端指标，也不是端到端指标。不得把下表的耗时写成 Android 真机/Android Keystore/Room 端到端结果。

### B.1 测试方法（源码确认）

- 基准代码：`app/src/test/java/org/fcitx/fcitx5/android/data/secureclipboard/CryptoPerformanceBenchmarkTest.kt:30-67`；
- 每档文本长度：10 / 100 / 1_000 / 10_000 / 100_000 UTF-16 code unit；内容为 ASCII 小写字母随机串（`:39-43`）；
- 每档先 warmup 10 次，再测 30 次（`:33-34`）；每次为 **encrypt+decrypt 一次往返**（`:69-73`）；
- 密钥为测试用确定性非机密 AES-256 key（`:28`），AAD 为 `SecureClipboardAad.payload(1, system_clipboard)`（`:45`）；
- 运行环境：桌面 JVM 的 JCE provider（非 Android Keystore、非真机 SoC）。

### B.2 结果（encrypt+decrypt 往返，单位 µs）

| 文本长度（UTF-16 单元） | 平均 | min | max |
| --- | --- | --- | --- |
| 10 | 123.7 | 29 | 370 |
| 100 | 53.1 | 29 | 128 |
| 1 000 | 106.3 | 47 | 165 |
| 10 000 | 313.2 | 241 | 734 |
| 100 000 | 1 799.9 | 1 533 | 2 270 |

另：超限策略（`MAX_TEXT_LENGTH + 1` 字符）在**任何加密之前**被拒绝，耗时 586µs（`CryptoPerformanceBenchmarkTest.kt:75-85`）。

### B.3 如何正确解读

1. **只有 avg/min/max，没有 P50/P95/P99**：30 个样本的算术平均 + 极值；本报告不把 avg 冒充 P50，也没有从这批数据推导分位数。
2. **桌面 JVM ≠ Android 设备**：不同 CPU、不同 JCE provider、无 Keystore 硬件密钥、无线程调度/省电限制；Android Keystore 的加解密（尤其首次使用/冷启动）可能明显更慢，必须实测。
3. **不含端到端成本**：不含 Keystore 密钥加载、UTF-8 编码/字符串拷贝、Room 事务与 fsync、Binder、UI、清空系统剪贴板、进程调度；因此**不能**把 10 字符 123.7µs 或 100k 字符 1.8ms 当作“复制→加密→落库→清空”的设备耗时。
4. **短文本噪声**：100 字符 avg（53.1µs）低于 10 字符（123.7µs），说明小样本下 JIT/GC/计时抖动明显；不应据此得出“越长越快”的结论。
5. **100k 字符**：平均往返 1.8ms（桌面 JVM），但该长度接近 `MAX_TEXT_LENGTH`；设备上还受内存峰值、Room 写入与 Keystore 影响，**无任何设备数据**。
6. **超限拒绝很便宜**：586µs 说明策略层先于加密拒绝是有效的 DoS 防护方向（仍需设备验证）。

### B.4 与第 2 节指标的关系

| 指标 | 本附录能覆盖的部分 | 仍缺 |
| --- | --- | --- |
| P2 文本加密耗时 | 部分：桌面 JVM 的 encrypt+decrypt 参考值 | 设备 Keystore 路径、P50/P95/P99、首次冷启动 |
| P3/P4/P5 | 不能覆盖 | Room 事务/fsync、Keystore、Binder、清空延迟、端到端 |
| P7/P8/P9/P10/P11 | 不能覆盖 | CPU/内存/功耗/启动/候选响应全部需真机 |
| P12 文本长度梯度 | 部分：JVM 10–100k 梯度 | 设备梯度、内存峰值、超长策略端到端 |

**结论**：维护者 已提供**桌面 JVM 参考基准**（2 个基准测试通过），可作为加密原语成本的 sanity check；但第 2 节定义的所有设备端指标（P1、P3–P11 及 P2 的设备部分）仍为“未验证”，本报告不据此声称性能达标。
