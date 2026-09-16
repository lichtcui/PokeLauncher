# 调试

> 操作手册见 [`../AGENTS.md`](../AGENTS.md)。

## 游戏 WebView（DevTools）

ArkWeb 用 **domain socket**（不是 tcp），端口转发方式：

```bash
HDC=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc
PID=$("$HDC" shell pidof com.lichtcui.pokerogue | tr -d '\r' | awk '{print $1}')
"$HDC" fport rm tcp:9222 tcp:9222 >/dev/null 2>&1
"$HDC" fport tcp:9222 localabstract:webview_devtools_remote_$PID
curl -s http://127.0.0.1:9222/json/list
```

- 前提：App 里 `setWebDebuggingAccess(true)`（由 `common/Const.ets` 的 `DEBUG_UI` 控制，见 `EntryAbility`）。**发布默认为 `false`**，调试前需临时改成 `true` 并重新编译安装，且当前已加载 Web 页（进游戏后）。
- **进程 PID 变化后要重新转发**；多个旧转发会互相冲突，先 `hdc fport rm tcp:9222 <旧目标>`。
- 可用 DevTools 协议 `Runtime.evaluate` 在页面里执行 JS（例如模拟导出 blob 下载）。
- 参考脚本：`/tmp/opencode/eval.js`、`prof.js`、`prof2.js`（本机临时目录，非仓库文件）。
