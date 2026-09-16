# AGENTS.md

本仓库是 **HarmonyOS NEXT 原生 HAP 项目**（ArkTS）。本文件记录开发/编译/真机验证的操作步骤与约定，供后续 AI 或开发者直接照做。

> 项目说明见 `README.md`；环境/架构/调试/作弊/自更新等专题见 [`docs/`](./docs)（§4）。

---

## 1. 编译（命令行，推荐）

```bash
export DEVECO_SDK_HOME=/Applications/DevEco-Studio.app/Contents/sdk
export PATH="/Applications/DevEco-Studio.app/Contents/tools/node/bin:/Applications/DevEco-Studio.app/Contents/tools/ohpm/bin:$PATH"

/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw \
  assembleHap --mode module -p product=default -p buildMode=debug --no-daemon
```

- 产物：`entry/build/default/outputs/default/entry-default-signed.hap`
- 只看错误：`... | rg -i "ERROR|Error Message|BUILD SUCCESS|BUILD FAILED"`
- 若报 `compatibleSdkVersion` 不匹配 → 见 [`docs/environment.md`](./docs/environment.md)。
- ArkTS 严格模式：不要用 `any`；`fileIo` 没有 `writeFileSync`（用 `openSync`+`writeSync`+`closeSync`）；`getHostContext()` 返回 `Context | undefined` 需判空。
- **每次改完代码都必须走完「编译 → 安装 → 重启 → 截图观察」闭环**（见 §2）。只 `assembleHap` 不安装，设备上跑的仍是旧版本，会误判「改动没生效」。

## 2. 安装 / 启动 / 验证（每次必做）

> 编译通过 ≠ 改动生效。**每次改完代码都要按下面 4 步装到真机并截图确认**，不要只编译就结束。

```bash
HDC=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc
BUNDLE=com.lichtcui.pokerogue

# 1) 编译（见 §1）→ 2) 覆盖安装
"$HDC" install -r entry/build/default/outputs/default/entry-default-signed.hap
# 3) 干净重启
"$HDC" shell aa force-stop $BUNDLE
"$HDC" shell aa start -a EntryAbility -b $BUNDLE
# 4) 截图观察（命令见 docs/device-cli.md），确认改动已生效
```

- 安装成功后 App 名 **PokeRogue**，bundle = `com.lichtcui.pokerogue`。
- 签名：DevEco 已配置自动签名，`build-profile.json5` 里的 `signingConfigs` **属于本地敏感信息，不要提交**（仓库里的版本是 `signingConfigs: []`）。若本地缺失，用 DevEco `File > Project Structure > Signing Configs` 勾选自动签名重新生成。
- 截图/点击/日志等真机命令见 [`docs/device-cli.md`](./docs/device-cli.md)。

## 3. 约定

- **不要提交** `build-profile.json5` 的签名信息、`signature/`、`local.properties`、`.idea/`、`.hvigor/`、`build/`。
- `build-profile.json5` 已设置 **`skip-worktree`**（`git ls-files -v` 显示 `S`）：
  - 仓库里保存的是**干净版**（`signingConfigs: []`）；本地 DevEco 写入的签名改动被 git 忽略，`git status` 不会显示它 —— 这是正常的。
  - 若确实要修改该文件（如改 `compatibleSdkVersion`）：
    ```bash
    git update-index --no-skip-worktree build-profile.json5   # 先解除
    # ... 修改并 commit ...
    git update-index --skip-worktree build-profile.json5      # 再恢复
    ```
- 临时调试开关：`common/Const.ets` 的 `DEBUG_UI`（**默认 false**；true 时开 Web 调试并注入性能探针，仅本地调试用）、`FORCE_WILL_READ_FREQUENTLY`（默认 true，强制 2D canvas 走 CPU，消除 `getImageData` GPU 读回开销）。
- 改 UI 文案/布局主要在 `pages/Index.ets` 与 `pages/Settings.ets` 的 `@Builder`/`build()`。
- **ArkUI 坑**：`@Builder` 的**值参数**不会触发重渲染，动态文案要直接在 `build()` 里读 `@State`（见 `Settings.ets` 的「检查更新」「删除本地数据」行）。
- 新增静态资源放 `entry/src/main/resources/base/media/`，引用 `$r('app.media.xxx')`。
- **新增作弊条目只改 `model/Cheats.ets`**（见 [`docs/cheats.md`](./docs/cheats.md)），UI/状态/注入会自动生效。
- 提交前先 `hvigorw assembleHap` 确认编译通过，再真机验证。

## 4. 专题文档（`docs/`）

| 文档 | 内容 |
| --- | --- |
| [`docs/environment.md`](./docs/environment.md) | 本机工具链路径、SDK/设备版本约束 |
| [`docs/architecture.md`](./docs/architecture.md) | 目录结构、运行原理、关键行为 |
| [`docs/device-cli.md`](./docs/device-cli.md) | 真机交互（截图/点击/滑动）与日志命令 |
| [`docs/debugging.md`](./docs/debugging.md) | 游戏 WebView DevTools 调试 |
| [`docs/cheats.md`](./docs/cheats.md) | 作弊框架、新增作弊条目 |
| [`docs/app-update.md`](./docs/app-update.md) | 应用自更新能力边界、实现、发版流程 |
| [`docs/troubleshooting.md`](./docs/troubleshooting.md) | 常见问题 |
