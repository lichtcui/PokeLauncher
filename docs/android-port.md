# 安卓版方案（PokeLauncher → Android）

> 决策与分期计划。实施完成后本文件即成为安卓模块的专题文档（对齐 [`architecture.md`](./architecture.md) 的角色）。
>
> 现状：**P0 打靶阶段**（骨架已落地在 [`../android`](../android)）。

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

- [`Cheats.ets`](../entry/src/main/ets/model/Cheats.ets) 的 `CHEATS` 注册表与 7 条 `script` → [`InjectScripts.kt`](../android/app/src/main/java/com/lichtcui/pokelauncher/server/InjectScripts.kt)
- [`CheatState.ets`](../entry/src/main/ets/model/CheatState.ets) 的 `SCENE_RUNTIME`（场景捕获：`__onScene` / `__onPhase` / `Array.prototype.push` hook / `Phaser.Game` 包装）+ `buildBootScript()` 拼接逻辑
- `FORCE_WILL_READ_FREQUENTLY` 的 2D canvas 补丁（**Chromium 通病，Android WebView 同样需要**，启动 28s → 4s 的收益照样拿）
- `SaveGuard` 的 JS 片段（保留，正好让存档落进会被 Auto Backup 备份的 `files/`）
- `resolveRequestPath()` 路径穿越判定、镜像列表、测速策略
- 原子安装状态机（`game.new` → `.extract-complete` 标记 → 两次 rename → `recover()`）
- `Format.ets` 三个格式化函数（改为 Kotlin JVM 单测）

### 3.2 需要重写（平台 API 层）

| HarmonyOS / ArkTS | Android / Kotlin |
| --- | --- |
| `EntryAbility` | `Application` + 单 `MainActivity` |
| `LocalHttpServer`（TCPSocketServer，手写 HTTP/1.1） | `NanoHTTPD` 2.3.1 实例 ×2：`127.0.0.1:18787`（资源）+ `:8001`（游戏内 API stub） |
| `request.agent` 后台下载 | OkHttp + Range（`.part` 续传）+ 前台服务（`dataSync`） |
| `zlib.decompressFile` | `java.util.zip.ZipFile` |
| `preferences` | DataStore(Preferences) |
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
│  ├─ core/       Const / Paths(resolveRequestPath) / Format —— 无 Android 依赖，可 JVM 单测
│  ├─ server/     GameServer(NanoHTTPD×2) / InjectScripts(canvas 补丁·SaveGuard·作弊运行时)
│  ├─ install/    GameRepository(Release 查询·镜像测速·断点续传) / GameInstaller(原子解压切换·recover)
│  ├─ web/        PokeWebView(设置·JS 桥·DownloadListener·onShowFileChooser·静音)
│  └─ ui/         Compose：首页四态 / 设置 / 参数调整 / 法律 / 游戏页
└─ app/src/test/  core 层单测（对齐鸿蒙 ohosTest 的覆盖范围）
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

### 4.1 已落地代码（P0 骨架，可编译可装机）

```
android/                                   Gradle 8.14.3 / AGP 8.11.0 / Kotlin 2.0.21 / Compose BOM 2024.12.01
├─ gradlew, gradle/wrapper/                wrapper（已生成，distributionUrl = gradle-8.14.3-bin）
├─ app/build.gradle.kts                    applicationId=com.lichtcui.pokelauncher, minSdk 26, target/compile 35
├─ app/proguard-rules.pro                  keep InjectScripts / Const / @JavascriptInterface / NanoHTTPD
├─ app/src/main/java/.../
│  ├─ App.kt                               Application：启动日志 + 仅 debug 开 WebView 远程调试
│  ├─ MainActivity.kt                      唯一 Activity（singleTask + configChanges 不重建）
│  ├─ core/ Const / Paths / Mime / Format  无 Android 依赖，可 JVM 单测
│  ├─ server/ GameServer.kt                NanoHTTPD×2（18787 资源 / 8001 API stub）
│  ├─ server/ InjectScripts.kt             引导脚本：canvas 补丁 + 计时埋点 + 错误转发
│  ├─ install/ GameInstaller.kt            原子解压切换 + recover() + 存储预检
│  ├─ web/ PokeWebView.kt                  WebView 配置 + PokeNative JS 桥
│  └─ ui/ Theme / SpikeController / SpikeScreen   打靶页（= P0 的全部 UI）
├─ app/src/test/java/.../core/             18 个 JVM 单测（Paths 7 / Format 6 / Mime 5）
└─ app/src/main/res/                       图标（品牌红环）/ 主题 / network_security_config
```

