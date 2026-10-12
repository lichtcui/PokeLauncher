# AGENTS.md

本仓库是 **HarmonyOS NEXT 原生 HAP 项目**（ArkTS）。本文件只记录**本项目特有**的约定；通用的 DevEco / 命令行编译 / 真机操作 / 签名 / 发布流程交给全局 skill（§1），不要再抄回这里。

> 项目说明见 [`README.md`](./README.md)；架构 / 作弊 / 自更新等专题见 [`docs/`](./docs)（§3）。

---

## 1. 通用 HarmonyOS 操作 → 用 skill

| 场景 | skill |
| --- | --- |
| `hvigorw` 编译失败；Node / SDK / Java / Hvigor 环境诊断 | `harmonyos-build-doctor` |
| 接手项目、只读基线审计（Git / 构建 / 模块 / SDK / 签名） | `harmonyos-project-audit` |
| 签名配置（`signingConfigs`、凭据边界、Git 安全） | `harmonyos-release-signing` |
| 发布前产物独立校验（`hap-sign-tool` / SHA-256 / 冒烟） | `harmonyos-release-check` |
| ArkTS/ArkUI/NDK API 查询、DevEco Studio / 模拟器、`hdc`/`uitest`/`aa`/`bm`/`hilog`/`hidumper`、ArkWeb DevTools | `harmony-next` |

> 这些是**本机全局安装**的 skill（由 triage 按需路由）。换机器 / CI / 别人 clone 时未必存在，此时按通用 HarmonyOS 流程自行处理。

- 产物：`entry/build/default/outputs/default/entry-default-signed.hap`
- App 名 **PokeRogue**（launcher label / `EntryAbility_label`；描述里写明“离线鸿蒙版”），bundle 仍为 `com.lichtcui.pokelauncher`（改 bundle 会换沙箱并丢存档）
- **每次改完代码都必须走完「编译 → 安装 → 重启 → 截图观察」闭环**。只编译不安装，设备上跑的仍是旧版本，会误判「改动没生效」。
- ⚠️ `build-profile.json5` 的 `compatibleSdkVersion` 为 `"5.0.0(12)"`、`targetSdkVersion` 为 `"6.1.1(24)"`（本机 SDK 是 API 26，真机是 API 24）。不要按旧文档把 `compatibleSdkVersion` 抬到 24——代码里多处以 `deviceInfo.sdkApiVersion` 对 API 12~21 的新特性做运行时兜底（见 [`docs/app-update.md`](./docs/app-update.md)），抬高会丢掉低版本兼容。报「sdk version 不匹配」先查这里。

## 2. 本项目约定

### 仓库 / 提交

- **不要提交** `build-profile.json5` 的签名信息、`signature/`、`local.properties`、`.idea/`、`.hvigor/`、`build/`。
- `build-profile.json5` 已设置 **`skip-worktree`**（`git ls-files -v` 显示 `S`）：
  - 仓库里保存的是**干净版**（`signingConfigs: []`）；本地 DevEco 写入的签名改动被 git 忽略，`git status` 不会显示它 —— 这是正常的。
  - 若确实要修改该文件（如改 `compatibleSdkVersion`）：
    ```bash
    git update-index --no-skip-worktree build-profile.json5   # 先解除
    # ... 修改并 commit ...
    git update-index --skip-worktree build-profile.json5      # 再恢复
    ```
- 用 DevEco Studio 打开本目录**首次 Sync** 会生成 `hvigorw`、`hvigor/hvigor-wrapper.js`、`local.properties`（未入库）。
- 本地签名缺失时：DevEco `File > Project Structure > Signing Configs` 勾选自动签名重新生成。
- 本仓库有两个远端：`origin`（Gitee，App 更新清单 `version.json` 由它读取）与 `github`（GitHub，主页/Issue/文档）。**每个提交都要推到两边**：`git push origin master && git push github master`（或一条命令 `git push both master`，`both` 是一个远端配两个 push URL）。GitHub 走 HTTPS，国内网络可能不稳/被墙，报 HTTP2/连接失败时重试即可——但**至少要保证 Gitee 推上去了**（App 自更新依赖它）。
- **提交前先编译通过**，再真机验证。命令行校验（debug 包，最快）：
  ```bash
  export DEVECO_SDK_HOME=/Applications/DevEco-Studio.app/Contents/sdk
  export PATH="/Applications/DevEco-Studio.app/Contents/tools/node/bin:/Applications/DevEco-Studio.app/Contents/tools/ohpm/bin:$PATH"
  /Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw assembleHap --mode module -p product=default -p buildMode=debug --no-daemon
  ```
  改动涉及 release 混淆时另跑 `-p buildMode=release` 并真机回归（见下）。

