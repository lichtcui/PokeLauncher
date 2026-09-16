# 真机日志

> 操作手册见 [`../AGENTS.md`](../AGENTS.md)。截图 / 点击 / 滑动 / `dumpLayout` / `hilog` 等通用 `hdc` 命令见 `harmony-next` skill（含 `device_ui_action.py`、`device_evidence_bundle.py`），不在此重复。

## 本 App 的日志 tag

`hilog.info(0x0000, TAG, ...)` 会以 `A00000/<TAG>` 出现。本 App 用到的 TAG：

```
GameRepository  GamePage  Home  Settings  LocalHttp  Notifier  CheatState  AppUpdate
```

- 游戏内 `console.log` 以 `ARKWEB-CONSOLE` 出现（排查游戏侧与作弊注入时最有用）。
- 抓日志：后台起 `hdc shell hilog > /tmp/log.txt`，抓完 `kill`。**不要加 `-x`**（那是「打印完缓冲区就退出」）。
- 过滤：`rg -i "GameRepository|CheatState|ARKWEB-CONSOLE" /tmp/log.txt`

## 坐标

截图 **1260×2844**；`uitest uiInput` 的坐标是**物理像素**，别把缩放后的显示坐标直接拿去点。

> 调试游戏 WebView（DevTools）见 [`debugging.md`](./debugging.md)。
