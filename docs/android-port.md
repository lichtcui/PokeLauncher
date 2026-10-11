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
| `Web` 组件 | `android.webkit.WebView`（后台静音用 `MUTE_AUDIO`，不支持时降级到 `game.sound.mute`，见 §4.3.6-5） |
| `javaScriptProxy` | `addJavascriptInterface` |
| `WebDownloadDelegate` | **JS 拦截 + `PokeNative.saveDownload`**（游戏「导出数据」是 `Blob` + `<a download>`，`blob:` 不一定会走 `DownloadListener`），残留的 `blob:` 用 `DownloadListener` + XHR 兜底（见 §4.3.7-1，P4 已落地） |
| `DocumentViewPicker`（保存） | `MediaStore.Downloads`（API 29+ 零权限；API 26–28 落应用专属下载目录，**不申请存储权限**） |
| `DocumentViewPicker`（选 zip） | SAF `ACTION_OPEN_DOCUMENT` |
| 游戏内 `<input type=file>` 导入存档 | `WebChromeClient.onShowFileChooser` + SAF（Compose 层 `rememberLauncherForActivityResult`）（P4 已落地） |
| `hilog` | `Log`（tag 统一 `PokeLauncher`，见 [`device-cli.md`](./device-cli.md) 的 tag 约定） |
| `WindowHolder.setOrientation` | `Activity.requestedOrientation` |
| `apiAtLeast()` | `Build.VERSION.SDK_INT` |
| `filesDir` | `getExternalFilesDir(null)`（507MB zip + ~1.5GB 解压，内部存储会爆） |
| `AppUpdate.ets`（检测 + `Want` 打开浏览器，**无法应用内装 HAP**） | OkHttp 下 APK → **`FileProvider` + `ACTION_VIEW`** 交系统安装器（`REQUEST_INSTALL_PACKAGES` 只是常规权限，真正的门禁是 `canRequestPackageInstalls()` → `ACTION_MANAGE_UNKNOWN_APP_SOURCES`）（P4 已落地，见 §4.3.8） |

### 3.3 目录规划

```
android/
├─ app/src/main/java/com/lichtcui/pokelauncher/
│  ├─ core/       Const / Paths / Format / Version / Prefs —— 无 Android 依赖，可 JVM 单测
│  ├─ server/     GameServer(NanoHTTPD×2) / InjectScripts(canvas 补丁 + Phaser 包装) / ServerDiag(端口占用诊断)
│  ├─ cheat/      Cheats(注册表·JS 逐字保真) / CheatFormat(纯编解码) / CheatState(状态+持久化) / CheatScripts(生成 /__cheats__.js)
│  ├─ install/    GameRepository(Release 查询·镜像测速·断点续传) / GameInstaller(原子解压切换·recover) / DownloadService(前台服务)
│  ├─ web/        PokeWebView(JS 桥·文件选择转发) / SaveGuard(存档镜像·JS 桥) / DownloadBridge(存档导出拦截·落盘) / WebCache(clearCache 时机)
│  ├─ appupdate/  AppUpdate(清单解析·版本比较·节流) / AppUpdateRepository(拉清单·开链接) / ApkUpdate(自更新纯逻辑) / ApkUpdater(下包·校验·安装 Intent)
│  └─ ui/         Compose：首页四态 / 设置 / 参数调整 / 法律 / 游戏页 / 打靶页（P3 已全部落地，清单见 §4.1）
└─ app/src/test/  JVM 纯函数单测（core / install / cheat / server / web / appupdate）
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

### 4.1 已落地代码（P0–P4 已实现部分，可编译可装机）

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
│  ├─ core/ OrientationPref.kt             屏幕方向偏好（portrait/landscape/system，**只作用于游戏页**）
│  ├─ appupdate/ ApkUpdate.kt              应用内自更新纯逻辑：阶段/进度/文件名清洗/zip 魔数校验/按钮文案映射（**纯函数**）
│  ├─ appupdate/ ApkUpdater.kt             更新包 IO：`apk/` 目录 + OkHttp 下载（`.part` → `fd.sync()` → 改名）+ 校验 + 清旧包 + FileProvider 安装 Intent
│  ├─ appupdate/ AppUpdate.kt              应用自更新纯逻辑：清单解析（含 android* 字段）+ 版本比较 + 检查节流
│  ├─ appupdate/ AppUpdateRepository.kt    清单拉取（OkHttp + 模块级 TTL 缓存）+ 打开链接
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
│  ├─ web/ PokeWebView.kt                  WebView 配置 + PokeNative JS 桥（含存档导出）+ PokeSaveGuard 存档桥 + 切后台静音 + 文件选择器转发
│  ├─ web/ DownloadBridge.kt               存档导出：JS 拦截脚本 + 文件名清洗 + MediaStore/应用目录落盘 + 兜底脚本（纯部分可单测）
│  ├─ web/ SaveGuard.kt                    存档镜像（filesDir/saves/localstorage.json + .bak）+ 注入脚本
│  ├─ web/ WebCache.kt                     clearCache 时机（活实例优先，否则临时实例；仅主线程）
│  └─ ui/                                  首页 + 打靶页 + 设置页 + 参数调整页 + 法律页 + 游戏页（P3 全部落地）
│     ├─ Theme.kt / Common.kt              主题色；页面外壳公共件（返回栏 / 分组标题 / 导航行 / 状态行 / formatMs / 自更新动作分发 / 进度条 / 拉起安装器）
│     ├─ PageOrientation.kt                根组件套用屏幕方向（游戏页用偏好，其余页面手机竖屏/平板跟随系统）
│     ├─ RootScreen.kt                     应用根：主题 + 路由（6 页布尔量）+ 单一 BackHandler + 方向 + **自更新包就绪后拉起安装器**
│     ├─ HomeState.kt                      首页四态 + 精灵球阶段 + 流程文案（**纯函数**，可 JVM 单测）
│     ├─ HomeScreen.kt                     首页四态视图（对应鸿蒙 Index.ets + LauncherViews.ets）
│     ├─ PokeballLoader.kt                 精灵球摇晃动画（±14° / 450ms，与鸿蒙同参数）
│     ├─ GameState.kt                      游戏页纯逻辑：浮层判定（错误 > 加载中）+ 返回按钮常量 + 静音兜底脚本（**纯函数**）
│     ├─ GameScreen.kt                     游戏页：WebView + 返回按钮（3s 淡出）+ 加载/错误浮层 + 悬浮指标面板
│     ├─ SpikeController.kt                应用级控制器（首页 / 设置页 / 打靶页共用同一实例）
│     ├─ SpikeScreen.kt / CheatScreen.kt   开发者诊断页；参数调整页（纯注册表驱动）
│     └─ SettingsScreen.kt / LegalText.kt / LegalScreen.kt   设置页；法律正文（纯数据）；法律页
├─ app/src/test/java/.../                  **270 个 JVM 单测**（core 31：Paths 7 / Format 6 / Mime 5 / Version 4 / OrientationPref 7 / Prefs 2；
│                                          install 50：DownloadUrls 12 / DownloadState 16 / DownloadStatus 16 / ReleaseInfo 6；
│                                          cheat 71：CheatFormat 27 / CheatScripts 14 / CheatState 13 / Cheats 17；
│                                          server 20：InjectScripts 10 / ServerDiag 10；web 36：SaveGuard 22 / DownloadBridge 14；
│                                          appupdate 33：AppUpdate 18 / ApkUpdate 15；ui 29：HomeState 13 / GameState 9 / LegalText 7）
└─ app/src/main/res/                       图标（品牌红环）/ 通知图标 ic_download / 精灵球 ic_pokeball / 主题 / network_security_config
                                          / xml/backup_rules.xml + xml/data_extraction_rules.xml（Auto Backup：只备设置偏好 + files/saves）
                                          / xml/provider_paths.xml（自更新：只放行 apk/ 两个路径）
```

已实测通过（本机，**尚无真机**）：