已实测通过（本机，**尚无真机**）：

| 检查 | 结果 |
| --- | --- |
| `./gradlew assembleDebug` | ✅ `app-debug.apk` 9.2 MB |
| `./gradlew assembleRelease`（R8 + 资源收缩） | ✅ `app-release-unsigned.apk` 924 KB |
| `./gradlew testDebugUnitTest` | ✅ 18 tests, 0 failures |
| release 包 JS 桥是否存活 | ✅ 差分比对 debug/release 的 dex：`PokeNative` / `reportTiming` / `reportState` / `$Landroid/webkit/JavascriptInterface;` 均在 |
| manifest 基线 | ✅ minSdk 26 / targetSdk 35 / 仅 INTERNET + ACCESS_NETWORK_STATE / cleartext 全局 false + 回环白名单 |

### 4.2 怎么编译 / 怎么跑

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export ANDROID_HOME=/usr/local/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$PATH"
cd android
./gradlew assembleDebug           # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # core 层单测
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

### 4.3 与上面计划的差异（实现期决定）

1. **NanoHTTPD 换成有界线程池**：默认 `DefaultAsyncRunner` 是「每连接一个线程」且无上限，而游戏冷启动会并发拉数百个资源。改用 16 槽位的 `PooledAsyncRunner`。
2. **没做 gzip 响应**：P0 要与鸿蒙基线做**公平** A/B，压缩会掩盖 canvas 补丁的真实收益；P5 再按需加。
3. **A/B 开关做进了 UI**：`canvas=0/1` 由查询参数传给 `/__pk_boot.js`，服务端按请求生成脚本，所以切开关只需重载，不必重装资源。
4. **`gameSession` 计数器强制重建 WebView**：只调 `loadUrl` 复用同一 WebView 不算冷启动（canvas 补丁是一次性注入），A/B 必须重建。
5. **8001 stub 与 18787 同生共死**：stub 起不来只告警不拦（游戏只是多等一次超时）。
6. **打靶页没有 ViewModel**：状态放在 `SpikeController`，由 Activity 持有，避开 `viewModel()` 工厂/反射在 R8 下的坑；`configChanges` 已声明旋转不重建。
7. **P0 不做 `onShowFileChooser`**：游戏内存档导入用的文件选择器留到 P4。

### 4.4 实测记录（待真机，空着等数据）

| # | 项 | 鸿蒙基线 | Android 补丁关 | Android 补丁开 |
| --- | --- | --- | --- | --- |
| 2 / 3 | Game ready（`ready` 埋点） | 28s → 4s | | |
| 7 | `getImageData` 次数 | ~2 万 | | |
| 4 | Chromium 主版本 | ArkWeb 级 | | |

**P0 未通过时**的退路：改用 `androidx.webkit` 的 `WebViewAssetLoader`（`https://appassets.androidplatform.net`，免 socket 免 cleartext），代价是丢 HTTP 缓存与 Range。

## 5. 分期计划

| 阶段 | 内容 | 工时 |
| --- | --- | --- |
| **P0 打靶** | 工程骨架（Kotlin + Compose, minSdk 26）+ NanoHTTPD + 注入 + 导入 zip + 进游戏计时 | 2–3 天 |
| **P1 下载/解压** | Release 查询 → 镜像并发测速 → 断点续传 → 原子解压切换 → `recover()`；前台服务 + 通知进度；暂停/继续/取消；SAF 手动导入 | 4–6 天 |
| **P2 服务与注入** | index.html 补丁、虚拟端点 `/__pk_boot.js`、端口占用兜底、8001 stub、`clearCache` 时机 | 3–4 天 |
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
