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
| 行为向量（两端） | ✅ 安卓 279 例单测全绿（`matchesTheSharedVectors` 等直接遍历向量）；鸿蒙侧把生成的 4 个纯函数 `resolveRequestPath / normalizeVersion / sameVersion / mimeOf` 与 `Format.ets` 按 `node` 复跑同一批向量 → **107 条全过**（与安卓同一批期望值）。 |
| 向量护栏 | ✅ 实测：把 `cases/format.json` 里 `1048575 → "1024 KB"` 改成 `"1023.9 KB"` → 安卓 `FormatTest` 红、鸿蒙侧复跑也红（同一份期望值）；在两端测试里各手写回一条路径期望值 → `--check` 报「又手写了一份期望值」。 |
| 向量在**鸿蒙真机**上跑 | ⚠️ **未跑**：`LocalHttpServer.test` / `Format.test` / `GameRepository.test` 都改成了遍历生成向量，只在编译期验证过（缺 `hdc` 设备）。 |

⏭️ 待补：接上鸿蒙真机后跑「安装 → 法律页 → 游戏页抓 `ARKWEB-CONSOLE`」与 `hdc shell aa test … OpenHarmonyTestRunner`
（后者会一次跑掉 `LegalText.test` 与三个改成遍历向量的套件）。