| 检查 | 结果 |
| --- | --- |
| `./gradlew assembleDebug` | ✅ `app-debug.apk` 10 MB |
| `./gradlew assembleRelease`（R8 + 资源收缩） | ✅ `app-release-unsigned.apk` 1.3 MB（含 OkHttp/Okio） |
| `./gradlew testDebugUnitTest` | ✅ **270 tests, 0 failures** |
| `./gradlew :app:lintDebug` | ✅ **0 errors**（27 warnings / 11 hints，均为依赖版本提示与 autoboxing 建议类，不阻塞；新增 1 条 `UseKtx String.toUri`，与 `AppUpdateRepository` 同类） |
| release 包 JS 桥是否存活 | ✅ 反编译 dex：`PokeNative` / `reportTiming` / `reportState` / **`PokeSaveGuard`** / **`load` / `save` / `notifyRestored`** 均在（`o1.g.load`：类名可混淆，方法名由 `-keepclassmembers @JavascriptInterface` 保住） |
| release 包新链路常量是否存活 | ✅ dex 里仍有 `tag_name` / `browser_download_url` / `receivedBytes` / `bytes=0-` / `If-Range` / `Content-Range` / `game.zip.download.json` / `pokerogue-download` |
| release 包注入链常量是否存活 | ✅ dex 里仍有 `__cheats__.js` ×4 / `__pk_boot.js` ×2 / `window.__pkGameHooks` ×3 / 7 条脚本标记（`__cheatCandy/Exp/Money/Luck/Catch/FreeEgg/Reward`）/ `cheatMaster`·`cheatEnabledIds`·`cheatParams` / `localstorage.json`·`localstorage.bak.json` / `SAVEGUARD` ×5 |
| manifest 基线 | ✅ minSdk 26 / targetSdk 35 / INTERNET + ACCESS_NETWORK_STATE + FOREGROUND_SERVICE(+DATA_SYNC) + POST_NOTIFICATIONS + WAKE_LOCK / cleartext 全局 false + 回环白名单 |
| 前台服务声明 | ✅ release 合并后 manifest 里 `com.lichtcui.pokelauncher.install.DownloadService` 未被混淆，`foregroundServiceType=0x1`（dataSync）、`exported=false`、`stopWithTask=false` |
| release 包中文常量存活（P3 参数调整页） | ✅ 字节搜索 dex：`参数调整总开关` / 风险提示两行全文 / `强制奖励稀有度` / `100% 捕获率` / `倍率修改` 等均在 |
| release 包中文常量存活（P3 设置页/法律页） | ✅ 字节搜索 dex：`设置` / `屏幕方向` / `竖屏` / `横屏` / `跟随系统` / `应用更新` / `离线资源更新` / `删除本地数据` / `更新到新版本` / `法律与免责声明` / `关于` / `应用版本` / `资源版本` / `资源来源` / `仍然开启` / `开启参数调整` / 两段风险确认全文 / `安卓版暂未发布` / `检查中…` / `已是最新` / `发现新版本` / `未安装` / `正在删除…` / `将删除已下载的离线资源，本地存档不受影响。确定删除吗？` / `本地数据已删除` / `最近更新：2026-09-18` 等全部在 |
| 方向偏好 / 更新比较 / 法律正文 | ✅ 纯逻辑已由单测覆盖（`OrientationPrefTest` 7 / `AppUpdateTest` 18 / `LegalTextTest` 7），三处都不依赖设备 |
| 首页四态 / 精灵球阶段 | ✅ 纯逻辑已由单测覆盖（`HomeStateTest` 13：四态判定、PAUSED 仍算流程中、安装中优先于暂停、文案逐字对齐鸿蒙、动画常量同值） |
| 游戏页浮层 / 返回按钮 / 静音 | ✅ 纯逻辑已由单测覆盖（`GameStateTest` 9：**错误浮层优先于加载浮层**、`loadErrorText` 前缀、存档回填提示只在 count>0 时出、`BACK_FADE_MS=3000` / `0.2f` 与鸿蒙同值、兜底脚本读 `window.__pkGame.sound.mute` 且不含 `$`） |
| release 包中文常量存活（P3 首页） | ✅ 字节搜索 dex：`正在检查本地资源…` / `PokeRogue 离线资源未安装` / `本应用是 PokeRogue 的离线安卓版，安装资源后即可断网游玩` / `需要存储` / `可用空间` / `· 需要开启通知权限` / `继续下载` / `换源重试（丢弃已下载的分片）` / `下载太慢？复制下方直链自行下载资源包，再用「上传资源」导入` / `上传资源` / `直链已复制` / `取消下载` / `确定取消下载吗？已下载的` / `将全部丢失。` / `请勿关闭应用` / `正在解压 PokeRogue 资源…` / `PokeRogue 资源准备就绪` / `离线资源已就绪` / `开始运行` / `开发者诊断（打靶页）` 全部在（48/48） |
| release 包中文常量存活（P3 游戏页） | ✅ 字节搜索 dex：`返回` / `正在载入游戏…` / `游戏加载失败` / `加载出错: ` / `重试` / `本地资源服务可能没起来` / `检测到本地存档缺失，已从快照恢复` / `项数据` / `页面有报错` / 指标面板 `canvas 补丁` / `DOM 载入` 全部在；静音兜底脚本的 `__pkGame` / `sound.mute` 也在 |
| 存档导出（P4）纯逻辑 + JS | ✅ `DownloadBridgeTest` 14 条：文件名清洗（路径分隔符 / `..` / 前导点 / 超长 / 控制字符 / 中文）、`Content-Disposition` 解析（含 `filename*=`）、成功/失败文案、同名文件 `(1)` 后缀、**注入脚本与兜底脚本都不含 `$`**、桥方法名与脚本互相对齐 |
| release 包存档导出常量存活（P4） | ✅ 字节搜索 dex：`已导出 ` / `导出失败` / `saveDownload` ×5 / `__pkDlInstalled` / `PKDL` ×15 / `URL.createObjectURL` / `XMLHttpRequest` / `data.prsv` 全部在（`proguard-rules.pro` 新增 `-keep class ...web.DownloadBridge`） |
| Auto Backup 规则（P4） | ✅ release 合并 manifest：`allowBackup=true` + `fullBackupContent=@xml/backup_rules` + `dataExtractionRules=@xml/data_extraction_rules`；APK 资源表里两个 xml 都在（被资源收缩重命名为 `res/Qq.xml` / `res/4j.xml`）且 manifest 指向正确；规则**只 include `sharedpref` + `file/saves`**（lint 会报「include 之外都算排除」，正好锁住「不备份 1.5GB 游戏本体」） |
| 应用内自更新（P4）纯逻辑 | ✅ `ApkUpdateTest` 15 条：文件名清洗（query/fragment/无扩展名/路径穿越/前导点/超长/中文与空格）、zip 魔数（`PK\x03\x04` / `05 06` / `07 08` / HTML / 短输入）、`MIN_APK_BYTES` 体积门槛、五阶段 → 按钮动作映射、各阶段文案与百分比（`total` 未知时退回绝对字节） |
| release 包自更新常量存活（P4） | ✅ 字节搜索 dex：`正在下载更新包…` / `正在校验更新包…` / `安装更新` / `重试更新` / `已下载完成` / `更新失败：` / `更新包已下载` / `下载到的不是安装包` / `清单里没有可用的安装包地址` / `已取消更新下载` / `下载不完整` / `更新包不见了` / `无法打开安装器` / `pokerogue-` / `apk download: done` 全部在 |
| 安装链路声明（P4） | ✅ release 合并 manifest：`REQUEST_INSTALL_PACKAGES` 在权限列表里（dex 里搜不到是正常的，它是 manifest-only）；`androidx.core.content.FileProvider` 未被混淆且 `exported=false` + `grantUriPermissions=true`、authority=`com.lichtcui.pokelauncher.fileprovider`、meta-data 指向 `@xml/provider_paths`（资源表 `0x7f0c0003` → `res/zz.xml`）；APK 里该 xml 只剩 `external-files-path apk/` + `files-path apk/`（**没放行整棵树**，见 §6-#38） |

> ⚠️ **release 包不能靠类名取证**：R8 混淆后 `SettingsScreenKt` / `CommonKt` / `HomeScreenKt` / `RootScreenKt` / `GameScreenKt` / `SpikeController` 这些类名在 dex 里**根本不存在**（被改名或 class-merging 内联掉；`HomeStateKt` / `GameStateKt` / `DownloadUrls` 甚至直接是 `R8$$REMOVED$$CLASS$$<n>`——方法全被内联，如 `ui.GameStateKt.muteFallbackScript(boolean)` 只作为 member 行出现在 `PokeWebViewKt` 里）。查 release 只有两条可靠路径：① dex 字符串（中文常量用字节搜索）；② `app/build/outputs/mapping/release/mapping.txt`（看类被映射成什么、哪些名字被 keep 规则保住；被合并的类只在 member 行里出现，如 `ui.HomeStateKt.prepPhaseOf(...)`）。

> 验 dex 字符串时注意：**字符串池是 UTF-8**，`strings -a` 只能看到 ASCII，中文要用字节搜索：
> `python3 -c "import sys;print(sys.stdin.buffer.read().count('参数调整'.encode()))" < classes.dex`。

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

打靶页流程（入口：首页右上角「诊断」；或 设置页 → 关于 → 「开发者诊断（打靶页）」）：`安装本地 game.zip` → 等「已安装 = 是」 → `进入游戏` → 读悬浮面板计时 → `退出` → 关掉 canvas 补丁开关 → `重载`（**会重建 WebView**，复现真正的冷启动）→ 再读一次 → 两次数据对比。

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

**P3 首页四态（启动器主页）**

根组件 `ui/RootScreen.kt` 按四态渲染，四态判定全在纯函数 `ui/HomeState.kt` 里（`HomeStateTest` 13 条覆盖）：

| 态 | 条件 | 界面 |
| --- | --- | --- |
| `CHECKING` | 冷启动读盘未结束 | 「正在检查本地资源…」+ 精灵球 |
| `NOT_DOWNLOADED` | 无资源、无进行中的流程 | 标题 + 简介 + 来源/体积/需要存储（含可用空间）卡片 + 3 条须知 + `开始下载` / `继续下载` + 换源重试 + 直链复制（镜像 / GitHub）+ 「上传资源」（SAF 选 zip） |
| `FLOW` | `status.isActive \|\| PAUSED \|\| installing \|\| holdReady` | 精灵球（±14° / 450ms 摇晃）+ 阶段文案 + 进度条 `${percent}%` + 速率/剩余时间 + 暂停/继续 + 取消（带确认弹窗） |
| `READY` | `gameReady` 且不在流程中 | 96dp 精灵球 + 「离线资源已就绪」+ 版本号 + `开始运行` + 阻塞原因（有则红字）+ 「参数调整」入口（仅总开关开时） |

