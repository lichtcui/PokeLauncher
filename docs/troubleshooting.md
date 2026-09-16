# 常见问题

> 操作手册见 [`../AGENTS.md`](../AGENTS.md)。通用 HarmonyOS 报错（SDK 版本不匹配、`hvigorw` 环境、ArkWeb API 时序、ArkUI 渲染）交给 `harmonyos-build-doctor` / `harmony-next` skill，本表只留**项目特有**的现象。

| 现象 | 处理 |
| --- | --- |
| 启动白屏 / 极慢 | 检查 `index.html` 注入是否生效（`willReadFrequently`，见 [`architecture.md`](./architecture.md)） |
| 下载通知堆积 / 孤儿任务 | 首页启动时会清理；必要时卸载重装 |
| 作弊没生效 | 作弊是**加载时注入**：改配置后要**重新进入游戏页**（返回首页再进，或重启 App）才会重新注入（不能热生效）；抓日志看 `served /__cheats__.js` 与 `ARKWEB-CONSOLE` 的 `CHEAT ...`（见 [`cheats.md`](./cheats.md)） |
| 作弊页入口不显示 | 首页「作弊设置」仅在设置页开启「作弊模式」后显示 |
