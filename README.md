# harmony-pokerouge

在 HarmonyOS NEXT 上以**原生 HAP** 运行 PokeRogue 的离线启动器。

- 开发/编译/真机操作手册：[`AGENTS.md`](./AGENTS.md) ← **改代码前先看这个**
- 技术方案：[`方案.md`](./方案.md)

## 原理

```
HAP(仅代码) ──首次启动──► 从 GitHub Release 下载 game.zip（镜像加速 + 测速选源）
                          ──zlib 解压──► <sandbox>/files/game
                          ──本地 HTTP 服务 http://127.0.0.1:18787──► Web 组件加载 index.html
```

- **不把游戏资源打进 HAP**，运行时下载/更新资源包。
- 用**本地 HTTP 服务器**（`LocalHttpServer`）把沙箱目录映射成 `http://127.0.0.1:18787`，而不是 `onInterceptRequest`（后者每请求 ~55ms IPC，会卡）。
- 服务器在返回 `index.html` 时注入脚本，强制 2D canvas `willReadFrequently:true`，消除 ArkWeb `getImageData` 的 GPU 读回开销（游戏启动 **28s → 4s**）。
- 存档走 Web 组件的 `localStorage`（`domStorageAccess(true)`，origin 固定）。

## 功能

- 首页启动器：未下载 / 下载中 / 解压中 / 已就绪 四态；就绪页极简（精灵球 + 开始游戏 + 右上角设置入口）
- 下载：GitHub Release 资源包、国内镜像自动测速选源、进度/速度/剩余时间、暂停/继续/取消（二次确认）
- 解压：`zlib` 原生解压
- 游戏：离线运行（Web 组件 + 本地服务），切后台自动静音 BGM
- 存档导入/导出：文件选择器导入；blob 导出保存到 `下载/com.lichtcui.pokerogue/`
- 设置页：检查更新 / 刷新缓存 / 删除数据 / 关于；横屏、作弊等选项预留（即将推出）

## 环境要求

- **DevEco Studio 26.0.0**（自带 HarmonyOS SDK，本机为 **API 26**）
- 真机：HarmonyOS NEXT（本机实测 Pura 70 Pro+，**API 24**）
- 详细命令见 [`AGENTS.md`](./AGENTS.md)

## 快速开始

```bash
export DEVECO_SDK_HOME=/Applications/DevEco-Studio.app/Contents/sdk
export PATH="/Applications/DevEco-Studio.app/Contents/tools/node/bin:/Applications/DevEco-Studio.app/Contents/tools/ohpm/bin:$PATH"

/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw \
  assembleHap --mode module -p product=default -p buildMode=debug --no-daemon

HDC=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc
"$HDC" install -r entry/build/default/outputs/default/entry-default-signed.hap
"$HDC" shell aa start -a EntryAbility -b com.lichtcui.pokerogue
```

> 用 DevEco Studio 打开本目录首次 Sync 时，会自动生成 `hvigorw`、`hvigor/hvigor-wrapper.js`、`local.properties` 等（未入库）。
> 签名用 DevEco 的 `File > Project Structure > Signing Configs > Automatically generate signature`。

## 目录结构

```
entry/src/main/
├─ module.json5                     # INTERNET 权限
└─ ets/
   ├─ entryability/EntryAbility.ets # 本地服务启动、窗口（状态栏/安全区）、Web 调试
   ├─ common/{Const,Mime}.ets       # 常量 / MIME 映射
   ├─ components/PokeballLoader.ets # 精灵球动画（下载/解压摇晃；逐帧打开未使用）
   ├─ model/
   │  ├─ GameRepository.ets         # 下载/解压/删除/版本/接管
   │  ├─ LocalHttpServer.ets        # 本地 HTTP 服务 + index.html 注入
   │  ├─ LocalContentProvider.ets   # 旧 onInterceptRequest 方案（保留参考）
   │  ├─ Notifier.ets               # 通知
   │  └─ WindowHolder.ets           # 窗口背景色
    └─ pages/
       ├─ Index.ets                  # 首页启动器
       ├─ Settings.ets               # 设置页
       └─ GamePage.ets               # 离线游戏页
```

## 状态

已在真机（Pura 70 Pro+ / API 24）验证：下载（镜像 ~1.4MB/s）、解压、离线游戏、启动加速、存档导出、后台静音、全屏与安全区适配、首页极简改版与设置页（检查更新/刷新缓存/删除）。