- 优先级刻意是 **`initializing` → `inFlow` → `gameReady`**：更新资源时旧包还在（`gameReady=true`），必须显示下载流程而不是就绪页；反之冷启动读盘未完成时不能闪一下「未安装」。
- **PAUSED 算「流程中」**（`DownloadStatus.isActive` 不含它）：否则用户一暂停就被弹回未安装页，再也点不到「继续」。
- 状态数据全部来自 `SpikeController`（`initializing` / `installing` / `download` / `gameReady` / `installedVersion` / `availableBytes` / `pendingDownload` / `pendingUpdate`），页面本身不持有业务状态。
- 设置入口（`⚙`）只在 `READY` 显示（对齐鸿蒙：那里也只在就绪态给齿轮）；**「诊断」按钮常驻右上角**（见 §4.3.5-2）。
- 设置页里的「更新到新版本」会置 `pendingUpdate` 标记，由首页消费后自动发起下载（见 §4.3.4-3）。

**P3 参数调整页（设置页 → 「参数调整」行进入）**

顶部总开关 → 两个分组（倍率修改 / 其他）→ 每条一个开关；声明了 `param` 的条目多一个滑块（拖动实时改内存、松手才落盘）。打开总开关前有账号风险确认弹窗（文案与鸿蒙版逐字一致，从鸿蒙的设置页搬到了本页）。改动需**重载游戏页**才生效（`__CHEAT_PARAMS__` 是页面加载时烘进脚本的，与鸿蒙口径一致）。页面完全由 `CHEATS` 驱动，**新增条目不需改 UI**。

**P3 设置页 / 法律页（首页右上角 `⚙` 进入；打靶页也能从设置页的「开发者诊断（打靶页）」进入）**

- 运行设置：屏幕方向三段（竖屏 / 横屏 / 跟随系统，只影响游戏页）；「参数调整」入口行（显示已开启/已关闭，**开关本体在参数调整页**）。
- 数据管理：`应用更新`（点一下强制检查；没有 `androidVersionCode` 时显示「安卓版暂未发布」，有更新时多出一行「去更新」+ 更新说明，点是**应用内下载 APK**，见下面的 P4 小节）；`离线资源更新`（对比本地资源版本，发现新版时多出一行「更新到新版本」→ 返回首页自动开始下载）；`删除本地数据`（确认弹窗 → 异步删除 → Toast + 返回首页）。
- 关于：应用版本（`BuildConfig`）/ 资源版本 / 资源来源 / 作者 / GitHub（浏览器打开）/ 法律与免责声明（进入法律页，10 个章节）/ **开发者诊断（打靶页）**（调试与发布包都常驻：真机判据要在「还没装资源」时就能跑）。

**P3 游戏页（首页「开始运行」进入）**

全屏 WebView + 三样浮层，对应鸿蒙 `pages/GamePage.ets`：

| 元素 | 实现 | 与鸿蒙的对应 |
| --- | --- | --- |
| 返回按钮 | 进入/重载后全显 **3 秒** → 淡到 `0.2`；淡出状态下点一下只把它**唤回**（不退出），全显时才真返回 | `setTimeout(3000)` + `opacity(backVisible ? 1 : 0.2)`，语义逐字对齐（常量在 `ui/GameState.kt`） |
| 加载浮层 | `pageLoaded=false` 时的半透明蒙层 + 精灵球 + 「正在载入游戏…」 | 鸿蒙靠 Web 组件自己白屏，安卓补一层，顺便把首次载入的闪白遮掉（底色 `GAME_BG_ARGB`） |
| 错误浮层 | 居中卡片「游戏加载失败」+ `加载出错: <detail>` + 一行说明 + `重试`；面板里只留一行「页面有报错」 | 鸿蒙是右上角一行 12sp 小字（无按钮） |
| 方向 | 根组件的 `ApplyPageOrientation(gamePageActive = showGame)` 套用设置页选的方向（竖/横/跟随系统） | `WindowHolder.setOrientation(readOrientation())` |
| 静音 | Activity 进后台（`ON_PAUSE`）→ `MUTE_AUDIO` 特性优先、否则注入 `game.sound.mute`；回前台解除；销毁 WebView 前先静音 | 鸿蒙 `onPageHide/onPageShow` + `setAudioMuted`（受 `apiAtLeast(20)` 限制，低版本同样得降级） |
| 存档回填提示 | `PokeSaveGuard.notifyRestored` → `SpikeController.saveRestoredSeq` → 游戏页 Toast「检测到本地存档缺失，已从快照恢复 N 项数据」 | 鸿蒙在 `onPageEnd` 里弹同一句 toast |

- **浮层判定顺序是「错误 > 加载中」**（`gameOverlayOf`）：加载失败时 `pageLoaded` 永远是 false，顺序反了用户只会看到「正在载入…」。
- 返回按钮放**左上角**（鸿蒙在右上角）：右上角被打靶指标面板占着，两个叠一起会互相吃点击；错误浮层也因此从右上角改成居中卡片。见 §4.3.6。
- 打靶指标面板（`canvas 补丁` / `DOM 载入` / 计时点 / `gid` / 重载 / 退出）**仍是打靶期产物，发布前必须挪到 debug 开关后面**（见 §4.3.6-8）。

**P4 应用内自更新（设置页 → 应用更新 / 首页「发现新版本」弹窗）**

安卓侧比鸿蒙多做一步：鸿蒙只能「检测 + 打开浏览器」（普通应用装不了 HAP，见 [`app-update.md`](./app-update.md)），安卓可以直接下 APK 交给系统安装器。

点「去更新」→ `ApkUpdater.downloadApk` 下到 `getExternalFilesDir()/apk/pokerogue-<版本>.apk`（先写 `.part`，`fd.sync()` 后改名）→ 校验体积 + zip 魔数 → `pruneOldApks` 清掉上一次的包 → 阶段置 `READY` → 根组件（`RootScreen`）的 `LaunchedEffect` 拉起系统安装器。