### 代码

- ArkTS 严格模式：不要用 `any`；`fileIo` 没有 `writeFileSync`（用 `openSync`+`writeSync`+`closeSync`）；`getHostContext()` 返回 `Context | undefined` 需判空。
- 改 UI 文案/布局：首页各状态视图在 `components/LauncherViews.ets`（`NotDownloadedView` / `DownloadFlowView` / `ReadyView`），下载/解压业务在 `model/LauncherSession.ets`（页面只做渲染 + 状态回写），动画在 `components/PokeballLoader.ets`（阶段枚举 `PrepPhase` 与时长常量也在该文件），设置页在 `pages/Settings.ets` 的 `build()`。
- **ArkUI 坑**：`@Builder` 的**值参数**不会触发重渲染，动态文案要直接在 `build()` 里读 `@State`（见 `Settings.ets` 的「检查更新」「删除本地数据」行）；需要随状态刷新时用 `@Component` + `@Prop`。
- **release 已开混淆**（`entry/build-profile.json5` + `entry/obfuscation-rules.txt`）。新增 JSON 解析字段或 `javaScriptProxy` 的 `methodList` 方法名时，必须把对应属性名加进 `-keep-property-name`，否则 release 包会静默读不到值。未开 `-enable-filename-obfuscation`（会破坏 `main_pages.json` 静态路由）。
- 新增静态资源放 `entry/src/main/resources/base/media/`，引用 `$r('app.media.xxx')`。
- **新增作弊条目只改 `shared/cheats.json` + 新增一个 `shared/js/cheats/<id>.js`**，然后跑 `node scripts/gen-shared.mjs`（见 [`docs/cheats.md`](./docs/cheats.md)、[`shared/README.md`](./shared/README.md)）。两端的注册表（`entry/src/main/ets/model/Cheats.ets`、`android/.../cheat/Cheats.kt`）是**生成物，不要手改**；UI/状态/注入会自动生效。
  - ⚠️ 命名：该功能在 **UI 上叫「参数调整」**（`作弊` 字样会触发应用商店审核风险），代码/文档沿用 `cheat` 命名。**新增任何用户可见文案都用「参数调整」**。
- **跨端共享代码 = `shared/`（生成，不是引用）**：鸿蒙 ArkTS 与安卓 Kotlin 没有共同运行时（KMP 不支持 OHOS target），两端之间真正能共享的只有 **JS 字符串、注册表数据、文案与映射表**。`scripts/gen-shared.mjs` 从 `shared/**` 生成 **10 个文件**（每端 5 个：`Cheats` / `SharedJs` / `LegalText` / `Mime` + 测试用的行为向量 `Cases.gen.ets`（鸿蒙 ohosTest）/ `CasesGen.kt`（安卓单测）），生成物一律**不要手改**。
  - 护栏：`hvigorfile.ts` 的 `checkSharedCode` 插件与 Gradle 的 `:app:checkSharedCode`（`preBuild` 前置）都会跑 `node scripts/gen-shared.mjs --check`，**不一致直接构建失败**；它还会拦「把共享 JS 重新内联回某一端」与 `$` / `"""` / 反引号 这类会静默破坏 raw string 的字符，并对账 `common/Const.ets` ↔ `core/Const.kt`（端口 / 镜像列表 / 版本清单地址；这两份**不生成**，因为各自还有平台独有内容），以及**测速参数**（`PROBE_BYTES` / `PROBE_TIMEOUT_MS` / `RANK_TTL_MS`）、**存储预检系数**（鸿蒙 `requiredStorage()` 的 `2.4 / 3.2` ↔ 安卓 `Storage.FACTOR_FRESH / FACTOR_UPDATE`）、`USER_AGENT`（有意不同，白名单锁死）。
