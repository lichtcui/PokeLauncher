#!/usr/bin/env node
/**
 * 跨端共享代码生成器（`shared/**` → 两端源码）。
 *
 * 为什么是 codegen 而不是「引用」：鸿蒙是 ArkTS、安卓是 Kotlin，**没有共同运行时**
 * （KMP 不支持 OHOS target），因此两端之间唯一能共享的就是**字符串与数据**。
 * `shared/` 里放唯一真相，本脚本把它生成成两边的真实源码。
 *
 * 用法：
 *   node scripts/gen-shared.mjs           # 生成（改完 shared/** 必须跑）
 *   node scripts/gen-shared.mjs --check   # 只校验：生成文件是否过期 + 两端常量/注入链是否分叉
 *
 * 生成物（**不要手改**）：
 *   entry/src/main/ets/model/Cheats.ets        （鸿蒙）参数调整注册表
 *   entry/src/main/ets/common/SharedJs.ets     （鸿蒙）共享 JS 常量
 *   android/.../cheat/Cheats.kt                （安卓）参数调整注册表
 *   android/.../core/SharedJs.kt               （安卓）共享 JS 常量
 *
 * 另有两份纯逻辑常量不生成、只对账（它们各自还有平台独有内容，见 `checkConstParity`）：
 *   entry/src/main/ets/common/Const.ets  ↔  android/.../core/Const.kt
 */