- **下载不用前台服务**：安装包只有几 MB 且必须用户盯着点「安装」，套 `dataSync` 反而吃掉 Android 15 的 6h/24h 配额（507MB 资源包才需要那套）；代价见 §4.3.8-1。
- 没有「安装未知应用」权限时：中文 Toast 说明 + 直接跳 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`（本应用的开关项），回来再点一次即可。
- 弹窗与设置页行共用同一套 `apkActionOf` / `apkRowLabel` / `apkRowValue`（纯函数，`ApkUpdateTest` 锁住）：**下载中两处都显示「取消」**，不然会双开下载。
- 日志：`apk update: start file=… url=…`、`apk download: done url=… bytes=… dest=…`、`apk update: ready` / `invalid package` / `cancelled`。
- 清单里没有 `apkUrl` 时不走这条链，退回浏览器打开 `pageUrl`（与鸿蒙一致）；`androidVersionCode` 缺失时整块不出现（显示「安卓版暂未发布」）。

**P4 存档导出 / 导入 + Auto Backup（游戏页之上，不需要新页面）**

游戏内的「导出数据」是一个 `Blob` + `<a download>`，安卓侧 `blob:` 不一定会走到 `DownloadListener`，所以主路径是**在页面里拦住下载**：

| 环节 | 实现 | 与鸿蒙的对应 |
| --- | --- | --- |
| 导出拦截（主路径） | `/__pk_boot.js` 之前再插一段脚本：补 `URL.createObjectURL` / `HTMLAnchorElement.prototype.click` + 捕获阶段 `click`，拿到 Blob 后 `FileReader.readAsDataURL` → `PokeNative.saveDownload(name, mime, b64)` | 鸿蒙 `onBeforeDownload` 拦到请求后自己写沙箱 |
| 导出兜底 | 真的漏到 `DownloadListener` 且是 `blob:` → 注入 XHR 脚本（`blobFallbackScript`）把同一份数据再走一次桥 | 同上（鸿蒙没有这条，因为 Web 组件不触发下载就不会漏） |
| 落盘位置 | API 29+ → `MediaStore.Downloads`（`IS_PENDING` 保证要么完整要么没有）；API 26–28 → `getExternalFilesDir(DIRECTORY_DOWNLOADS)` + 同名 `(1)` 后缀 | 鸿蒙 `DocumentViewPicker`（用户选目录）+ `item.start(target)` |
| 文件名 | 页面传上来的名字一律过 `safeFileName`（去路径分隔符 / `..` / 控制字符 / 前导点 / 超长截断） | 鸿蒙直接拼 `pathOfUri(result[0]) + '/' + fileName`（没有清洗，路径穿越风险靠系统 picker 兑现） |
| 结果反馈 | `SpikeController.onSaveExport` 切 IO 落盘，回到主线程弹 Toast：「已导出 <name> 到「下载」」/「导出失败：<detail>」 | 鸿蒙无提示（写成功与否用户自己看文件管理器） |
| 导入（游戏内选文件） | `WebChromeClient.onShowFileChooser` → `params.createIntent()` → Compose `rememberLauncherForActivityResult` → `FileChooserParams.parseResult` 回给 WebView | 鸿蒙 `onShowFileSelector` + `DocumentViewPicker` |
| Auto Backup | `res/xml/backup_rules.xml`（≤ API 30）+ `res/xml/data_extraction_rules.xml`（API 31+）：只备 `sharedpref` + `file/saves` | 鸿蒙 `SaveGuard` 把 `localStorage` 镜像到 `files/saves`，安卓额外把它交给系统备份 |

- **导出/导入都不申请任何存储权限**：`MediaStore.Downloads` 与 SAF 都是零权限通道（刻意对齐 §6-#8，不学参考 App 的 `WRITE_EXTERNAL_STORAGE` 路线）。
- **`onShowFileChooser` 必须恰好回一次值**：用户取消 / 页面提前销毁 / 连续两次请求都得把上一次的 `callback` 用 `null` 结掉（`FileChooserSlot.cancel()`），否则 WebView 里那次文件选择永久挂住。
- Auto Backup 是**配置项**，没有任何可单测的代码；取证方式是 release 合并 manifest + APK 资源表（见 §4.1）。

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

### 4.3.3 P3（UI）实现期决定

> P3 分块推进：**第一块 = 参数调整页**（已落地），后续是首页四态/下载流/精灵球动画、设置页、游戏页、法律页。

1. **参数调整页完全由 `CHEATS` 驱动**：分组、条目、参数范围、显示文本全部来自注册表，新增条目只改 `cheat/Cheats.kt`（与鸿蒙版「只改 `Cheats.ets`」同一条规则）。这也是为什么把 `CheatParam.sliderSteps()` 放在注册表旁边而不是 UI 文件里。
2. **总开关放在参数调整页顶部**（鸿蒙版在「设置 → 运行设置」）：开关离条目越近越不容易误操作，也让本页自成一个可验证的闭环（P3 第一块不改设置页）。设置页落地后**只加入口链接**（已落地：一行「参数调整」，右侧显示已开启/已关闭），不重复放开关，避免两处状态分叉。
3. **不引 `navigation-compose`**：P3 全部页面加起来只有六个（首页/设置/参数调整/法律/打靶/游戏），根组件（`ui/RootScreen.kt`）里的几个布尔量 + **唯一**一个 `BackHandler` 就够（它的 `when` 顺序必须与渲染 `when` 一致，否则返回键会跳到看不见的页面）；多一层依赖还多一层混淆风险。等页面数量真的超了再换。
4. **滑块档位换算放在 `cheat/Cheats.kt`**：Material3 的 `steps` 是「端点之间的档位数」（0..4 step1 → 3），传错就是崩溃或该滑不动；Compose 文件无法 JVM 单测，所以把它放在注册表旁边（`CheatsTest` 覆盖，含「不能为负」与「每个参数都适配」。基准值均来自注册表，新增参数越界会在单测里直接红）。
5. **拖动中只改内存，松手才落盘**（与鸿蒙版一致）：SharedPreferences 是同步 `commit`，每次滑动都写盘会掉帧；拖动中只调 `setParamLive`，`onValueChangeFinished` 才 `setParam`。
6. **改动需重载才生效**：`__CHEAT_PARAMS__` 是 `/__cheats__.js` 在被请求时烘进脚本的，所以页面上写「改动会在重载游戏页后生效」——不承诺「拖动即生效」（与鸿蒙行为一致）。
7. **文案红线延伸到整页**：标题就叫「参数调整」，风险提示文案与鸿蒙版**逐字一致**（账号风险 + 兼容性风险两行）。日后加新文案先看 `AGENTS.md` §2 的命名约定。

### 4.3.4 P3（设置页 / 法律页）实现期决定

> P3 第二块：**设置页 + 法律页**（含屏幕方向偏好、应用自更新模块、参数调整入口、删除本地数据）。

1. **共享 `version.json` 新增 5 个安卓字段**（本轮唯一跨端数据结构变更）：`androidVersionCode` / `androidVersionName` / `androidMinVersionCode`（0 = 未声明）/ `androidChangelog` / `apkUrl`。安卓**只认** `android*` 字段：没有 `androidVersionCode` 就判 `androidPublished=false`，界面显示「安卓版暂未发布」；**绝不**拿鸿蒙的 `versionCode`（当前 1001000）去比——安卓包号还很小，那会永远显示「发现新版本」，而且点进去是 HAP 下载页（判据见 §4.4 P3-7）。发版对齐规则写进了 §8。
2. **应用内自更新不在本阶段**：与鸿蒙版行为一致（检测 + 浏览器打开 `apkUrl`，无 `apkUrl` 时打开 `pageUrl`）。应用内下载安装 APK 要 FileProvider + `REQUEST_INSTALL_PACKAGES`，属 P4——**已在 §4.3.8 落地**（安卓从此比鸿蒙多一步：鸿蒙装不了 HAP，只能开浏览器）。
3. **「更新到新版本」用标记而不是直接开下载**：设置页只调 `controller.requestUpdate()`（置 `pendingUpdate`），回首页后由首页的 `LaunchedEffect` 消费标记并发起下载（等价于鸿蒙的 `AppStorage('pendingUpdate')`）。理由：申请通知权限要 Activity 的 launcher，「预检 → 启动前台服务」这条链只该有一份实现（首页），否则两个入口早晚分叉。（本块最初落在打靶页，§4.3.5-12 已搬到首页并删掉打靶页那份。）
4. **参数调整总开关不放设置页**（§4.3.3-2 的落地）：设置页只有一行入口，开关本体在参数调整页顶部；开启前的风险确认弹窗也搬到了那边（文案逐字一致）。一个状态只配一个控件。
5. **屏幕方向由根组件套用，且只对游戏页生效**：`ApplyPageOrientation(gamePageActive)`——游戏页用偏好（竖屏/横屏/跟随系统 → `PORTRAIT` / `SENSOR_LANDSCAPE` / `UNSPECIFIED`），其余页面手机恒竖屏、平板（`smallestScreenWidthDp ≥ 600`）跟随系统。放根组件而不是 `MainActivity`：进/出游戏页要**立即**生效，而 Activity 只创建一次；`configChanges` 已声明，改 `requestedOrientation` 不会重建 Activity。设置方向包了 `runCatching`（多窗口等场景会抛异常）。
6. **设置页不新建 Controller**：方向/参数调整/资源版本都在 `remember` 里直接读现有单例，只有「应用更新的检查结果」是模块级缓存（避开每次进出重发请求）。副产物：从设置页进参数调整再返回时设置页是**重建**的（状态重新读过，显示的就是最新的，不会出现两份状态）。
7. **「删除本地数据」改成异步**（顺带修了打靶页的同名按钮）：`deleteGame(onDone)` 把删目录树（~1.5GB）切到 `Dispatchers.IO`，Compose 状态回写仍在主线程；完成时额外清掉偏好里的 `lastCachedGameVersion`（资源没了，下次装回同一版本还得再清一次 WebView 缓存）。删除完成 → Toast + 回首页（与鸿蒙一致）。
8. **法律页文案双平台化，但章节集合必须与鸿蒙一致**：只改两处措辞（平台列举 / 更新载体 HAP↔APK），免责边界逐条对齐 `pages/Legal.ets`；正文单独放 `ui/LegalText.kt`（不依赖 Compose，所以能被 `LegalTextTest` 扫「无『作弊』」红线与章节 id 顺序）。
9. **「关于」直接读运行时值**：应用版本用 `BuildConfig`（当前 `0.1.0-p0 (1)`）、资源版本用 `SpikeController.installedVersion`、GitHub 用 `Const.PROJECT_GITHUB_URL`。安卓的 `versionCode/versionName` 现在还是 P0 的占位值，要在发版前与 `version.json` 的 `android*` 字段对齐（§8）。

### 4.3.5 P3（首页四态 / 下载流 / 精灵球）实现期决定

> P3 第三块：**首页四态 + 下载流 + 精灵球动画**（对应鸿蒙 `pages/Index.ets` + `components/LauncherViews.ets` + `components/PokeballLoader.ets`）。

1. **四态 / 阶段 / 文案先抽成纯函数**（`ui/HomeState.kt`）：本机没有模拟器也没有真机，Compose 组件跑不起来，判定逻辑如果写在 `@Composable` 里就等于零验证。抽成 `homeStateOf` / `inFlowOf` / `prepPhaseOf` / `flowTitleOf` / `isShaking` / `hidesProgress` 后，`HomeStateTest`（13 条）能把「PAUSED 仍算流程中」「安装中优先于暂停」「更新资源时不提前显示已就绪」「文案逐字对齐鸿蒙」这些真机才会暴露的坑锁住。
2. **两处刻意偏离鸿蒙的入口布局**：① 首页右上角**常驻**「诊断」文字按钮（鸿蒙只有就绪态给齿轮），因为 P0/P1 的真机判据必须在「刚装好、还没资源」时就能跑到；② 设置页「关于」组多一行「开发者诊断（打靶页）」，debug/release 都可见，免得真机验收时找不到入口。设置齿轮仍然只在就绪态出现。
3. **主页保持深色主题**（鸿蒙首页是白底）：安卓侧参数调整/设置/法律三页已经全是 `MaterialTheme` 深色，首页单独做白底会出现「进设置像换了个 App」。一致性优先，这条是真机评审时要重新确认的取舍。
4. **没有 `appHidden` 参数**（鸿蒙 `PokeballLoader` 有）：鸿蒙那边是为了让 60fps 动画在后台别烧 CPU；安卓侧动画是 450ms 一次的低频状态变化（`animateFloatAsState`），后台时 Compose 本来就不驱动帧，多一个参数只会多一条没人验证的分支。
5. **没有移植 `SHRINK_MS`**：那是鸿蒙「下载完成 → 收缩」那一拍的时长，安卓的 `DownloadService` 直接走 `PROBING → DOWNLOADING → EXTRACTING → DONE`，没有中间停留态，只有 `BURST_MS = 900`（解压完成后停一下让「PokeRogue 资源准备就绪」被看见）。同理 `PrepPhase` 只有 5 个值，**没有 `DOWNLOADED`**。
6. **精灵球动画 = 只做旋转**（±`SHAKE_ANGLE = 14f`，每 `SHAKE_INTERVAL_MS = 450` 换向，`SHAKE_ANIM_MS = 420` 过渡）：鸿蒙那边的 `openP`/`_open()` 注释里写着「开盖」，但实现里没有任何缩放/开盖关键帧，属于意向遗留。安卓按实际行为移植，不自己发明动画。
7. **精灵球图标是手写 vector**（`res/drawable/ic_pokeball.xml`）：上半红、下半白、中间黑带（用 `clip-path` 裁进圆内）+ 白心黑环。比塞 PNG 更省体积，也不会在 96dp 下糊。
8. **图标一律用文字字形，不引 `material-icons`**：`libs.versions.toml` 里没有 material-icons（只有 compose BOM/ui/ui-graphics/tooling/material3），新增依赖只为两个图标不值当。沿用代码库既有的 `‹` 风格，设置入口用 `⚙`（U+2699，系统字体自带）。
9. **`installing` 必须是独立状态**：本地 zip 导入和「删除本地数据」共用 `busy`（都怕重入），但只有前者该渲染成安装流程。如果从 `busy` 反推「正在解压」，删数据时首页会闪一下「正在解压」——所以 `SpikeController` 新增了 `installing`。
10. **`SpikeController` 不改名**：它现在同时服务首页/设置/打靶三页，「Spike」这个名字已经名不副实，但改名会污染 6 个文件的 diff 且没有任何功能收益（§4.3.4-6 也已经依赖它的单实例语义）。留待 P5 统一清理。
11. **`Prefs` 补 `getLong`/`putLong`**：24 小时自动检查需要存时间戳，SharedPreferences 原生支持 Long；未初始化时读走 fallback、写静默 no-op（`PrefsTest` 锁住），所以这些逻辑在 JVM 上也能跑。
12. **设置页的「更新到新版本」仍然回首页再下载**（§4.3.4-3 的 `pendingUpdate` 标记）：通知权限的 launcher 在首页，下载链只保留一份实现。本块把 `LaunchedEffect(controller.pendingUpdate)` 从打靶页搬到了首页（`HomeScreen`），打靶页的那段已删——否则两处都会消费同一个标记。

### 4.3.6 P3（游戏页）实现期决定

> P3 最后一块：**游戏页**（返回按钮淡出 / 加载+错误浮层 / 方向 / 静音），对应鸿蒙 `pages/GamePage.ets`（284 行）。

1. **返回按钮放左上角**（鸿蒙在右上角）：右上角被 P0–P2 的打靶指标面板占着，两个浮控件叠在一起会互相吃掉点击。代价是与鸿蒙不一致，对齐方案是把面板挪到 debug 开关后面（第 8 条）——那时可以把返回按钮搬回右上角。
2. **错误浮层从「右上角一行 12sp 小字」改成居中卡片**：卡片里是「游戏加载失败」+ `加载出错: <detail>` + 一句自助说明 + `重试` 按钮；面板里只留一行「页面有报错」免得被撑爆。理由：鸿蒙那行小字在游戏画面上几乎读不到，也没有任何恢复手段（只能自己找返回）。
3. **加载浮层是安卓新增的**：鸿蒙靠 Web 组件自己的白屏过渡，安卓 WebView 首帧前是一块惨白，且与暗色主题格格不入。安卓先铺 `GAME_BG_ARGB`（暗色）底，再叠一层 `0xCC000000` 蒙层 + 精灵球 + 「正在载入游戏…」，`pageLoaded` 一到就拿掉。
4. **切后台只静音、不 `WebView.onPause()`**（对齐鸿蒙注释里的「保留游戏进程」）：`onPause()` 会把 Phaser 的计时器一起冻住，回前台容易追帧/卡顿；代价是后台仍占 CPU/内存，留 P5 真机看电量再决定要不要加。
5. **静音双路径**：`WebViewFeature.MUTE_AUDIO` 可用就用 `WebViewCompat.setAudioMuted`（与鸿蒙 `controller.setAudioMuted` 同语义），不可用则注入 `window.__pkGame.sound.mute`（引导脚本已经把 Game 实例挂在那里）。Destroy 前也先静音一次（安卓上有「destroy 后音频还在响」的机型）。
6. **浮层判定做成纯函数**（`ui/GameState.kt` 的 `gameOverlayOf`，`GameStateTest` 9 条）：本机没有模拟器，写进 `@Composable` 就等于零验证。最要命的一条是**错误优先于加载中**——加载失败时 `pageLoaded` 永远是 false，顺序反了用户就只看到「正在载入…」。返回按钮的 `3000ms` / `0.2f` 也一并定了常量。
7. **存档回填提示用自增序号而不是条数**：`SpikeController.saveRestoredSeq` 每回填一次加一，游戏页 `LaunchedEffect(seq)` 弹一次 toast。用条数的话，两次回填条数恰好一样时状态值不变，第二次提示就不会弹。桥回调在 JS 线程，写状态前先切主线程。
8. **打靶指标面板本块保留原样，但必须在发布前挪到 debug 开关后面**：P0–P3 每条真机判据都要在截图里同时拍到游戏画面与计时，所以现在不能关；线上包带着它既丑又泄露实现细节。P5 清单里记一条。
9. **方向本块没写新代码**：§4.3.4-5 的 `ApplyPageOrientation(gamePageActive = controller.showGame)` 已经盖住游戏页，本块只确认它挂在了根组件（而不是 `MainActivity`）。切后台静音也走的是 Activity 生命周期（`LocalLifecycleOwner`），不另开一套。
10. **两项鸿蒙游戏页能力本块不做**：① `mediaOptions.audioSessionType`（鸿蒙把 Web 音频映射到 GAME 流，避免被其它音频压低）→ 安卓没有对应 API，只能靠第 5 条的两条路径；② `WebDownloadDelegate`（游戏内导出存档/下载文件）→ 当时判给 P4，**已在 §4.3.7 落地**。

### 4.3.7 P4（存档导出/导入 + Auto Backup）实现期决定

> P4 第一块：游戏内「导出数据」与存档导入，对应鸿蒙 `pages/GamePage.ets` 的 `WebDownloadDelegate` + `onShowFileSelector`。

1. **主路径是「在页面里拦下载」，而不是 `DownloadListener`**：参考实现（`Admiral-Billy/PokeRogue-App-Android`）走的是「`DownloadListener` 收到 `blob:` → 注入 XHR 拿 base64」，但 `blob:` 下载在部分 WebView 上根本不回调 `onDownloadStart`（游戏用的是 `URL.createObjectURL` + `<a download>`），失败模式是「点了没反应，也没有日志」。所以先把 `URL.createObjectURL` / `HTMLAnchorElement.prototype.click` / 捕获阶段 `click` 补上，自己拿到 Blob；`DownloadListener` 只保留成兜底（真漏下来了再用 XHR 过一遍桥），两条路径不会双发——JS 拦到就 `preventDefault`，原生下载根本不会启动。
2. **桥对象就用已有的 `PokeNative`**（不新建 `addJavascriptInterface`）：桥方法越多，release 包要保住的字符串就越多；`saveDownload` 由 `-keepclassmembers @JavascriptInterface` 规则自动保住，另外顺手把 `DownloadBridge` 整个类 keep 住（里面全是 JS 与中文文案常量）。
3. **零存储权限**：API 29+ 走 `MediaStore.Downloads`（`RELATIVE_PATH=Downloads`，不建子目录），API 26–28 走 `getExternalFilesDir(DIRECTORY_DOWNLOADS)`。两者都不需要 `WRITE_EXTERNAL_STORAGE` —— **故意不学参考实现**（它申请了存储权限往公共 `Downloads/` 写，Play 会挑刺）。代价：老机型上导出文件落在应用专属目录（卸载会删），Toast 里把真实路径写清楚。
4. **文件名、URL 都是不可信输入**：页面可能传来 `../../etc/passwd`、`..`、`/`、控制字符、超长名字。统一过 `safeFileName`（去非法字符 + 去前导点 + `trimEnd('.')` + 拒绝 `.`/`..` + >120 字符保扩展名截断）；写进 JS 的字符串再过 `jsStringLiteral`（转义 `\` `"` 换行制表 + `<` → `\u003c`，防提前关掉 `<script>`）。
5. **`IS_PENDING` 两阶段写入**：MediaStore 里先 `IS_PENDING=1` 插入 → 写完置 0；写中途失败就 `delete` 掉半成品（否则下载目录里会出现一个 0 字节的 `data.prsv`，用户以为导出成功了）。
6. **导入用 `WebChromeClient.onShowFileChooser` + Compose launcher 转发**：WebView 需要的是「一次回调」，而 Compose 的 `rememberLauncherForActivityResult` 是异步的，所以用非 Compose 的 `FileChooserSlot` 存住 callback（不放 state，避免重组），并且在 `onDispose` / 新请求到来时 `cancel()`（回 `null`）——不回值的话 WebView 里那次选择会永久挂住。
7. **导出结果回主线程弹 Toast**：`saveToDownloads` 是磁盘 IO，放 `Dispatchers.IO`；`saveExportSeq`（自增序号）触发 `LaunchedEffect`，不用字符串做状态（同名连续导出两次也会弹）。
8. **Auto Backup 只备「设置偏好 + `files/saves`」，而且必须是 include 白名单**：写了 `<include>` 之后其余全部不备，lint（`FullBackupContent`）也按这个语义校验（写 `<exclude domain="external">` 反而会被报「`.` is not in an included path」）。这一条很关键：游戏本体在 `getExternalFilesDir()/game`（约 1.5GB），而 Auto Backup 有 25MB 配额，一旦把外部存储估进去，**整次备份会失败，连存档一起丢**。
9. **Auto Backup 不能代替「应用内导出存档」**：备份是系统行为（用户没开就什么也不发生），且只在换机/重装时生效；“把存档拷出来”这个显式需求必须靠 `saveDownload` 这条链。两者是互补，不是二选一。
10. **API 29 的 `MediaStore.Downloads` 要 `@RequiresApi` 标注**：`saveViaMediaStore` 单独抽成方法后，外面那层 `if (SDK_INT >= Q)` 对 lint 不可见，会报 `NewApi` **error**（不是警告，会抦住 `lintDebug`）。