- **跨端共用文案与映射表**：法律页正文改 [`shared/legal.json`](./shared/legal.json)（占位符只能出现在 `placeholders` 里，两端措辞差异不许写进正文）；MIME 表改 [`shared/mime.json`](./shared/mime.json)（`:js` 必须是 `text/javascript`，生成器硬断言）。改完跑 `node scripts/gen-shared.mjs`。
- **JS 桥 / 虚拟端点 / 注入链顺序也是「写好几处」的契约**：`PokeSaveGuard` 的对象名与方法集合、`/__cheats__.js`、`__CHEAT_PARAMS__`、存档守卫→参数调整→canvas 的顺序，改任一处漏另一处都是**静默失效**（脚本拿到 `undefined` 直接跳过）。`node scripts/gen-shared.mjs --check` 会对账这些（含安卓 `@JavascriptInterface` ↔ JS 调用方 ↔ `BRIDGE_METHODS` ↔ `proguard-rules.pro`）。
- **`version.json` 是手写的**（发版第 2 步）：`--check` 会校验它——键名必须被某一端解析器认识、`minVersionCode ≤ versionCode`、写了 `android*`/`apkUrl` 就必须有 `androidVersionCode` + `androidVersionName`、URL 要带协议。手写清单的错字两端都会当它不存在，不拦就只能等用户反馈。
- **release 混淆白名单也要同步**：鸿蒙 `JSON.parse(...) as T` 解析出的字段（含 `assets: ReleaseAsset[]` 这类嵌套）与 `javaScriptProxy` 的 `methodList`，都必须在 `entry/obfuscation-rules.txt` 的 `-keep-property-name` 里——漏一个，release 包**静默读不到值**（debug 正常）。`--check` 会从源码递归收集字段名逐个核对，别再靠人记。
- **纯函数的行为期望值也只写一份**：路径判定（安全边界）/ 展示格式化 / 版本比较两端各有一份实现，期望值放 [`shared/cases/`](./shared/cases)（`paths.json` / `format.json` / `version.json`），两端测试**遍历同一套向量**（鸿蒙 `LocalHttpServer.test` / `Format.test` / `GameRepository.test`，安卓 `PathsTest` / `FormatTest` / `VersionTest` / `MimeTest`）。**不要在测试里再手写一份期望值**——`--check` 会用正则拦（连两端手抖写回一条 `resolveRequestPath("/%2e%2e/x")` 都报错）。`paths.expect = null` = 应当拒绝。鸿蒙那几个套件只能跑在设备上，所以另有 `node scripts/check-harmony-vectors.mjs`（把鸿蒙纯函数剪出来在 Node 里喂同一批向量，`--check` 会顺带跑）——**它只是无设备时的替代证据，真机在手仍要跑 `entry@ohosTest`**。
- **存档是 Web 的 `localStorage`，物理落在 `<沙箱>/cache/web/`**，会被「清除缓存」清掉。因此 `model/SaveGuard.ets` 会把它镜像到 `files/saves/` 并在清空时自动回填——**任何需要持久化的数据都不要放 `cache/`**，放 `files/`。

### 测试（ohosTest，跑在真机）

测试源码在 `entry/src/ohosTest/ets/test/`（hypium，项目根 `oh-package.json5` 的 `devDependencies`）。**新增套件要在 `List.test.ets` 里注册**。只放纯函数/无副作用逻辑。**判据是 `shared/` 里的同一套向量**（paths / format / version / mime / 法律页正文），套件只负责把两端实现接上去，别在测试里再抄一份期望值（生成器会拦）。当前套件：`LocalHttpServer.test.ets`（路径向量）/ `Format.test.ets`（格式化向量 + NaN·Infinity 手写边角）/ `GameRepository.test.ets`（版本比较向量 + MIME 表向量）/ `LegalText.test.ets`（法律页正文）。本机没设备时改跑 `node scripts/check-harmony-vectors.mjs`（paths / format / version / mime 四类，法律页正文由生成器对账、不需要跑）。

