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
 *   entry/src/main/ets/model/LegalText.ets     （鸿蒙）法律页正文
 *   entry/src/main/ets/common/Mime.ets         （鸿蒙）扩展名 → MIME
 *   android/.../cheat/Cheats.kt                （安卓）参数调整注册表
 *   android/.../core/SharedJs.kt               （安卓）共享 JS 常量
 *   android/.../ui/LegalText.kt                （安卓）法律页正文
 *   android/.../core/Mime.kt                   （安卓）扩展名 → MIME
 *
 * 另有几份纯逻辑常量不生成、只对账（它们各自还有平台独有内容，见 `checkConstParity`）：
 *   entry/src/main/ets/common/Const.ets  ↔  android/.../core/Const.kt
 *   测速参数、存储预检系数、USER_AGENT（有意不同，白名单锁死）
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
  legal: 'shared/legal.json',
  mime: 'shared/mime.json',
  etsLegalText: 'entry/src/main/ets/model/LegalText.ets',
  etsLegalPage: 'entry/src/main/ets/pages/Legal.ets',
  etsMime: 'entry/src/main/ets/common/Mime.ets',
  ktLegalText: 'android/app/src/main/java/com/lichtcui/pokelauncher/ui/LegalText.kt',
  ktMime: 'android/app/src/main/java/com/lichtcui/pokelauncher/core/Mime.kt',
  etsGameRepo: 'entry/src/main/ets/model/GameRepository.ets',
  ktGameRepo: 'android/app/src/main/java/com/lichtcui/pokelauncher/install/GameRepository.kt',
  etsSession: 'entry/src/main/ets/model/LauncherSession.ets',
  ktStorage: 'android/app/src/main/java/com/lichtcui/pokelauncher/install/Storage.kt',
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

  // 测速参数：两端各写一份（不在 Const 里），值必须一致，否则「测速选源」行为分叉
  const numPairs = [
    ['PROBE_BYTES', '测速读取字节数'],
    ['PROBE_TIMEOUT_MS', '单源测速超时'],
    ['RANK_TTL_MS', '测速排序缓存 TTL'],
  ];
  for (const [n, what] of numPairs) {
    const a = normExpr(pick(read(P.etsGameRepo), new RegExp(`const ${n}: number = ([^;]+);`)));
    const b = normExpr(pick(read(P.ktGameRepo), new RegExp(`const val ${n} = ([^\\n]+)`)));
    if (a === undefined || b === undefined) fail(`常量对账：${n}（${what}）在某一端找不到（解析失败？）`);
    else if (a !== b) fail(`常量分叉：${n}（${what}）鸿蒙=${a} 安卓=${b}`);
  }

  // 存储预检系数：鸿蒙 `size * (updating ? 3.2 : 2.4)` ↔ 安卓 Storage.FACTOR_UPDATE / FACTOR_FRESH
  // （安卓曾经恒用 3.2，导致同设备上比鸿蒙更早报「空间不足」——这个对账就是防它再漂回去）
  const upd = read(P.etsSession).match(/updating \? ([\d.]+) : ([\d.]+)\);/);
  const factorUpdate = pick(read(P.ktStorage), /const val FACTOR_UPDATE = ([\d.]+)/);
  const factorFresh = pick(read(P.ktStorage), /const val FACTOR_FRESH = ([\d.]+)/);
  if (!upd) fail('常量对账：解析 LauncherSession.requiredStorage 的 updating 分支失败');
  else {
    if (upd[1] !== factorUpdate) fail(`常量分叉：更新时的存储系数 鸿蒙=${upd[1]} 安卓=${factorUpdate}`);
    if (upd[2] !== factorFresh) fail(`常量分叉：首次安装的存储系数 鸿蒙=${upd[2]} 安卓=${factorFresh}`);
  }

  // USER_AGENT **有意不同**（两端各自标识自己）：这里显式锁死期望值，
  // 免得它悄悄被改成同一个、或改成别的串（GitHub API 会 403）
  const uaEts = pick(read(P.etsConst), /export const USER_AGENT: string = '([^']*)'/);
  const uaKt = pick(kt, /const val USER_AGENT = "([^"]*)"/);
  if (uaEts !== 'PokeLauncher-Harmony') fail(`USER_AGENT 变了：鸿蒙=${uaEts}（期望 PokeLauncher-Harmony）`);
  if (uaKt !== 'PokeLauncher-Android') fail(`USER_AGENT 变了：安卓=${uaKt}（期望 PokeLauncher-Android）`);
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
    // 法律页正文：页面只负责渲染，正文必须来自生成物
    [P.etsLegalPage, 'LEGAL_SECTIONS', ['本项目是第三方、非官方的'], '法律页正文'],
  ];
  for (const [file, marker, inlined, what] of rules) {
    if (!existsSync(join(ROOT, file))) {
      fail(`防内联检查：找不到 ${file}`);
      continue;
    }
    const text = read(file);
    if (!text.includes(marker)) fail(`防内联检查：${file} 没有引用 \`${marker}\`（${what} 应当来自 shared/）`);
    for (const frag of inlined) {
      if (text.includes(frag)) fail(`防内联检查：${file} 里重新内联了${what}（命中 \`${frag}\`），请改用 shared/ 生成物`);
    }
  }
}