### 4.3.8 P4（应用内自更新）实现期决定

> P4 第二块：设置页 / 首页的「去更新」从「打开浏览器」升级为「应用内下载 + 交系统安装器」，对应 § 4.3.4-3 当初刻意留下的 `apkUrl` 字段。

1. **APK 下载不开前台服务**：`dataSync` 前台服务是为了 507MB 资源包（用户会切后台），而安装包只有几 MB 且**必须用户亲手点「安装」**才能完成；用前台服务反而：① 吃掉 Android 15 的 6h/24h 配额（真在下载资源包时被推迟/杀）；② 还要给「后台静默下载完但用户没点安装」的多余状态兜底。所以下载跑在 `SpikeController` 的 application scope 里，用 `@Volatile` 取消标志逐块检查。代价：下载中切后台/被杀会断，回设置页重新点（文件名固定，会覆盖半截的 `.part`）。
2. **装包目录 `getExternalFilesDir(null)/apk/`**（不是 `cacheDir`、也不是公共 Downloads）：安装器要能读到它（FileProvider 只放行这一处）；自动清理不会删它；Auto Backup 因为是 include 白名单，天然不会去搬它（§4.3.7-8）。
3. **`.part` 两阶段写 + 魔数校验**：先写 `<name>.apk.part`，读完 `fd.sync()` 再 `renameTo`；中途被杀不会剩下一个「长度对得上、内容不全」的包（这种包安装器只会报「解析包时出现问题」）。下完用 `size >= 64KB` + `PK\x03\x04`/`05 06`/`07 08` 双条件判定——国内镜像返回 404 页面/HTML 时 `Content-Length` 也是 200 且非空，不给校验就会把一段 HTML 交给安装器。校验失败直接删包 + `FAILED`。
4. **`REQUEST_INSTALL_PACKAGES` 是常规权限，真正的门禁是用户那个「安装未知应用」开关**：拉起安装器前必须 `canRequestPackageInstalls()`（包在 `runCatching` 里，部分 ROM 会抛）；没开就 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` + `package:` 精确跳到本应用那一项，而不是让用户自己去设置里翻。
5. **FileProvider 只放行 `apk/`**（`external-files-path` + `files-path` 两条，分别对应有无外部存储）：FileProvider 的授权是**整条路径**，放行整棵树等于给拿到该 URI 的应用读全部私有文件。`provider_paths.xml` 的 path 必须与 `ApkUpdater.apkDir` 保持一致（改目录不改 xml = 装包时 `IllegalArgumentException`）。
6. **用户取消失终态用 `IDLE` 而不是 `FAILED`**：取消不是错误，弹回「重试更新」会让用户以为失败了；置 `IDLE` 并把「已取消更新下载」放在详情行（`ApkUpdateState.message`），行标题回到「去更新」。
7. **安装器的拉起放在根组件**：`READY` 之后不管当前在哪个页面（首页弹窗 / 设置页 / 打靶页）都要弹安装界面，所以 `LaunchedEffect(phase, fileName)` 写在 `PokeLauncherApp` 里；键只用「阶段 + 文件名」——从安装器返回后阶段不变，不会重复弹（否则是死循环）。没权限时**不自动**跳设置页，先 Toast 说清楚原因。
8. **两处 UI 的判定与文案必须是同一组纯函数**：`apkActionOf`（按阶段 → 开始/取消/安装）+ `apkRowLabel` / `apkRowValue`（标题与右侧文字）全部在 `appupdate/ApkUpdate.kt`，`ApkUpdateTest` 15 条锁住；否则很容易长歪成「一处可取消、一处重新下载」，点两下双开下载（同一目标文件互盖）。
9. **成功装好后 `pruneOldApks` 只留最新那个包**：每版一个文件名（`pokerogue-<版本>.apk`）会无限堆积；清理时机放在「校验通过之后」（删早了会把刚下好的包也带走，删晚了失败包会残留）。
10. **不做「静默安装 / 装完自动重启」**：`REQUEST_INSTALL_PACKAGES` 不允许静默安装，系统安装界面的「打开/完成」也拿不到可控回调；装完由系统接管（用户点「打开」即为新版本启动）。弹窗上的「安装更新」只是拉起界面，不要写成「已更新」。

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
| P2-8 | 参数调整文案红线 | 见 P3-4 / P3-10（真机验收统一在那两行做） | |

**P3（UI）——待真机项**

| # | 项 | 判据（看界面/日志） | 结果 |
| --- | --- | --- | --- |
| P3-1 | 参数调整页可进入/可返回 | 打靶页右上角「设置」→ 「参数调整」行进入；系统返回键回到设置页，再按回打靶页；总开关关闭时所有条目开关与滑块置灰 | |
| P3-2 | 滑块档位正确 | 「强制奖励稀有度」只有 5 档（Common/Great/Ultra/Rogue/Master，不能停在中间值）；「糖果获取倍率」能拉到 1 与 100 | |
| P3-3 | 状态持久化 | 改开关 + 拉滑块 → 杀进程重开 → 页面仍是改过的值；DevTools 里 `/__cheats__.js` 的 `__CHEAT_PARAMS__` 与新值一致 | |
| P3-4 | 文案红线 | 整页（标题/提示/开关/滑块）无「作弊」字样 | |
| P3-5 | 设置页可进入/可返回 | 打靶页右上角「设置」进入；返回键回打靶页；「参数调整」行显示的已开启/已关闭与实际一致（开关状态在参数调整页改完返回后应刷新） | |
| P3-6 | 屏幕方向 | 设置页选「横屏」→ 进入游戏页立即横屏、随传感器左右翻转；退出游戏立即回竖屏；选「跟随系统」后重启 App 仍生效（平板上启动器页面不强制竖屏） | |
| P3-7 | 应用更新（跨端关键判据） | 清单里**没有** `androidVersionCode` 时显示「安卓版暂未发布」（**绝不能**显示「发现新版本」）；点一下状态变「检查中…」再回初值；有 `androidVersionCode > 本地` 时出现「去更新（版本号）」并进入**应用内下载**（旧行为是用浏览器打开，已由 P4-8 取代）；清单 424/404（Gitee 挂了）显示「检查失败」而不是崩掉 | |
| P3-8 | 离线资源更新 / 更新到新版本 | 本地资源版本与远端不一致时显示「发现新版本」+ 一行「更新到新版本」；点它 → 返回首页并自动开始下载（日志与 P1 链路一致，**不弹**二次确认）；未装资源时显示「未安装」+ 首页下载引导文案 | |
| P3-9 | 删除本地数据 | 弹确认框（「本地存档不受影响」）→ 确认后行内显示「正在删除…」界面不卡死 → 完成 Toast + 回首页；回首页后「资源版本」= 未安装、能重新下载；日志有 `game deleted` 与 `已清除 WebView 缓存` | |
| P3-10 | 法律页 | 从设置页「法律与免责声明」进入：顶部提示条 + 10 个章节 + 更新日期；**通篇无「作弊」字样**（P2-8 的判据在这里落地）；返回键回设置页 | |
| P3-11 | 首页四态（关键判据） | ① 刚装好启动：短暂「正在检查本地资源…」后落到「未安装」（**不能**闪一下就绪页）；② 下载/解压中：显示下载流（**即使旧资源还在**——拿旧包装新版时不能直接跳就绪页）；③ 下载完成：先看到「PokeRogue 资源准备就绪」约 1s 再切就绪页；④ 暂停后回到首页仍是下载流（能点到「继续」），**不是**未安装页；⑤ 删除本地数据后回到未安装页 | |
| P3-12 | 下载流界面 | 进度条百分比与服务通知一致（±1%）；切后台再回来进度继续；「暂停」后按钮变「继续」且进度条保留；「取消下载」弹确认框（写清已下载多少、会全部丢失），取消后分片与状态文件都没了 | |
| P3-13 | 精灵球动画 | 静止/暂停时球停在正中（不摇）；下载与解压时按 ±14°、每 450ms 换向摇晃；切后台再回来不闪不卡（后台不烧 CPU） | |
| P3-14 | 未安装态的换源/直链/导入 | 「换源重试（丢弃已下载的分片）」只在失败后出现；点「复制镜像直链」→ Toast「直链已复制」；「上传资源」弹出 SAF 选择器 → 选一个 zip → 界面进入「正在解压…」且文件计数前进，完成后回就绪页并能进游戏 | |
| P3-15 | 应用更新弹窗（冷启动） | 首次启动（或清偏好后）冷启动会自动检查一次（日志有请求）；有 `androidVersionCode > 本地` 且 `androidPublished=true` 时才弹「发现新版本」弹窗，`forceUpdate=true` 时无「稍后」；24h 内重启不重复请求；点「去更新」开始应用内下载（清单里没 `apkUrl` 时才退回浏览器打开 `pageUrl`） | |
| P3-16 | 首页文案红线 | 首页/下载流/就绪页/弹窗逐字看一遍：无「作弊」字样（对应 P2-8 / P3-4 / P3-10） | |
| P3-17 | 游戏页返回按钮淡出 | 进入游戏后 3 秒内「返回」是全亮（点一下直接回首页）；3 秒后淡到 0.2（仍可见）；此时点一下只把它**唤回全亮**、**不退出**，再点才回首页；「重载」后计时重新开始 | |
| P3-18 | 游戏页加载 / 错误浮层 | ① 进游戏瞬间是暗底 + 精灵球 + 「正在载入游戏…」，首帧画出来后自动消失（**不能**闪一下惨白）；② 先 `stopServer`（或把 `gameDir` 改坏）再进游戏 → 出现居中「游戏加载失败」卡片 + `加载出错: …` + 「重试」，点「重试」能重新加载（**不能**一直停在「正在载入…」） | |
| P3-19 | 游戏页静音 | 进游戏听 BGM → 按 Home 退到后台：BGM 立即停（日志 `web audio muted=true`）；回前台恢复（`muted=false`）；点「返回」退出游戏页 BGM 也停；拿一台不支持 `MUTE_AUDIO` 的老机型验证 `game.sound.mute` 兜底同样生效 | |
| P3-20 | 存档回填提示 | 抹掉游戏 `localStorage`（DevTools）但保留 `files/saves/localstorage.json` → 重进游戏 → 弹「检测到本地存档缺失，已从快照恢复 N 项数据」，且游戏内进度确实是快照那份（P2-2 的 UI 侧判据） | |

**P4（平台特有）——待真机项**

| # | 项 | 判据（看界面/日志/文件） | 结果 |
| --- | --- | --- | --- |
| P4-1 | 游戏内导出存档（关键判据） | 游戏里点「导出数据」→ 弹 Toast「已导出 data.prsv 到「下载」」；开发者控制台有 `PKDL saved <name> <bytes>`；系统「文件/下载」里能看到 `data.prsv` 且大小与游戏内存档相当（不能是 0 字节） | |
| P4-2 | 导出文件能回导 | 把 `data.prsv` 拷回手机 → 游戏内「导入数据」→ 出现系统文件选择器 → 选该文件 → 存档真的回来了（P4-1 的闭环） | |
| P4-3 | 重复导出不产生垃圾 | 连点两次「导出数据」→ Toast 正常，控制台出现 `PKDL duplicate skipped`（5s 内相同内容不重复写）；手动导两次不同名字也不报错 | |
| P4-4 | 文件名不可信不加路 | 导出成功后看下载目录：文件名无 `/` `\` `..` 前导点；手动用 DevTools 把 `download` 属性改成 `../../x.prsv` 再导 → 仍然落在下载目录里（不能跑到别处） | |
| P4-5 | 旧机型路径（API 26–28 真机/模拟器） | 导出后 Toast 里是真实路径（含包名的应用专属目录）；用 `adb shell ls` 能在 `Android/data/com.lichtcui.pokelauncher/files/Download/` 下看到文件；全程**没有**弹存储权限请求 | |
| P4-6 | Auto Backup 不搬 1.5GB | `adb shell bmgr backupnow com.lichtcui.pokelauncher`（或 Android Studio 的 Back up 面板）→ 日志显示备份完成（不报 quota 超标）；备份包里只有偏好与 `saves/`（用 `bmgr list` + 解包看），没有 `game/` 目录 | |
| P4-7 | 清数据后恢复存档 | 备份一次 → `adb shell pm clear com.lichtcui.pokelauncher` → `bmgr restore` → 打开游戏：偏好回来了（方向/参数调整）且 `files/saves/localstorage.json` 在位（P2-2 的备份侧判据） | |
| P4-8 | 应用内更新下载（关键判据） | 清单填上有效的 `androidVersionCode` + `apkUrl` → 点「去更新」：行标题变「正在下载更新包…」且百分比前进；下载完变「安装更新」并**自动**弹系统安装界面；日志有 `apk update: start` / `apk download: done` / `apk update: ready` | |
| P4-9 | 装包校验挡住假包 | `apkUrl` 指向一个 HTML 页（或 404）→ 阶段到 `FAILED` + 「下载到的不是安装包（可能被网络劫持或链接失效）」，且 `apk/` 目录里**没有**残留文件 | |
| P4-10 | 未知来源门禁 | 先关掉系统的「安装未知应用」→ 点「安装更新」：Toast 中文说明 + 跳到**本应用**的开关项（不是静默失败、不是 crash）；打开开关回来再点 → 正常进安装界面 | |
| P4-11 | 取消与旧包清理 | 下载中点「取消下载」→ 行回到「去更新」且详情显示「已取消更新下载」，`apk/` 里没有 `.part`；再更新一次成功 → `apk/` 里只剩最新那一个包（旧包被 `pruneOldApks` 删掉） | |
| P4-12 | 弹窗与设置页进度同源 | 首页弹窗点「去更新」后就地变进度（此时只能「取消下载」，点外面关不掉）；关掉弹窗后设置页那行显示同一个百分比；下载完两处都变「安装更新」 | |
| P4-13 | 真机安装闭环（最终判据） | 用**够老**的一版包 + 清单指向新包 → 应用内更新 → 系统安装器装完点「打开」→ 「应用版本」显示新版本号，且**存档还在**（存档在 `getExternalFilesDir()/game` 与 `localStorage`，升级不清） | |

**P0 未通过时**的退路：改用 `androidx.webkit` 的 `WebViewAssetLoader`（`https://appassets.androidplatform.net`，免 socket 免 cleartext），代价是丢 HTTP 缓存与 Range。

