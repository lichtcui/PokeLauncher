# shared/ —— 两端共用的「唯一真相」

> ⚠️ **本目录的文件才是唯一真相**：**8 个生成物**——鸿蒙侧 `entry/src/main/ets/model/Cheats.ets`、
> `common/SharedJs.ets`、`model/LegalText.ets`、`common/Mime.ets`；安卓侧 `android/.../cheat/Cheats.kt`、
> `core/SharedJs.kt`、`ui/LegalText.kt`、`core/Mime.kt`——**全部由这里生成，不要手改**。

## 为什么存在

鸿蒙（ArkTS）与安卓（Kotlin）**没有共同运行时可共享类型**（KMP 不支持 OHOS target），所以「公共代码」只能是
**生成**，不可能是引用。本目录只放**纯 JS 字符串与注册表数据**——它们才是真正需要两端逐字一致的东西：

| 内容 | 文件 | 为什么必须一模一样 |
| --- | --- | --- |
| 参数调整条目注册表 | `cheats.json` + `js/cheats/*.js` | 脚本依赖游戏内部方法名，两端写法一旦分叉，一端会静默失效 |
| 场景捕获运行时 | `js/scene-runtime.core.js` | `__onScene` / `__onPhase` / `Array.prototype.push` hook 是 7 条脚本的共同底座 |
| 存档守卫 | `js/save-guard.js` | 数据安全功能，不允许「只有一端修好了」 |
| canvas 补丁 | `js/canvas-patch.js` | Phaser 每帧 `getImageData` 的 GPU 读回开销，两端同因同解 |
| 法律页正文 | `legal.json` | 同一份免责声明，各拄一份时改一边就漏另一边；平台差异（启动器名 / 设备 / 技术栈 / 中文接拉丁的空格）只允许写在 `placeholders` 里 |
| 扩展名 → MIME | `mime.json` | 本地 HTTP 服务的 `Content-Type`；`.js` 回错 MIME 会让 Chromium 拒绝执行模块脚本 → 白屏 |

**不放这里**的东西（有意为之）：平台 API 层（`LocalHttpServer` / `GameServer` / `Prefs` / 安装器）、UI 页面、
以及各端独有的常量（如 `USER_AGENT`、Android 的 `API_STUB_PORT`）。它们不共享不是遗漏，是没有共同点。

## 怎么改

```bash
node scripts/gen-shared.mjs          # 改完 shared/** 后重新生成两端代码
node scripts/gen-shared.mjs --check  # 只校验（生成文件是否过期 + 两端常量/注入链是否分叉）；CI 与构建里跑
```

- **新增一条参数调整条目**：在 `cheats.json` 的 `cheats` 数组加一块 + 写一个 `js/cheats/<id>.js`，
  两端 UI / 状态 / 注入会自动生效，**不需要改任何平台代码**。
- **改法律页文案**：改 `legal.json` 的 `sections`。两端共同的句子写正文，平台差异写 `placeholders`：
  新增差异必须给**两个平台**都加同名 key（少一个报错，多一个没人用也报错）。
  `spaceAfterDe` / `spaceAfterZai` 是「中文与拉丁文混排的空格」（「非官方的 HarmonyOS」要空格、
  「非官方的启动器」不要），取值就是单个空格或空串，**别把它们「清理」掉**。
- **加一条 MIME**：改 `mime.json` 的 `map`（小写扩展名、不含点）。`js` 必须是 `text/javascript`，
  生成器硬断言，不然白屏。
- 生成器会做结构校验（id 唯一、分组存在、`min<max`、`default` 在区间内、labels 档位数、脚本必须
  `__onScene`/`__onPhase`、声明了 `param` 必须读 `__CHEAT_PARAMS__.<param.id>`、法律页占位符两边一对一等）。
- **JS 里不得出现 `$` 或 `"""`**：Kotlin 的 `"""` raw string 会把 `$` 当模板插值。生成器会直接报错拦下。

## 不生成、只对账的常量

有些值两端各写一份（各自文件里还有平台独有内容，生成反而会把文件撕碎），`--check` 保证它们不分叉：

| 对账项 | 鸿蒙 | 安卓 |
| --- | --- | --- |
| 游戏端口 | `LocalHttpServer.PORT`（18787） | `Const.GAME_PORT` |
| 镜像列表 / 资源来源 / 版本清单地址 | `Const.ets` | `Const.kt` |
| 测速参数 | `GameRepository.PROBE_BYTES / PROBE_TIMEOUT_MS / RANK_TTL_MS` | 同名 |
| 存储预检系数 | `LauncherSession.requiredStorage()` 的 `2.4 / 3.2` | `Storage.FACTOR_FRESH / FACTOR_UPDATE` |
| `USER_AGENT` | `PokeLauncher-Harmony` | `PokeLauncher-Android`（**有意不同**，白名单锁死） |

## 构建期拦截

- Android：`:app:checkSharedCode`（`preBuild` 依赖）→ 过期即构建失败。
- HarmonyOS：根 `hvigorfile.ts` 的 `checkSharedCode` 插件 → 过期即构建失败。
- 发布：`scripts/build-release-app.sh` 里再跑一次 `--check`。

失败提示都是「跑 `node scripts/gen-shared.mjs`」，不是让你手改生成文件。
