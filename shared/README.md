# shared/ —— 两端共用的「唯一真相」

> ⚠️ **本目录的文件才是唯一真相**：**14 个生成物**——源码 12 个：鸿蒙侧 `entry/src/main/ets/model/Cheats.ets`、
> `common/SharedJs.ets`、`model/LegalText.ets`、`common/Mime.ets`、`common/SharedUi.ets`、`common/Theme.ets`；安卓侧
> `android/.../cheat/Cheats.kt`、`core/SharedJs.kt`、`ui/LegalText.kt`、`core/Mime.kt`、`core/SharedUi.kt`、`ui/Theme.kt`；
> 测试数据 2 个：鸿蒙 `entry/src/ohosTest/ets/test/Cases.gen.ets`、安卓 `android/app/src/test/.../CasesGen.kt`
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
| 下载候选 / 选源排序 | `cases/download.json` | 候选地址（镜像前缀 + 直连兜底）、测速排序、host / 角色展示两端各写一份，安卓那份文件头写着「对应鸿蒙版 `buildDownloadUrls` / `rankSources`」「与鸿蒙版 `Index.mirrorOf` 同规则」——注释不算护栏。分叉症状是「某个镜像没被用上 / 失败的源又撞一次 / 单源调试包被当成没测速结果跳过」，全都要真机下载才看得出来 |
| 本地服务缓存语义 | `cases/http.json` | `Cache-Control` 也是两端各写一份（安卓那边注释写着「与鸿蒙版 `cacheControl()` 一致」——注释不算护栏）；HTML / service-worker 被缓存住的症状是「改了注入内容却看不到变化」，只有真机看得出来 |
| 参数取值与展示 | `cases/cheat.json` | 「原始串 → 夹紧 → 滑块浮点吸附」与「labels 档位名 / 单位拼接」两端各写一份实现（鸿蒙 `common/CheatFormat.ets` ↔ 安卓 `cheat/CheatFormat.kt`）。**真分叉过**：单位拼接方向相反（`x10` vs `10x`）、labels 越界判定一宽一严、浮点吸附只有一端做；同步的 `params` 还刻意包含注册表里没有的形状（省略 `step` / 省略 `unit` / 小数 `step`），否则那几条分支没人守 |
| 用户可见文案与展示常量 | `ui.json` | 两端都会渲染的同一句话，各拄一份 → 生成 `common/SharedUi.ets` / `core/SharedUi.kt`（首页流程标题 / 进度行、存储不足提示、完成·失败通知、游戏页背景色）。这条是「同一件事写两处」的显影剂：存储不足提示一端「需要约 1229 MB」、另一端「需要约 1.2 GB」；游戏页背景色一端 `#484050`、另一端 `0xFF121212`；进度行的「剩余」前缀只有一端有。收录标准是**两端都渲染**：「完成」措辞（安卓「下载并安装完成」vs 鸿蒙「下载完成」）、安卓通知专有措辞（「正在寻找最快的下载源」「下载已暂停」）、单端阶段（安卓「未开始」「已取消」）**有意不收**（文件头的 `_doc` 列了完整名单） |
| 视觉令牌（颜色） | `theme.json` | 两端的颜色字面量曾经 41 种、真正同值的只有 5 个，而且主题相反（鸿蒙首页白 / 安卓首页深）——**同一条视觉规则写两处、还都「自己看着对」**。现在语义名 → hex 只写一份，生成 `common/Theme.ets` / `ui/Theme.kt`（安卓那份同时是 Material3 亮色方案，角色必须写全，不然组件默默变紫）。业务代码里不许再写死颜色（`checkThemeUsage` 按目录全量扫） |

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
- **加一条行为向量**：改 `cases/*.json`（`http.json` 的入参是**已解析好的游戏内路径**：`resolveRequestPath`
  已经去掉 query，所以向量里不带 `?`；`cheat.json` 的 `params` 形状与 `cheats.json` 的 `param` 一致）。
  **两端测试都遍历同一套向量**（鸿蒙 `ohosTest/*.test.ets`、安卓 `core/*Test.kt` / `cheat/CheatFormatTest.kt`），
  所以别在测试文件里再写一份期望值——生成器会拦（见下）。`paths.expect = null`
  表示「应当拒绝」（鸿蒙返回空串、安卓返回 `null`，各端测试自己换算）。
