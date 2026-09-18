# 应用自更新（app 本体）

> 操作手册见 [`../AGENTS.md`](../AGENTS.md)。

## 能力边界（勿再尝试应用内安装）

- HarmonyOS 普通应用**无法应用内静默安装 HAP**：`ohos.permission.INSTALL_BUNDLE` 是系统级权限，公开 SDK 无 `@ohos.bundle.installer`，`wantConstant` 也无安装 action。
- AppGallery 的 `@hms.core.appgalleryservice.updateManager` **仅对华为应用市场发布的 app 生效**，侧载分发用不了。
- 因此本 App 只做**检测 + 引导**：发现新版本弹窗，点「去更新」用浏览器打开下载页，用户自行下载侧载。

## 分发渠道划分（Gitee / GitHub）

| 用途 | 渠道 | 原因 |
| --- | --- | --- |
| 更新清单 `version.json` | **Gitee** Raw | **唯一由 App 自己发请求**的地址；`raw.githubusercontent.com` 国内常不可达 |
| 下载页 / Release | **Gitee** | 更新链路统一走 Gitee，与清单保持一致 |
| 项目主页 / Issue / 文档 | **GitHub** | 浏览器打开或纯文本，不走 App 请求，无被墙风险 |

改地址前先归类：**只要 App 会自己去拉，就必须留在 Gitee**；其余一律 GitHub（`common/Const.ets` 的 `PROJECT_GITHUB_URL`）。

## 已知限制：Release 页不附带 HAP

`build-profile.json5` 目前用的是 DevEco **自动签名**，其 Profile 为 `type: debug`，`debug-info.device-ids` 只包含开发者单台设备的 UDID。因此：

- 本地打出的 `entry-default-signed.hap` **只能装到那台设备**，传到 Release 对别人没有意义。
- 要让别人能装，必须改用 **release 签名**（华为开发者账号 + 发布证书 / Profile），见 `harmonyos-release-signing` skill。
- 在此之前 Release 页只放源码 + 自行构建说明（README「安装」一节）。

### 更硬的一条：release 签名的 HAP 根本不能侧载

实测把 release 签名的 HAP 用 `hdc install -r` 装到真机，会被设备直接拒绝：

```
error: signature verification failed due to not trusted app source. (9568322)

设备侧 HapVerify 日志：
  untrusted source app with release profile distributionType: 1   # 1 = app_gallery
  APP source is not trusted
  MSG_ERR_INSTALL_FAILED_APP_SOURCE_NOT_TRUESTED
```

即 **HarmonyOS NEXT 只允许 `app_gallery` 类型的应用从华为应用市场安装**。所以「Release 页发 HAP 让用户侧载」这条路对终端用户走不通，release 签名的唯一用途是**上架 AppGallery**。

## 发布构建（App Pack，上架用）

hvigor 的 `SignHap` / `SignApp` 只接受 DevEco 加密后的密码密文（`DecipherUtil.decryptPwd` 强制长度 ≥ 32 并做 AES-128-GCM 解密，明文报 `00303116`），那份密文依赖 DevEco 在本机生成的 `~/.ohos/config/material/`，无法在 CI 复现。因此用脚本绕开：

```bash
scripts/build-release-app.sh
# -> build/outputs/default/harmony-pokerouge-default-release-signed.app
```

脚本四步：hvigor 出**未签名** HAP + App Pack → `hap-sign-tool` 签 HAP → 把签名后的 HAP 换回 App Pack → 签 App Pack 并校验（**同时校验内层 HAP** 的 profile 类型与 `device-ids`）。

签名材料从 `SIGN_DIR`（默认 `~/.ohos/release`）读取，密码放 `$SIGN_DIR/pwd`，全部在仓库外、不入库。App Pack 的 `pack.info` 只含元数据（无 HAP 摘要），所以替换内层 HAP 是安全的。

## 实现

| 文件 | 职责 |
| --- | --- |
| 仓库根 `version.json` | 远程版本清单，通过 Gitee Raw 读取（`master` 分支） |
| `common/Const.ets` | `APP_UPDATE_MANIFEST_URL` / `APP_UPDATE_PAGE_URL` / `APP_UPDATE_CHECK_INTERVAL_MS`（24h） |
| `model/AppUpdate.ets` | `readLocalVersion()`（`bundleManager.getBundleInfoForSelfSync`）、`fetchManifest()`、`checkAppUpdate()`、`shouldAutoCheck()/markChecked()`（preferences 节流）、`openDownloadPage()`（`Want` viewData+browsable 打开浏览器） |
| `pages/Index.ets` | 启动 `maybeAutoCheckAppUpdate()`（节流；失败静默）；有更新弹 `AlertDialog`（`forceUpdate` 时单按钮 + `autoCancel:false`） |
| `pages/Settings.ets` | 「数据管理 → 应用更新」手动检查 + 更新日志 + 去更新；原「检查更新」改名「离线资源更新」 |

## version.json 格式

```json
{
  "versionCode": 1000001,
  "versionName": "1.0.1",
  "minVersionCode": 1000000,
  "forceUpdate": false,
  "changelog": "1. 修复…\n2. 新增…",
  "downloadUrl": "https://gitee.com/licht3345/PokeLauncher/releases",
  "pageUrl": "https://gitee.com/licht3345/PokeLauncher/releases"
}
```