## 5. 分期计划

| 阶段 | 内容 | 工时 | 状态 |
| --- | --- | --- | --- |
| **P0 打靶** | 工程骨架（Kotlin + Compose, minSdk 26）+ NanoHTTPD + 注入 + 导入 zip + 进游戏计时 | 2–3 天 | ✅ 代码完成 + 本机编译/单测/release 混淆校验通过；**真机判据待跑（§4.4）** |
| **P1 下载/解压** | Release 查询 → 镜像并发测速 → 断点续传 → 原子解压切换 → `recover()`；前台服务 + 通知进度；暂停/继续/取消；SAF 手动导入 | 4–6 天 | ✅ 代码完成（71 单测全绿，debug/release 均编译通过）；**真机判据待跑（§4.4）** |
| **P2 服务与注入** | index.html 补丁、虚拟端点 `/__pk_boot.js` + `/__cheats__.js`、端口占用兜底、8001 stub、`clearCache` 时机、参数调整状态与存档镜像 | 3–4 天 | ✅ 代码完成（累计 180 单测全绿，debug/release 均编译通过，lint 0 error）；**真机判据待跑（§4.4）** |
| **P3 UI** | Compose：首页四态 + 下载流 + 精灵球动画、设置页、参数调整页、游戏页（返回按钮淡出/错误浮层/方向/静音）、法律页 | 4–6 天 | ✅ 代码完成（参数调整页 / 设置页 / 法律页 / 首页四态 + 下载流 + 精灵球 / 游戏页全部落地；累计 240 单测全绿，debug/release 均编译通过，lint 0 error）；**真机判据待跑（§4.4 P3 全部）** |
| **P4 平台特有** | 存档导出/导入、Auto Backup、应用内自更新（FileProvider + `REQUEST_INSTALL_PACKAGES`）、通知权限 | 3–4 天 | ✅ 代码完成（存档导出/导入 + Auto Backup + 应用内自更新 + 通知权限全部落地；累计 270 单测全绿，debug/release 均编译通过，lint 0 error）；**真机判据待跑（§4.4 P4 全部）** |
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