- **改两端同一角色的用户可见文案**：改 `ui.json` 的 `items`（`text` 简单常量 / `template` 带 `{占位符}` 的函数 /
  `argb` 颜色）。`value` 里不能出现 `$` `'` `"` `\` 换行（会破坏生成的字面量），模板的 `args` 必须与
  `{占位符}` 一一对应。生成的常量必须被业务代码**真的用上**：`checkUiUsage` 逐文件列了「必须出现」与
  「不许再内联」的片段，内联回去 = 构建失败。
- **改一个 UI 颜色**：改 `theme.json` 的 `colors`（语义名 + `doc` + `#RRGGBB`）与 `materialLight`（安卓
  Material3 角色 → 令牌名，**必顶写全**）。业务代码里不要再写 `'#DA3838'` / `Color(0xFFDA3838)` /
  `Color.White`：`checkThemeUsage` 会扫两个 UI 源码目录（新增页面自动纳入），写死即失败；
  同时每个令牌都必须在两端代码里被引用过（僵尸令牌也报错）。同一个 hex 允许有多个语义名
  （`background` / `surface` / `onBrand` / `onScrim` 都是白），**不要去重**。
  另外**鸿蒙的 `Text` 必须显式写 `.fontColor(...)`**（`checkHarmonyTextColor` 扫全目录；扫之前会抹掉
  注释与字符串，所以 `// Text('…')` 不算）：不写就用**框架默认色**（真机实测渲染 `#313131`），
  那个值既不在 `theme.json` 里、也不在源码里，系统深色模式或框架升级一动就静默换色，
  而安卓同位置的默认值是 Material3 `onSurface`（`#333333`）——本来就不是同一个。
  大标题与返回箭头统一 pin 到 `textPrimary`（`#333333`，跟鸿蒙原观感一致、零视觉变化）：
  首页三个大标题（未安装 / 下载流程 / 就绪）、设置 / 参数调整 / 法律三个二级页大标题，
  以及安卓 `LauncherTopBar` 的 `navigationIconContentColor`（不写就落到 `onSurfaceVariant` `#999999`，
  而鸿蒙同一个「‹」是 `#333333`——30sp 大字形，差一档看得见）。
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
| 精灵球动画节奏 | `PokeballLoader.ets` 的 `SHAKE_INTERVAL_MS / SHAKE_ANGLE / SHAKE_ANIM_MS / BURST_MS` | `HomeState.kt` 同名（安卓那里注释写着「与鸿蒙版同值」——注释不算护栏） |
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

## 反向护栏：安卓原生侧的目录名 / 白名单

同一端里「一件事写两个文件」的地方，错一处代价都很大，也一并钉住：

| 两处 | 对不上会怎样 |
| --- | --- |
| `Const.SAVE_DIR_NAME` ↔ `backup_rules.xml` + `data_extraction_rules.xml` 的 `<include domain="file" path=…>`（并顺带对账鸿蒙 `SaveGuard.SNAPSHOT_DIR`） | 备份规则把存档排除在外 → 只有换机 / 重装时才发现档没了；多备一个 `external` 更糟：游戏本体 1.5GB 撞 25MB 配额，整次备份失败捎带存档一起丢 |
| `ApkUpdater.DIR_NAME` ↔ `provider_paths.xml` 的路径白名单 | FileProvider 只认白名单路径，缺了会在安装时抛 `IllegalArgumentException`；多放行一条等于把私有目录读权限授出去 |

## 反向护栏：文字颜色不许靠「框架默认值」

