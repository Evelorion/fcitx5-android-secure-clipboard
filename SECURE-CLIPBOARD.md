# Secure Clipboard fork — 隐私优先双通道加密剪贴板

本仓库是 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 的一个实验性 fork，在**保留原有输入功能**的前提下新增了双通道安全剪贴板子系统。

> 这是研究/测试分支：构建产物为 **debug 签名**，未在一加真机（OxygenOS/ColorOS）上验证，不适合应用商店发布。

## 实现内容

- **安全摘录（通道 A）**：`ACTION_PROCESS_TEXT` / `ACTION_SEND` / 输入法盾牌按钮捕获选中文字 → 用户确认 → AES-256-GCM 加密落库，**全程不写系统剪贴板**。
- **普通复制保护（通道 B）**：系统剪贴板监听 → 校验 → 加密 → 事务提交 → 重读快照一致后**立即清空系统剪贴板**；串行队列、TOCTOU/竞态检测、失败不误删原文。
- **加密存储**：Android Keystore AES-256-GCM + 独立 HMAC 去重键；Room 库无明文列；旧明文数据库迁移后清空（secure_delete + VACUUM）。
- **私有粘贴**：InputConnection.commitText 直接提交，不重新填充系统剪贴板；可选设备凭据验证。
- **隐私历史**：查看 / 搜索 / 固定 / 删除 / 全部清除；按需本地解密，列表只显示遮罩。
- **加密备份**：PBKDF2-HMAC-SHA256(210k) + AES-256-GCM 口令加密的导出/导入文件。
- **插件隔离**：严格模式移除第三方 clipboard transformer；安全条目不进 fcitx 引擎剪贴板。
- **中文设置页**：小企鹅输入法 → 隐私与安全 → 安全剪贴板。
- **备份/导出排除**：Android 备份与用户数据导出排除剪贴板库。

## 测试与证据

- 81 个安全剪贴板 JVM 单测全部通过（crypto/策略/迁移/事件状态机/导出格式/基准）。
- Android 16 x86_64 模拟器实测：双通道、立即清空、迁移清空旧明文、搜索、加密备份导出/导入。
- 零权限红队 App 10 项攻击：私有库 / IPC / Provider / logcat / 外部目录 / 伪造入口均被拦截；安全模式下复制后的系统剪贴板被清空；旧模式对照下前台 App 可读（平台行为，需开启安全模式）。

完整报告见 [docs/security/README.md](docs/security/README.md)，重点：

- [01 源码审计](docs/security/01-source-audit.md)
- [02 威胁模型](docs/security/02-threat-model.md)
- [03 数据流隐私报告](docs/security/03-dataflow-privacy-report.md)
- [08 构建与测试报告](docs/security/08-build-and-test-report.md)
- [09 搜索/备份与红队实测](docs/security/09-rogue-app-red-team.md)

## 产物与签名

GitHub Release 中提供的 APK 为 **debug 签名测试构建**，签名密钥不随仓库分发。正式发布请使用自己的 upload key 构建 `release` 变体。

构建方式（Windows + NDK/CMake/ECM/gettext）见 [docs/security/README.md](docs/security/README.md)。
