# AGENTS.md

本仓库是 **HarmonyOS NEXT 原生 HAP 项目**（ArkTS）。本文件记录开发/编译/真机验证的操作步骤与约定，供后续 AI 或开发者直接照做。

> 技术方案见 `方案.md`，项目说明见 `README.md`。

---

## 1. 环境（本机已装）

| 项 | 值 |
| --- | --- |
| DevEco Studio | `/Applications/DevEco-Studio.app`（版本 26.0.0） |
| SDK | `/Applications/DevEco-Studio.app/Contents/sdk`（**API 26**，HarmonyOS 26.0.0） |
| hvigor | `/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw` |
| hdc | `/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc` |
| node / ohpm | `/Applications/DevEco-Studio.app/Contents/tools/{node,ohpm}/bin` |
| 真机 | HUAWEI Pura 70 Pro+，HarmonyOS **6.1.1 / API 24** |

> ⚠️ SDK 是 API 26，但设备是 API 24，所以 `build-profile.json5` 里
> `compatibleSdkVersion` / `targetSdkVersion` 必须是 `"6.1.1(24)"`（不是 `26.0.0`，也不是 `5.0.0(12)`）。

---

## 2. 编译（命令行，推荐）

```bash
export DEVECO_SDK_HOME=/Applications/DevEco-Studio.app/Contents/sdk
export PATH="/Applications/DevEco-Studio.app/Contents/tools/node/bin:/Applications/DevEco-Studio.app/Contents/tools/ohpm/bin:$PATH"

/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw \
  assembleHap --mode module -p product=default -p buildMode=debug --no-daemon
```

- 产物：`entry/build/default/outputs/default/entry-default-signed.hap`
- 只看错误：`... | rg -i "ERROR|Error Message|BUILD SUCCESS|BUILD FAILED"`
- 若报 `compatibleSdkVersion` 不匹配 → 见上方 ⚠️。
- ArkTS 严格模式：不要用 `any`；`fileIo` 没有 `writeFileSync`（用 `openSync`+`writeSync`+`closeSync`）；`getHostContext()` 返回 `Context | undefined` 需判空。
- **每次改完代码都必须走完「编译 → 安装 → 重启 → 截图观察」闭环**（见 §3）。只 `assembleHap` 不安装，设备上跑的仍是旧版本，会误判「改动没生效」。

## 3. 安装 / 启动 / 验证（每次必做）

> 编译通过 ≠ 改动生效。**每次改完代码都要按下面 4 步装到真机并截图确认**，不要只编译就结束。

```bash
HDC=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc
BUNDLE=com.lichtcui.pokerogue

# 1) 编译（见 §2）→ 2) 覆盖安装
"$HDC" install -r entry/build/default/outputs/default/entry-default-signed.hap
# 3) 干净重启
"$HDC" shell aa force-stop $BUNDLE
"$HDC" shell aa start -a EntryAbility -b $BUNDLE
# 4) 截图观察（见 §4 截图命令），确认改动已生效
```

- 安装成功后 App 名 **PokeRogue**，bundle = `com.lichtcui.pokerogue`。
- 签名：DevEco 已配置自动签名，`build-profile.json5` 里的 `signingConfigs` **属于本地敏感信息，不要提交**（仓库里的版本是 `signingConfigs: []`）。若本地缺失，用 DevEco `File > Project Structure > Signing Configs` 勾选自动签名重新生成。

## 4. 真机交互（无 GUI 时用）

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

## 5. 日志

```bash
# 流式抓日志（要后台起、抓完 kill；不要用 -x，-x 是"打印完缓冲区就退出"）
"$HDC" shell hilog > /tmp/log.txt 2>&1 &
HPID=$!; sleep 1; ...触发操作...; kill $HPID

# 抓完过滤（本 App 的 tag 形如 A00000/<Tag>）
rg -i "GameRepository|GamePage|Home|Settings|LocalHttp|Notifier" /tmp/log.txt
```

- 本 App 代码里的 `hilog.info(0x0000, TAG, ...)` 会以 `A00000/<TAG>` 出现。
- 游戏内 `console.log` 会以 `ARKWEB-CONSOLE` 出现（排查游戏侧问题时很有用）。

## 6. 调试游戏 WebView（DevTools）

ArkWeb 用 **domain socket**（不是 tcp），端口转发方式：

```bash
HDC=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc
PID=$("$HDC" shell pidof com.lichtcui.pokerogue | tr -d '\r' | awk '{print $1}')
"$HDC" fport rm tcp:9222 tcp:9222 >/dev/null 2>&1
"$HDC" fport tcp:9222 localabstract:webview_devtools_remote_$PID
curl -s http://127.0.0.1:9222/json/list
```

- 前提：App 里 `setWebDebuggingAccess(true)`（已由 `DEBUG_UI` 控制，见 `EntryAbility`），且当前已加载 Web 页（进游戏后）。
- **进程 PID 变化后要重新转发**；多个旧转发会互相冲突，先 `hdc fport rm tcp:9222 <旧目标>`。
- 可用 DevTools 协议 `Runtime.evaluate` 在页面里执行 JS（例如模拟导出 blob 下载）。
- 参考脚本：`/tmp/opencode/eval.js`、`prof.js`、`prof2.js`（本机临时目录，非仓库文件）。

## 7. 架构与关键文件