// ------------------------------------------------------------------ 校验 shared/legal.json

const PLATFORMS = ['harmony', 'android'];

/**
 * 法律页正文的占位符契约：
 * - 两端必须定义**同一组**占位符 key（少一个会在渲染时报错，多一个说明正文删了没同步）；
 * - 每个 key 必须真的被正文用到（防止改完正文忘了删占位符，看着像还有差异）；
 * - 段落里不允许出现未命名/未闭合的花括号（`{` 写错会静默输出给用户）。
 */
function assertLegal(legal) {
  const tables = legal.placeholders ?? {};
  const platforms = Object.keys(tables);
  if (platforms.length !== PLATFORMS.length || PLATFORMS.some((p) => !platforms.includes(p))) {
    fail(`legal.json: placeholders 必须恰好是 ${PLATFORMS.join(' / ')}（当前 ${platforms.join(' / ') || '空'}）`);
    return null;
  }
  const keySets = PLATFORMS.map((p) => Object.keys(tables[p]).sort());
  const first = keySets[0].join('|');
  if (keySets.some((k) => k.join('|') !== first)) {
    fail(`legal.json: 两端占位符 key 不一致\n    鸿蒙: ${keySets[0].join(', ')}\n    安卓: ${keySets[1].join(', ')}`);
  }

  const used = new Set();
  const ids = new Set();
  const sections = legal.sections ?? [];
  if (sections.length === 0) fail('legal.json: sections 为空');
  for (const s of sections) {
    if (!s.id) fail('legal.json: 章节缺 id');
    else if (ids.has(s.id)) fail(`legal.json: 章节 id 重复：${s.id}`);
    else ids.add(s.id);
    if (!s.title || !s.title.trim()) fail(`legal.json: ${s.id} 缺 title`);
    if (!Array.isArray(s.body) || s.body.length === 0) fail(`legal.json: ${s.id} 的 body 为空`);
    for (const t of s.body ?? []) {
      if (!t.trim()) fail(`legal.json: ${s.id} 有空段落`);
      for (const m of t.matchAll(/\{(\w+)\}/g)) used.add(m[1]);
      if (/[{}]/.test(t.replace(/\{\w+\}/g, ''))) {
        fail(`legal.json: ${s.id} 段落里有未命名/未闭合的花括号：${t.slice(0, 40)}`);
      }
    }
  }
  for (const k of used) {
    if (!(k in tables[PLATFORMS[0]])) fail(`legal.json: 正文用了占位符 {${k}}，但 placeholders 里没定义`);
  }
  for (const k of keySets[0]) {
    if (!used.has(k)) fail(`legal.json: placeholders 里的 {${k}} 没有任何段落用到（改正文时把它删掉）`);
  }
  return tables;
}

/** 按平台渲染占位符；渲染后残留花括号即报错（不允许把原始占位符发到用户眼前） */
function renderLegal(legal, platform) {
  const table = legal.placeholders[platform];
  const render = (text) => {
    const out = text.replace(/\{(\w+)\}/g, (_, k) => {
      if (!(k in table)) fail(`legal.json: 平台 ${platform} 缺占位符 {${k}}`);
      return table[k];
    });
    if (/[{}]/.test(out)) fail(`legal.json: 平台 ${platform} 渲染后残留花括号：${out.slice(0, 40)}`);
    return out;
  };
  return {
    banner: render(legal.banner),
    updatedAt: render(legal.updatedAt),
    sections: legal.sections.map((s) => ({
      id: s.id,
      title: render(s.title),
      body: s.body.map(render),
    })),
  };
}

