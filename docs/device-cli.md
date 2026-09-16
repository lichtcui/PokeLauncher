# 真机日志

> 操作手册见 [`../AGENTS.md`](../AGENTS.md)。截图 / 点击 / 滑动 / `dumpLayout` / `hilog` 等通用 `hdc` 命令见 `harmony-next` skill（含 `device_ui_action.py`、`device_evidence_bundle.py`），不在此重复。

## 本 App 的日志 tag

`hilog.info(0x0000, TAG, ...)` 会以 `A00000/<TAG>` 出现。本 App 用到的 TAG：

```
GameRepository  GamePage  Home  Settings  LocalHttp  Notifier  CheatState  AppUpdate  WebCache  PokeRogue
```

- 游戏内 `console.log` 以 `ARKWEB-CONSOLE` 出现（排查游戏侧与作弊注入时最有用）。
- 抓日志：后台起 `hdc shell hilog > /tmp/log.txt`，抓完 `kill`。**不要加 `-x`**（那是「打印完缓冲区就退出」）。
- 过滤：`rg -i "GameRepository|CheatState|ARKWEB-CONSOLE" /tmp/log.txt`

## 坐标

截图 **1260×2844**；`uitest uiInput` 的坐标是**物理像素**，别把缩放后的显示坐标直接拿去点。

## 已知限制（本项目实测）

- **沙箱只读**：`hdc shell`（uid 2000）能**读**应用沙箱（`ls`/`cat`/`hdc file recv`），但**不能写**（`touch`/`mkdir` 报 `Permission denied`），即使目录权限是 `drwxrwxrwx`。因此无法人工制造「解压中断」等状态来测 `recover()`。
- **首页右上角齿轮点不动**：齿轮落在系统手势区，`uitest` 注入的 `click/doubleClick/longClick` 都会被吞。要进设置页用真机手点；要造「未下载」态用 `bm clean -d`。

```bash
# 读沙箱（游戏目录 / 下载中的 zip）
B=com.lichtcui.pokerogue
F=/data/app/el2/100/base/$B/haps/entry/files
"$HDC" shell "ls -la $F $F/game | head"
"$HDC" shell "ls -la /data/app/el2/100/base/$B/haps/entry/cache/game.zip"
```

- **清数据**：`hdc shell bm clean -d -n com.lichtcui.pokerogue` 清空 filesDir（游戏资源）+ preferences + localStorage（**会丢存档与作弊设置**），之后需重启 App。

> 调试游戏 WebView（DevTools）见 [`debugging.md`](./debugging.md)。
