# 作弊框架（如何新增作弊条目）

> 操作手册见 [`../AGENTS.md`](../AGENTS.md)。

所有作弊都是**实时 hook**：注入脚本在游戏启动前捕获运行中的 Phaser 场景（`BattleScene`），再 hook 游戏方法或直接改内存数据；**注入后在游戏内即时生效**（无需像旧方案那样存档 + 读档）。⚠️ 更改开关/数值需要**重新进入游戏页**（或重启 App）才会重新注入。

## 架构

| 文件 | 职责 |
| --- | --- |
| `model/Cheats.ets` | 作弊注册表 `CHEATS: Cheat[]`，每条 `{ id, name, desc, group, script, param? }`。**新增作弊只改这里。** |
| `model/CheatState.ets` | 内存单例 + 持久化（`pokerogue_settings` 的 `cheatMaster` / `cheatEnabledIds` / `cheatParams`）+ `buildBootScript()`（注入 `__CHEAT_PARAMS__` + **场景捕获运行时** + 按启用清单拼接 script） |
| `LocalHttpServer.ets` | `/index.html` 注入 `<script src="/__cheats__.js">`；`/__cheats__.js` 请求时调 `buildBootScript()` 动态下发（`no-cache`） |
| `pages/Settings.ets` | 「作弊模式」总开关（开启时弹账号风险 + 兼容性风险确认框） |
| `pages/Cheats.ets` | 条目列表页（按 `CHEAT_GROUPS` 分组；每条一个 Switch；声明 `param` 的额外显示数值 Slider），从首页就绪页「作弊设置」进入 |
| `pages/Index.ets` | 就绪页显示「作弊设置」入口（仅总开关开启时） |

## 场景捕获运行时（`buildBootScript` 注入）

在 IIFE 顶部注入共享引导，提供：

- `__onScene(cb)`：BattleScene 就绪时回调（未就绪则排队）。
- `__onPhase(name, cb)`：包装 `scene.phaseManager.create`，当创建名为 `name` 的 phase 时回调其实例（可在其 `start()` 前 hook 原型）。用于 hook `AttemptCapturePhase`（捕获）、`SelectModifierPhase`（奖励）等。
- 捕获手段（任一成功即可）：
  1. hook `Array.prototype.push`（Phaser `SceneManager` 加场景时触发），再轮询 `scene.sys.game`；
  2. 用 `Object.defineProperty(window, "Phaser", {set})` 在 `window.Phaser` 赋值时**包装 `Phaser.Game` 构造函数**，`new Phaser.Game()` 时拿到实例。
- 用「方法名鸭子类型」判定 BattleScene：有 `addMoney` / `getPlayerParty` / `ui` / `phaseManager`。
- 捕获后 `window.__cheatScene` 暴露实例（便于 DevTools 调试），并卸载 push 钩子。
- 日志（`ARKWEB-CONSOLE`）：`CHEAT phaser Game wrapped` / `CHEAT game constructed` / `CHEAT watch scenes=N` / `CHEAT scene captured` / `CHEAT phase hook installed`。

## 现有条目与 hook 点（参考）

| 条目 | hook 点 |
| --- | --- |
| 糖果倍率 | `GameData.addStarterCandy` |
| 经验倍率 | `Pokemon.addExp` |
| 金币倍率 | `BattleScene.addMoney` |
| 幸运值拉满 | 直接改 `pokemon.luck`（+ `addPlayerPokemon` 补新成员） |
| 100% 捕获率 | `AttemptCapturePhase.failCatch`（改为 `this.catch()`） |
| 免费抽蛋 | 蛋池 handler 的 `consumeVouchers`（置空）+ 保底 `voucherCounts` |
| 强制奖励稀有度 | `SelectModifierPhase.getModifierTypeOptions`（强制 `this.modifierTiers`） |

## 添加一条作弊（步骤）

1. 在 `model/Cheats.ets` 的 `CHEATS` 数组追加一项：

   ```ts
   {
     id: 'money',             // 唯一且稳定；持久化用它，发布后不要改
     name: '金币获取倍率',
     desc: '说明文字（显示在作弊页）',
     group: 'multiplier',     // 分组：'multiplier'=倍率修改 / 'other'=其他
     // 用 __onScene 拿实时 scene；用 __CHEAT_PARAMS__ 读数值参数
     script: `__onScene(function(scene){
       var N = Number(__CHEAT_PARAMS__.money || 10);
       var proto = Object.getPrototypeOf(scene);
       if (proto && typeof proto.addMoney === "function" && !proto.__cheatMoney) {
         var orig = proto.addMoney;
         proto.addMoney = function(amount){ return orig.call(this, amount * N); };
         proto.__cheatMoney = true;
       }
     });`,
     // 可选：数值型参数（作弊页自动出现 Slider）
     param: { id: 'money', label: '倍率', min: 1, max: 100, default: 10, step: 1, unit: 'x' }
   }
   ```

2. **不需要改任何 UI/状态代码**：作弊页自动遍历 `CHEATS` 渲染，开关与数值参数自动持久化。
3. 验证：`hvigorw assembleHap` → 安装 → 设置开「作弊模式」→ 首页「作弊设置」打开该条 → 进入游戏 → 抓 `ARKWEB-CONSOLE` 日志确认 hook 命中。

## script 运行环境与约定

- **执行时机**：`index.html` 解析到 `<head>` 后、游戏自身脚本之前（加载时注入）。
- **数值参数**：条目声明 `param` 后，`buildBootScript()` 会在 IIFE 顶部注入 `var __CHEAT_PARAMS__={ <paramId>: <value>, ... };`，脚本内用 `__CHEAT_PARAMS__.xxx` 读取（值已按 `min/max` 夹取）。
- **实时 hook 约定**：hook 前先判 `typeof proto.x === "function"` 且用标记位（如 `proto.__cheatMoney`）防重复；hook 时保留 `orig` 并 `orig.call(this, ...)`，保证 `this` 正确。
- 多条按 `CHEATS` 顺序拼接，整体包在一个 IIFE 内；**每条单独 try/catch**，单条报错不影响其它（错误以 `CHEAT err <id>` 打到 `ARKWEB-CONSOLE`）。
- 总开关关闭或无启用条目时，`/__cheats__.js` 返回空内容（不报错）。
- **生效方式**：改配置后需**重新进入游戏页**（返回首页再进，或重启 App）才会重新加载并注入；已进入的对局不会热更新脚本。
- **调试**：用 [`debugging.md`](./debugging.md) 的 DevTools，`window.__cheatScene` 可直接访问实时场景；`ARKWEB-CONSOLE` 看注入日志。

## 注意

- `script` 是字符串，注意转义（外层单引号则内部用双引号，或用反引号）；它是纯 JS，不受 ArkTS 类型限制。
- ⚠️ 作弊会污染存档，官方有检测机制（可能被标记/封禁），仅离线使用，**勿导入在线版**。
- **实时依赖游戏内部方法名**：本 App 会从 GitHub Release 自动更新 `game.zip`，游戏升级后方法名可能变化 → 作弊可能失效。新增条目时优先选稳定的公开方法名，并注意用鸭子类型（方法名）而非类名识别。
- 需要「直接改数据」而非「放大增量」时（如幸运值、扭蛋券），直接改 `scene`/`scene.gameData` 上的内存对象即可；要「持续维持」则 hook 相应入口（如 `addPlayerPokemon`、`consumeVouchers`）。phase 相关的 hook 用 `__onPhase(phaseName, cb)`。