- 比较基准是 **`versionCode`**（整数），`versionName` 仅展示。
- `forceUpdate: true` 或 本地 `versionCode < minVersionCode` → 弹窗不可关闭。
- 强制更新时**不写入节流时间戳**，下次启动会重新检查并提示。

## 发版流程

1. 递增 `AppScope/app.json5` 的 `versionCode` / `versionName`。
2. 同步更新仓库根 `version.json`，提交并推到 `master`（Gitee 与 GitHub 各推一份，清单由 Gitee 读取）。
3. 上传新 HAP 到 Gitee Release。
4. 在 GitHub 建同名 tag + Release，附同一份 HAP（**需先具备 release 签名**，见上节）。

## 验证

- 临时把远程 `versionCode` 调大 → 重启 App 应弹窗；设置页「应用更新」应显示「发现新版本」。
- 手动检查失败会显示「检查失败」；启动自动检查失败仅打日志（`Settings`/`Home`/`AppUpdate` tag）。
- 自动检查节流键：偏好 `pokelauncher_settings` 的 `appUpdateLastCheck`。

## 侧载分发（HAP Store）复检记录

> 复检于 2026-09-18。放弃 AppGallery，改走第三方 HAP 资源站 + 自动签名安装工具。

- **机制**：HAP Store（如 sydxky.cn）提供资源站；用户用「小白调试助手 / HoKit / HapKit」下载后**自动申请调试证书并重签名安装**。因此发布物用 **debug 签名的 HAP**（`entry-default-signed.hap`），**不要传 app_gallery 的 release 包**（实测 release 包 `distribution: app_gallery`，设备拒绝侧载，见上「更硬的一条」）。
- **已验证**：当前 HAP 为 debug profile（`device-ids` 仅作者单机）、纯 ArkTS 无 `libs/`/HSP，第三方工具重签无阻碍。
- **已降到 API 12（2026-09-18）**：`compatibleSdkVersion="5.0.0(12)"`、`targetSdkVersion` 保留 `6.1.1(24)`，产物 `minAPIVersion=50000012`，声称覆盖 HarmonyOS NEXT 5.0+。4 处 API > 12 全部用 `deviceInfo.sdkApiVersion` 运行时兜底：
  - `pages/GamePage.ets` `AudioSessionType.AMBIENT`（@20）：≥20 设置；低版本不设置 → BGM 会被系统降音（**功能降级**，无等价替代）
  - `model/GameRepository.ets` `zlib.ParallelStrategy`（@18）：≥18 并行解压；低版本串行（仅慢一些）
  - `pages/GamePage.ets` `fileUri.FileUri`（@15）：≥15 用；低版本回退 `@ohos.uri` 的 `URI.path`
  - `model/GameRepository.ets` `request.agent.Notification`（@15）/ `visibility`（@21）/ `wantAgent`（@22）：<22 整体不设置通知，回退 `gauge`（仍显进度），**通知点击不再拉起应用**
  - 已在 API 24 真机冒烟（首页 + 游戏页正常）；**API 12~23 真机仍未验证**。注意编译器和 hvigor **不校验 API 版本**（降级也能编过），可能仍有未扫出的 >12 调用，需真机/模拟器确认。
  - 调试开关 `Const.DEBUG_FORCE_OLD_API`（默认 false）：置 true 时 `apiAtLeast()` 恒 false，可在高版本真机上强制走降级路径。本机 API 24 实测：四条降级分支**全部可用**——首页/游戏页不崩、完整「下载 → 解压（zlib 串行）→ 就绪」、导出存档（`uri.URI.path` 回退，文件成功落盘 19116B）。**仍缺**：真实 API 12~17 设备（安装 + 可能未扫出的 >12 调用）。
- **设备类型（部分已做，2026-09-18）**：`deviceTypes=['phone','tablet']`；新增 `WindowHolder.isTablet()`，平板首页/设置页不再强制竖屏，大屏内容 `constraintSize({maxWidth:560})` 居中。**平板侧未验证**（本机无镜像/真机）；PC（`2in1`）尚未加入。
- **本地模拟器不可用（2026-09-18 实测）**：开发机是 **Intel Mac (x86_64)**，Device Manager 报 `the local emulator does not support mac x86 pcs. please use the emulator on mac arm and windows pcs`。即本机**本地模拟器（含 phone/tablet）全部不可用**。可用替代：Device Manager 的 **Remote Simulator（远程模拟器，需登录华为账号）**、真机、或 Windows / Apple Silicon 机器。
- **待办（当前环境无法完成，需真机/资源）**：
  1. 在**未注册**的 HarmonyOS 6.1+ 设备上跑通「下载 → 签名 → 安装」端到端（本机设备在 debug profile 白名单里，证明不了）。
  2. 覆盖升级的**签名一致性**（工具是否复用同一调试证书）与存档保留验证。
  3. 平板验证：本地模拟器不支持 Intel Mac；可用远程模拟器 / MatePad 真机 / Windows 机器。
  4. HarmonyOS 5.x 验证：同上，需远程模拟器 / 旧系统真机 / Windows 机器上的旧镜像。
- **更新地址**：上架后可改 Gitee `version.json` 的 `pageUrl` / `downloadUrl`（App 优先用远程 `pageUrl`），**无需重新打包**；`Const.APP_UPDATE_PAGE_URL` 只是远程为空时的兜底，改它才需重发版本。