P3 新增（两个都是本机实测到的「工具坑」，不是业务坑）：

22. **本机的 compose lint 检查器会直接崩掉 `lintDebug`**：写 `Modifier.then(...)` 会触发 `SuspiciousModifierThenDetector`，声明 `fun Modifier.xxx()` 扩展又会触发 `ModifierDeclarationDetector`，两者都以 `NoClassDefFoundError`（`androidx/compose/lint/Names$Ui$Layout`）结束——**是任务失败，不是一条警告**。当前的处理是避开这两种写法（用 if/else 选 Modifier），而不是 `lint { disable ... }`（这些检查本身有用）；真要写 Modifier 扩展，先去升级 compose-lint 版本。
23. **release 包不能靠类名取证**：R8 混淆后 `SettingsScreenKt` / `CommonKt` / `SpikeController` 这些类名在 dex 里搜不到（改名 / class-merging 内联）。查 release 只有两条可靠路径：dex 字符串（中文用字节搜索）+ `mapping.txt`。
24. **`SpikeController.deleteGame()` 是异步的**（多了 `onDone` 回调）：删 1.5GB 目录树切到了 IO，调用方不能假设「函数返回就是删完了」；它同时会清掉 `lastCachedGameVersion`。

P3 首页新增：

25. **四态不能只看 `gameReady`**：更新资源包时旧包还在（`gameReady=true`），只看它就永远进不了下载页；要把「流程中」排在前面。同理 `DownloadPhase.PAUSED` **不属于** `isActive`，漏算的后果是用户一暂停就被弹回未安装页、再也点不到「继续」。
26. **`installing` 不能从 `busy` 反推**：本地导入与「删除本地数据」共用 `busy`，反推的结果是删数据时首页闪一下「正在解压」。新增独立状态。
27. **`pendingUpdate` 只能有一处消费**：`LaunchedEffect(controller.pendingUpdate)` 必须待在首页（申请通知权限的 launcher 在那里），打靶页里那段已删——两处都留着会双开下载 / 双弹权限框。
28. **首页「诊断」入口刻意常驻**（鸿蒙只在就绪态给齿轮）：P0/P1 的真机判据必须在「刚装好、还没资源」时就能跑到；这是有意偏离，别为了「还原鸿蒙」把它藏回就绪态。
29. **`installer.recover()` 不能放进「刷新状态」这类热路径**：它会删掉/接管 `stagingDir`，而下载服务可能正拿着同一个目录解压——用户点一下「刷新」就会把解压踩成半成品。恢复只在启动时跑一次（`SpikeController.init`，与鸿蒙同口径）。
30. **`initializing = false` 要等首帧状态全部落定**：已安装资源的用户否则会先闪一下「未安装 / 可用空间缺失」（首帧状态是异步从磁盘读的）。

