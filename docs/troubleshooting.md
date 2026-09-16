# 常见问题

> 操作手册见 [`../AGENTS.md`](../AGENTS.md)。

| 现象 | 处理 |
| --- | --- |
| `install failed due to older sdk version` | `compatibleSdkVersion` 要改成设备的 `"6.1.1(24)"` |
| `17100001 Init error` | 调用了需要 Web 组件已挂载的 API（如 `setDownloadDelegate`/`setAudioMuted`），移到 `onControllerAttached` |
| 启动白屏/极慢 | 检查 index.html 注入是否生效（`willReadFrequently`） |
| 下载通知堆积/孤儿任务 | 首页启动时会清理；必要时卸载重装 |
| `@Builder` 里的动态文字不刷新 | `@Builder` 值参数不触发重渲染；动态文案直接在 `build()` 里读 `@State`（`Settings.ets` 检查更新/删除行） |
| DevTools 连不上 | 见 [`debugging.md`](./debugging.md)（确认已进游戏页、PID 正确、先删旧 fport 再转发） |
| 作弊没生效 | 作弊是**加载时注入**：改配置后要**重新进入游戏页**（返回首页再进，或重启 App）才会重新注入（不能热生效）；抓日志看 `served /__cheats__.js` 与 `ARKWEB-CONSOLE` 的 `CHEAT ...`（见 [`cheats.md`](./cheats.md)） |
| 作弊页入口不显示 | 首页「作弊设置」仅在设置页开启「作弊模式」后显示 |
