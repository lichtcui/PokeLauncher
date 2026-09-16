# 应用自更新（app 本体）

> 操作手册见 [`../AGENTS.md`](../AGENTS.md)。

## 能力边界（勿再尝试应用内安装）

- HarmonyOS 普通应用**无法应用内静默安装 HAP**：`ohos.permission.INSTALL_BUNDLE` 是系统级权限，公开 SDK 无 `@ohos.bundle.installer`，`wantConstant` 也无安装 action。
- AppGallery 的 `@hms.core.appgalleryservice.updateManager` **仅对华为应用市场发布的 app 生效**，侧载分发用不了。
- 因此本 App 只做**检测 + 引导**：发现新版本弹窗，点「去更新」用浏览器打开下载页，用户自行下载侧载。

## 实现

| 文件 | 职责 |
| --- | --- |
| 仓库根 `version.json` | 远程版本清单，通过 Gitee Raw 读取（`master` 分支） |
| `common/Const.ets` | `APP_UPDATE_MANIFEST_URL` / `APP_UPDATE_PAGE_URL` / `APP_UPDATE_CHECK_INTERVAL_MS`（24h） |
| `model/AppUpdate.ets` | `readLocalVersion()`（`bundleManager.getBundleInfoForSelfSync`）、`fetchManifest()`、`checkAppUpdate()`、`shouldAutoCheck()/markChecked()`（preferences 节流）、`openDownloadPage()`（`Want` viewData+browsable 打开浏览器） |
| `pages/Index.ets` | 启动 `maybeAutoCheckAppUpdate()`（节流；失败静默）；有更新弹 `AlertDialog`（`forceUpdate` 时单按钮 + `autoCancel:false`） |
| `pages/Settings.ets` | 「数据管理 → 应用更新」手动检查 + 更新日志 + 去更新；原「检查更新」改名「游戏资源更新」 |

## version.json 格式

```json
{
  "versionCode": 1000001,
  "versionName": "1.0.1",
  "minVersionCode": 1000000,
  "forceUpdate": false,
  "changelog": "1. 修复…\n2. 新增…",
  "downloadUrl": "https://gitee.com/licht3345/harmony-pokerogue/releases",
  "pageUrl": "https://gitee.com/licht3345/harmony-pokerogue/releases"
}
```

- 比较基准是 **`versionCode`**（整数），`versionName` 仅展示。
- `forceUpdate: true` 或 本地 `versionCode < minVersionCode` → 弹窗不可关闭。
- 强制更新时**不写入节流时间戳**，下次启动会重新检查并提示。

## 发版流程

1. 递增 `AppScope/app.json5` 的 `versionCode` / `versionName`。
2. 同步更新仓库根 `version.json`，提交并推到 Gitee `master`。
3. 上传新 HAP 到 Gitee Release。

## 验证

- 临时把远程 `versionCode` 调大 → 重启 App 应弹窗；设置页「应用更新」应显示「发现新版本」。
- 手动检查失败会显示「检查失败」；启动自动检查失败仅打日志（`Settings`/`Home`/`AppUpdate` tag）。
- 自动检查节流键：偏好 `pokerogue_settings` 的 `appUpdateLastCheck`。