P3 游戏页新增：

31. **游戏页浮层的判定顺序必须是「错误 > 加载中」**：加载失败时 `pageLoaded` 永远是 false，顺序反了就永远停在「正在载入游戏…」，用户看不到任何错误。`gameOverlayOf` 已按此写、`GameStateTest` 锁住（与坑 #25 同一类：判定顺序错了编译器不会报错）。
32. **`RequiresFeature` 检查必须写在 `if` 条件里**：写成 `val ok = WebViewFeature.isFeatureSupported(...)` 再 `if (ok)` 会被 lint 报 `setAudioMuted should only be called if the feature MUTE_AUDIO is present`（当前基线 0 error / 26 warning，别再加回去）；同时**降级分支不能省**——`MUTE_AUDIO` 在旧 WebView 上不存在，只写 `setAudioMuted` 就是一台机器静音、另一台报错。
33. **切后台静音不能顺手加 `WebView.onPause()`**：会把 Phaser 的计时器一起冻住，回前台容易追帧/卡顿（鸿蒙同样只静音、保留游戏进程）。要省电另开一个开关，别塞进静音这条路径里。

P4 新增：

34. **`blob:` 下载不一定走 `DownloadListener`**：游戏用 `URL.createObjectURL` + `<a download download>` 导出存档，部分 WebView 根本不回调 `onDownloadStart`（症状是「点了没反应、日志也没有」）。可靠做法是在页面里拦（补 `createObjectURL` / `HTMLAnchorElement.prototype.click` / 捕获阶段 `click`），`DownloadListener` 只当兜底；两条路要避免双发（拦到就 `preventDefault`）。
35. **Auto Backup 的 25MB 配额会被 1.5GB 游戏本体直接抦爆**：规则必须是 **include 白名单**（只放 `sharedpref` + `file/saves`），而且不要画蛇添足写 `<exclude domain="external">`——lint 的 `FullBackupContent` 会报「`.` is not in an included path」（写了 include 之后其余本来就都不备）。
36. **`MediaStore.Downloads` 这类 API 29 接口要标 `@RequiresApi`**：把分区存储分支抽成 `saveViaMediaStore()` 后，外层的 `SDK_INT >= Q` 判定对 lint 不可见，会报 `NewApi` **error** 而不是警告（`lintDebug` 直接失败）。
37. **`onShowFileChooser` 的 `callback` 必须恰好回一次值**：玩家取消选择、连点两次「导入」、或页面在被选文件期间销毁时，都得把上一份 callback 用 `null` 结掉（`FileChooserSlot.cancel()`）；漏了就是 WebView 里那次文件选择永久挂住（游戏后续再也弹不出选择器）。
38. **更新包必须用 `FileProvider` 交给安装器**：`ACTION_VIEW` 的 `data` 给 `file://` 在 API 24+ 非 `FileUriExposedException` 就是被安装器静默拒绝，必须是 `FileProvider.getUriForFile` + `FLAG_GRANT_READ_URI_PERMISSION`；`provider_paths.xml` **只放行 `apk/`**——FileProvider 授的是整条路径，放行整棵树等于把私有目录里所有文件读权限送出去（Auto Backup 的 `backup_rules.xml` 是同一个道理）。
39. **`REQUEST_INSTALL_PACKAGES` 只是常规权限，真正的门禁是用户的「安装未知应用」开关**：不检查 `canRequestPackageInstalls()` 就 `startActivity`，症状是「点了没反应」（有的 ROM 报错、有的静默忽略、有的把你带到设置页列表）。必须在拉起安装器前判断并给出中文说明，用 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` + `package:` 精确跳到本应用那一项。
40. **APK 下载必须 `.part` + `fd.sync()` + 改名，下完必须验魔数**：直接写目标文件，中途被杀会留下一个「长度对得上但内容不全」的包，安装器只会报一句语焉不详的「解析包时出现问题」；不校验 zip 魔数/最小体积，国内镜像的 404 页（`Content-Length` 也是 200）会被当成安装包交给安装器。
41. **自更新弹窗与设置页行的状态必须同源**：两处各自写「下载中怎么办」很容易长歪成「一处可取消、一处重新下载」，点两下就双开下载（同一个目标文件互相覆盖，进度还会来回跳）。判定与文案统一走 `apkActionOf` / `apkRowLabel` / `apkRowValue` 三个纯函数（`ApkUpdateTest` 锁住）。

## 7. 风险

| 风险 | 等级 | 应对 |
| --- | --- | --- |
| Dart/第三方库里没有的 WebView 老版本跑不动游戏 | 中 | P0 判据 4；启动版本门禁 + 引导升级 |
| 国产 ROM 后台杀进程导致 507MB 下载中断 | 高 | 前台服务 + 断点续传 + 通知；引导用户加白名单 |
| 国内网络下 Gradle 拉依赖慢 | 低 | Aliyun 镜像仓库兜底（已实测 0.1s） |
| 直发 APK 需用户允许「安装未知来源」 | 中 | 已落地：`canRequestPackageInstalls()` 门禁 + `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 精准跳转 + 中文 Toast（§4.3.8-4 / §6-#39） |
| 游戏更新导致「参数调整」hook 失效 | 低 | 与鸿蒙同源，关闭开关即恢复纯净 |

## 8. 发布

- 出包：`cd android && ./gradlew assembleRelease`（keystore 不入库，走 `~/.gradle/gradle.properties` 或本地 `keystore.properties`，对齐 [`AGENTS.md`](../AGENTS.md) 的签名边界约定）。
- 版本清单：仓库根 [`version.json`](../version.json) 是**两个平台共用**的一份清单。安卓发版时要写这 5 个字段：`androidVersionCode` / `androidVersionName` / `androidMinVersionCode`（可选，低于它强制更新）/ `androidChangelog` / `apkUrl`（留空则「去更新」打开 `pageUrl`）。
  - `apkUrl` 指向 APK 直链（Gitee / GitHub Release 附件皆可）：安卓端会**应用内下载并交系统安装器**；文件名从 URL 末段取，所以别指向目录（否则文件名会取成目录名）。
  - `androidVersionCode` / `androidVersionName` 必须与 `android/app/build.gradle.kts` 的 `versionCode` / `versionName` 完全一致（现在还是 P0 占位值 `1` / `0.1.0-p0`）。
  - **只写鸿蒙字段而不写 `androidVersionCode` 时，安卓端会显示「安卓版暂未发布」**：这是刻意设计（宁可说「未发布」也不能把用户引到 HAP 页），但也意味着缺字段时安卓用户收不到任何更新提示。
- 发版同时推 `origin`(Gitee) 与 `github` 两个远端（见 AGENTS.md §2）。