```bash
export DEVECO_SDK_HOME=/Applications/DevEco-Studio.app/Contents/sdk
export PATH="/Applications/DevEco-Studio.app/Contents/tools/node/bin:/Applications/DevEco-Studio.app/Contents/tools/ohpm/bin:$PATH"
HVIGOR=/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw
HDC=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc

"$HVIGOR" assembleHap --mode module -p module=entry@ohosTest -p product=default -p buildMode=debug --no-daemon
"$HDC" install -r entry/build/default/outputs/default/entry-default-signed.hap
"$HDC" install -r entry/build/default/outputs/ohosTest/entry-ohosTest-signed.hap
"$HDC" shell aa test -b com.lichtcui.pokelauncher -m entry_test -s unittest OpenHarmonyTestRunner -s timeout 60000
# 通过判据：输出 `OHOS_REPORT_RESULT: ... Failure: 0, Error: 0`
```

### 调试开关（`common/Const.ets`）

- `DEBUG_UI`（**默认 false**）：true 时开 ArkWeb 远程调试，仅本地调试用。
- `FORCE_WILL_READ_FREQUENTLY`（默认 true）：强制 2D canvas 走 CPU，消除 `getImageData` GPU 读回开销。

## 3. 专题文档（`docs/`）

| 文档 | 内容 |
| --- | --- |
| [`docs/architecture.md`](./docs/architecture.md) | 目录结构、运行原理、关键行为 |
| [`shared/README.md`](./shared/README.md) | 跨端共享代码（`shared/**` 生成两端源码的机制与护栏） |
| [`docs/cheats.md`](./docs/cheats.md) | 作弊框架、新增作弊条目 |
| [`docs/app-update.md`](./docs/app-update.md) | 应用自更新能力边界、实现、发版流程 |
| [`docs/device-cli.md`](./docs/device-cli.md) | 本 App 的日志 tag 与抓日志要点 |
| [`docs/debugging.md`](./docs/debugging.md) | 游戏 WebView DevTools 调试前置条件 |
| [`docs/troubleshooting.md`](./docs/troubleshooting.md) | 常见问题（仅项目特有） |

---

## 4. 安卓版（`android/`）

