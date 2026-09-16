# 常见问题

> 操作手册见 [`../AGENTS.md`](../AGENTS.md)。通用 HarmonyOS 报错（SDK 版本不匹配、`hvigorw` 环境、ArkWeb API 时序、ArkUI 渲染）交给 `harmonyos-build-doctor` / `harmony-next` skill，本表只留**项目特有**的现象。

## 启动 / 游戏

| 现象 | 处理 |
| --- | --- |
| 启动白屏 / 极慢 | 检查 `index.html` 注入是否生效（`willReadFrequently`，见 [`architecture.md`](./architecture.md)） |
| 点「开始游戏」提示「本地服务未就绪」 | 本地 HTTP 服务端口被占用（`18787`）。重启 App；仍失败则抓 `LocalHttp` tag 看 `startSharedServer failed` |
| 游戏内「返回」按钮挡住信息 | 按钮 3 秒后自动淡出，点击可唤回，再点才返回（`GamePage.resetBackTimer`） |

## 资源下载 / 更新

| 现象 | 处理 |
| --- | --- |
| 更新资源包后仍是旧内容 | 下载/解压完成后会**自动清 Web 缓存**；若仍异常，去「设置 → 删除本地数据」后重下 |
| 下载中途被杀 / 重启后一直「正在下载」不动 | 接管逻辑**只接管活跃任务**（`RUNNING/RETRYING/WAITING/PAUSED`）。若仍卡住，取消后重下 |
| 下载失败后卡在「未下载」且显示「下载失败：」 | 已改为回退错误码显示；看 `GameRepository` / `Home` tag 的 `download attempt N failed` |
| 下载源 1 失败自动换源 | 正常行为：4 个镜像 + 直连按测速排序依次尝试，**只有下载失败才换源** |
| 解压失败 / 显示「解压结果缺少 index.html」 | 校验未通过（包损坏或结构不符）。现有安装不受影响，重下即可 |
| 解压中被杀后「已就绪」但进游戏报错 | 已由原子安装 + `recover()` 修复；旧版本遗留的坏安装需「删除本地数据」后重下 |
| 下载通知堆积 / 孤儿任务 | 首页启动时清理**本应用自己的**完成/失败通知；后台下载任务的进度通知由 `request.agent` 管理 |
| 需要通知权限 | 首次进入未下载态时申请；拒绝后不再弹窗 |

## 存档

存档是 Web 的 `localStorage`，物理位置 `<沙箱>/cache/web/`，origin 固定 `http://127.0.0.1:18787`（端口固定不回落，见 `LocalHttpServer.PORT` 注释）。App 会把它镜像一份到 `<沙箱>/files/saves/localstorage.json`（`model/SaveGuard.ets`）。

| 现象 | 处理 |
| --- | --- |
| 存档突然没了 | 先抓 `SaveGuard` tag：`snapshot saved` 表示快照在正常写入，`restored N keys from snapshot` 表示已自动回填 |
| 「清除缓存」后进游戏弹「已从快照恢复」 | 预期行为：`cache/web` 被清空后由 `files/saves/` 回填，避免用户无感知丢档 |
| 快照没生成 | 看 `SaveGuard` tag 的 `init`（`initSaveGuard` 在 `EntryAbility.onWindowStageCreate`）；再看注入脚本是否执行（`ARKWEB-CONSOLE` 的 `SAVEGUARD ...`） |
| 想清掉快照重来 | 删除 `<沙箱>/files/saves/`（`bm clean -d` 会一并清） |

**快照覆盖不到的场景**：卸载重装、`bm clean -d` 清数据——这两种会删掉整个沙箱，只能用游戏内「导出存档」落到 `下载/com.lichtcui.pokelauncher/` 兜底。

**已验证（2026-09-16 真机）**：`bm clean -c -n com.lichtcui.pokelauncher` 会删掉 `cache/web/`，即**确实会清空 localStorage 存档**；`files/saves/` 不受影响，重进游戏页后 8 个键（含 `data_Guest` 495KB）逐字节还原一致。所以「清除缓存」这条风险是真实的，不是理论推测。

## 作弊

| 现象 | 处理 |
| --- | --- |
| 作弊没生效 | 作弊是**加载时注入**：改配置后要**重新进入游戏页**（返回首页再进，或重启 App）才会重新注入（不能热生效）；抓日志看 `served /__cheats__.js` 与 `ARKWEB-CONSOLE` 的 `CHEAT ...`（见 [`cheats.md`](./cheats.md)） |
| 参数调整入口不显示 | 首页「参数调整」仅在设置页开启总开关后显示 |
| 游戏更新后作弊失效 | 实时 hook 依赖游戏内部方法名，属预期；关闭作弊即可 |

## 应用自更新

| 现象 | 处理 |
| --- | --- |
| 设置页「应用更新」显示「检查失败」 | Gitee Raw 拉取失败（网络/被墙）。启动自动检查失败仅打日志（`AppUpdate` tag） |
| 发现新版本但点「去更新」没反应 | 应用内无法安装 HAP，只能引导浏览器；看 `AppUpdate` tag 的 `startAbility failed` |

## 测试 / 排查环境（本机真机）

| 现象 | 处理 |
| --- | --- |
| `hdc shell` 无法写沙箱（`touch`/`mkdir` 被拒） | 正常：设备沙箱对 `shell` 只读。只能**读**（`ls`/`cat`/`hdc file recv`），无法人工制造中断状态 |
| `uitest uiInput click` 点不动首页右上角齿轮 | 齿轮落在系统手势区，注入点击被吞。改用真机手点，或 `bm clean -d -n <bundle>` 造「未下载」态后从首页按钮触发下载 |
| 想验证「未下载 → 下载 → 解压」全流程 | `hdc shell bm clean -d -n com.lichtcui.pokelauncher` 清数据（**会丢存档与参数调整配置**），重启 App 后点「下载游戏资源」 |
| 想验证「下载中被杀 → 接管」 | 下载开始约 30s 后 `aa force-stop`（后台任务会继续，看 `cache/game.zip` 仍在增长），再启动 App，应显示进度并接管 |
