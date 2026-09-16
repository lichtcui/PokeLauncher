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
- App 名 **PokeRogue**，bundle = `com.lichtcui.pokerogue`
- **每次改完代码都必须走完「编译 → 安装 → 重启 → 截图观察」闭环**。只编译不安装，设备上跑的仍是旧版本，会误判「改动没生效」。
- ⚠️ `build-profile.json5` 的 `compatibleSdkVersion` / `targetSdkVersion` 必须是 `"6.1.1(24)"`（本机 SDK 是 API 26，真机是 API 24）。报「sdk version 不匹配」先查这里。

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
- 提交前先编译通过，再真机验证。

### 代码

- ArkTS 严格模式：不要用 `any`；`fileIo` 没有 `writeFileSync`（用 `openSync`+`writeSync`+`closeSync`）；`getHostContext()` 返回 `Context | undefined` 需判空。
- 改 UI 文案/布局主要在 `pages/Index.ets` 与 `pages/Settings.ets` 的 `@Builder`/`build()`。
- **ArkUI 坑**：`@Builder` 的**值参数**不会触发重渲染，动态文案要直接在 `build()` 里读 `@State`（见 `Settings.ets` 的「检查更新」「删除本地数据」行）。
- 新增静态资源放 `entry/src/main/resources/base/media/`，引用 `$r('app.media.xxx')`。
- **新增作弊条目只改 `model/Cheats.ets`**（见 [`docs/cheats.md`](./docs/cheats.md)），UI/状态/注入会自动生效。

### 测试（ohosTest，跑在真机）

测试源码在 `entry/src/ohosTest/ets/test/`（hypium，项目根 `oh-package.json5` 的 `devDependencies`）。**新增套件要在 `List.test.ets` 里注册**。只放纯函数/无副作用逻辑（当前覆盖 `normalizeVersion` / `mimeOf`）。

```bash
export DEVECO_SDK_HOME=/Applications/DevEco-Studio.app/Contents/sdk
export PATH="/Applications/DevEco-Studio.app/Contents/tools/node/bin:/Applications/DevEco-Studio.app/Contents/tools/ohpm/bin:$PATH"
HVIGOR=/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw
HDC=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc

"$HVIGOR" assembleHap --mode module -p module=entry@ohosTest -p product=default -p buildMode=debug --no-daemon
"$HDC" install -r entry/build/default/outputs/default/entry-default-signed.hap
"$HDC" install -r entry/build/default/outputs/ohosTest/entry-ohosTest-signed.hap
"$HDC" shell aa test -b com.lichtcui.pokerogue -m entry_test -s unittest OpenHarmonyTestRunner -s timeout 60000
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