// ------------------------------------------------------------------ 生成：法律页正文

/**
 * ArkTS 字面量：统一用单引号（与仓库原有 .ets 风格一致，diff 更小）。
 * 不用 JSON.stringify——它会输出双引号，把一个「换内容」的 diff 变成整个文件的 diff。
 */
const etsStr = (s) => `'${s.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`;

/** Kotlin 字面量：`$` 会被当模板插值，必须转义（ArkTS 侧用 etsStr，不用管） */
const ktStr = (s) => `"${s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\$/g, '\\$')}"`;

const LEGAL_DOC_HEADER = `/**
 * 法律与免责声明页的正文（纯数据，无 UI 依赖，可单测）。
 *
 * ⚠️ **本文件由 \`scripts/gen-shared.mjs\` 自动生成，不要手改**：唯一真相在仓库根 \`shared/legal.json\`。
 * 改文案跑 \`node scripts/gen-shared.mjs\`，两侧（鸿蒙 / 安卓）一起更新。
 *
 * 两端唯一的措辞差异只允许放在 legal.json 的 \`placeholders\` 里（如平台名、技术栈），
 * 正文段落一律共用——这样改一次措辞不会漏掉另一边。
 * ⚠️ 全部文案不得出现「作弊」字样（应用商店审核风险），统一叫「参数调整」。
 */`;

function genEtsLegalText(r) {
  const sections = r.sections
    .map(
      (s) =>
        `  {\n    id: ${etsStr(s.id)},\n    title: ${etsStr(s.title)},\n    body: [\n${s.body
          .map((b) => `      ${etsStr(b)}`)
          .join(',\n')}\n    ]\n  }`
    )
    .join(',\n');

  return `${LEGAL_DOC_HEADER}\n\nexport interface LegalSection {\n  id: string;\n  title: string;\n  body: string[];\n}\n\n/** 页面顶部常驻提示条 */\nexport const LEGAL_BANNER: string = ${etsStr(r.banner)};\n\n/** 页脚日期 */\nexport const LEGAL_UPDATED_AT: string = ${etsStr(r.updatedAt)};\n\n/** 章节顺序即页面展示顺序 */\nexport const LEGAL_SECTIONS: LegalSection[] = [\n${sections}\n];\n`;
}

function genKtLegalText(r) {
  const sections = r.sections
    .map(
      (s) =>
        `    LegalSection(\n        id = ${ktStr(s.id)},\n        title = ${ktStr(s.title)},\n        body = listOf(\n${s.body
          .map((b) => `            ${ktStr(b)}`)
          .join(',\n')}\n        )\n    )`
    )
    .join(',\n');

  return `package com.lichtcui.pokelauncher.ui

${LEGAL_DOC_HEADER}

/** 一条免责章节 */
data class LegalSection(val id: String, val title: String, val body: List<String>)

/** 页面顶部常驻提示条 */
const val LEGAL_BANNER: String = ${ktStr(r.banner)}

/** 页脚日期 */
const val LEGAL_UPDATED_AT: String = ${ktStr(r.updatedAt)}

/** 章节顺序即页面展示顺序（\`LegalTextTest\` 锁住 id 集合与顺序） */
val LEGAL_SECTIONS: List<LegalSection> = listOf(
${sections}
)
`;
}

// ------------------------------------------------------------------ 校验 / 生成：MIME 表

/**
 * 本地 HTTP 服务的 Content-Type 表。
 * Chromium 对 `<script type="module">` 做**严格 MIME 校验**：`.js` 回错 MIME 直接白屏，
 * 所以 `js` 这一条是硬断言，不是注释里的提醒。
 */
function assertMime(mime) {
  const map = mime.map ?? {};
  const keys = Object.keys(map);
  if (keys.length === 0) fail('mime.json: map 为空');
  for (const [k, v] of Object.entries(map)) {
    if (k !== k.toLowerCase()) fail(`mime.json: 扩展名必须小写：${k}`);
    if (!/^[a-z0-9]+$/.test(k)) fail(`mime.json: 扩展名字符可疑（不要带点）：${k}`);
    if (!/^[\w.+-]+\/[\w.+-]+$/.test(v)) fail(`mime.json: ${k} 的值不像 MIME：${v}`);
  }
  if (map.js !== 'text/javascript') fail(`mime.json: js 必须是 text/javascript（当前 ${map.js}）—— 回错会白屏`);
  for (const k of ['html', 'css', 'json', 'wasm', 'webmanifest']) {
    if (!map[k]) fail(`mime.json: 缺少关键扩展名 ${k}（游戏资源里确实用到）`);
  }
  if (!mime.fallback) fail('mime.json: 缺 fallback');
  return map;
}

