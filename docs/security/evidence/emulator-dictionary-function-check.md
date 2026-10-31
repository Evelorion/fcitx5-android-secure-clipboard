# 词库 / 输入功能回归实测（Android 16 x86_64 模拟器）

> 时间：2026-10-31 09:49–10:00（Asia/Shanghai），对象：`org.fcitx.fcitx5.android.debug`（含安全剪贴板改造的 x86_64 debug 构建）。
> 目的：确认本次安全剪贴板改造没有破坏词库、输入法引擎、附加组件、拼音用户词库与用户数据导出。

## 1. 内置输入法 / 引擎

“输入法”页面可添加的输入法（实测）：

- English、拼音、双拼、五笔字型、五笔拼音、自然码、电报码

“附加组件”页面全部加载（实测，无缺失/无崩溃）：

Android 输入法前端、Android 英文键盘、简繁转换、剪贴板、全角字符、Lua IME API、输入法选择器、Lua 附加组件加载器、Android 弹出提示与通知、**拼音**、**额外拼音功能**、标点、快速输入、拼写、**码表**、Unicode。

APK 内自带词典资源（aapt list 确认）：

- `assets/usr/share/libime/sc.dict`、`zh_CN.lm`、`zh_CN.lm.predict`、`extb.dict`
- `assets/usr/share/fcitx5/pinyin/chaizi.dict`、`symbols`
- `assets/usr/share/fcitx5/table/wbx.main.dict`、`wbpy.main.dict`、`zrm.main.dict`、`db.main.dict`
- `assets/usr/share/fcitx5/inputmethod/{pinyin,shuangpin,wbpy,wbx,zrm,db}.conf`

## 2. 拼音输入实测（字典 + 语言模型 + 候选）

操作：默认输入法切换为“拼音”，在系统设置搜索框输入 `nihao`。

- 预编辑：`ni hao`
- 候选：`1. 你好`（默认高亮）、`2. 👋`、`3. 拟好`、`4. 你`、`5. 拟`、`6. 泥`、`7. 逆`
- 空格提交后，输入框内容变为：`ifinihao你好`

说明：候选词来自内置 `sc.dict` + `zh_CN.lm`（含词频/联想/emoji 候选），证明拼音词库与语言模型工作正常。
截图：`emulator-pinyin-candidates.png`

## 3. 用户词库 / 学习

输入后应用外部数据目录自动创建并更新：

```
external/data/pinyin/user.dict     339 字节（09:54 创建，09:56 更新）
external/data/pinyin/user.history   29 字节（09:54，09:56 更新）
```

证明用户词库写入/历史学习正常。

## 4. 词库管理页面

- 拼音附加组件 → 配置页正常（双拼方案、候选数量、预测、学习等选项齐全）
- “管理词库” → “拼音词库”页面正常打开（新安装无自定义词库，列表为空，属预期）
- 码表附加组件 → 配置页正常；“管理码表输入法”页面正常打开（无自定义码表，属预期）

## 5. 用户数据导出（确认词典被导出、剪贴板库被排除）

通过“高级 → 导出用户数据”保存到 Downloads：`fcitx5-android_2026-10-31T09_59_13Z.zip`，**7,592 字节，24 个条目**。

- 包含：`external/config/conf/{pinyin,androidkeyboard,androidnotification,chttrans,punctuation}.conf`、`external/config/profile`、`external/data/pinyin/user.dict`、`user.history`、`shared_prefs/...`、`metadata.json`
- **不包含**：`clbdb`、`clbdb-wal`、`secure_clbdb`、`secure_clbdb-wal`（剪贴板数据库按设计排除；`databases/` 为空目录）
- 结论：用户数据导出功能正常，且词典/配置包含、剪贴板私密库不导出。

## 6. 稳定性

- 全流程无 `FATAL EXCEPTION`；小企鹅进程存活（pid 8074）。
- 未验证：真机软键盘触摸打字、OEM 输入法切换、云输入/插件扩展词库、导入用户数据（本轮只做导出）。

## 备注：模拟器测试限制

本轮通过**硬件按键事件**驱动 Fcitx 拼音引擎完成输入验证（`adb shell input keyevent`）；该模拟器上对 Fcitx 软键盘窗口的触摸注入不生效（`input tap` 无法触发键盘按键），因此软键盘点按路径未在模拟器验证，需真机确认。