```
entry/src/main/ets/
├─ entryability/EntryAbility.ets   # 启动本地 HTTP 服务、窗口设置、DEBUG_UI 时开 Web 调试
├─ common/
│  ├─ Const.ets                    # 常量（URL/镜像/DEBUG_UI/FORCE_CANVAS 等）
│  └─ Mime.ets                     # 扩展名 → MIME
├─ components/PokeballLoader.ets   # 精灵球动画（shake=下载/解压摇晃；open=逐帧打开，暂未使用）
├─ model/
│  ├─ GameRepository.ets           # 下载(request.agent+镜像) / 解压(zlib) / 删除 / 版本 / 接管
│  ├─ LocalHttpServer.ets          # 本地 HTTP 服务(127.0.0.1:18787) + index.html 注入
│  ├─ LocalContentProvider.ets     # 旧的 onInterceptRequest 方案（已不用，保留参考）
│  ├─ Notifier.ets                 # 通知权限 + 完成/失败通知
│  └─ WindowHolder.ets             # 主窗口引用 + setWindowBackgroundColor
└─ pages/
   ├─ Index.ets                  # 首页启动器（状态机 + 下载 UI；就绪页极简：精灵球 + 开始游戏 + 齿轮）
   ├─ Settings.ets               # 设置页（游戏选项占位 / 更新·缓存·删除 / 关于）
   └─ GamePage.ets               # 离线游戏页（Web + 下载代理 + 音频静音）
```

### 运行原理（重要）

1. App 启动时在 `EntryAbility` 里拉起 **本地 HTTP 服务**（`LocalHttpServer`，`http://127.0.0.1:18787`），把沙箱 `<filesDir>/game` 映射出去。
2. 首次进入：从 GitHub Release 下载 `game.zip`（`request.agent`，带镜像加速与测速选源），用 `zlib.decompressFile` 解压到 `<filesDir>/game`。
3. `GamePage` 用 `Web` 组件加载 `http://127.0.0.1:18787/index.html`，游戏资源全部由本地服务器提供。
4. **不要再用 `onInterceptRequest`**：实测每个请求约 55ms（主线程 IPC），改为本地 HTTP 服务后走真实 HTTP + 缓存。
5. `index.html` 由 `LocalHttpServer.servePatchedIndex` 注入一小段脚本：强制 2D canvas `willReadFrequently:true`，消除 ArkWeb `getImageData` 的 GPU 读回开销（启动 28s → 4s）。

### 关键行为

- **存档**：游戏 `localStorage`（`domStorageAccess(true)`，origin 固定为 `127.0.0.1:18787`，**改端口会丢档**）。
- **导出存档**：游戏用 blob URL + `<a download>`；由 `GamePage` 的 `WebDownloadDelegate` 捕获，`DocumentViewPicker` 保存到 `下载/com.lichtcui.pokerogue/`。**必须在 `onControllerAttached` 里注册**（在 `aboutToAppear` 注册会报 17100001）。
- **后台静音**：`onPageHide` → `controller.setAudioMuted(true)`；`onPageShow` → `false`。
- **窗口**：保留顶部状态栏、隐藏底部导航栏、`setWindowLayoutFullScreen(false)`（安全区避让），底部留白用 `setWindowBackgroundColor` 染成游戏色 `#484050`。
- **设置页与更新**：首页就绪页右上角齿轮 → `pages/Settings.ets`。设置页「更新」置 `AppStorage.setOrCreate('pendingUpdate', true)` 后 `router.back()`，由 `Index.onPageShow` 消费并调 `startDownload()`（复用首页下载/解压 UI）；删除数据后返回首页，`onPageShow` 重新判定并回落未下载态。

## 8. 约定

- **不要提交** `build-profile.json5` 的签名信息、`signature/`、`local.properties`、`.idea/`、`.hvigor/`、`build/`。
- `build-profile.json5` 已设置 **`skip-worktree`**（`git ls-files -v` 显示 `S`）：
  - 仓库里保存的是**干净版**（`signingConfigs: []`）；本地 DevEco 写入的签名改动被 git 忽略，`git status` 不会显示它 —— 这是正常的。
  - 若确实要修改该文件（如改 `compatibleSdkVersion`）：
    ```bash
    git update-index --no-skip-worktree build-profile.json5   # 先解除
    # ... 修改并 commit ...
    git update-index --skip-worktree build-profile.json5      # 再恢复
    ```
- 临时调试开关：`common/Const.ets` 的 `DEBUG_UI`（开发显示镜像源/开 Web 调试）、`FORCE_CANVAS`（实验，默认 false）。
- 改 UI 文案/布局主要在 `pages/Index.ets` 与 `pages/Settings.ets` 的 `@Builder`/`build()`。
- **ArkUI 坑**：`@Builder` 的**值参数**不会触发重渲染，动态文案要直接在 `build()` 里读 `@State`（见 `Settings.ets` 的「检查更新」「删除本地数据」行）。
- 新增静态资源放 `entry/src/main/resources/base/media/`，引用 `$r('app.media.xxx')`。
- 提交前先 `hvigorw assembleHap` 确认编译通过，再真机验证。

## 9. 常见问题

| 现象 | 处理 |
| --- | --- |
| `install failed due to older sdk version` | `compatibleSdkVersion` 要改成设备的 `"6.1.1(24)"` |
| `17100001 Init error` | 调用了需要 Web 组件已挂载的 API（如 `setDownloadDelegate`/`setAudioMuted`），移到 `onControllerAttached` |
| 启动白屏/极慢 | 检查 index.html 注入是否生效（`willReadFrequently`） |
| 下载通知堆积/孤儿任务 | 首页启动时会清理；必要时卸载重装 |
| `@Builder` 里的动态文字不刷新 | `@Builder` 值参数不触发重渲染；动态文案直接在 `build()` 里读 `@State`（`Settings.ets` 检查更新/删除行） |
| DevTools 连不上 | 确认已进游戏页、PID 正确、先删旧 fport 再转发 |
