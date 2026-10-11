# 安卓版方案（PokeLauncher → Android）

> 决策与分期计划。实施完成后本文件即成为安卓模块的专题文档（对齐 [`architecture.md`](./architecture.md) 的角色）。
>
> 现状：**P0（打靶）+ P1（下载/解压链路）+ P2（服务与注入链）代码已完成，本机编译/单测/R8/lint 均已验证；三者都还没有真机数据**，代码在 [`../android`](../android)。

## 1. 为什么这件事风险低

本 App 的本质是「**壳 + 本地 HTTP 服务器 + WebView**」：把游戏资源在运行时下载到沙箱，用本地 HTTP 服务以固定 origin 提供给 WebView，从而离线游玩并保住 `localStorage` 存档。这套架构在 Android 上是**原生主场**，而且已有先例验证过可行性：

| 参考实现 | 许可 | 做法 | 我们怎么用 |
| --- | --- | --- | --- |
| [`Admiral-Billy/PokeRogue-App-Android`](https://github.com/Admiral-Billy/PokeRogue-App-Android) | **MIT** | Kotlin 单 Activity + NanoHTTPD + WebView；从**同一个** GitHub Release 下 `game.zip` 解压到 `getExternalFilesDir()/game`；blob→base64 桥导出存档；`onShowFileChooser` 导入 | ✅ 可借鉴（MIT，保留版权声明）。它证明了「NanoHTTPD + WebView 能跑通」 |
| [`VdustR/pokerogue-offline`](https://github.com/VdustR/pokerogue-offline) | **AGPL-3.0** | assets 内置整包 + `OfflineAssetWebViewClient` | ❌ **不要抄代码**（本项目 MIT，会被 AGPL 传染） |

> 巧合也是红利：本项目下载 `game.zip` 的来源 `Admiral-Billy/pokerogue`，正是那个 MIT 安卓版的作者仓库（`game.zip` 当前 **507 MB**，下载量 1.7 万+）。

**结论：安卓版在若干方面比鸿蒙版更容易，不是「对等移植」而是「升级移植」**

| 能力 | 鸿蒙现状 | Android |
| --- | --- | --- |
| 本地 HTTP 服务 | 手写 TCPSocketServer（583 行） | NanoHTTPD（BSD-3），已有先例 |
| 下载 | `request.agent`（进度事件不可靠，靠 1s 轮询兜底） | OkHttp + Range，断点续传完全可控 |
| 应用自更新 | ❌ 只能「检测 + 引导浏览器打开下载页」 | ✅ 真·应用内下载 APK 并调起安装 |
| 后台静音 | `controller.setAudioMuted` | `WebViewCompat.setAudioMuted`（`MUTE_AUDIO` feature）+ Phaser JS 兜底 |
| 存档易失性 | ⚠️ localStorage 在 `cache/web/`，会被「清除缓存」清掉 → 必须 SaveGuard | ✅ WebView localStorage 在 app `data/`（不在 `cache/`），系统「清除缓存」不动它；还能挂 Auto Backup |
| 写公共目录 | DocumentViewPicker | `MediaStore.Downloads`（API 29+ 零权限） |
| 全文件权限 | 无此概念 | ✅ **不需要** `MANAGE_EXTERNAL_STORAGE`（先例申请了，Play 会拒，我们不学） |

## 2. 决策记录（已定）

| 项 | 决策 | 理由 |
| --- | --- | --- |
| 仓库形态 | 本仓库加 **`android/` 子目录**（monorepo） | 共用 `docs/`、共用作弊注册表、共用 issue 与版本节奏 |
| 分发渠道 | **GitHub/Gitee Release 直发 APK** | 功能全保留（含「参数调整」+ 应用内自更新），与鸿蒙版定位一致 |
| UI 技术 | **Jetpack Compose** | 4 页 + 下载状态流 + 精灵球动画，状态管理最省事 |
| 首版范围 | 先做 **P0 打靶验证** | 先证伪风险（性能 / 兼容性），再投全部工时 |

## 3. 架构映射（HarmonyOS → Android）

### 3.1 原样复用（纯 JS 字符串 / 纯算法，几乎零改动）

这是本次移植最大的红利——**最有价值的部分不需要重写**：

- [`Cheats.ets`](../entry/src/main/ets/model/Cheats.ets) 的 `CHEATS` 注册表与 7 条 `script` → [`cheat/Cheats.kt`](../android/app/src/main/java/com/lichtcui/pokelauncher/cheat/Cheats.kt)（**JS 逐字保真**，用脚本比对过每一行）
- [`CheatState.ets`](../entry/src/main/ets/model/CheatState.ets) 的 `SCENE_RUNTIME`（场景捕获：`__onScene` / `__onPhase` / `Array.prototype.push` hook / `Phaser.Game` 包装）+ `buildBootScript()` 拼接逻辑 → `cheat/CheatScripts.kt`；纯编解码 → `cheat/CheatFormat.kt`
- `FORCE_WILL_READ_FREQUENTLY` 的 2D canvas 补丁（**Chromium 通病，Android WebView 同样需要**，启动 28s → 4s 的收益照样拿）→ `server/InjectScripts.kt`
- `SaveGuard` 的 JS 片段（保留；Android 侧它不再是「抢救存档」，而是 Auto Backup / 导出兜底，见 §4.3.2）→ `web/SaveGuard.kt`
- `resolveRequestPath()` 路径穿越判定、镜像列表、测速策略
- 原子安装状态机（`game.new` → `.extract-complete` 标记 → 两次 rename → `recover()`）
- `Format.ets` 三个格式化函数（改为 Kotlin JVM 单测）

**唯一一处刻意偏离**：`window.Phaser` 属性描述符的归属。鸿蒙版由「参数调整」脚本持有，Android 侧改由 `/__pk_boot.js` 独占（否则两个脚本各 `defineProperty` 一次，后者覆盖前者，必有一方的 setter 永不触发），详见 §4.3.2 第 1 条。

### 3.2 需要重写（平台 API 层）

| HarmonyOS / ArkTS | Android / Kotlin |
| --- | --- |
| `EntryAbility` | `Application` + 单 `MainActivity` |
| `LocalHttpServer`（TCPSocketServer，手写 HTTP/1.1） | `NanoHTTPD` 2.3.1 实例 ×2：`127.0.0.1:18787`（资源）+ `:8001`（游戏内 API stub） |
| `request.agent` 后台下载 | OkHttp 4.12 + Range（`.part` 续传）+ 前台服务（`dataSync`）（⚠️ Android **没有** App 被杀仍继续下载的等价物，见 §4.3） |
| `zlib.decompressFile` | `java.util.zip.ZipFile` |
| `preferences` | **SharedPreferences**（库名同为 `pokelauncher_settings`）（不采用原先写的 DataStore，理由见 §4.3.2 第 10 条） |
| `@ohos.router` + `pages/` | Compose Navigation |
| `Web` 组件 | `android.webkit.WebView` |
| `javaScriptProxy` | `addJavascriptInterface` |
| `WebDownloadDelegate` | `DownloadListener` + **blob→base64 JS 桥**（Android 不触发 `blob:` 下载，必须做桥） |
| `DocumentViewPicker`（保存） | `MediaStore.Downloads`（API 29+ 零权限） |
| `DocumentViewPicker`（选 zip） | SAF `ACTION_OPEN_DOCUMENT` |
| 游戏内 `<input type=file>` 导入存档 | `WebChromeClient.onShowFileChooser` |
| `hilog` | `Log`（tag 统一 `PokeLauncher`，见 [`device-cli.md`](./device-cli.md) 的 tag 约定） |
| `WindowHolder.setOrientation` | `Activity.requestedOrientation` |
| `apiAtLeast()` | `Build.VERSION.SDK_INT` |
| `filesDir` | `getExternalFilesDir(null)`（507MB zip + ~1.5GB 解压，内部存储会爆） |

### 3.3 目录规划

```
android/
├─ app/src/main/java/com/lichtcui/pokelauncher/
│  ├─ core/       Const / Paths / Format / Version / Prefs —— 无 Android 依赖，可 JVM 单测
│  ├─ server/     GameServer(NanoHTTPD×2) / InjectScripts(canvas 补丁 + Phaser 包装) / ServerDiag(端口占用诊断)
│  ├─ cheat/      Cheats(注册表·JS 逐字保真) / CheatFormat(纯编解码) / CheatState(状态+持久化) / CheatScripts(生成 /__cheats__.js)
│  ├─ install/    GameRepository(Release 查询·镜像测速·断点续传) / GameInstaller(原子解压切换·recover) / DownloadService(前台服务)
│  ├─ web/        PokeWebView(JS 桥) / SaveGuard(存档镜像·JS 桥) / WebCache(clearCache 时机)
│  └─ ui/         Compose：首页四态 / 设置 / 参数调整 / 法律 / 游戏页（目前只有打靶页）
└─ app/src/test/  JVM 纯函数单测（core / install / cheat / server / web）
```

`core/` 不引 Android 依赖，未来要复用到 iOS/桌面时可直接搬。

> 注：这是**目标结构**。P0 已落地的文件与实际差异见 §4.1。

## 4. P0 打靶验证（判据先行）

**目标：在不写 UI 的前提下，证明「Android WebView 能离线跑这个游戏，且性能可接受」。若不过关，后面全部白干。**

| # | 验证项 | 判据 | 怎么测 |
| --- | --- | --- | --- |
| 1 | NanoHTTPD + WebView 能加载游戏 | 游戏进入主菜单，无白屏 | 导入 `game.zip` → 启动服务 → 进游戏页 |
| 2 | canvas 补丁收益真实存在 | 注入 `willReadFrequently` 前后，WebView 首帧耗时**显著下降** | 应用内计时埋点：`loadUrl` → 游戏 `create()` 完成 |
| 3 | 启动耗时可接受 | 与鸿蒙实测（补丁后 4s 级）同量级 | 同上，多跑 3 次取中位数 |
| 4 | WebView 版本底线 | 记录 `WebViewCompat.getCurrentWebViewPackage()` 的 Chromium 版本；确认真机 ≥ 100 | 应用内直接显示 |
| 5 | cleartext 放行生效 | `http://127.0.0.1:18787/index.html` 能加载，无 `ERR_CLEARTEXT_NOT_PERMITTED` | network security config 只白名单 localhost |
| 6 | 存档落点正确 | 游戏内存档 → 重启 App 仍在；点系统「清除缓存」后仍在 | 手动验证 |
| 7 | 音频 | BGM 能自动播放（`mediaPlaybackRequiresUserGesture=false` 生效） | 听 |

### 4.1 已落地代码（P0–P2，可编译可装机）

```
android/                                   Gradle 8.14.3 / AGP 8.11.0 / Kotlin 2.0.21 / Compose BOM 2024.12.01
├─ gradlew, gradle/wrapper/                wrapper（已生成，distributionUrl = gradle-8.14.3-bin）
├─ app/build.gradle.kts                    applicationId=com.lichtcui.pokelauncher, minSdk 26, target/compile 35
├─ app/proguard-rules.pro                  keep InjectScripts / Const / @JavascriptInterface / NanoHTTPD
├─ app/src/main/java/.../
│  ├─ App.kt                               Application：启动日志 + 仅 debug 开远程调试 + 偏好/参数调整状态/存档目录初始化
│  ├─ MainActivity.kt                      唯一 Activity（singleTask + configChanges 不重建）
│  ├─ core/ Const / Paths / Mime / Format  无 Android 依赖，可 JVM 单测
│  ├─ core/ Version.kt                     release-<commit> 归一化（与鸿蒙 normalizeVersion 一致）
│  ├─ core/ Prefs.kt                       SharedPreferences 封装（库名 pokelauncher_settings）
│  ├─ server/ GameServer.kt                NanoHTTPD×2（18787 资源 / 8001 API stub）+ /__cheats__.js 端点 + 端口重试
│  ├─ server/ InjectScripts.kt             注入链：存档守卫 + 参数调整 + 引导脚本（canvas 补丁/计时/Phaser 包装）
│  ├─ server/ ServerDiag.kt                启动失败诊断（端口占用判定 + 中文提示，纯函数）
│  ├─ install/ GameInstaller.kt            原子解压切换 + recover() + readVersion() + 存储预检
│  ├─ install/ GameRepository.kt           Release 查询 / 镜像并发测速 / Range 续传 / 交棒解压
│  ├─ install/ ReleaseInfo.kt              Release JSON 解析（字段名都是字符串字面量，R8 安全）
│  ├─ install/ DownloadUrls.kt             候选地址构造 + 测速排序（纯函数）
│  ├─ install/ DownloadState.kt            `.download.json` 状态 + ResumePolicy + RangePolicy（纯函数）
│  ├─ install/ Storage.kt                  3.2× 空间预检（纯函数）
│  ├─ install/ DownloadStatus.kt           状态快照 / DownloadBus / ProgressMeter / 通知文案（纯逻辑）
│  ├─ install/ DownloadService.kt          前台服务：通知进度 + 暂停/继续/取消 action + onTimeout
│  ├─ cheat/ Cheats.kt                     7 条参数调整注册表（JS 逐字保真自 Cheats.ets）
│  ├─ cheat/ CheatFormat.kt                ids/params 编解码 + 夹紧 + JS 数字字面量（纯函数）
│  ├─ cheat/ CheatState.kt                 总开关/启用项/参数 + SharedPreferences 持久化
│  ├─ cheat/ CheatScripts.kt               SCENE_RUNTIME + buildBootScript()（/__cheats__.js 的内容）
│  ├─ web/ PokeWebView.kt                  WebView 配置 + PokeNative JS 桥 + PokeSaveGuard 存档桥
│  ├─ web/ SaveGuard.kt                    存档镜像（filesDir/saves/localstorage.json + .bak）+ 注入脚本
│  ├─ web/ WebCache.kt                     clearCache 时机（活实例优先，否则临时实例；仅主线程）
│  └─ ui/ Theme / SpikeController / SpikeScreen   打靶页（= P0–P2 的全部 UI）
├─ app/src/test/java/.../                  **180 个 JVM 单测**（core 22：Paths 7 / Format 6 / Mime 5 / Version 4；
│                                          install 49：DownloadUrls 11 / DownloadState 16 / DownloadStatus 16 / ReleaseInfo 6；
│                                          cheat 68：CheatFormat 27 / CheatScripts 14 / CheatState 13 / Cheats 14；
│                                          server 19：InjectScripts 9 / ServerDiag 10；web 22：SaveGuard 22）
└─ app/src/main/res/                       图标（品牌红环）/ 通知图标 ic_download / 主题 / network_security_config
```

已实测通过（本机，**尚无真机**）：

| 检查 | 结果 |
| --- | --- |
| `./gradlew assembleDebug` | ✅ `app-debug.apk` 9.8 MB |
| `./gradlew assembleRelease`（R8 + 资源收缩） | ✅ `app-release-unsigned.apk` 1.1 MB（含 OkHttp/Okio） |
| `./gradlew testDebugUnitTest` | ✅ **180 tests, 0 failures** |
| `./gradlew :app:lintDebug` | ✅ **0 errors**（24 warnings / 6 hints，均为依赖版本提示类，不阻塞） |
| release 包 JS 桥是否存活 | ✅ 反编译 dex：`PokeNative` / `reportTiming` / `reportState` / **`PokeSaveGuard`** / **`load` / `save` / `notifyRestored`** 均在（`o1.g.load`：类名可混淆，方法名由 `-keepclassmembers @JavascriptInterface` 保住） |
| release 包新链路常量是否存活 | ✅ dex 里仍有 `tag_name` / `browser_download_url` / `receivedBytes` / `bytes=0-` / `If-Range` / `Content-Range` / `game.zip.download.json` / `pokerogue-download` |
| release 包注入链常量是否存活 | ✅ dex 里仍有 `__cheats__.js` ×4 / `__pk_boot.js` ×2 / `window.__pkGameHooks` ×3 / 7 条脚本标记（`__cheatCandy/Exp/Money/Luck/Catch/FreeEgg/Reward`）/ `cheatMaster`·`cheatEnabledIds`·`cheatParams` / `localstorage.json`·`localstorage.bak.json` / `SAVEGUARD` ×5 |
| manifest 基线 | ✅ minSdk 26 / targetSdk 35 / INTERNET + ACCESS_NETWORK_STATE + FOREGROUND_SERVICE(+DATA_SYNC) + POST_NOTIFICATIONS + WAKE_LOCK / cleartext 全局 false + 回环白名单 |
| 前台服务声明 | ✅ release 合并后 manifest 里 `com.lichtcui.pokelauncher.install.DownloadService` 未被混淆，`foregroundServiceType=0x1`（dataSync）、`exported=false`、`stopWithTask=false` |

### 4.2 怎么编译 / 怎么跑

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export ANDROID_HOME=/usr/local/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$PATH"
cd android
./gradlew assembleDebug           # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # JVM 纯函数单测（core / install / cheat / server / web）
./gradlew :app:lintDebug           # 必须 0 error（提交前跑）
```

上真机（**必须**：模拟器/真机的 WebView 版本差异极大）：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 507MB 资源包走 SAF 选择器很慢，开发期直接推进沙箱（免权限）
adb push game.zip /sdcard/Android/data/com.lichtcui.pokelauncher/files/game.zip
# 或者：adb push game.zip /sdcard/Download/game.zip，再用打靶页的「选择 zip」
adb logcat -s PokeLauncher          # 全部日志统一 tag
```

打靶页流程：`安装本地 game.zip` → 等「已安装 = 是」 → `进入游戏` → 读悬浮面板计时 → `退出` → 关掉 canvas 补丁开关 → `重载`（**会重建 WebView**，复现真正的冷启动）→ 再读一次 → 两次数据对比。

**P1 下载链路（打靶页「资源下载（P1）」卡片）**

`查询最新版本`（显示 tag / 大小）→ `开始下载` → 存储预检（3.2×）→ 并发测速（每源读 128KB、4s 硬上限）→ 按速度依次尝试 → Range 续传 → 解压 → 原子切换 → 通知「已完成」。

- 通知里带 `暂停` / `继续` / `取消`；App 退到后台、从最近任务划掉都会继续（`stopWithTask=false`）。
- 进程被系统杀掉后分片仍在：回到 App 卡片会提示「发现未完成的下载……点『继续下载』从断点续传」。
- `继续下载` 保留分片；`换源重试` 才会丢弃分片从头下（首次失败也会保留分片，所以优先点「继续下载」）。
- 只想跑通全链路时：把 `Const.kt` 的 `DEBUG_ASSET_URL` 指向一个小测试包 URL（跳过 Release 查询与镜像测速），几秒即可跑完。
- 日志关键字（`adb logcat -s PokeLauncher`）：`probe <url> speed=… KB/ms`、`resume from N bytes`、`服务端忽略 Range`、`downloaded N bytes from …`、`资源已安装，删除 zip 释放空间`。

**P2 注入链（自动生效；参数调整页留在 P3，现在只能通过偏好/开关验证）**

`/index.html` 每次请求都动态拼注入链（顺序：**存档守卫 → 参数调整 → 引导脚本**），所以改完状态只需重载游戏页，不必重装资源：

- 存档守卫（`web/SaveGuard.kt` → `filesDir/saves/localstorage.json`）：`localStorage` 整体为空时用快照回填；写盘去重 + `.bak` 轮转。
- 参数调整（`/__cheats__.js`，与鸿蒙版同名端点）：只有 `CheatState.hasAnyEnabled()`（总开关 && 至少一条启用）为真才注入标签；脚本里参数按 `min/max` 夹紧。
- 引导脚本（`/__pk_boot.js`）：canvas 补丁 + 计时埋点 + `window.Phaser` 包装（唯一所有者），并把 Game 实例派发给 `window.__pkGameHooks` 上的场景捕获回调。
- 日志关键字：`served patched index.html (canvasPatch=…, cheats=…, saveGuard=…)`、`served /__cheats__.js (N bytes)`、`已清除 WebView 缓存`、`SAVEGUARD restored N keys`、`TIMING {…}`。

### 4.3 与上面计划的差异（实现期决定）

1. **NanoHTTPD 换成有界线程池**：默认 `DefaultAsyncRunner` 是「每连接一个线程」且无上限，而游戏冷启动会并发拉数百个资源。改用 16 槽位的 `PooledAsyncRunner`。
2. **没做 gzip 响应**：P0 要与鸿蒙基线做**公平** A/B，压缩会掩盖 canvas 补丁的真实收益；P5 再按需加。
3. **A/B 开关做进了 UI**：`canvas=0/1` 由查询参数传给 `/__pk_boot.js`，服务端按请求生成脚本，所以切开关只需重载，不必重装资源。
4. **`gameSession` 计数器强制重建 WebView**：只调 `loadUrl` 复用同一 WebView 不算冷启动（canvas 补丁是一次性注入），A/B 必须重建。
5. **8001 stub 与 18787 同生共死**：stub 起不来只告警不拦（游戏只是多等一次超时）。
6. **打靶页没有 ViewModel**：状态放在 `SpikeController`，由 Activity 持有，避开 `viewModel()` 工厂/反射在 R8 下的坑；`configChanges` 已声明旋转不重建。
7. **P0 不做 `onShowFileChooser`**：游戏内存档导入用的文件选择器留到 P4。

### 4.3.1 P1（下载链路）实现期决定

1. **Android 没有 `request.agent` 的等价物**：鸿蒙靠系统后台任务，App 被杀仍继续、重启后 `adoptDownload()` 接管；Android 只能用「前台服务 + `.part` 分片 + `.download.json` 状态」。因此本地保留的能力是：**后台可继续、被杀后能续传（但需要用户回 App 点一下）**。不引入 WorkManager：它擅长的“时机协商/退避重试”对“用户正盯着的 507MB 下载”没意义，还多一层调度黑箱（也省一个依赖）。
2. **服务端忽略 Range 时必须从头重下**：拿到 200（而非 206）时把 `writeOffset` 归零并 `setLength(0)`。直接追加会拼出一个必然解压失败的 zip（这是这种下载器最常见的坑）。同时用 `If-Range: <etag>` 防“资源已换包但分片还是旧的”。
3. **只在下载失败时换源**：解压失败**不**去换源重下（等于白下整个 507MB 包），直接上抛给用户。上一个源失败时顺手 `invalidateRankCache()`（排序已不可信）。
4. **续传时把上次的源提到最前**：否则测速挑中另一个镜像，已下的 400MB 分片全部作废。
5. **直连兜底不参与测速**：它永远排最后（测速白读 128KB，且直连通常最慢）；但它是“保底”，不能被跳过；反之测速失败的镜像在有兜底时直接淘汰。
6. **测速用 `Range: bytes=0-131071` 而不是 HEAD**：大量镜像不返回 `content-length` 或不支持 HEAD，“真切读 128KB”才能反映真实吞吐。
7. **前台服务 = `dataSync` 类型**：Android 15（targetSdk 35）起 `dataSync` 在 24h 内累计只能跑 6h，到点系统调 `onTimeout`；我们覆写它（保存进度 → 发「回到 App 可继续」→ `stopSelf`），不硬撑也不让系统杀成 ANR。启动全部由用户操作触发（API 34+ 不允许后台自拉 `dataSync` 前台服务）。
8. **暂停 = 保留分片 + 保留通知**：暂停后仍留住前台服务与通知（带「继续」action），否则用户必须回 App 才能续传（体验基线参照系统下载器）。
9. **状态总线而不是 bind/回调**：`DownloadBus`（进程级 `StateFlow`）是唯一数据源，UI 与通知都读它；服务比 Activity 活得久（旋转、回首页不丢状态）。
10. **终态由服务发布**：仓库只回报 `DownloadOutcome`，`DONE/PAUSED/CANCELLED/FAILED` 的文案与通知由 `DownloadService` 统一写（避免两处各写一份阶段文案）。
11. **JSON 字段名全用字符串字面量**（`optString("tag_name")`）：不用反射式 JSON，所以 release 包不需要 `-keep-property-name`；`proguard-rules.pro` 里已把这个约定写死，别改成“属性名当 key”。
12. **`partial wake lock`（60 分钟上限）**：507MB 下载时屏幕熄灭、CPU 休眠会把 socket 卡住；带上限是为了万一逻辑有漏也不会变成“后台耗电”投诉。

### 4.3.2 P2（服务与注入链）实现期决定

1. **`window.Phaser` 只能有一个所有者**（本项目最重要的一个 JS 侧坑）：两个注入脚本各自 `Object.defineProperty(window,'Phaser',…)` 时，**后者覆盖前者**，于是总有一方的 setter 永不触发--症状是「计时埋点静默消失」或「场景捕获拿不到 Game 实例」。鸿蒙版只有 cheats 一家包装，Android 多了 P0 的引导脚本，因此定规：**包装归 `/__pk_boot.js`**，cheats 只把 `__watch` 压进 `window.__pkGameHooks`；引导脚本构造出 Game 实例后统一派发。`__pkGameHooks` 两边都用 `x = x || []` **复用**而非重定数组（否则先注册的回调会丢）。cheats 自己的 `Array.prototype.push` 路径保持不变（它本来就是主路径）。
2. **注入顺序固定且被单测锁住**：存档守卫（最早，必须在游戏读 localStorage 之前回填）→ 参数调整（要比游戏脚本早拿到钩子）→ 引导脚本（最后，持有 `Phaser` 属性描述符）。顺序错了不报错但静默失效，所以 `InjectScriptsTest` 断言三个标签的 `indexOf` 关系。
3. **两个虚拟端点都不带 query**（与鸿蒙一致），改为服务端 `Cache-Control: no-cache` 保证每次拿到新脚本；切 canvas 开关仍然只靠 `?canvas=0/1` 传给引导脚本。
4. **端口占用兜底 = 先 `stop()` → 等 150ms → 再试一次**：绝大多数情况是「自己的残留实例还占着端口」（重进游戏页/上次 stop 未回收）。**两次都失败才判 FAILED**，并把中文提示写进 `errorText`；**绝不做多端口回退**（见坑 #2）。
5. **`ServerDiag` 按「异常类型 + 文案关键词」判定端口占用**：鸿蒙能拿到 `err.code===98`，Android 只能靠 `BindException` / `EADDRINUSE` / `Address already in use` / `Could not bind` 这几种形态（NanoHTTPD 会给 `BindException`，部分 ROM 文案不同）。提示里必须写清「端口不能改，改了会让存档失效」。
6. **清缓存的时机有三个**：安装完成（下载链路 + 手动导入两条路径）、删除本地数据、**资源版本变更检测**。
7. **最后一条是 Android 独有的**：鸿蒙只在「解压完成」那一次清缓存，而 Android 的下载可以在前台服务里装完——那时游戏页可能从未打开（甚至 App 已退出），时机就错过了。因此把「已为其清过缓存的版本」记在偏好 `lastCachedGameVersion`，冷启动对一下，两种时机都能覆盖。资源包的 bundle 走 `max-age=31536000`，漏了就一定是「装完还是旧界面」。
8. **`clearCache` 的边界**：只调 `WebView.clearCache(true)`，**绝不** `clearStorage` / `WebStorage.deleteAllData()`（那会连存档一起干掉）；优先用当前活着的 WebView 实例，没有就临时造一个再 `destroy`（HTTP 缓存是全 App 共享的，哪个实例清都一样）；必须主线程，不在主线程就转投主线程（`WebCache.clear` 会记日志）。
9. **SaveGuard 在 Android 的定位与鸿蒙不同**：鸿蒙是因为 localStorage 落在 `cache/web/`（会被「清除缓存」清）而必须镜像；Android 的 localStorage 本来就在应用数据目录。这里保留它的目的是 **Auto Backup / 存档导出（P4）的兜底**，并且**只在 localStorage 整体为空时回填**，不覆盖玩家现有存档。落盘到 `filesDir/saves/`（内部存储，Auto Backup 默认覆盖；不用 external）。
10. **持久化用 SharedPreferences 而不是本文原先写的 DataStore**：服务端在第 N 个资源请求上需要**同步**读 `hasAnyEnabled()` 才能决定注入哪些标签；DataStore 只有异步 API，会逼出一层「内存快照 + 首帧竞态」的额外状态机。偏好项很少，同步读写足够，且零新依赖（工具链仍是 Kotlin 2.0.21）。
11. **写存档的策略**：去重（与 `diskContent` / `queuedContent` 比）+ 单线程串行 executor + 写前把主文件 `rename` 成 `.bak`。游戏内每 60s 无条件 flush 一次，没变化时不应产生任何 IO（`SaveGuardTest` 覆盖）。
12. **JS 逐字保真**：7 条脚本的 body/name/desc/param 用脚本逐行比对过（与 `Cheats.ets` 全等），只有 `SCENE_RUNTIME` 末尾按第 1 条改。单测额外锁住两条易错约束：**脚本里不得出现 `$`**（Kotlin raw string 会当模板插值）、**声明了 param 就必须真的读 `__CHEAT_PARAMS__.<id>`**（否则是假旋钮）。
13. **`PokeSaveGuard` 桥的混淆边界**：类名可以被 R8 改名（JS 侧用的是传给 `addJavascriptInterface` 的字符串），但方法名必须保留——靠既有的 `-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }` 规则；release dex 已验 `load` / `save` / `notifyRestored` 未被改名。

### 4.4 实测记录（待真机，空着等数据）

**P0（性能）**

| # | 项 | 鸿蒙基线 | Android 补丁关 | Android 补丁开 |
| --- | --- | --- | --- | --- |
| 2 / 3 | Game ready（`ready` 埋点） | 28s → 4s | | |
| 7 | `getImageData` 次数 | ~2 万 | | |
| 4 | Chromium 主版本 | ArkWeb 级 | | |

**P1（下载/解压）——待真机项，逐条取证**

| # | 项 | 判据（看日志/界面） | 结果 |
| --- | --- | --- | --- |
| P1-1 | 完整链路 | 一次点击 → 测速 → 下载 → 解压 → 「已安装=是」→ 能进游戏 | |
| P1-2 | 测速选源 | 日志有 `probe <url> speed=… KB/ms`，实际下载用的 host 是排序第一（界面「当前源」） | |
| P1-3 | 断点续传 | 下到 ~30% 时强杀进程 → 重开 App → 卡片提示未完成下载 → 点「继续下载」→ 日志 `resume from N bytes`，且**不重头**下 | |
| P1-4 | 忽略 Range 的兜底 | `DEBUG_ASSET_URL` 指向不支持 Range 的静态服务器 → 日志出现 `服务端忽略 Range`，且解压成功（zip 未损坏） | |
| P1-5 | 暂停/继续/取消 | 三按钮行为与通知 action 一致；取消后分片与状态文件都被删 | |
| P1-6 | 后台不中断 | 退到后台 / 从最近任务划掉后下载继续，通知进度前进 | |
| P1-7 | 通知权限 | API 33+ 首次点下载弹权限框；拒绝也不影响下载（只是完成/失败提醒不显示） | |
| P1-8 | 存储预检 | 剩余空间不足 3.2× 时**开始前**就拦下并显示中文提示 | |
| P1-9 | Android 15 `dataSync` 超时 | 需 ≥ 6h 持续下载才能复现（实际难以验证）：到点时日志有 `dataSync 前台服务超时`，回 App 可继续 | |

**P2（注入链 / 端口 / 缓存）——待真机项，逐条取证**

| # | 项 | 判据（看日志/界面/控制台） | 结果 |
| --- | --- | --- | --- |
| P2-1 | 注入链真的执行了 | 日志 `served patched index.html (canvasPatch=…, cheats=…, saveGuard=…)`；WebView 控制台能看到 `PK boot script patchCanvas=…`；DevTools 里 `<head>` 有三个注入标签 | |
| P2-2 | 存档镜像与回填 | 游戏内存档 → 等 2s → 应用数据目录出现 `files/saves/localstorage.json`；用 DevTools 清空 localStorage → 重进游戏 → 控制台 `SAVEGUARD restored N keys` 且存档回来 | |
| P2-3 | 清缓存不伤存档 | 点系统「清除缓存」后进游戏，存档仍在（P0 判据 6 的 Android 版） | |
| P2-4 | 参数调整实时生效 | 开启至少两条（如 `糖果获取倍率` ×10 + `幸运值拉满`）→ 控制台 `CHEAT candy live x10`，捕获宝可梦得到的糖果 ×10、全队幸运值为 SSS | |
| P2-5 | 关开关就回原版 | 关总开关 + 重载 → `cheats=false`，不再注入 `/__cheats__.js`，行为回到纯净版 | |
| P2-6 | 端口占用兜底 | 先用其它进程占住 18787（另一个实例 / `nc -l 18787`）→ 点进游戏 → 重试两次后失败，日志/界面给出「端口被占用」中文提示（不是白屏也不是英文异常） | |
| P2-7 | 版本变更清缓存 | 装完资源后日志有 `已清除 WebView 缓存`；杀掉 App → 换成另一个资源包再启动（或手改偏好里 `lastCachedGameVersion`）→ 冷启动日志再清一次 | |
| P2-8 | 参数调整文案红线 | **P3 才能验**：参数调整页与任何用户可见文案不得出现「作弊」字样（审核风险） | |

**P0 未通过时**的退路：改用 `androidx.webkit` 的 `WebViewAssetLoader`（`https://appassets.androidplatform.net`，免 socket 免 cleartext），代价是丢 HTTP 缓存与 Range。

## 5. 分期计划

| 阶段 | 内容 | 工时 | 状态 |
| --- | --- | --- | --- |
| **P0 打靶** | 工程骨架（Kotlin + Compose, minSdk 26）+ NanoHTTPD + 注入 + 导入 zip + 进游戏计时 | 2–3 天 | ✅ 代码完成 + 本机编译/单测/release 混淆校验通过；**真机判据待跑（§4.4）** |
| **P1 下载/解压** | Release 查询 → 镜像并发测速 → 断点续传 → 原子解压切换 → `recover()`；前台服务 + 通知进度；暂停/继续/取消；SAF 手动导入 | 4–6 天 | ✅ 代码完成（71 单测全绿，debug/release 均编译通过）；**真机判据待跑（§4.4）** |
| **P2 服务与注入** | index.html 补丁、虚拟端点 `/__pk_boot.js` + `/__cheats__.js`、端口占用兜底、8001 stub、`clearCache` 时机、参数调整状态与存档镜像 | 3–4 天 | ✅ 代码完成（累计 180 单测全绿，debug/release 均编译通过，lint 0 error）；**真机判据待跑（§4.4）** |
| **P3 UI** | Compose：首页四态 + 下载流 + 精灵球动画、设置页、参数调整页、游戏页（返回按钮淡出/错误浮层/方向/静音）、法律页 | 4–6 天 |
| **P4 平台特有** | 存档导出/导入、Auto Backup、应用内自更新（FileProvider + `REQUEST_INSTALL_PACKAGES`）、通知权限 | 3–4 天 |
| **P5 打磨发布** | 真机矩阵（多 WebView 版本）、性能调优、GitHub Actions 出 APK、签名发版 | 3–5 天 |

**完整对等移植 ≈ 3–4 周；MVP（P0–P2 + 极简 UI）≈ 1.5 周。**

## 6. 关键坑（按杀伤力排序）

1. **cleartext HTTP**：`android:usesCleartextTraffic="false"`（保持默认安全），只用 `network_security_config.xml` 给 `127.0.0.1` / `localhost` 开域白名单。别一把梭 `true`（Play 不友好）。
2. **端口不能回退**：与鸿蒙同理——origin 变了存档就丢。`18787` 被占用时**宁可启动失败并明确提示**，绝不静默换端口（对应鸿蒙「不做多端口回退」的决定）。
3. **507MB 下载**：慢网 + 国产 ROM 后台杀进程是最大失败源。「镜像并发测速 → 断点续传 → 前台服务 + 通知进度」缺一不可。存储预检按 **3.2× ≈ 1.6GB**。
4. **WebView 版本碎片化**：游戏要吃现代 Chromium。启动读 `WebViewCompat.getCurrentWebViewPackage()`，主版本 < 100 拦下并引导升级（国产 ROM 无 Play 更新，这是真实风险）。
5. **`setMediaPlaybackRequiresUserGesture(false)`** 必须设，否则 BGM 第一次不响。
6. **后台静音**：`WebViewCompat.setAudioMuted` 有 feature 检查（`WebViewFeature.MUTE_AUDIO`），不支持时降级注入 `game.sound.mute = true`（Phaser 的 `WebAudioSoundManager` 支持全局 mute），复用场景捕获运行时拿到 game 实例。
7. **`clearCache` 不能写成 `clearStorage`**：资源更新后只清 HTTP/JS 缓存；`clearStorage` 会把 `localStorage` 存档一起干掉。
8. **别学先例申请 `MANAGE_EXTERNAL_STORAGE`**：那是为了直接写公共 `Downloads/`，Play 会拒。用 `MediaStore.Downloads` 零权限达成同样效果。
9. **存档是 Web 的 `localStorage`**：不要往 `cacheDir` 放任何需要持久化的东西（Android 的「清除缓存」会清 `cacheDir`）。
10. **README 里 `game.zip` 直链、镜像列表**与鸿蒙版共用一套常量，发版时两边同步。
11. **Android 15 的 `dataSync` 前台服务有 6h/24h 上限**：超时系统调 `Service.onTimeout(int, int)`（只给几秒收尾）。必须在那里保存进度并 `stopSelf`，否则会被判 ANR/被杀；另外 API 34+ **不允许后台自行启动前台服务**，所有启动必须由用户操作触发（按钮/通知 action）。
12. **API 33+ 的 `POST_NOTIFICATIONS`**：不授权时前台服务通知仍会显示（FGS 免授权），但 `notify()` 的“完成/失败”普通通知会被系统静默丢弃——所以发起下载前顺手申请一次。
13. **`ForegroundServiceStartNotAllowedException`（API 31+）**：不要在任何“后台自发”的时机（开机、定时、网络回调）去起下载服务；P1 不在启动时自动续传，而是把未完成下载显示为一条提示，由用户点一下（这也正好符合“不耗用户流量”的预期）。
14. **服务端忽略 `Range` 返回 200 时**，必须 `setLength(0)` 从头写；否则把整包追加到旧分片后面，得到的是一个能下载完但必然解压失败的 zip。

P2 新增（同样很能坑人）：

15. **两个注入脚本各自 `defineProperty(window,'Phaser')` → 后者覆盖前者**，必有一方的 setter 永不触发（症状：计时埋点静默消失，或参数调整拿不到 Game 实例）。Android 侧规定包装归 `/__pk_boot.js`，另一方注册 `window.__pkGameHooks`；该数组两边都用 `x = x || []` **复用**，重新赋值 = 丢掉已注册的回调。
16. **注入链的顺序不能乱**（存档守卫最早、引导脚本最后），错了不报错但静默失效；`InjectScriptsTest` 用 `indexOf` 关系锁住。
17. **端口被占用时只重试一次就够**，而且绝不能「干脆换个端口」：origin 一变，`localStorage` 就换了键 = 存档全丢（与坑 #2 同一条红线）。
18. **`clearCache` 必须主线程，且绝不能用 `clearStorage`**：后者会把 localStorage（存档）一起干掉。Android 的存档不会被系统「清除缓存」清掉，所以应用内清缓存时更没理由动 storage。
19. **资源包更新后必须清 WebView HTTP 缓存**：大资源走 `max-age=31536000`，漏一次就是「装了新版还是旧界面」。注意下载会在前台服务里装完（游戏页可能从未打开），所以要额外用偏好里的版本号做一次冷启动对账。
20. **「参数调整」文案红线在安卓侧同样适用**：任何用户可见文案不得出现「作弊」字样（应用商店审核风险），代码/文档内部继续用 cheat。
21. **Kotlin raw string 里的 `$` 是模板插值**：从 ArkTS 搬 JS 时逐行检查（`SaveGuardTest` / `CheatsTest` 都断言了「脚本里无 `$`」来防回归）。

## 7. 风险

| 风险 | 等级 | 应对 |
| --- | --- | --- |
| Dart/第三方库里没有的 WebView 老版本跑不动游戏 | 中 | P0 判据 4；启动版本门禁 + 引导升级 |
| 国产 ROM 后台杀进程导致 507MB 下载中断 | 高 | 前台服务 + 断点续传 + 通知；引导用户加白名单 |
| 国内网络下 Gradle 拉依赖慢 | 低 | Aliyun 镜像仓库兜底（已实测 0.1s） |
| 直发 APK 需用户允许「安装未知来源」 | 中 | 首次自更新时给图文引导 |
| 游戏更新导致「参数调整」hook 失效 | 低 | 与鸿蒙同源，关闭开关即恢复纯净 |

## 8. 发布

- 出包：`cd android && ./gradlew assembleRelease`（keystore 不入库，走 `~/.gradle/gradle.properties` 或本地 `keystore.properties`，对齐 [`AGENTS.md`](../AGENTS.md) 的签名边界约定）。
- 版本清单：安卓侧的 `versionCode` / `versionName` 需与仓库根 [`version.json`](../version.json) 对齐，供应用内自更新比对。
- 发版同时推 `origin`(Gitee) 与 `github` 两个远端（见 AGENTS.md §2）。
