# harmony-pokerouge

在 HarmonyOS NEXT 上以**原生 HAP** 运行 PokeRogue 的离线启动器。

- 开发/编译/真机操作手册：[`AGENTS.md`](./AGENTS.md) ← **改代码前先看这个**

## 原理

```
HAP(仅代码) ──首次启动──► 从 GitHub Release 下载 game.zip（镜像加速 + 测速选源）
                          ──zlib 解压──► <sandbox>/files/game
                          ──本地 HTTP 服务 http://127.0.0.1:18787──► Web 组件加载 index.html
```

- **不把游戏资源打进 HAP**，运行时下载/更新资源包。
- 用**本地 HTTP 服务器**（`LocalHttpServer`）把沙箱目录映射成 `http://127.0.0.1:18787`，而不是 `onInterceptRequest`（后者每请求 ~55ms IPC，会卡）。
- 服务器在返回 `index.html` 时注入脚本：`<script src="/__cheats__.js">`（作弊，按启用清单动态生成）+ 强制 2D canvas `willReadFrequently:true`，消除 ArkWeb `getImageData` 的 GPU 读回开销（游戏启动 **28s → 4s**）。
- 存档走 Web 组件的 `localStorage`（`domStorageAccess(true)`，origin 固定）。

## 功能

- 首页启动器：未下载 / 下载中 / 解压中 / 已就绪 四态；就绪页极简（精灵球 + 开始游戏 + 作弊设置 + 右上角设置入口）
- 下载：GitHub Release 资源包、国内镜像自动测速选源、进度/速度/剩余时间、暂停/继续/取消（二次确认）
- 解压：`zlib` 原生解压
- 游戏：离线运行（Web 组件 + 本地服务），切后台自动静音 BGM；右上角「返回」3 秒后自动淡出（点击唤回），避免遮挡游戏信息
- 存档导入/导出：文件选择器导入；blob 导出保存到 `下载/com.lichtcui.pokerogue/`
- 屏幕方向：竖屏 / 横屏 / 跟随系统（仅游戏页生效，首页/设置页恒竖屏）
- 设置页：屏幕方向、作弊模式开关、应用更新 / 游戏资源更新 / 删除数据 / 关于
- 应用自更新检测：启动自动（24h 节流）+ 设置页手动；发现新版本弹窗展示更新日志，引导到浏览器下载页（应用内无法静默安装 HAP，只能引导）
- 作弊（离线，需先开启「作弊模式」）：首页就绪页「作弊设置」选择条目，进入游戏后**实时 hook 生效**（无需像旧方案那样存档 + 读档）；更改条目后需重新进入游戏页才会重新注入。条目可扩展（见 [`docs/cheats.md`](./docs/cheats.md)）

## 作弊（离线）

在「设置 → 游戏 → 作弊模式」开启后，首页就绪页会出现「作弊设置」入口。条目分两组：

- **倍率修改**：糖果 / 经验 / 金币获取倍率（×N）
- **其他**：幸运值拉满（SSS）、100% 捕获率、免费抽蛋、强制奖励稀有度

所有条目均为**实时 hook**，注入后在游戏内立即生效；但**更改开关或数值需要重新进入游戏页**（返回首页再进，或重启 App）才会重新注入。

> 实现原理（Phaser 场景捕获 / `__onPhase`）、每条作弊的 hook 点，以及**如何新增一条作弊**见 [`docs/cheats.md`](./docs/cheats.md)。

### ⚠️ 风险

- **账号风险**：PokéRogue 官方有检测机制，修改数据可能被标记（Flagged）甚至封禁；作弊后的存档不纯净，**勿导入在线版**。
- **兼容性风险**：实时作弊依赖游戏内部方法名。本 App 会从 GitHub Release 自动更新游戏资源，**游戏升级后作弊可能失效或表现异常**（届时关闭作弊即可）。

## 应用自更新

应用本体（HAP）的更新只能做到**检测 + 引导**：HarmonyOS 下普通应用无 `INSTALL_BUNDLE` 系统权限，公开 SDK 也不提供 HAP 安装接口，无法应用内静默安装。

- 远程清单：仓库根目录 [`version.json`](./version.json)（通过 Gitee Raw 读取）。
- 检测：启动自动检查（24h 节流）+ 设置页「数据管理 → 应用更新」手动检查。
- 引导：发现新版本弹窗（`forceUpdate` 或低于 `minVersionCode` 时不可关闭），点「去更新」用浏览器打开下载页，用户自行侧载。

> 能力边界、实现细节与发版流程见 [`docs/app-update.md`](./docs/app-update.md)。

## 环境与构建

- 开发：**DevEco Studio 26.0.0**（自带 HarmonyOS SDK，本机为 **API 26**）；真机：HarmonyOS NEXT（实测 Pura 70 Pro+，**API 24**）。
- 产物：`entry/build/default/outputs/default/entry-default-signed.hap`。
- 编译 / 真机安装 / 签名 / 发布流程见 [`AGENTS.md`](./AGENTS.md)（通用部分由 `harmonyos-*` skill 承载）。

## 目录结构

```
entry/src/main/
├─ module.json5                     # INTERNET 权限
└─ ets/
   ├─ entryability/EntryAbility.ets # 本地服务启动、窗口（状态栏/安全区）、Web 调试
   ├─ common/{Const,Mime}.ets       # 常量 / MIME 映射
   ├─ components/PokeballLoader.ets # 精灵球摇晃动画（下载/解压中）
   ├─ model/
   │  ├─ GameRepository.ets         # 下载/解压/删除/版本/接管
   │  ├─ AppUpdate.ets              # 应用自更新：本地版本读取 + version.json 比对 + 引导下载
   │  ├─ LocalHttpServer.ets        # 本地 HTTP 服务 + index.html 注入 + /__cheats__.js
   │  ├─ Notifier.ets               # 通知
   │  ├─ WindowHolder.ets           # 窗口背景色 + 屏幕方向
   │  ├─ OrientationPref.ets        # 屏幕方向偏好读写
   │  ├─ Cheats.ets                 # 作弊注册表（新增作弊改这里）
   │  └─ CheatState.ets             # 作弊状态 + 持久化 + 注入脚本生成
    └─ pages/
       ├─ Index.ets                  # 首页启动器（含「作弊设置」入口）
       ├─ Settings.ets               # 设置页
       ├─ Cheats.ets                 # 作弊条目页
       └─ GamePage.ets               # 离线游戏页
```

## 状态

已在真机（Pura 70 Pro+ / API 24）验证：下载（镜像 ~1.4MB/s）、解压、离线游戏、启动加速、存档导出、后台静音、全屏与安全区适配、首页极简改版、设置页（检查更新/删除）、屏幕方向（竖屏/横屏/跟随系统）、作弊（场景捕获 + 实时 hook：糖果/经验/金币倍率、幸运值拉满、100% 捕获率、免费抽蛋、强制奖励稀有度）。应用自更新检测已接入（启动自动 + 设置页手动 + 弹窗引导）。

> ⚠️ 作弊仅供离线使用：官方有检测机制，可能被标记/封禁；作弊后的存档不纯净，**勿导入在线版**。作弊依赖游戏内部实现，游戏更新后可能失效。新增作弊条目见 [`docs/cheats.md`](./docs/cheats.md)。
