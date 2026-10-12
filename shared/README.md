# shared/ —— 两端共用的「唯一真相」

> ⚠️ **本目录的文件才是唯一真相**：**10 个生成物**——源码 8 个：鸿蒙侧 `entry/src/main/ets/model/Cheats.ets`、
> `common/SharedJs.ets`、`model/LegalText.ets`、`common/Mime.ets`；安卓侧 `android/.../cheat/Cheats.kt`、
> `core/SharedJs.kt`、`ui/LegalText.kt`、`core/Mime.kt`；测试数据 2 个：鸿蒙
> `entry/src/ohosTest/ets/test/Cases.gen.ets`、安卓 `android/app/src/test/.../CasesGen.kt`
> ——**全部由这里生成，不要手改**。

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
| 纯函数行为向量 | `cases/paths.json`、`cases/format.json`、`cases/version.json` | 路径判定 / 展示格式 / 版本比较两端各写一份实现，**期望值也只该有一份**；否则「一端改了、另一端没改」只能靠人眼比对两个测试文件 |

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
- **加一条行为向量**：改 `cases/*.json`。**两端测试都遍历同一套向量**（鸿蒙 `ohosTest/*.test.ets`、
  安卓 `core/*Test.kt`），所以别在测试文件里再写一份期望值——生成器会拦（见下）。`paths.expect = null`
  表示「应当拒绝」（鸿蒙返回空串、安卓返回 `null`，各端测试自己换算）。
- **`node scripts/check-harmony-vectors.mjs`**：把鸿蒙的纯函数从 `.ets` 里剪出来、在 Node 里喂同一批向量。
  鸿蒙那几个套件只能跑在**设备**上（本机常常没有 `hdc` 设备），没它的话「鸿蒙侧实现是否满足向量」
  在开发期就没人回答得了。`--check` 会顺带调用它（失败即构建失败）。
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

## 反向护栏：JS 桥 / 虚拟端点也对账

`shared/` 之外还有一类「三方各写一份字面量」的接口——**桥对象名与方法名、虚拟端点、注入链顺序**。
它们没有类型，改一边漏一边的后果是**静默失效**（脚本拿到 `undefined` 就悄悄跳过），
因此 `--check` 把它们也钉住：

| 契约 | 三方 | 对账内容 |
| --- | --- | --- |
| 存档守卫桥 | `shared/js/save-guard.js`（`window.PokeSaveGuard` + `load/save/notifyRestored`）↔ 鸿蒙 `GamePage.ets` 的 `javaScriptProxy{name, methodList}` ↔ 安卓 `SaveGuard.BRIDGE_NAME` + `PokeWebView.kt` 的 `SaveGuardBridge` | 对象名相等、**方法集合完全相等**（鸿蒙 `methodList` 是白名单，少一个就静默失败） |
| 打靶 / 导出桥 | 安卓 `NativeBridge`（`PokeNative`）↔ 各段 JS 字符串 | 每个 `@JavascriptInterface` 方法都要有 JS 调用方；`BRIDGE_METHODS` 里的名字必须真的声明过 |
| 参数调整脚本端点 | 安卓 `Const.CHEAT_SCRIPT_PATH` ↔ 鸿蒙 `LocalHttpServer` 路由 | 两端都得是 `/__cheats__.js` |
| 数值参数通道 | `shared/js/cheats/*.js` 读的 `__CHEAT_PARAMS__` ↔ 两端参数调整运行时 | 两端都要 `var __CHEAT_PARAMS__ =`（否则共享脚本静默退回默认值） |
| 注入链顺序 | 鸿蒙 `servePatchedIndex` / 安卓 `InjectScripts` | 存档守卫 → 参数调整 → canvas 补丁（守卫必须早于游戏读 localStorage） |
| 安卓混淆 | `proguard-rules.pro` | `@android.webkit.JavascriptInterface <methods>;` 必须保留（名字不能改，见上） |

## 反向护栏：手写清单与 release 混淆白名单

另有两类「写了两处、错一处不报错」的东西被 `--check` 钉住：

| 对象 | 两处 | 对账内容 |
| --- | --- | --- |
| `version.json`（**手写**，发版流程第 2 步） | 鸿蒙 `AppUpdate.ets` 的 `ManifestJson` ↔ 安卓 `appupdate/AppUpdate.kt` 的 `optX("…")` | 每个键至少被一端认识（打错字 → 两端都静默忽略）；`versionCode` 正整数、`minVersionCode ≤ versionCode`；写了 `android*`/`apkUrl` 就必须有 `androidVersionCode` + `androidVersionName`（否则安卓退化成拿鸿蒙的 versionCode 比 → 永远「发现新版本」且指向 HAP 页）；三个 URL 必须是 http(s) |
| 鸿蒙 release 混淆 `-keep-property-name` | `entry/obfuscation-rules.txt` ↔ 源码里真正解析的 JSON 键 | 从 `JSON.parse(...) as T` 出发递归收集字段名（含 `assets: ReleaseAsset[]` 这种嵌套）+ `javaScriptProxy` 的 `methodList`，逐个查白名单——漏一个，**release 包静默读不到值**（debug 正常、release 一律走默认值） |

## 反向护栏：向量必须真被用上

生成器不只管「生成」，还管「别退回去」：