const MIME_DOC_HEADER_ETS = `/**
 * 扩展名 → MIME 映射（本地 HTTP 服务的 Content-Type）。
 *
 * ⚠️ **本文件由 \`scripts/gen-shared.mjs\` 自动生成，不要手改**：唯一真相在仓库根 \`shared/mime.json\`。
 *
 * 关键：ArkWeb 基于 Chromium，对 <script type="module"> 有严格 MIME 校验，
 * 若 .js 返回错误的 MIME 会导致白屏，必须返回 text/javascript。
 */`;

function genEtsMime(map, fallback) {
  const entries = Object.entries(map)
    .map(([k, v]) => `  [${etsStr(k)}, ${etsStr(v)}]`)
    .join(',\n');
  return `${MIME_DOC_HEADER_ETS}\n\nconst MIME_MAP: Map<string, string> = new Map<string, string>([\n${entries}\n]);\n\n/** 无扩展名 / 未知扩展名的退化值 */\nconst MIME_FALLBACK: string = ${etsStr(fallback)};\n\nexport function mimeOf(path: string): string {\n  const dot: number = path.lastIndexOf('.');\n  if (dot < 0) {\n    return MIME_FALLBACK;\n  }\n  const ext: string = path.substring(dot + 1).toLowerCase();\n  const mime: string | undefined = MIME_MAP.get(ext);\n  return mime !== undefined ? mime : MIME_FALLBACK;\n}\n`;
}

function genKtMime(map, fallback) {
  const entries = Object.entries(map)
    .map(([k, v]) => `        ${ktStr(k)} to ${ktStr(v)}`)
    .join(',\n');
  return `package com.lichtcui.pokelauncher.core

/**
 * 扩展名 → MIME 映射（本地 HTTP 服务的 Content-Type）。
 *
 * ⚠️ **本文件由 \`scripts/gen-shared.mjs\` 自动生成，不要手改**：唯一真相在仓库根 \`shared/mime.json\`。
 *
 * 关键：WebView 基于 Chromium，对 \`<script type="module">\` 做严格 MIME 校验，
 * \`.js\` 返回错 MIME 会导致白屏，必须返回 \`text/javascript\`。
 */
object Mime {

    /** 无扩展名 / 未知扩展名的退化值 */
    const val FALLBACK = ${ktStr(fallback)}

    private val MAP = mapOf(
${entries}
    )

    fun mimeOf(path: String): String {
        val dot = path.lastIndexOf('.')
        if (dot < 0) return FALLBACK
        val ext = path.substring(dot + 1).lowercase()
        return MAP[ext] ?: FALLBACK
    }
}
`;
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

const legal = JSON.parse(read(P.legal));
assertLegal(legal);
const mime = JSON.parse(read(P.mime));
const mimeMap = assertMime(mime);

const outputs = [
  [P.etsCheats, genEtsCheats(registry)],
  [P.etsSharedJs, genEtsSharedJs(sharedJs)],
  [P.ktCheats, genKtCheats(registry)],
  [P.ktSharedJs, genKtSharedJs(sharedJs)],
  [P.etsLegalText, genEtsLegalText(renderLegal(legal, 'harmony'))],
  [P.ktLegalText, genKtLegalText(renderLegal(legal, 'android'))],
  [P.etsMime, genEtsMime(mimeMap, mime.fallback)],
  [P.ktMime, genKtMime(mimeMap, mime.fallback)],
];

checkConstParity();
checkNoInline();

// 校验没过就不写文件：免得把半成品生成物写进工作区，掩盖真正的错误
if (problems.length) {
  console.error(`\n✗ shared/ 校验失败（${problems.length} 项），生成物**未写入**：`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}

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

if (CHECK) {
  if (stale) {
    console.error(`\n✗ ${stale} 个生成文件与 shared/ 不一致，跑一下：node scripts/gen-shared.mjs`);
    process.exit(1);
  }
  console.log('✓ shared/ 校验通过：生成文件最新、两端常量与注入链一致');
} else {
  console.log('✓ shared/ 已同步（两端源码已生成）');
}