颜色令牌只能管**写出来的**颜色。`Text` 不写 `fontColor` 时用的是框架默认色（鸿蒙真机实测渲染
`#313131`）——它既不在 `theme.json` 里、也不在两端任何源码里，令牌层看不见它：系统深色模式或框架
升级换掉它就静默变色，而安卓同位置的默认值是 Material3 `onSurface`（`textPrimary` `#333333`），
默认值本来就不同。所以 `checkHarmonyTextColor` 要求鸿蒙 UI 里每个 `Text(...)` 链上必须出现
`.fontColor(...)`（先做括号配对再顺序读链式调用，扫前抹掉注释与字符串，`// Text('…')` 不误报）。

桌面上的另一半管不到：安卓这边 `colorScheme.onSurface` / `onBackground` 都映射到 `textPrimary`，
所以「不写 color」是被令牌固定住的；真正会漂的是**组件自己给内容色**的槽位
（`TopAppBar` 的 `navigationIconContentColor` 就是），那条只能靠写死在代码里，护栏看不出对错。

## 反向护栏：向量必须真被用上

生成器不只管「生成」，还管「别退回去」：

- 各端向量测试文件必须引用生成容器（`PATH_CASES` / `CasesGen.PATH_CASES` …），否则报错；
- 各端测试文件里**不允许再手写**那些期望值（命中即报错，例如安卓测试里再写
  `resolveRequestPath("/%2e%2e/x")`、鸿蒙测试里再写 `'%2E%2E'`、两端参数测试里再写向量里的
  `'x10'` / `"Master"` 这类展示文案）；
- 各端业务代码里不允许把 `ui.json` 的文案重新内联回去（`checkUiUsage` 的 forbidden 片段），
  也不允许把 `CheatFormat` 的取值逻辑再抄一份——`CheatState.ets` 必须调用 `resolveNumber` / `numberLiteral`，
  参数调整页必须调用 `displayValue`；
- 各端 UI 源码里不允许再写死颜色字面量（`checkThemeUsage`：鸿蒙 `'#RRGGBB'`、安卓 `Color(0x…)` /
  `Color.White`），也不允许出现没人引用的令牌——全靠目录扫描，新增页面自动纳入，不用维护文件清单；
- 生成的 Kotlin 文档注释里不允许出现 `/*`（Kotlin 块注释**可嵌套**，写 `shared/cases/*.json`
  会让整个文件变成未闭合注释 → 「Unclosed comment」，踩过一次）；