同一个仓库里的**第二个 App**：`android/` 是独立的 Gradle 工程（不在 HAP 编译范围内），bundle/applicationId 也叫 `com.lichtcui.pokelauncher`，目前在 **P0（打靶）+ P1（下载/解压链路）+ P2（注入链/端口兜底/缓存）+ P3（Compose UI：首页四态/下载流、设置页、法律页、参数调整页、游戏页）+ P4（平台特有：存档导出/导入 + Auto Backup + 应用内自更新 + 通知权限）代码全部完成**；**都还没在真机上跑过判据**（本机也无模拟器）。完整方案、分期计划、关键坑见 [`docs/android-port.md`](./docs/android-port.md)。

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export ANDROID_HOME=/usr/local/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$PATH"
cd android
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM 纯函数单测 282 例（core / install / cheat / server / web / appupdate / ui）
./gradlew :app:lintDebug       # 需 0 error（MissingPermission 等）
```

Android 侧的红线（改之前先读）：

- **端口固定 18787，绝不回退**：localStorage 以 origin 为键，换端口 = 丢存档（与鸿蒙侧同一条约束）。
- **存档在 WebView 的 localStorage**，物理落在应用数据目录；因此**任何要持久化的数据都不要放 `cacheDir`**（Android「清除缓存」会清）。清理只用 `WebView.clearCache()`，**不能**用 `clearStorage`（会连存档一起干掉）。下载分片（`game.zip.part` / `game.zip.download.json`）同理，只放 `filesDir` / `getExternalFilesDir`。
- **下载只能由用户操作启动**：targetSdk 35 下 API 34+ 禁止后台自拉 `dataSync` 前台服务（会抛 `ForegroundServiceStartNotAllowedException`）。不要在开机/启动/定时里自动开下，只能显示「未完成下载」提示，由用户点。
- **`keystore.properties` / `*.jks` / `*.keystore` 绝不入库**（已加进 `android/.gitignore`，同 `signature/` 的红线）。
- **release 已开 R8**：新增 JS 桥方法名（`@JavascriptInterface`）或 `JSONObject` 解析的字段名时，要同步 `app/proguard-rules.pro`；写完后用 `./gradlew assembleRelease` 验证，并确认 debug/release 的 dex 行为一致（`PokeNative` 等字符串两边都要在）。注：下载链路的 JSON 全部用**字符串字面量**（`optString("tag_name")`）解析，不要改成反射式/序列化框架式的“属性名当 key”。
- **断点续传不能拼坏包**：服务端忽略 `Range` 返回 200 时必须 `setLength(0)` 从头重下；只有**下载失败**才换源，解压失败不换源（重下 507MB 白亏）。
- **通用代码对齐两套实现**：`core/Const.kt` 与 `common/Const.ets`（端口、镜像列表、资源来源）必须同步修改，否则两端行为分叉——这份**由 `node scripts/gen-shared.mjs --check` 自动对账**（两端构建都会跑，不一致就失败）；同一道对账还盯着测速参数（`PROBE_*` / `RANK_TTL_MS`）与存储预检系数（两端的 `2.4 / 3.2` 曾分叉过：安卓恒用 3.2，会同设备上比鸿蒙更早报空间不足）。
- **`window.Phaser` 的属性描述符只能由 `/__pk_boot.js` 持有**：「参数调整」运行时（`cheat/CheatScripts.kt`）只往 `window.__pkGameHooks` 里压回调，**不得**再 `Object.defineProperty(window,'Phaser',…)`（写两次会静默覆盖，先注册的一方 setter 永不触发）；两边都用 `x = x || []` 复用数组。注入顺序固定为 **存档守卫 → 参数调整 → 引导脚本**（`server/InjectScripts.kt`，`InjectScriptsTest` 锁住）。
- **WebView 缓存只用 `clearCache(true)`，**绝不能**用 `clearStorage`/`deleteAllData()`**（会连 localStorage 存档一起干掉）；`clearCache` 必须主线程（统一走 `web/WebCache.kt`）。资源包版本变了要清一次（偏好键 `lastCachedGameVersion`，覆盖「后台服务里装完时游戏页从未打开」的时机）。
- **参数调整（cheat）**：UI 文案一律叫「**参数调整**」（不得出现「作弊」字样，审核风险）；`cheat/Cheats.kt` 与 `entry/.../model/Cheats.ets` 都是 `shared/` 的**生成物**（改一边即可、由 `scripts/gen-shared.mjs` 同步两边），共享 JS **不得出现 `$`**（Kotlin raw string 会当模板插值，生成器与单测都有断言）；声明了 `param` 就必须真的读 `__CHEAT_PARAMS__`。开关是「总开关 && 至少一条启用」才注入 `/__cheats__.js`。参数调整页（`ui/CheatScreen.kt`）完全由注册表驱动，**新增条目不需要改 UI**（只改 `shared/`）；总开关在参数调整页顶部（与鸿蒙的「设置页」位置不同），开启前有账号风险确认弹窗，设置页里**只加入口行**（显示已开启/已关闭），不重复放开关。
- **状态持久化用 `core/Prefs.kt`（SharedPreferences，库名 `pokelauncher_settings` 与鸿蒙一致），不用 DataStore**：本地 HTTP 服务要在处理 `/index.html` 时**同步**读 `hasAnyEnabled()`。存档镜像落 `filesDir/saves/`（对应鸿蒙的 SaveGuard，只为 Auto Backup/导出兜底），同样不得放 `cacheDir`。
- **首页四态/阶段判定写在 `ui/HomeState.kt` 的纯函数里**（`homeStateOf` / `inFlowOf` / `prepPhaseOf` / `flowTitleOf` …），**不要**把它们塞回 `@Composable`：本机没有模拟器/真机，Compose 组件零验证，纯函数能被 `HomeStateTest` 锁住。两条容易改错的边界：四态优先级是 `initializing` → `inFlow` → `gameReady`（更新资源时旧包还在，不能直接跳就绪页）；**`DownloadPhase.PAUSED` 算「流程中」**（它不在 `isActive` 里，漏算会让用户暂停后再也点不到「继续」）。
- **`SpikeController.installing` 是独立状态**：它和 `busy` 不是一回事——「删除本地数据」也是 `busy`，但绝不能渲染成「正在解压」。
- **`pendingUpdate` 标记只能由首页（`ui/HomeScreen.kt`）消费**：通知权限 launcher 在首页，下载链只保留一份实现（打靶页里那段已删；两处都留着会双开下载）。
- **打靶页入口在首页右上角常驻「诊断」按钮 + 设置页「开发者诊断（打靶页）」行**（debug/release 都可见）：真机判据要在「刚装好、还没资源」时就能跑到；首页的设置齿轮（`⚙`）仍然只在就绪态出现。图标一律用文字字形，**不引 `material-icons`**（`libs.versions.toml` 里没有这个依赖）。
- **`installer.recover()` 只在启动路径（`SpikeController.init`）调用一次**：它会删掉/接管 `stagingDir`，而下载服务可能正在同一个目录里解压，把它塞进「刷新状态」之类的热路径会被踩成半成品。顺带：首帧状态（未完成下载/版本/可用空间）要一次取回，`initializing = false` 必须等状态全部落定（否则已安装用户会先闪一下「未安装」）。
- **游戏页浮层/返回按钮的判定写在 `ui/GameState.kt` 的纯函数里**（`gameOverlayOf`），**判定顺序必须是「错误 > 加载中」**：加载失败时 `pageLoaded` 永远是 false，写反了用户只会看到「正在载入游戏…」而看不到任何错误（`GameStateTest` 锁住）。返回按钮的 `BACK_FADE_MS = 3000` / `BACK_FADED_ALPHA = 0.2f` 与鸿蒙 `GamePage.ets` 同值；淡出状态下点一下只把按钮唤回、**不退出**（两次点击才返回）。
- **游戏页切后台只静音，不加 `WebView.onPause()`**：`onPause()` 会冻住 Phaser 的计时器，回前台追帧/卡顿（鸿蒙同样只静音、保留游戏进程）。静音优先 `WebViewCompat.setAudioMuted`，但 `WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)` **必须写在 `if` 条件里**（写成局部变量会被 lint `RequiresFeature` 报警告），并且**必须保留 `window.__pkGame.sound.mute` 降级分支**（旧 WebView 没有这个特性）；销毁 WebView 前也先静音一次。
- **游戏页右上角的打靶指标面板（`canvas 补丁` / `DOM 载入` / 计时 / `重载` / `退出`）是 P0–P2 判据的产物，发布前必须挪到 debug 开关后面**（现在 debug/release 都常驻）。返回按钮因此放在左上角，别为了「对齐鸿蒙」把它搬回右上角（两个浮控件会互相吃点击）。
- **存档导出（游戏内「导出数据」）的主路径是「在页面里拦下载」，不是 `DownloadListener`**：游戏用 `URL.createObjectURL` + `<a download>`，部分 WebView 不回调 `onDownloadStart`（症状：点了没反应、日志也没有）。拦到就 `preventDefault`，`DownloadListener` 只作 `blob:` 兜底（`web/DownloadBridge.kt`，两条路径不能双发）。
- **导出落盘零权限，且文件名/URL 都是不可信输入**：API 29+ 写 `MediaStore.Downloads`（`IS_PENDING` 两阶段、失败删半成品），API 26–28 写 `getExternalFilesDir(DIRECTORY_DOWNLOADS)`；**不申请 `WRITE_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE`**（不要学参考 App，Play 会拒）。页面传来的名字一律过 `DownloadBridge.safeFileName`，拼进 JS 的字符串一律过 `jsStringLiteral`，别直接信 `download` 属性。`MediaStore` 那个分支要标 `@RequiresApi(Q)`，否则 lint 报 `NewApi` **error**。
- **Auto Backup 规则是 include 白名单**（`res/xml/backup_rules.xml` + `data_extraction_rules.xml`）：只备 `sharedpref` + `file/saves`，**不要**加 `<exclude domain="external">`（写了 include 之后其余本来就不备，lint 会报 `is not in an included path`）。这里错一下的代价是整次备份失败捎带存档一起丢：游戏本体在外部存储有 1.5GB，而配额只有 25MB。
- **`WebChromeClient.onShowFileChooser` 的 `callback` 必须恰好回一次值**：玩家取消、连点两次、页面被销毁时都要把上一份用 `null` 结掉（`FileChooserSlot.cancel()`），否则游戏里那次文件选择永久挂住（`ui/GameScreen.kt`）。
- **应用内自更新（P4）：`apkUrl` 的 APK 落到 `getExternalFilesDir()/apk/`，交给系统安装器必须走 `FileProvider`**（`file://` 在 API 24+ 会 `FileUriExposedException`/被静默拒绝）；`res/xml/provider_paths.xml` **只放行 `apk/` 两个路径，且必须与 `ApkUpdater.apkDir` 一致**（FileProvider 授的是整条路径，放行整棵树 = 送出私有目录读权限；改目录不改 xml 会 `IllegalArgumentException`）。`REQUEST_INSTALL_PACKAGES` 只是常规权限，真正的门禁是 `ApkUpdater.canInstall()`（`canRequestPackageInstalls()`）+ `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 精准跳转。
- **APK 下载用 `.part` 两阶段写 + 魔数校验**：写 `.part` → `fd.sync()` → `renameTo`（否则被杀后剩下「长度对得上、内容不全」的包，安装器只会报「解析包时出现问题」）；下完必须 `size >= 64KB` + `PK\x03\x04`/`05 06`/`07 08`（国内镜像的 404 HTML 页 `Content-Length` 也是 200）。自更新**不开前台服务**（几 MB 的包 + 必须用户亲手点「安装」，套 `dataSync` 只会白吃 Android 15 的 6h/24h 配额），取消用 `@Volatile` 标志逐块检查。
- **自更新的阶段判定与按钮文案必须是同一组纯函数**（`appupdate/ApkUpdate.kt` 的 `apkActionOf` / `apkRowLabel` / `apkRowValue`）：首页弹窗与设置页行两处各自写一份，很容易长歪成「一处可取消、一处重新下载」，点两下就双开下载（`ApkUpdateTest` 锁住）。安装器在**根组件**（`ui/RootScreen.kt`）按 `LaunchedEffect(phase, fileName)` 拉起，从安装器返回不重复弹（键不变）。
- **无真机不下结论**：模拟器与真机的 WebView/Chromium 版本差异极大，P0 的七项判据、P1 的下载判据与 P2 的注入/端口/缓存判据、P3 的 UI 判据、P4 的存档导出/Auto Backup/应用内更新判据（见 [`docs/android-port.md`](./docs/android-port.md) §4.4）必须在真机上跑（`adb install -r` → 打开 App → 截图读悬浮面板指标，日志 `-s PokeLauncher`）。
- **应用自更新只认 `version.json` 的 `android*` 字段**（`androidVersionCode` / `androidVersionName` / `androidMinVersionCode` / `androidChangelog` / `apkUrl`；与鸿蒙字段同在一份清单里）：缺 `androidVersionCode` 时安卓端必须显示「安卓版暂未发布」，**绝不**拿鸿蒙的 `versionCode` 比（会永远显示「发现新版本」并指向 HAP 页）；有 `apkUrl` 时走应用内下载（安卓能做到鸿蒙做不到的应用内更新），没 `apkUrl` 才退回浏览器打开 `pageUrl`。发版对齐规则见 [`docs/android-port.md`](./docs/android-port.md) §8。
- **`ui/` 里不要写 `Modifier.then(...)`，也不要声明 `fun Modifier.xxx()` 扩展**：本机 lint 版本下这两者会让 compose 的 lint 检查器 `NoClassDefFoundError`，**直接弄挂 `:app:lintDebug`**（是任务失败，不是警告）。用 if/else 选 Modifier。
- **真机验收的「屏幕方向」只在游戏页生效**：偏好存 `core/OrientationPref.kt`（键 `orientation`），由根组件的 `ApplyPageOrientation` 套用；启动器自己的页面在手机上恒竖屏、平板上跟随系统（与鸿蒙 `WindowHolder` 同口径）。
- 日志统一 tag `PokeLauncher`（`adb logcat -s PokeLauncher`），与鸿蒙侧同 tag 便于对照。
