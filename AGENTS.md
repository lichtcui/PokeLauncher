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
- **新增作弊条目只改 `model/Cheats.ets`**（见 [`docs/cheats.md`](./docs/cheats.md)），UI/状态/注入会自动生效。
  - ⚠️ 命名：该功能在 **UI 上叫「参数调整」**（`作弊` 字样会触发应用商店审核风险），代码/文档沿用 `cheat` 命名。**新增任何用户可见文案都用「参数调整」**。
- **存档是 Web 的 `localStorage`，物理落在 `<沙箱>/cache/web/`**，会被「清除缓存」清掉。因此 `model/SaveGuard.ets` 会把它镜像到 `files/saves/` 并在清空时自动回填——**任何需要持久化的数据都不要放 `cache/`**，放 `files/`。

### 测试（ohosTest，跑在真机）

测试源码在 `entry/src/ohosTest/ets/test/`（hypium，项目根 `oh-package.json5` 的 `devDependencies`）。**新增套件要在 `List.test.ets` 里注册**。只放纯函数/无副作用逻辑（当前覆盖 `normalizeVersion` / `mimeOf` / `common/Format.ets` 的三个格式化函数）。

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
| [`docs/cheats.md`](./docs/cheats.md) | 作弊框架、新增作弊条目 |
| [`docs/app-update.md`](./docs/app-update.md) | 应用自更新能力边界、实现、发版流程 |
| [`docs/device-cli.md`](./docs/device-cli.md) | 本 App 的日志 tag 与抓日志要点 |
| [`docs/debugging.md`](./docs/debugging.md) | 游戏 WebView DevTools 调试前置条件 |
| [`docs/troubleshooting.md`](./docs/troubleshooting.md) | 常见问题（仅项目特有） |

---

## 4. 安卓版（`android/`）

同一个仓库里的**第二个 App**：`android/` 是独立的 Gradle 工程（不在 HAP 编译范围内），bundle/applicationId 也叫 `com.lichtcui.pokelauncher`，目前在 **P0（打靶）+ P1（下载/解压链路）+ P2（注入链/端口兜底/缓存）代码已完成，但都还没在真机上跑过判据**，下一阶段是 P3（Compose UI）。完整方案、分期计划、关键坑见 [`docs/android-port.md`](./docs/android-port.md)。

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export ANDROID_HOME=/usr/local/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$PATH"
cd android
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM 纯函数单测 180 例（core / install / cheat / server / web）
./gradlew :app:lintDebug       # 需 0 error（MissingPermission 等）
```

Android 侧的红线（改之前先读）：

- **端口固定 18787，绝不回退**：localStorage 以 origin 为键，换端口 = 丢存档（与鸿蒙侧同一条约束）。
- **存档在 WebView 的 localStorage**，物理落在应用数据目录；因此**任何要持久化的数据都不要放 `cacheDir`**（Android「清除缓存」会清）。清理只用 `WebView.clearCache()`，**不能**用 `clearStorage`（会连存档一起干掉）。下载分片（`game.zip.part` / `game.zip.download.json`）同理，只放 `filesDir` / `getExternalFilesDir`。
- **下载只能由用户操作启动**：targetSdk 35 下 API 34+ 禁止后台自拉 `dataSync` 前台服务（会抛 `ForegroundServiceStartNotAllowedException`）。不要在开机/启动/定时里自动开下，只能显示「未完成下载」提示，由用户点。
- **`keystore.properties` / `*.jks` / `*.keystore` 绝不入库**（已加进 `android/.gitignore`，同 `signature/` 的红线）。
- **release 已开 R8**：新增 JS 桥方法名（`@JavascriptInterface`）或 `JSONObject` 解析的字段名时，要同步 `app/proguard-rules.pro`；写完后用 `./gradlew assembleRelease` 验证，并确认 debug/release 的 dex 行为一致（`PokeNative` 等字符串两边都要在）。注：下载链路的 JSON 全部用**字符串字面量**（`optString("tag_name")`）解析，不要改成反射式/序列化框架式的“属性名当 key”。
- **断点续传不能拼坏包**：服务端忽略 `Range` 返回 200 时必须 `setLength(0)` 从头重下；只有**下载失败**才换源，解压失败不换源（重下 507MB 白亏）。
- **通用代码对齐两套实现**：`core/Const.kt` 与 `common/Const.ets`（端口、镜像列表、资源来源）必须同步修改，否则两端行为分叉。
- **`window.Phaser` 的属性描述符只能由 `/__pk_boot.js` 持有**：「参数调整」运行时（`cheat/CheatScripts.kt`）只往 `window.__pkGameHooks` 里压回调，**不得**再 `Object.defineProperty(window,'Phaser',…)`（写两次会静默覆盖，先注册的一方 setter 永不触发）；两边都用 `x = x || []` 复用数组。注入顺序固定为 **存档守卫 → 参数调整 → 引导脚本**（`server/InjectScripts.kt`，`InjectScriptsTest` 锁住）。
- **WebView 缓存只用 `clearCache(true)`，**绝不能**用 `clearStorage`/`deleteAllData()`**（会连 localStorage 存档一起干掉）；`clearCache` 必须主线程（统一走 `web/WebCache.kt`）。资源包版本变了要清一次（偏好键 `lastCachedGameVersion`，覆盖「后台服务里装完时游戏页从未打开」的时机）。
- **参数调整（cheat）**：UI 文案一律叫「**参数调整**」（不得出现「作弊」字样，审核风险）；`cheat/Cheats.kt` 的 7 条 JS 必须与 `entry/src/main/ets/model/Cheats.ets` **逐字同步**（改一边就得改另一边），且**不得出现 `$`**（Kotlin raw string 会当模板插值，单测有断言）；声明了 `param` 就必须真的读 `__CHEAT_PARAMS__`。开关是「总开关 && 至少一条启用」才注入 `/__cheats__.js`。
- **状态持久化用 `core/Prefs.kt`（SharedPreferences，库名 `pokelauncher_settings` 与鸿蒙一致），不用 DataStore**：本地 HTTP 服务要在处理 `/index.html` 时**同步**读 `hasAnyEnabled()`。存档镜像落 `filesDir/saves/`（对应鸿蒙的 SaveGuard，只为 Auto Backup/导出兜底），同样不得放 `cacheDir`。
- **无真机不下结论**：模拟器与真机的 WebView/Chromium 版本差异极大，P0 的七项判据、P1 的下载判据与 P2 的注入/端口/缓存判据（见 [`docs/android-port.md`](./docs/android-port.md) §4.4）必须在真机上跑（`adb install -r` → 打开 App → 截图读悬浮面板指标，日志 `-s PokeLauncher`）。
- 日志统一 tag `PokeLauncher`（`adb logcat -s PokeLauncher`），与鸿蒙侧同 tag 便于对照。
