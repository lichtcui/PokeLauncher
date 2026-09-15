# harmony-pokerouge

在 HarmonyOS NEXT 上以**原生 HAP** 运行 PokeRogue 的离线启动器。

- 技术方案：[`方案.md`](./方案.md)
- UI/交互设计：[`设计.md`](./设计.md)

## 原理

```
HAP(仅代码) ──首次启动──► 从 GitHub Release 下载 game.zip
                          ──zlib 解压──► <sandbox>/files/game
                          ──Web 组件加载 https://pokerogue.local/index.html
                             （onInterceptRequest 从沙箱喂文件，setResponseData(fd)）
```

- 不把游戏资源打进 HAP，运行时下载/更新资源包。
- 存档走 Web 组件的 `localStorage`（`domStorageAccess(true)`，origin 固定）。

## 环境要求

- **DevEco Studio 5.0+**（含 HarmonyOS NEXT SDK，API 12）
- Node.js（DevEco 自带 hvigor 使用）
- 真机：HarmonyOS NEXT（5.x）手机

> 本机当前未检测到 DevEco Studio / SDK，需先安装后再构建。

## 构建与运行

1. 用 DevEco Studio 打开本目录（`File > Open`）。
2. 首次打开会提示 Sync，DevEco 会生成 `hvigorw`、`hvigor/hvigor-wrapper.js`、`local.properties` 等（这些文件未入库）。
3. 配置签名：`File > Project Structure > Signing Configs`，勾选 **Automatically generate signature**（需登录华为开发者账号并连接设备）。
4. 连接真机，点 **Run**，或命令行：
   ```bash
   ./hvigorw assembleHap --mode module -p product=default
   hdc install -r entry/build/default/outputs/default/entry-default-signed.hap
   ```
5. 安装后也可通过第三方 HAP 安装站分发（重签/共享证书）。

## 目录结构

```
entry/src/main/
├─ module.json5                     # INTERNET 权限
└─ ets/
   ├─ entryability/EntryAbility.ets # 默认竖屏、入口
   ├─ common/
   │  ├─ Const.ets                  # URL/路径常量
   │  └─ Mime.ets                   # 扩展名 → MIME（模块脚本必须 text/javascript）
   ├─ model/
   │  ├─ GameRepository.ets         # 下载 / 解压 / 删除 / 版本
   │  ├─ LocalContentProvider.ets   # onInterceptRequest 资源代理
   │  └─ Notifier.ets               # 通知权限 + 完成/失败通知
   └─ pages/
      ├─ Index.ets                  # 首页（未下载/下载中/解压中/已就绪）
      └─ GamePage.ets               # 离线游戏页（Web + 拦截）
```

## 开发进度

| 模块 | 状态 |
| --- | --- |
| M0 工程骨架 | ✅ 完成 |
| M1 离线闭环验证 | 🟡 待真机验证（下载/解压/拦截加载，决定方案可行性） |
| M2 数据层 GameRepository | ✅ 代码完成 |
| M3 LocalContentProvider | ✅ 代码完成 |
| M4 GamePage | ✅ 代码完成 |
| M5 首页 Home | ✅ 代码完成、真机 UI 已确认 |
| M6 通知 | ✅ 代码完成 |
| M7 后台/方向 | 🟡 best-effort，待真机验证 |
| M8 打包分发 | ✅ 已签名并安装到真机（API 24） |

## 待验证项（装好 DevEco 后）

1. **M1**：`OnlinePage` 能否正常加载并游玩 pokerogue.net（Phaser/WebGL/音频）。
2. **M3/M4**：`onInterceptRequest` 返回 fd 后，模块脚本 MIME 是否正确、能否不白屏。
3. **M7**：切后台 BGM 是否停止、回前台是否恢复（JS 注入为 best-effort，需按实际 Phaser 实例调整）。
4. 大文件下载/解压耗时与磁盘占用（原版约 531MB）。
