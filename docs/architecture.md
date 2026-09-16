# 架构与关键文件

> 操作手册见 [`../AGENTS.md`](../AGENTS.md)。

## 目录结构

```
entry/src/main/ets/
├─ entryability/EntryAbility.ets   # 启动本地 HTTP 服务、窗口设置、DEBUG_UI 时开 Web 调试
├─ common/
│  ├─ Const.ets                    # 常量（URL/镜像/DEBUG_UI/FORCE_WILL_READ_FREQUENTLY 等）
│  └─ Mime.ets                     # 扩展名 → MIME
├─ components/PokeballLoader.ets   # 精灵球摇晃动画（下载/解压中）
├─ model/
│  ├─ GameRepository.ets           # 下载(request.agent+镜像) / 解压(zlib) / 删除 / 版本 / 接管
│  ├─ AppUpdate.ets                # 应用自更新：本地版本读取 + version.json 比对 + 引导下载（见 app-update.md）
│  ├─ LocalHttpServer.ets          # 本地 HTTP 服务(127.0.0.1:18787 固定端口) + index.html 注入 + /__cheats__.js
│  ├─ Notifier.ets                 # 通知权限 + 完成/失败通知
│  ├─ WindowHolder.ets             # 主窗口引用 + setWindowBackgroundColor + setOrientation
│  ├─ OrientationPref.ets          # 屏幕方向偏好（竖屏/横屏/跟随系统）读写
│  ├─ Cheats.ets                   # 作弊注册表（条目内容，新增作弊改这里）
│  └─ CheatState.ets               # 作弊状态单例 + 持久化 + 生成注入脚本 buildBootScript()
└─ pages/
   ├─ Index.ets                  # 首页启动器（状态机 + 下载 UI；就绪页：精灵球 + 开始游戏 + 作弊设置 + 齿轮）
   ├─ Settings.ets               # 设置页（屏幕方向 / 作弊模式开关 / 更新·缓存·删除 / 关于）
   ├─ Cheats.ets                 # 作弊条目页（从首页「作弊设置」进入）
   └─ GamePage.ets               # 离线游戏页（Web + 下载代理 + 音频静音 + 方向应用）
```

## 运行原理（重要）

1. App 启动时在 `EntryAbility` 里拉起 **本地 HTTP 服务**（`LocalHttpServer`，`http://127.0.0.1:18787`），把沙箱 `<filesDir>/game` 映射出去。
2. 首次进入：从 GitHub Release 下载 `game.zip`（`request.agent`，带镜像加速与测速选源），用 `zlib.decompressFile` 解压到 `<filesDir>/game`。
3. `GamePage` 用 `Web` 组件加载 `http://127.0.0.1:18787/index.html`，游戏资源全部由本地服务器提供。
4. **不要再用 `onInterceptRequest`**：实测每个请求约 55ms（主线程 IPC），改为本地 HTTP 服务后走真实 HTTP + 缓存。
5. `index.html` 由 `LocalHttpServer.servePatchedIndex` 注入：`<script src="/__cheats__.js">`（作弊注入）+ 强制 2D canvas `willReadFrequently:true`（消除 ArkWeb `getImageData` 的 GPU 读回开销，启动 28s → 4s）。
6. `/__cheats__.js` 是**虚拟端点**：请求时按当前启用的作弊清单动态生成组合脚本，在游戏脚本前执行。详见 [`cheats.md`](./cheats.md)。
7. 屏幕方向由 `OrientationPref` 持久化：首页/设置页始终竖屏（`Index.onPageShow` 强制 `PORTRAIT`），只有 `GamePage.onPageShow` 应用用户选择（横屏 `AUTO_ROTATION_LANDSCAPE` / 跟随系统 `UNSPECIFIED`）。

## 关键行为

- **存档**：游戏 `localStorage`（`domStorageAccess(true)`，origin 固定为 `127.0.0.1:18787`，**改端口会丢档**）。
- **导出存档**：游戏用 blob URL + `<a download>`；由 `GamePage` 的 `WebDownloadDelegate` 捕获，`DocumentViewPicker` 保存到 `下载/com.lichtcui.pokerogue/`。**必须在 `onControllerAttached` 里注册**（在 `aboutToAppear` 注册会报 17100001）。
- **后台静音**：`onPageHide` → `controller.setAudioMuted(true)`；`onPageShow` → `false`。
- **窗口**：保留顶部状态栏、隐藏底部导航栏、`setWindowLayoutFullScreen(false)`（安全区避让），底部留白用 `setWindowBackgroundColor` 染成游戏色 `#484050`。
- **返回按钮**：游戏页右上角「返回」全显 3 秒后自动淡出（`GamePage.resetBackTimer`），避免遮挡游戏信息；点击淡出的按钮可唤回、再点才返回；系统返回手势（`onBackPress`）作为兜底。
- **设置页与更新**：首页就绪页右上角齿轮 → `pages/Settings.ets`。设置页「更新」置 `AppStorage.setOrCreate('pendingUpdate', true)` 后 `router.back()`，由 `Index.onPageShow` 消费并调 `startDownload()`（复用首页下载/解压 UI）；删除数据后返回首页，`onPageShow` 重新判定并回落未下载态。
- **作弊**：设置页开「作弊模式」（需确认账号风险 + 兼容性风险）→ 首页就绪页出现「作弊设置」入口 → 进 `pages/Cheats` 选条目 → 进入游戏页时 `/__cheats__.js` 按启用清单注入，**注入后在游戏内实时生效**（无需像旧方案那样存档 + 读档）。**更改配置后需重新进入游戏页**（返回首页再进，或重启 App）才会重新注入。新增条目见 [`cheats.md`](./cheats.md)。
