# 真机命令与日志

> 操作手册见 [`../AGENTS.md`](../AGENTS.md)。

## 真机交互（无 GUI 时用）

```bash
HDC=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc

# 截图（设备端 → 本机）
"$HDC" shell snapshot_display -f /data/local/tmp/s.jpeg
"$HDC" file recv /data/local/tmp/s.jpeg /tmp/s.jpeg

# 点击 / 按键 / 滑动（坐标为物理像素，1260x2844）
"$HDC" shell uitest uiInput click 630 1738
"$HDC" shell uitest uiInput keyEvent Back        # 或 Home / Power
"$HDC" shell uitest uiInput swipe 630 5 630 2400 2000

# 读取控件坐标（返回 JSON，含 text/bounds）
"$HDC" shell uitest dumpLayout -p /data/local/tmp/l.json
"$HDC" file recv /data/local/tmp/l.json /tmp/l.json
```

> 注意：`uitest` 的坐标是**物理像素**。截图 1260×2844，别用截图显示坐标直接点。

## 日志

```bash
# 流式抓日志（要后台起、抓完 kill；不要用 -x，-x 是"打印完缓冲区就退出"）
"$HDC" shell hilog > /tmp/log.txt 2>&1 &
HPID=$!; sleep 1; ...触发操作...; kill $HPID

# 抓完过滤（本 App 的 tag 形如 A00000/<Tag>）
rg -i "GameRepository|GamePage|Home|Settings|LocalHttp|Notifier|CheatState" /tmp/log.txt
```

- 本 App 代码里的 `hilog.info(0x0000, TAG, ...)` 会以 `A00000/<TAG>` 出现。
- 游戏内 `console.log` 会以 `ARKWEB-CONSOLE` 出现（排查游戏侧问题时很有用）。
- 调试游戏 WebView（DevTools）见 [`debugging.md`](./debugging.md)。
