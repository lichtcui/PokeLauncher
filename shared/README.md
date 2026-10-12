# shared/ —— 两端共用的「唯一真相」

> ⚠️ **本目录的文件才是唯一真相**：`entry/src/main/ets/model/Cheats.ets`、`entry/src/main/ets/common/SharedJs.ets`、
> `android/.../cheat/Cheats.kt`、`android/.../core/SharedJs.kt` 都由这里生成，**不要手改生成文件**。

## 为什么存在

鸿蒙（ArkTS）与安卓（Kotlin）**没有共同运行时可共享类型**（KMP 不支持 OHOS target），所以「公共代码」只能是
**生成**，不可能是引用。本目录只放**纯 JS 字符串与注册表数据**——它们才是真正需要两端逐字一致的东西：

| 内容 | 文件 | 为什么必须一模一样 |
| --- | --- | --- |
| 参数调整条目注册表 | `cheats.json` + `js/cheats/*.js` | 脚本依赖游戏内部方法名，两端写法一旦分叉，一端会静默失效 |
| 场景捕获运行时 | `js/scene-runtime.core.js` | `__onScene` / `__onPhase` / `Array.prototype.push` hook 是 7 条脚本的共同底座 |
| 存档守卫 | `js/save-guard.js` | 数据安全功能，不允许「只有一端修好了」 |
| canvas 补丁 | `js/canvas-patch.js` | Phaser 每帧 `getImageData` 的 GPU 读回开销，两端同因同解 |

**不放这里**的东西（有意为之）：平台 API 层（`LocalHttpServer` / `GameServer` / `Prefs` / 安装器）、UI 页面、
以及各端独有的常量（如 `USER_AGENT`、Android 的 `API_STUB_PORT`）。它们不共享不是遗漏，是没有共同点。

## 怎么改

```bash
node scripts/gen-shared.mjs          # 改完 shared/** 后重新生成两端代码
node scripts/gen-shared.mjs --check  # 只校验（生成文件是否过期 + 两端常量/注入链是否分叉）；CI 与构建里跑
```

- **新增一条参数调整条目**：在 `cheats.json` 的 `cheats` 数组加一块 + 写一个 `js/cheats/<id>.js`，
  两端 UI / 状态 / 注入会自动生效，**不需要改任何平台代码**。
- 生成器会做结构校验（id 唯一、分组存在、`min<max`、`default` 在区间内、labels 档位数、脚本必须
  `__onScene`/`__onPhase`、声明了 `param` 必须读 `__CHEAT_PARAMS__.<param.id>`）。
- **JS 里不得出现 `$` 或 `"""`**：Kotlin 的 `"""` raw string 会把 `$` 当模板插值。生成器会直接报错拦下。

## 构建期拦截

- Android：`:app:checkSharedCode`（`preBuild` 依赖）→ 过期即构建失败。
- HarmonyOS：根 `hvigorfile.ts` 的 `checkSharedCode` 插件 → 过期即构建失败。
- 发布：`scripts/build-release-app.sh` 里再跑一次 `--check`。

失败提示都是「跑 `node scripts/gen-shared.mjs`」，不是让你手改生成文件。