- **Kotlin 的色值字面量必须补 alpha**：`Color(0xDA3838)` 是 `0x00DA3838` = **全透明**（不是「差一点」，
  是整个色块看不见），生成器用 `ktArgb()` 把 `#RRGGBB` 补成 `0xFFRRGGBB`（8 位色值不补）。
  这类 bug 单看代码看不出来，`--check` 也只保证「和上次生成的一致」——改生成器时要真的**读一遍生成物**。

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
| 鸿蒙真机 | ✅ 部分：`entry@ohosTest` 已真机跑完（`Tests run: 25, Failure: 0, Error: 0`，套件：路径 / 缓存 / 版本 / 格式化 / MIME / 下载源 / 参数取值 / 法律页）；首页未下载态已做像素对账。⚠️ **仍未跑**：下载流四阶段与就绪页、设置 / 参数调整页、游戏页（`#484050` 底 + 注入链）—— 那几屏要先在设备上装 507MB 资源包才看得到。 |
| `LegalText.test.ets`（ohosTest） | ✅ 已真机执行（在同一次 `entry@ohosTest` 的 25 例里，含「章节 id + 顺序」「文案不为空」「两种禁用词」）。 |
| 行为向量（两端） | ✅ 安卓 260 例单测全绿（各套件直接遍历向量）；鸿蒙侧由 `scripts/check-harmony-vectors.mjs` 复跑同一批向量 → **235 条全过**（paths / format / version / cache / download / mime / cheat，含手写边角：formatDuration/formatSpeed/formatBytes 喂 NaN 与 Infinity、`numberLiteral` 喂非有限值），并且已在**真机 `entry@ohosTest` 实跑**（25 个测试方法遍历同一套向量，`Failure: 0, Error: 0`）。 |
| 参数取值 / 展示向量 | ✅ 实测：新增 `cheat.json` 后两端同时接上 —— 安卓 `CheatFormatTest`（16 例）与鸿蒙 `CheatFormat.test.ets` 遍历同一批 23 条向量；把两端 `displayValue` 的拼接方向改成相反 → 鸿蒙侧 Node 复跑与安卓单测同时红。旧实现的两个真实分叉已修：鸿蒙单位方向（`10x` → `x10`）、安卓 `displayValue` 越界取 label 的判定。 |
| 共享文案 / 展示常量 | ✅ 实测：`shared/ui.json` 的 13 条生成 `SharedUi.ets` / `SharedUi.kt`；首页流程标题与阶段说明（下载 / 解压 / 就绪 / 测速 / 连接中 / 暂停）、进度行的「剩余」前缀、存储不足提示（两端同一条 + 同一个格式化器）、完成 / 失败通知、游戏页背景色（两端统一 `#484050`）全部改为引用生成物；`checkUiUsage` 反向要求业务代码引用（逐文件列了「必须出现」与「不许再内联」的片段），内联回去即失败。 |
| 视觉令牌（颜色） | ✅ 实测：`shared/theme.json` 的 28 个令牌（24 个不同 hex，其中 `#FFFFFF` 有 4 个语义名、`#99000000` 有 2 个）生成 `common/Theme.ets` / `ui/Theme.kt`（安卓那份同时是 Material3 亮色方案，36 个角色必顶写全）。两端 UI 里原本 41 种颜色字面量（仅 5 个同值、主题相反）全部改为引用令牌，机械替换后 `--check` 一次性通过。**护栏反向测过**：把写死的 hex 写回去 → 报出文件与行号（鸿蒙 `LauncherViews.ets:35` / 安卓 `HomeScreen.kt:430`，改回即过）；把只被一处引用的令牌（`gamePanel`）的引用删掉 → 报「僵尸令牌」。⚠️ 边界：Material3 角色映射本身也算引用，所以只被角色映射用到的令牌不会报僵尸（有意如此——那些角色是给 Compose 组件读的）。 |
| 视觉令牌：真机像素对账 | ✅ 鸿蒙真机（`uitest screenCap` 无损 PNG + `uitest dumpLayout` 拿 bounds 定位区域）：首页未下载态逐值命中令牌 —— 页面底 `#FFFFFF`（`dumpLayout` 的 `backgroundColor` 也是 `#FFFFFFFF`）、主按钮 `#DA3838` + 白字、信息卡 `#F7F7F7`、次按钮 `#F2F2F2`、字段名 `#888888`、字段值 `#333333`；另有脚本逐处核对鸿蒙 11 个文件的 104 处令牌引用与旧 hex **全部同值**（令牌化没动鸿蒙外观）。⚠️ `snapshot_display` 的 JPEG 会偏色（`#DA3838` → `#DA3738`），像素判据要用 `screenCap` 的 PNG。 |
| 文字颜色（不被默认值管的部分） | ✅ 实测：真机像素对账发现**大标题没被令牌固定** —— 首页三个大标题与三个二级页大标题都没写 `fontColor`，走框架默认色（实测渲染 `#313131`），安卓同位置是 `onSurface` `#333333`；安卓 `LauncherTopBar` 的返回箭头落到 `navigationIconContentColor` = `onSurfaceVariant` `#999999`，而鸿蒙同一个「‹」是 `#333333`。已全部 pin 到 `textPrimary`（鸿蒙零视觉变化）。**护栏反向测过**：删掉任一处 `.fontColor` → 报文件与行号；新增 `checkHarmonyTextColor` 对注释/字符串里的 `Text(` 不误报（先做括号配对、扫前抹掉注释与字符串）。 |
| 下载源向量 | ✅ 实测 5 种改法全被拦（鸿蒙 `common/DownloadUrls.ets` 各改一处、改完即还原，安卓侧同一套向量由 `DownloadUrlsTest` 跑）：删掉「空 assetUrl → 无候选」守卫、超时（speed 0）不再当失败跳过、直连兜底不再排最后、本地调试包不再走单源、镜像前缀判断松成「以 `https://github.com` 开头」。 |
| 缓存语义向量 | ✅ 实测 5 种改法全被拦：鸿蒙漏掉 `.htm` 判定、鸿蒙去掉大小写归一（`/INDEX.HTML` 会被缓存一年）、向量里删掉 `webmanifest` 那条、向量里长缓存期望被改掉、安卓 `HttpCache` 去掉大小写归一（`HttpCacheTest` 红）。 |
| 该脚本挂在 `--check` 上 | ✅ 实测：改坏一条向量（`1048575 → "1023.9 KB"`）→ 两端构建都会失败在 `checkSharedCode`；去掉鸿蒙 `formatSpeed` 的 `isFinite` 兜底 → 同样失败（这条正是脚本第一次跑就抓出来的：`formatSpeed(NaN)` 会显示 `NaN KB/s`，已修）。 |
| 向量护栏 | ✅ 实测：把 `cases/format.json` 里 `1048575 → "1024 KB"` 改成 `"1023.9 KB"` → 安卓 `FormatTest` 红、鸿蒙侧复跑也红（同一份期望值）；在两端测试里各手写回一条路径期望值 → `--check` 报「又手写了一份期望值」。 |
| 向量在**鸿蒙真机**上跑 | ⚠️ **未跑**：`LocalHttpServer.test` / `Format.test` / `GameRepository.test` / `DownloadUrls.test` 都（改）成了遍历生成向量，只在编译期验证过（缺 `hdc` 设备）。 |
| 版本号对齐 | ✅ 实测 6 种改法：清单落后于 `AppScope/app.json5`（拦）、清单超前（只提示、exit 0）、版本号相同而 `versionName` 不一致（拦）、安卓 `gradle versionCode=2` 而清单 `androidVersionCode=1`（拦）、安卓版本名不一致（拦）、安卓清单超前（只提示）。 |
| 手写清单 `version.json` | ✅ 实测 6 种改法全被拦：键名打错（`androidVersioncode`）、写了 `android*` 却没有 `androidVersionName`、只写 `apkUrl`、`minVersionCode > versionCode`、`pageUrl` 缺协议、`versionName` 清空。 |
| 原生目录 / 白名单 | ✅ 实测 8 种改法全被拦：鸿蒙摇晃幅度 14→15、安卓 `BURST_MS` 900→950、存档目录改名、备份规则多备 `external`、备份规则漏掉存档目录、数据提取只漏一块的 include、APK 目录改名、FileProvider 多放行一条 `cache-path`。 |
| 混淆白名单 | ✅ 实测 3 种改法全被拦：白名单删掉嵌套类字段（`ReleaseAsset.browser_download_url` / `.size`）、删掉 `methodList` 里的 `load`、`ManifestJson` 新增字段却不同步白名单。 |
| JS 桥 / 端点对账 | ✅ 实测 6 种改法全被拦（各只改一处、改完即还原）：安卓桥方法改名、`BRIDGE_METHODS` 里写不存在的方法、鸿蒙 `methodList` 少一项、鸿蒙注入链顺序调换、安卓端点改名、鸿蒙不再生成 `__CHEAT_PARAMS__`（这一条第一版写成 `includes` 被注释骗过，已收紧为 `var __CHEAT_PARAMS__ =` 正则）。 |

⏭️ 待补：接上鸿蒙真机后跑「安装 → 法律页 → 游戏页抓 `ARKWEB-CONSOLE`」与 `hdc shell aa test … OpenHarmonyTestRunner`
（后者会一次跑掉 `LegalText.test` 与四个改成遍历向量的套件）。