import { readFileSync, writeFileSync, existsSync, readdirSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const CHECK = process.argv.includes('--check');

const P = {
  registry: 'shared/cheats.json',
  sceneCore: 'shared/js/scene-runtime.core.js',
  saveGuard: 'shared/js/save-guard.js',
  canvasPatch: 'shared/js/canvas-patch.js',
  cheatJsDir: 'shared/js/cheats',
  etsCheats: 'entry/src/main/ets/model/Cheats.ets',
  etsSharedJs: 'entry/src/main/ets/common/SharedJs.ets',
  etsCheatState: 'entry/src/main/ets/model/CheatState.ets',
  etsSaveGuard: 'entry/src/main/ets/model/SaveGuard.ets',
  etsHttpServer: 'entry/src/main/ets/model/LocalHttpServer.ets',
  etsConst: 'entry/src/main/ets/common/Const.ets',
  ktCheats: 'android/app/src/main/java/com/lichtcui/pokelauncher/cheat/Cheats.kt',
  ktSharedJs: 'android/app/src/main/java/com/lichtcui/pokelauncher/core/SharedJs.kt',
  ktCheatScripts: 'android/app/src/main/java/com/lichtcui/pokelauncher/cheat/CheatScripts.kt',
  ktSaveGuard: 'android/app/src/main/java/com/lichtcui/pokelauncher/web/SaveGuard.kt',
  ktInjectScripts: 'android/app/src/main/java/com/lichtcui/pokelauncher/server/InjectScripts.kt',
  ktConst: 'android/app/src/main/java/com/lichtcui/pokelauncher/core/Const.kt',
};

const problems = [];
const fail = (msg) => problems.push(msg);
const read = (rel) => readFileSync(join(ROOT, rel), 'utf8');

/** 去掉末尾空行 / 右侧空白（两端 raw string 的缩进交给各自的 trimIndent 处理） */
function cleanJs(text) {
  const lines = text.split('\n').map((l) => l.replace(/\s+$/, ''));
  while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
  while (lines.length && lines[0].trim() === '') lines.shift();
  return lines.join('\n');
}

// ------------------------------------------------------------------ 校验 shared/**

/**
 * 共享 JS 必须能安全地嵌进两种宿主：
 * - Kotlin 的 `"""` raw string：`$` 会被当模板插值，`"""` 会提前结束字面量；
 * - ArkTS 的模板串：反引号与 `${` 会提前结束 / 变成插值。
 * 这些都是**静默**的坑（编译不过还算好的，插值成空串才是灾难），所以在这里硬拦。
 */
function assertJsEmbeddable(name, js) {
  for (const [bad, why] of [
    ['$', 'Kotlin raw string 会把它当模板插值'],
    ['"""', '会提前结束 Kotlin 的 """ 字面量'],
    ['`', '会提前结束 ArkTS 的模板串'],
    ['${', 'ArkTS 模板串会变成插值'],
  ]) {
    if (js.includes(bad)) fail(`${name}: JS 里出现 ${JSON.stringify(bad)} —— ${why}`);
  }
}

function assertRegistry(r) {
  if (!Array.isArray(r.groups) || r.groups.length === 0) fail('cheats.json: groups 为空');
  if (!Array.isArray(r.cheats) || r.cheats.length === 0) fail('cheats.json: cheats 为空');
  const groupIds = new Set(r.groups.map((g) => g.id));
  if (groupIds.size !== r.groups.length) fail('cheats.json: group id 重复');
  for (const g of r.groups) {
    if (!g.title || !g.subtitle) fail(`cheats.json: 分组 ${g.id} 缺 title/subtitle`);
  }
  const ids = new Set();
  const paramIds = new Set();
  for (const c of r.cheats) {
    if (!c.id) fail('cheats.json: 有 cheats 缺 id');
    if (ids.has(c.id)) fail(`cheats.json: cheat id 重复: ${c.id}`);
    ids.add(c.id);
    if (!c.name || !c.desc) fail(`cheats.json: ${c.id} 缺 name/desc`);
    if (!groupIds.has(c.group)) fail(`cheats.json: ${c.id} 的分组 ${c.group} 未在 groups 中`);
    if (!c.script) fail(`cheats.json: ${c.id} 缺 script`);
    if (c.param) {
      const p = c.param;
      if (paramIds.has(p.id)) fail(`cheats.json: param id 重复: ${p.id}`);
      paramIds.add(p.id);
      if (!p.label) fail(`cheats.json: ${c.id} param 缺 label`);
      if (!(p.min < p.max)) fail(`cheats.json: ${c.id} param min<max 不成立`);
      if (!(p.default >= p.min && p.default <= p.max)) fail(`cheats.json: ${c.id} param default 越界`);
      if (p.step !== undefined && !(p.step > 0)) fail(`cheats.json: ${c.id} param step 必须 > 0`);
      if (p.labels && p.labels.length !== Math.floor(p.max - p.min) + 1) {
        fail(`cheats.json: ${c.id} param labels 个数(${p.labels.length}) 与档位数(${Math.floor(p.max - p.min) + 1}) 不一致`);
      }
    }
  }
}

/** 注册表 + 脚本的结构约束（与 CheatsTest.kt 同源，但这里在生成期就报错） */
function assertScriptContracts(cheats) {
  for (const c of cheats) {
    const js = c.__js;
    if (!js.includes('__onScene(') && !js.includes('__onPhase(')) {
      fail(`${c.id}: 脚本必须通过 __onScene/__onPhase 注册，否则注入后不会生效`);
    }
    if (c.param && !js.includes('__CHEAT_PARAMS__.' + c.param.id)) {
      fail(`${c.id}: 声明了 param 却没读 __CHEAT_PARAMS__.${c.param.id}（会是「假旋钮」）`);
    }
  }
  // 脚本目录里不留孤儿文件（删条目时最容易忘）
  const files = readdirSync(join(ROOT, P.cheatJsDir))
    .filter((f) => f.endsWith('.js'))
    .map((f) => 'cheats/' + f)
    .sort();
  const used = cheats.map((c) => c.script).sort();
  for (const f of files) if (!used.includes(f)) fail(`${P.cheatJsDir}/${f} 没有被 cheats.json 引用（孤儿文件）`);
}

// ------------------------------------------------------------------ 数字 / 字面量

const numLit = (n) => (Number.isInteger(n) ? `${n}.0` : `${n}`);
const asStr = (s) => JSON.stringify(s);

// ------------------------------------------------------------------ 生成：鸿蒙注册表

function genEtsCheats(reg) {
  const groups = reg.groups
    .map((g) => `  {\n    id: '${g.id}',\n    title: ${asStr(g.title)},\n    subtitle: ${asStr(g.subtitle)}\n  }`)
    .join(',\n');
  const cheats = reg.cheats
    .map((c) => {
      const lines = [`    id: '${c.id}',`, `    name: ${asStr(c.name)},`, `    desc: ${asStr(c.desc)},`, `    group: '${c.group}',`];
      if (c.param) {
        const p = c.param;
        lines.push('    param: {');
        lines.push(`      id: ${asStr(p.id)},`);
        lines.push(`      label: ${asStr(p.label)},`);
        lines.push(`      min: ${p.min},`);
        lines.push(`      max: ${p.max},`);
        lines.push(`      default: ${p.default},`);
        if (p.step !== undefined) lines.push(`      step: ${p.step},`);
        if (p.unit !== undefined) lines.push(`      unit: ${asStr(p.unit)},`);
        if (p.labels) lines.push(`      labels: [${p.labels.map(asStr).join(', ')}]`);
        const last = lines.pop().replace(/,$/, '');
        lines.push(last);
        lines.push('    },');
      }
      lines.push(`    script: \`${c.__js}\``);
      return `  {\n${lines.join('\n')}\n  }`;
    })
    .join(',\n');

  return `/**
 * 参数调整（代码里沿用 cheat 命名）注册表。
 *
 * ⚠️ **本文件由 \`scripts/gen-shared.mjs\` 自动生成，不要手改**。
 * 唯一真相在仓库根 \`shared/cheats.json\` + \`shared/js/cheats/<id>.js\`：
 * 改完跑 \`node scripts/gen-shared.mjs\`，两端（鸿蒙 / 安卓）一起更新。
 *
 * 所有条目均为「实时调整」：脚本在游戏启动前捕获运行中的 Phaser 场景（BattleScene），
 * 然后 hook 游戏方法或直接改内存数据，开启后即时生效、无需重启。
 * 数值型条目可声明 param，脚本里通过 \`__CHEAT_PARAMS__.<param.id>\` 读取当前值。
 * 实时实现依赖游戏内部方法名，游戏更新后可能失效（详见 README「参数调整原理」）。
 *
 * ⚠️ 风险：官方对修改存档有检测机制，Hook 改数据可能被标记。开启后的存档不纯净，
 * 不建议再导入在线版。
 * ⚠️ UI 文案一律「参数调整」，「作弊」字样有应用商店审核风险。
 */

export interface CheatParam {
  id: string;
  label: string;
  min: number;
  max: number;
  default: number;
  step?: number;
  unit?: string;
  /** 可选：把数值映射为文字显示（如奖励档位 0=Common … 4=Master） */
  labels?: string[];
}

/** 条目分组：multiplier=倍率修改；other=其他（直接改数据） */
export type CheatGroup = ${reg.groups.map((g) => `'${g.id}'`).join(' | ')};

export interface CheatGroupInfo {
  id: CheatGroup;
  title: string;
  subtitle: string;
}

/** 分组顺序即参数调整页的展示顺序 */
export const CHEAT_GROUPS: CheatGroupInfo[] = [
${groups}
];

export interface Cheat {
  id: string;
  name: string;
  desc: string;
  group: CheatGroup;
  script: string;
  param?: CheatParam;
}

/** 条目顺序即参数调整页的展示顺序 */
export const CHEATS: Cheat[] = [
${cheats}
];
`;
}

// ------------------------------------------------------------------ 生成：安卓注册表

/** `multiplier` → `MULTIPLIER`（新的分组 id 也照这个规则，写在 shared/README.md 里） */
const enumName = (id) => id.replace(/[^a-zA-Z0-9]+/g, '_').toUpperCase();

function indentBlock(text, pad) {
  return text
    .split('\n')
    .map((l) => (l.trim() === '' ? '' : pad + l))
    .join('\n');
}

function genKtCheats(reg) {
  const enums = reg.groups
    .map((g) => `    ${enumName(g.id)}(${asStr(g.id)}, ${asStr(g.title)}, ${asStr(g.subtitle)})`)
    .join(',\n');
  const cheats = reg.cheats
    .map((c) => {
      const lines = [
        `        id = ${asStr(c.id)},`,
        `        name = ${asStr(c.name)},`,
        `        desc = ${asStr(c.desc)},`,
        `        group = CheatGroup.${enumName(c.group)},`,
      ];
      if (c.param) {
        const p = c.param;
        const parts = [
          `id = ${asStr(p.id)}`,
          `label = ${asStr(p.label)}`,
          `min = ${numLit(p.min)}`,
          `max = ${numLit(p.max)}`,
          `defaultValue = ${numLit(p.default)}`,
        ];
        if (p.step !== undefined) parts.push(`step = ${numLit(p.step)}`);
        if (p.unit !== undefined) parts.push(`unit = ${asStr(p.unit)}`);
        if (p.labels) parts.push(`labels = listOf(${p.labels.map(asStr).join(', ')})`);
        lines.push(`        param = CheatParam(${parts.join(', ')}),`);
      }
      lines.push(`        script = """\n${indentBlock(c.__js, '            ')}\n        """.trimIndent()`);
      return `    Cheat(\n${lines.join('\n')}\n    )`;
    })
    .join(',\n');

  return `package com.lichtcui.pokelauncher.cheat

/**
 * 「参数调整」条目注册表。
 *
 * ⚠️ **本文件由 \`scripts/gen-shared.mjs\` 自动生成，不要手改**。
 * 唯一真相在仓库根 \`shared/cheats.json\` + \`shared/js/cheats/<id>.js\`：
 * 改完跑 \`node scripts/gen-shared.mjs\`，两端（鸿蒙 / 安卓）一起更新。
 *
 * 所有条目均为「实时调整」：脚本在游戏启动前捕获运行中的 Phaser 场景（BattleScene），
 * 然后 hook 游戏方法或直接改内存数据，开启后即时生效、无需重启。
 * 数值型条目可声明 [CheatParam]，脚本里通过 \`__CHEAT_PARAMS__.<param.id>\` 读取当前值。
 *
 * ⚠️ 命名：UI 上叫「参数调整」（\`作弊\` 字样有应用商店审核风险），代码沿用 cheat。
 * ⚠️ 脚本由生成器保证不含 \`$\` / \`"""\`（Kotlin raw string 的两个坑），别手工加回来。
 * ⚠️ 滑块档位换算在 [sliderSteps]（手写文件），不在本生成文件里。
 */
data class CheatParam(
    val id: String,
    val label: String,
    val min: Double,
    val max: Double,
    /** Kotlin 关键字冲突，对应鸿蒙版的 \`default\` */
    val defaultValue: Double,
    val step: Double = 1.0,
    val unit: String = "",
    /** 可选：把数值映射为文字显示（如奖励档位 0=Common … 4=Master） */
    val labels: List<String> = emptyList()
)

/** 条目分组：multiplier=倍率修改；other=其他（直接改数据） */
enum class CheatGroup(val id: String, val title: String, val subtitle: String) {
${enums}
}

data class Cheat(
    val id: String,
    val name: String,
    val desc: String,
    val group: CheatGroup,
    val script: String,
    val param: CheatParam? = null
)

/** 分组顺序即参数调整页的展示顺序 */
val CHEAT_GROUPS: List<CheatGroup> = CheatGroup.entries.toList()

/** 条目顺序即参数调整页的展示顺序（也是注入时的脚本拼接顺序） */
val CHEATS: List<Cheat> = listOf(
${cheats}
)
`;
}

// ------------------------------------------------------------------ 生成：共享 JS 常量

const SHARED_JS_HEADER_ETS = `/**
 * 跨端共享的注入 JS 常量。
 *
 * ⚠️ **本文件由 \`scripts/gen-shared.mjs\` 自动生成，不要手改**：唯一真相在仓库根 \`shared/js/\`。
 * 改完跑 \`node scripts/gen-shared.mjs\`（安卓侧 \`core/SharedJs.kt\` 会同步更新）。
 *
 * 这里只放**两端必须逐字一致**的 JS；平台独有的行为（谁持有 window.Phaser 描述符、
 * <script> 怎么包装）留在各端自己那几行里。
 */
`;

function genEtsSharedJs(js) {
  return `${SHARED_JS_HEADER_ETS}
/**
 * 场景捕获运行时公共部分（\`/__cheats__.js\` 的底座）。
 * 只到 \`Array.prototype.push\` hook 为止——**Phaser 包装的尾部各端不同**：
 * 鸿蒙由参数调整脚本自己 \`defineProperty\`，Android 由 \`/__pk_boot.js\` 独占（避免后者覆盖前者）。
 */
export const SCENE_RUNTIME_CORE: string = \`${js.sceneCore}\`;

/** 存档守卫注入脚本（纯 JS，不含 <script> 包装；两端都用同一个 \`PokeSaveGuard\` 桥） */
export const SAVE_GUARD_JS: string = \`${js.saveGuard}\`;

/** canvas willReadFrequently 补丁（纯 JS 片段；Phaser 每个 Text 都调 getImageData，GPU 读回开销很大） */
export const CANVAS_PATCH_JS: string = \`${js.canvasPatch}\`;
`;
}

function genKtSharedJs(js) {
  const block = (doc, name, body) => `    /** ${doc} */
    val ${name}: String = """
${indentBlock(body, '        ')}
    """.trimIndent()`;

  return `package com.lichtcui.pokelauncher.core

/**
 * 跨端共享的注入 JS 常量。
 *
 * ⚠️ **本文件由 \`scripts/gen-shared.mjs\` 自动生成，不要手改**：唯一真相在仓库根 \`shared/js/\`。
 * 改完跑 \`node scripts/gen-shared.mjs\`（鸿蒙侧 \`common/SharedJs.ets\` 会同步更新）。
 *
 * 这里只放**两端必须逐字一致**的 JS；平台独有的行为（谁持有 window.Phaser 描述符、
 * <script> 怎么包装）留在各端自己那几行里。
 * raw string 里的 \`$\` 会被当模板插值，所以生成器保证这些 JS 不含 \`$\`。
 */
object SharedJs {

${block('场景捕获运行时公共部分（`/__cheats__.js` 的底座）。尾部（Phaser 包装）由各端自己追加。', 'SCENE_RUNTIME_CORE', js.sceneCore)}

${block('存档守卫注入脚本（纯 JS，不含 `<script>` 包装）。', 'SAVE_GUARD_JS', js.saveGuard)}

${block('canvas willReadFrequently 补丁（纯 JS 片段，内联进引导脚本）。', 'CANVAS_PATCH_JS', js.canvasPatch)}
}
`;
}

// ------------------------------------------------------------------ 对账：不生成但要同步的常量

function pick(text, re) {
  const m = text.match(re);
  return m ? m[1] : undefined;
}

function normExpr(s) {
  return s === undefined ? undefined : s.replace(/\s+/g, '').replace(/L$/, '');
}

/** `Const.ets` ↔ `Const.kt` 的共享常量（其余是各端独有，不比对） */
function checkConstParity() {
  const ets = read(P.etsConst);
  const kt = read(P.ktConst);
  const names = [
    'RELEASE_API',
    'API_MIRROR',
    'DEFAULT_ASSET',
    'GAME_DIR_NAME',
    'DEBUG_ASSET_URL',
    'APP_UPDATE_MANIFEST_URL',
    'APP_UPDATE_PAGE_URL',
    'PROJECT_GITHUB_URL',
  ];
  for (const n of names) {
    const a = pick(ets, new RegExp(`export const ${n}\\s*:\\s*string\\s*=\\s*\\n?\\s*'([^']*)'`));
    const b = pick(kt, new RegExp(`const val ${n}\\s*=\\s*\\n?\\s*"([^"]*)"`));
    if (a === undefined || b === undefined) fail(`常量对账：${n} 在某一端找不到（解析失败？）`);
    else if (a !== b) fail(`常量分叉：${n}\n    鸿蒙: ${a}\n    安卓: ${b}`);
  }

  const arrEts = pick(ets, /export const DOWNLOAD_MIRRORS: string\[\] = \[([\s\S]*?)\];/);
  const arrKt = pick(kt, /val DOWNLOAD_MIRRORS = listOf\(([\s\S]*?)\)/);
  // 只取引号内的值（两端分别是单/双引号，不取整体匹配，否则引号本身也会被当成差异）
  const listOf = (s, re) =>
    s === undefined ? undefined : [...s.matchAll(re)].map((m) => m[1]).join('|');
  const a1 = listOf(arrEts, /'([^']+)'/g);
  const a2 = listOf(arrKt, /"([^"]+)"/g);
  if (a1 === undefined || a2 === undefined) fail('常量对账：DOWNLOAD_MIRRORS 解析失败');
  else if (a1 !== a2) fail(`常量分叉：DOWNLOAD_MIRRORS\n    鸿蒙: ${a1}\n    安卓: ${a2}`);

  const flagEts = pick(ets, /export const FORCE_WILL_READ_FREQUENTLY: boolean = (true|false)/);
  const flagKt = pick(kt, /const val FORCE_WILL_READ_FREQUENTLY = (true|false)/);
  if (flagEts !== flagKt) fail(`常量分叉：FORCE_WILL_READ_FREQUENTLY 鸿蒙=${flagEts} 安卓=${flagKt}`);

  const ttlEts = normExpr(pick(ets, /export const APP_UPDATE_CHECK_INTERVAL_MS: number = ([^;]+);/));
  const ttlKt = normExpr(pick(kt, /const val APP_UPDATE_CHECK_INTERVAL_MS = ([^\n]+)/));
  if (ttlEts !== ttlKt) fail(`常量分叉：APP_UPDATE_CHECK_INTERVAL_MS 鸿蒙=${ttlEts} 安卓=${ttlKt}`);

  // 端口：鸿蒙写在 LocalHttpServer（有意不复用 Const.ets 的服务端常量区）
  const portEts = pick(read(P.etsHttpServer), /const PORT: number = (\d+);/);
  const portKt = pick(kt, /const val GAME_PORT = (\d+)/);
  if (portEts === undefined || portKt === undefined) fail('常量对账：游戏端口解析失败');
  else if (portEts !== portKt) fail(`常量分叉：游戏端口 鸿蒙=${portEts} 安卓=${portKt}（改端口会丢存档！）`);
}

