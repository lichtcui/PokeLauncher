# 调试（游戏 WebView DevTools）

> 操作手册见 [`../AGENTS.md`](../AGENTS.md)。ArkWeb 的 **domain socket** 机制、`hdc fport` 转发、CDP `Runtime.evaluate` 等通用用法见 `harmony-next` skill（《ArkWeb WebView CDP 调试与字段到达证明》），不在此重复。

本项目特有：

- **前置条件**：`common/Const.ets` 的 `DEBUG_UI` 必须临时改为 `true` 并重新编译安装（**发布默认为 `false`**），且 Web 页已加载（先进游戏页）。
- 转发目标是 `webview_devtools_remote_<PID>`；**进程 PID 变化后要重新转发**，多个旧转发会互相冲突，先删旧的再建新的。
- 作弊调试：`window.__cheatScene` 可直接访问实时场景（见 [`cheats.md`](./cheats.md)）。