- 各端向量测试文件必须引用生成容器（`PATH_CASES` / `CasesGen.PATH_CASES` …），否则报错；
- 各端测试文件里**不允许再手写**那些期望值（正则命中即报错，例如安卓测试里再写
  `resolveRequestPath("/%2e%2e/x")`、鸿蒙测试里再写 `'%2E%2E'`）；
- 生成的 Kotlin 文档注释里不允许出现 `/*`（Kotlin 块注释**可嵌套**，写 `shared/cases/*.json`
  会让整个文件变成未闭合注释 → 「Unclosed comment」，踩过一次）。

## 构建期拦截

- Android：`:app:checkSharedCode`（`preBuild` 依赖）→ 过期即构建失败。
- HarmonyOS：根 `hvigorfile.ts` 的 `checkSharedCode` 插件 → 过期即构建失败。
- 发布：`scripts/build-release-app.sh` 里再跑一次 `--check`。

失败提示都是「跑 `node scripts/gen-shared.mjs`」，不是让你手改生成文件。

## 验证状态（2026-10-12）

| 环节 | 状态 |
| --- | --- |
| `--check` 拦截 | ✅ 实测：故意把 `mime.json` 的 `js` 改成 `text/plain` → 鸿蒙构建失败在 `checkSharedCode` 插件、安卓失败在 `:app:checkSharedCode`；改回即通过。「占位符两端不对齐」「多了没人用的占位符」两种改法同样被拦。 |
| 生成物落进发布产物 | ✅ 抽查 release 包字面量：鸿蒙 `ets/modules.abc` 含「HarmonyOS NEXT 启动器」措辞，安卓 `classes.dex` 含「启动器（Launcher）」措辞（各只含**本平台**那一份，R8 / 混淆后未丢）。 |
| 安卓真机 | ✅ 装 debug 包 → 设置 → 法律与免责声明：10 章节全渲染、正文为安卓措辞、新增句「应用启动时会访问 Gitee…」在位；进游戏页正常（canvas 补丁 + Phaser v3.90.0 起来 ⇒ MIME 表端到端可用）。 |
| 鸿蒙真机 | ⚠️ **未跑**（本机无 `hdc` 设备）：法律页渲染与游戏页注入链 / ArkWeb 日志判据都还没在设备上确认；鸿蒙侧目前只有「debug + release 编译通过 + 发布产物字面量抽查 + 用 Python 复跑 `LegalText` 那套断言」。 |
| `LegalText.test.ets`（ohosTest） | ⚠️ 只编译通过（故意写坏能正确报错），**没在真机上执行过**（同样缺设备）。 |
| 行为向量（两端） | ✅ 安卓 279 例单测全绿（各套件直接遍历向量）；鸿蒙侧由 `scripts/check-harmony-vectors.mjs` 复跑同一批向量 → **113 条全过**（paths / format / version / mime，含 NaN / Infinity 四条手写边角）。 |
| 该脚本挂在 `--check` 上 | ✅ 实测：改坏一条向量（`1048575 → "1023.9 KB"`）→ 两端构建都会失败在 `checkSharedCode`；去掉鸿蒙 `formatSpeed` 的 `isFinite` 兜底 → 同样失败（这条正是脚本第一次跑就抓出来的：`formatSpeed(NaN)` 会显示 `NaN KB/s`，已修）。 |
| 向量护栏 | ✅ 实测：把 `cases/format.json` 里 `1048575 → "1024 KB"` 改成 `"1023.9 KB"` → 安卓 `FormatTest` 红、鸿蒙侧复跑也红（同一份期望值）；在两端测试里各手写回一条路径期望值 → `--check` 报「又手写了一份期望值」。 |
| 向量在**鸿蒙真机**上跑 | ⚠️ **未跑**：`LocalHttpServer.test` / `Format.test` / `GameRepository.test` 都改成了遍历生成向量，只在编译期验证过（缺 `hdc` 设备）。 |
| 手写清单 `version.json` | ✅ 实测 6 种改法全被拦：键名打错（`androidVersioncode`）、写了 `android*` 却没有 `androidVersionName`、只写 `apkUrl`、`minVersionCode > versionCode`、`pageUrl` 缺协议、`versionName` 清空。 |
| 混淆白名单 | ✅ 实测 3 种改法全被拦：白名单删掉嵌套类字段（`ReleaseAsset.browser_download_url` / `.size`）、删掉 `methodList` 里的 `load`、`ManifestJson` 新增字段却不同步白名单。 |
| JS 桥 / 端点对账 | ✅ 实测 6 种改法全被拦（各只改一处、改完即还原）：安卓桥方法改名、`BRIDGE_METHODS` 里写不存在的方法、鸿蒙 `methodList` 少一项、鸿蒙注入链顺序调换、安卓端点改名、鸿蒙不再生成 `__CHEAT_PARAMS__`（这一条第一版写成 `includes` 被注释骗过，已收紧为 `var __CHEAT_PARAMS__ =` 正则）。 |

⏭️ 待补：接上鸿蒙真机后跑「安装 → 法律页 → 游戏页抓 `ARKWEB-CONSOLE`」与 `hdc shell aa test … OpenHarmonyTestRunner`
（后者会一次跑掉 `LegalText.test` 与三个改成遍历向量的套件）。