/**
 * 防「重新内联」：共享 JS 一旦被某端重新抄回自己的文件，这里立刻报错。
 *
 * 这是本次去重的**长期护栏**——codegen 只保证生成物正确，不保证没人把旧代码粘回去；
 * 而重新内联的后果是「改 shared/ 时那一端不跟着变」，也就是我们要消灭的那个 bug。
 */
function checkNoInline() {
  const rules = [
    [P.etsCheatState, 'SCENE_RUNTIME_CORE', ['__isBattle(', 'Array.prototype.push=__hp'], '场景捕获运行时'],
    [P.ktCheatScripts, 'SCENE_RUNTIME_CORE', ['__isBattle(', 'Array.prototype.push=__hp'], '场景捕获运行时'],
    [P.etsSaveGuard, 'SAVE_GUARD_JS', ['__sgInstalled'], '存档守卫 JS'],
    [P.ktSaveGuard, 'SAVE_GUARD_JS', ['__sgInstalled'], '存档守卫 JS'],
    [P.etsHttpServer, 'CANVAS_PATCH_JS', ['HTMLCanvasElement.prototype.getContext=function'], 'canvas 补丁'],
    [P.ktInjectScripts, 'CANVAS_PATCH_JS', ['HTMLCanvasElement.prototype.getContext=function'], 'canvas 补丁'],
  ];
  for (const [file, marker, inlined, what] of rules) {
    if (!existsSync(join(ROOT, file))) {
      fail(`防内联检查：找不到 ${file}`);
      continue;
    }
    const text = read(file);
    if (!text.includes(marker)) fail(`防内联检查：${file} 没有引用 \`${marker}\`（${what} 应当来自 shared/）`);
    for (const frag of inlined) {
      if (text.includes(frag)) fail(`防内联检查：${file} 里重新内联了${what}（命中 \`${frag}\`），请改用 SharedJs`);
    }
  }
}

// ------------------------------------------------------------------ 主流程

const registry = JSON.parse(read(P.registry));
assertRegistry(registry);
for (const c of registry.cheats) {
  const rel = join(P.cheatJsDir, c.script.replace(/^cheats\//, ''));
  if (!existsSync(join(ROOT, rel))) {
    fail(`cheats.json: ${c.id} 引用的脚本不存在: ${rel}`);
    c.__js = '';
    continue;
  }
  c.__js = cleanJs(readFileSync(join(ROOT, rel), 'utf8'));
  assertJsEmbeddable(`${c.id} 脚本`, c.__js);
}
const sharedJs = {
  sceneCore: cleanJs(read(P.sceneCore)),
  saveGuard: cleanJs(read(P.saveGuard)),
  canvasPatch: cleanJs(read(P.canvasPatch)),
};
for (const [k, v] of Object.entries(sharedJs)) assertJsEmbeddable(`${k}.js`, v);
assertScriptContracts(registry.cheats.filter((c) => c.__js !== undefined));

const outputs = [
  [P.etsCheats, genEtsCheats(registry)],
  [P.etsSharedJs, genEtsSharedJs(sharedJs)],
  [P.ktCheats, genKtCheats(registry)],
  [P.ktSharedJs, genKtSharedJs(sharedJs)],
];

checkConstParity();
checkNoInline();

let stale = 0;
for (const [file, content] of outputs) {
  const abs = join(ROOT, file);
  const current = existsSync(abs) ? readFileSync(abs, 'utf8') : null;
  const same = current === content;
  if (CHECK) {
    if (!same) {
      stale++;
      console.error(`✗ 生成文件已过期：${file}`);
    }
  } else if (!same) {
    writeFileSync(abs, content);
    console.log(`${current === null ? '生成' : '更新'} ${file}`);
  }
}

if (problems.length) {
  console.error(`\n✗ shared/ 校验失败（${problems.length} 项）：`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}
if (CHECK) {
  if (stale) {
    console.error(`\n✗ ${stale} 个生成文件与 shared/ 不一致，跑一下：node scripts/gen-shared.mjs`);
    process.exit(1);
  }
  console.log('✓ shared/ 校验通过：生成文件最新、两端常量与注入链一致');
} else {
  console.log('✓ shared/ 已同步（两端源码已生成）');
}
