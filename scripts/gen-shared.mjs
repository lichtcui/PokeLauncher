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
 *   entry/src/ohosTest/ets/test/Cases.gen.ets  （鸿蒙）跨端行为向量（测试数据）
 *   android/app/src/test/.../CasesGen.kt       （安卓）跨端行为向量（测试数据）
 *
 * 另有几份纯逻辑常量不生成、只对账（它们各自还有平台独有内容，见 `checkConstParity`）：
 *   entry/src/main/ets/common/Const.ets  ↔  android/.../core/Const.kt
 *   测速参数、存储预检系数、USER_AGENT（有意不同，白名单锁死）
 */
import { readFileSync, writeFileSync, existsSync, readdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
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
  casesPaths: 'shared/cases/paths.json',
  casesFormat: 'shared/cases/format.json',
  casesVersion: 'shared/cases/version.json',
  etsCases: 'entry/src/ohosTest/ets/test/Cases.gen.ets',
  ktCases: 'android/app/src/test/java/com/lichtcui/pokelauncher/CasesGen.kt',
  etsHttpTest: 'entry/src/ohosTest/ets/test/LocalHttpServer.test.ets',
  etsFormatTest: 'entry/src/ohosTest/ets/test/Format.test.ets',
  etsRepoTest: 'entry/src/ohosTest/ets/test/GameRepository.test.ets',
  ktPathsTest: 'android/app/src/test/java/com/lichtcui/pokelauncher/core/PathsTest.kt',
  ktFormatTest: 'android/app/src/test/java/com/lichtcui/pokelauncher/core/FormatTest.kt',
  ktVersionTest: 'android/app/src/test/java/com/lichtcui/pokelauncher/core/VersionTest.kt',
  ktMimeTest: 'android/app/src/test/java/com/lichtcui/pokelauncher/core/MimeTest.kt',
  ktProguard: 'android/app/proguard-rules.pro',
  etsGamePage: 'entry/src/main/ets/pages/GamePage.ets',
  ktPokeWebView: 'android/app/src/main/java/com/lichtcui/pokelauncher/web/PokeWebView.kt',
  etsAppUpdate: 'entry/src/main/ets/model/AppUpdate.ets',
  etsLegalText: 'entry/src/main/ets/model/LegalText.ets',
  etsLegalPage: 'entry/src/main/ets/pages/Legal.ets',
  etsMime: 'entry/src/main/ets/common/Mime.ets',
  ktLegalText: 'android/app/src/main/java/com/lichtcui/pokelauncher/ui/LegalText.kt',
  ktMime: 'android/app/src/main/java/com/lichtcui/pokelauncher/core/Mime.kt',
  etsGameRepo: 'entry/src/main/ets/model/GameRepository.ets',
  ktGameRepo: 'android/app/src/main/java/com/lichtcui/pokelauncher/install/GameRepository.kt',
  etsSession: 'entry/src/main/ets/model/LauncherSession.ets',
  ktStorage: 'android/app/src/main/java/com/lichtcui/pokelauncher/install/Storage.kt',
  etsPokeball: 'entry/src/main/ets/components/PokeballLoader.ets',
  ktHomeState: 'android/app/src/main/java/com/lichtcui/pokelauncher/ui/HomeState.kt',
  ktApkUpdater: 'android/app/src/main/java/com/lichtcui/pokelauncher/appupdate/ApkUpdater.kt',
  ktBackupRules: 'android/app/src/main/res/xml/backup_rules.xml',
  ktExtractionRules: 'android/app/src/main/res/xml/data_extraction_rules.xml',
  ktProviderPaths: 'android/app/src/main/res/xml/provider_paths.xml',
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

  // 精灵球动画节奏：两端各写一份，安卓那几行注释就写着「与鸿蒙版同值」——
  // 靠注释维系的一致性迟早会漂（改一端忘另一端 → 两个 App 手感不一样，谁也不会去量）
  const numLit = (v) => (v === undefined ? undefined : v.replace(/\s+/g, '').replace(/[LlFf]$/, ''));
  const animPairs = [
    ['SHAKE_INTERVAL_MS', '摇晃半周期（ms）'],
    ['SHAKE_ANGLE', '摇晃幅度（度）'],
    ['SHAKE_ANIM_MS', '单次摇晃的补间时长（ms）'],
    ['BURST_MS', '解压完成 → 就绪页的停留（ms）'],
  ];
  for (const [n, what] of animPairs) {
    const a = numLit(pick(read(P.etsPokeball), new RegExp(`const ${n}: number = ([^;]+);`)));
    const b = numLit(pick(read(P.ktHomeState), new RegExp(`const val ${n} = ([^\\n]+)`)));
    if (a === undefined || b === undefined) fail(`常量对账：动画常量 ${n}（${what}）在某一端找不到（解析失败？）`);
    else if (a !== b) fail(`常量分叉：动画常量 ${n}（${what}）鸿蒙=${a} 安卓=${b}`);
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

// ------------------------------------------------------------------ 校验 / 生成：跨端行为向量
/**
 * 行为向量（`shared/cases/*.json`）→ 两端测试数据。
 *
 * 为什么需要：这些纯函数（路径判定 / 展示格式化 / 版本比较）两端各写了一份实现，
 * 以前也各写了一份**期望值**——于是「一边改了四舍五入、另一边没改」这种分叉
 * 没人会发现（除非刚好有人同时看两个测试文件）。现在期望值只有一份。
 *
 * 生成的是**数据**不是断言：各端测试用自己语言的断言 API 遍历这些向量。
 * 另：`checkCaseUsage` 反向要求各端测试确实引用了生成物（否则向量又变成摆设）。
 */
function assertCases(cases) {
  const list = (name, arr, min) => {
    if (!Array.isArray(arr) || arr.length < (min ?? 1)) {
      fail(`cases: ${name} 必须是非空数组`);
      return [];
    }
    return arr;
  };
  const str = (where, v) => {
    if (typeof v !== 'string') fail(`cases: ${where} 必须是字符串`);
    return typeof v === 'string' ? v : '';
  };
  const num = (where, v) => {
    if (typeof v !== 'number' || !Number.isFinite(v)) fail(`cases: ${where} 必须是有限数字`);
    return typeof v === 'number' ? v : 0;
  };
  const uniq = (where, values) => {
    const seen = new Set();
    for (const v of values) {
      if (seen.has(v)) fail(`cases: ${where} 有重复项：${v}`);
      seen.add(v);
    }
  };

  // 路径判定
  const paths = list('paths.cases', cases.paths?.cases, 10);
  for (const c of paths) {
    const input = str('paths.input', c.input);
    if (c.expect !== null && typeof c.expect !== 'string') {
      fail(`cases: paths.expect 只能是字符串或 null（${input}）`);
      continue;
    }
    // 放行的期望值里不允许出现 `..`：那是安全边界，写错了等于白名单
    if (typeof c.expect === 'string' && c.expect.includes('..')) {
      fail(`cases: ${input} 被期望放行，但放行值里含 \`..\``);
    }
  }
  uniq('paths.input', paths.map((c) => c.input));

  // 展示格式化
  const fmt = cases.format ?? {};
  const bytes = list('format.bytes', fmt.bytes, 5);
  const speed = list('format.speed', fmt.speed, 3);
  const duration = list('format.duration', fmt.duration, 8);
  const errorDetail = list('format.errorDetail', fmt.errorDetail, 3);
  for (const [n, arr] of [['bytes', bytes], ['speed', speed], ['duration', duration]]) {
    for (const c of arr) {
      num(`format.${n}.in`, c.in);
      str(`format.${n}.out`, c.out);
    }
    uniq(`format.${n}.in`, arr.map((c) => c.in));
  }
  for (const c of errorDetail) {
    if (c.message !== null) str('format.errorDetail.message', c.message);
    if (c.code !== null) num('format.errorDetail.code', c.code);
    str('format.errorDetail.out', c.out);
  }

  // 版本比较
  const ver = cases.version ?? {};
  const normalize = list('version.normalize', ver.normalize, 3);
  const same = list('version.same', ver.same, 4);
  for (const c of normalize) {
    str('version.normalize.in', c.in);
    str('version.normalize.out', c.out);
  }
  uniq('version.normalize.in', normalize.map((c) => c.in));
  for (const c of same) {
    str('version.same.local', c.local);
    str('version.same.remote', c.remote);
    if (typeof c.out !== 'boolean') fail(`cases: version.same.out 必须是布尔（${c.local} vs ${c.remote}）`);
  }
  // 空串语义：`same` 里必须有「任一端为空 → false」的向量，否则那个坑没人守
  const emptySide = same.filter((c) => c.local === '' || c.remote === '');
  if (emptySide.length < 2 || emptySide.some((c) => c.out !== false)) {
    fail('cases: version.same 必须包含「任一端为空 → false」的向量');
  }

  return { paths, fmt: { bytes, speed, duration, errorDetail }, ver: { normalize, same } };
}

/** mime.json → 两端共用的 MIME 向量（顺带把图里的每条映射都锁一遍） */
function mimeCases(map, fallback) {
  const cases = Object.entries(map).map(([ext, mime]) => ({ path: `/x.${ext}`, expect: mime }));
  cases.push({ path: '/no-extension', expect: fallback });
  cases.push({ path: '/x.unknownext', expect: fallback });
  cases.push({ path: '/UPPER.PNG', expect: map.png });
  return cases;
}

const CASES_DOC = [
  '跨端行为向量（纯数据）。',
  '',
  '⚠️ **本文件由 `scripts/gen-shared.mjs` 自动生成，不要手改**：唯一真相在仓库根 `shared/cases/` 下的 JSON。',
  '期望值只写一份，两端测试遍历同一套向量——这样才能抓住「一端改了、另一端没改」的分叉。',
].join('\n');

/** 把一段多行说明渲染成 JSDoc 体（每行前缀 ` * `） */
const docBlock = (text) => text.split('\n').map((l) => (l ? ` * ${l}` : ' *')).join('\n');

function genEtsCases(c, mimes) {
  const lit = (v) => (v === null ? 'null' : typeof v === 'string' ? etsStr(v) : String(v));
  const rows = (arr, fn) => arr.map((x) => `  ${fn(x)}`).join(',\n');
  return `/**
${docBlock(CASES_DOC)}
 */

export interface PathCase {
  input: string;
  /** null = 应当拒绝（鸿蒙返回空串、安卓返回 null） */
  expect: string | null;
}

export interface NumberCase {
  input: number;
  expect: string;
}

export interface ErrorDetailCase {
  message: string | null;
  code: number | null;
  expect: string;
}

export interface NormalizeCase {
  input: string;
  expect: string;
}

export interface SameCase {
  local: string;
  remote: string;
  expect: boolean;
}

export interface MimeCase {
  path: string;
  expect: string;
}

/** 本地 HTTP 服务的路径判定（安全边界） */
export const PATH_CASES: PathCase[] = [
${rows(c.paths, (x) => `{ input: ${etsStr(x.input)}, expect: ${lit(x.expect)} }`)}
];

/** \`formatBytes\` */
export const BYTES_CASES: NumberCase[] = [
${rows(c.fmt.bytes, (x) => `{ input: ${x.in}, expect: ${etsStr(x.out)} }`)}
];

/** \`formatSpeed\` */
export const SPEED_CASES: NumberCase[] = [
${rows(c.fmt.speed, (x) => `{ input: ${x.in}, expect: ${etsStr(x.out)} }`)}
];

/** \`formatDuration\`（NaN / Infinity 在 JSON 里表达不了，由测试自己补） */
export const DURATION_CASES: NumberCase[] = [
${rows(c.fmt.duration, (x) => `{ input: ${x.in}, expect: ${etsStr(x.out)} }`)}
];

/** \`formatErrorDetail\` */
export const ERROR_DETAIL_CASES: ErrorDetailCase[] = [
${rows(c.fmt.errorDetail, (x) => `{ message: ${lit(x.message)}, code: ${lit(x.code)}, expect: ${etsStr(x.out)} }`)}
];

/** \`normalizeVersion\` */
export const NORMALIZE_CASES: NormalizeCase[] = [
${rows(c.ver.normalize, (x) => `{ input: ${etsStr(x.in)}, expect: ${etsStr(x.out)} }`)}
];

/** \`sameVersion\` */
export const SAME_CASES: SameCase[] = [
${rows(c.ver.same, (x) => `{ local: ${etsStr(x.local)}, remote: ${etsStr(x.remote)}, expect: ${x.out} }`)}
];

/** \`mimeOf\`（由 mime.json 生成） */
export const MIME_CASES: MimeCase[] = [
${rows(mimes, (x) => `{ path: ${etsStr(x.path)}, expect: ${etsStr(x.expect)} }`)}
];
`;
}

function genKtCases(c, mimes) {
  const lit = (v) => (v === null ? 'null' : typeof v === 'string' ? ktStr(v) : String(v));
  const num = (n) => (Number.isInteger(n) ? `${n}.0` : String(n));
  const longLit = (n) => `${n}L`;
  const rows = (arr, fn) => arr.map((x) => `        ${fn(x)}`).join(',\n');
  return `package com.lichtcui.pokelauncher

/**
${docBlock(CASES_DOC)}
 */
object CasesGen {

    data class PathCase(val input: String, val expect: String?)

    data class LongCase(val input: Long, val expect: String)

    data class DurationCase(val input: Double, val expect: String)

    data class ErrorDetailCase(val message: String?, val code: Int?, val expect: String)

    data class NormalizeCase(val input: String, val expect: String)

    data class SameCase(val local: String, val remote: String, val expect: Boolean)

    data class MimeCase(val path: String, val expect: String)

    /** 本地 HTTP 服务的路径判定（安全边界） */
    val PATH_CASES = listOf(
${rows(c.paths, (x) => `PathCase(${ktStr(x.input)}, ${lit(x.expect)})`)}
    )

    /** \`Format.bytes\` */
    val BYTES_CASES = listOf(
${rows(c.fmt.bytes, (x) => `LongCase(${longLit(x.in)}, ${ktStr(x.out)})`)}
    )

    /** \`Format.speed\` */
    val SPEED_CASES = listOf(
${rows(c.fmt.speed, (x) => `LongCase(${longLit(x.in)}, ${ktStr(x.out)})`)}
    )

    /** \`Format.duration\`（NaN / Infinity 在 JSON 里表达不了，由测试自己补） */
    val DURATION_CASES = listOf(
${rows(c.fmt.duration, (x) => `DurationCase(${num(x.in)}, ${ktStr(x.out)})`)}
    )

    /** \`Format.errorDetail\` */
    val ERROR_DETAIL_CASES = listOf(
${rows(c.fmt.errorDetail, (x) => `ErrorDetailCase(${lit(x.message)}, ${lit(x.code)}, ${ktStr(x.out)})`)}
    )

    /** \`Version.normalize\` */
    val NORMALIZE_CASES = listOf(
${rows(c.ver.normalize, (x) => `NormalizeCase(${ktStr(x.in)}, ${ktStr(x.out)})`)}
    )

    /** \`Version.same\` */
    val SAME_CASES = listOf(
${rows(c.ver.same, (x) => `SameCase(${ktStr(x.local)}, ${ktStr(x.remote)}, ${x.out})`)}
    )

    /** \`Mime.mimeOf\`（由 mime.json 生成） */
    val MIME_CASES = listOf(
${rows(mimes, (x) => `MimeCase(${ktStr(x.path)}, ${ktStr(x.expect)})`)}
    )
}
`;
}

/**
 * 反向护栏：向量生成出来了，还得有人用。
 * 各端测试文件必须引用生成物里的容器名，且不得再手写路径穿越向量的期望值
 * （否则就是「测试看着还在，其实已经和 shared/ 脱钩」）。
 */
function checkCaseUsage() {
  // need: 必须引用的生成容器名；forbidden: 正则（命中即报「又手写了一份期望值」）
  const rules = [
    [P.etsHttpTest, ['PATH_CASES'], ['/%2e%2e', '%2E%2E', '%E4%2e%2e', '%252e'], '鸿蒙路径向量'],
    [
      P.etsFormatTest,
      ['BYTES_CASES', 'DURATION_CASES'],
      ["'0 B'", "'1\\.0 MB'", '约 1 小时 1 分钟', '错误码 7'],
      '鸿蒙格式化向量',
    ],
    [P.etsRepoTest, ['NORMALIZE_CASES', 'SAME_CASES', 'MIME_CASES'], ['text/javascript', 'image/png', 'release-'], '鸿蒙版本 / MIME 向量'],
    [P.ktPathsTest, ['CasesGen.PATH_CASES'], ['resolveRequestPath\\("[^"]*(%|\\.\\.)'], '安卓路径向量'],
    [
      P.ktFormatTest,
      ['CasesGen.BYTES_CASES', 'CasesGen.DURATION_CASES'],
      ['"0 B"', '"1\\.0 MB"', '约 1 小时 1 分钟', '"错误码 7"'],
      '安卓格式化向量',
    ],
    [P.ktVersionTest, ['CasesGen.NORMALIZE_CASES', 'CasesGen.SAME_CASES'], ['"release-'], '安卓版本向量'],
    [P.ktMimeTest, ['CasesGen.MIME_CASES'], ['"text/javascript"', '"image/png"'], '安卓 MIME 向量'],
  ];
  for (const [file, need, forbidden, what] of rules) {
    if (!existsSync(join(ROOT, file))) {
      fail(`向量使用检查：找不到 ${file}`);
      continue;
    }
    const text = read(file);
    for (const n of need) {
      if (!text.includes(n)) fail(`向量使用检查：${file} 没有引用 \`${n}\`（${what} 应当来自 shared/cases/）`);
    }
    for (const f of forbidden) {
      const re = new RegExp(f);
      const m = text.match(re);
      if (m) fail(`向量使用检查：${file} 里又手写了一份期望值（命中 \`${m[0]}\`，模式 /${f}/），请改成遍历生成向量`);
    }
  }
}

/**
 * Kotlin 的块注释**可以嵌套**（与 C/JS 不同）：生成的文档注释里只要出现一个 `/*`
 * （比如写成 `shared/cases/*.json`），整个文件就会一路注释到结尾并报
 * 「Unclosed comment」——这个坑踩过一次，用生成器拦住。
 */
function assertKotlinDocs(outputs) {
  for (const [file, content] of outputs) {
    if (!file.endsWith('.kt')) continue;
    for (const line of content.split('\n')) {
      const t = line.trimStart();
      if (!t.startsWith('* ') && t !== '*') continue;
      if (t.includes('/*') || t.includes('*/')) {
        fail(`生成的 Kotlin 文档注释里出现了 /* 或 */（嵌套注释会让文件变成未闭合注释）：${file}\n    ${t}`);
      }
    }
  }
}

/**
 * 鸿蒙侧纯函数 × 向量：`scripts/check-harmony-vectors.mjs` 把鸿蒙的纯函数从 `.ets` 里剪出来，
 * 在 Node 里喂与安卓**同一批**向量。
 *
 * 为什么要挂在 `--check` 上：鸿蒙的这些测试（`LocalHttpServer.test` / `Format.test` /
 * `GameRepository.test`）只能跑在**设备**上，而本机经常没有 `hdc` 设备——不挂在这儿，
 * 「鸿蒙侧实现到底满不满足向量」在开发期就没人回答得了。脚本失败时是**响亮报错**（函数找不到 /
 * 语法错误 / 值不等），不会静默通过。
 */
function checkHarmonyVectors() {
  const r = spawnSync(process.execPath, [join(ROOT, 'scripts/check-harmony-vectors.mjs')], { encoding: 'utf8' });
  if (r.error) {
    fail(`鸿蒙侧向量复跑无法启动（${r.error.message}）：scripts/check-harmony-vectors.mjs`);
    return;
  }
  if (r.status !== 0) {
    const indent = (r.stdout + r.stderr).trim().split('\n').map((l) => `      ${l}`).join('\n');
    fail(`鸿蒙侧纯函数不满足 shared/ 向量（scripts/check-harmony-vectors.mjs）：\n${indent}`);
  }
}

/**
 * 更新清单 `version.json` 的格式对账。
 *
 * 这份清单是**手写**的（发版流程第 2 步，见 docs/app-update.md），两端各自解析：
 * 鸿蒙 `AppUpdate.ets` 的 `ManifestJson`、安卓 `appupdate/AppUpdate.kt` 的 `optX("…")`。
 * 手写清单最容易犯的错是**键名打错**——两端的宽松解析都会当它不存在，于是：
 * 「安卓版暂未发布」「发现新版本但按钮指向 HAP 页」这类问题**要等用户反馈才发现**。
 *
 * 检查项：
 *   1. 每个键至少被某一端认识（打错字的键 → 报错）；
 *   2. 鸿蒙必填 `versionCode`(正整数) / `versionName`(非空)，`minVersionCode` 不得大于 `versionCode`
 *      （否则最新版自己也被强制更新）；
 *   3. 安卓侧要么整块不写（= 安卓版暂未发布，允许），要么 `androidVersionCode` + `androidVersionName`
 *      齐全；写了 `apkUrl` 就必须有 `androidVersionCode`（否则安卓会拿鸿蒙的 versionCode 比）；
 *   4. `downloadUrl` / `pageUrl` / `apkUrl` 必须是 http(s) 链接。
 */
function checkManifestSchema() {
  const manifestRel = 'version.json';
  if (!existsSync(join(ROOT, manifestRel))) {
    fail('更新清单：仓库根缺 version.json（App 自更新靠它，见 docs/app-update.md）');
    return;
  }
  let manifest;
  try {
    manifest = JSON.parse(read(manifestRel));
  } catch (err) {
    fail(`更新清单：${manifestRel} 不是合法 JSON（${err instanceof Error ? err.message : String(err)}）`);
    return;
  }

  const etsManifest = typeBody(read('entry/src/main/ets/model/AppUpdate.ets'), 'ManifestJson');
  const harmonyKeys = new Set(
    (etsManifest || '')
      .split('\n')
      .map((l) => (/^\s*([A-Za-z_$][\w$]*)\??\s*:/.exec(l) || [])[1])
      .filter((x) => x !== undefined)
  );
  const androidKeys = new Set(
    readdirSync(join(ROOT, 'android/app/src/main/java/com/lichtcui/pokelauncher/appupdate'))
      .filter((f) => f.endsWith('.kt'))
      .flatMap((f) =>
        [...read(`android/app/src/main/java/com/lichtcui/pokelauncher/appupdate/${f}`).matchAll(/opt(?:Int|Long|String|Boolean)\("([^"]+)"/g)].map(
          (m) => m[1]
        )
      )
  );
  const known = new Set([...harmonyKeys, ...androidKeys]);
  if (harmonyKeys.size === 0 || androidKeys.size === 0) {
    fail('更新清单：从两端解析器里读不到字段名（解析写法变了？这条检查会失效）');
    return;
  }

  const keys = Object.keys(manifest);
  for (const k of keys) {
    if (!known.has(k)) {
      fail(`更新清单：${manifestRel} 里的键 \`${k}\` 两端解析器都不认识（打错字？两端都会静默忽略它）`);
    }
  }

  const isInt = (v) => typeof v === 'number' && Number.isInteger(v);
  if (!isInt(manifest.versionCode) || manifest.versionCode <= 0) {
    fail(`更新清单：versionCode 必须是正整数（当前 ${JSON.stringify(manifest.versionCode)}）`);
  }
  if (typeof manifest.versionName !== 'string' || manifest.versionName.length === 0) {
    fail('更新清单：versionName 必须是非空字符串（界面上要展示它）');
  }
  if (manifest.minVersionCode !== undefined && !isInt(manifest.minVersionCode)) {
    fail(`更新清单：minVersionCode 必须是整数（当前 ${JSON.stringify(manifest.minVersionCode)}）`);
  }
  if (isInt(manifest.versionCode) && isInt(manifest.minVersionCode) && manifest.minVersionCode > manifest.versionCode) {
    fail(`更新清单：minVersionCode(${manifest.minVersionCode}) 大于 versionCode(${manifest.versionCode})：最新版自己也会被强制更新`);
  }

  const androidKeysUsed = keys.filter((k) => k.startsWith('android') || k === 'apkUrl');
  if (androidKeysUsed.length > 0) {
    if (!isInt(manifest.androidVersionCode) || manifest.androidVersionCode <= 0) {
      fail(
        `更新清单：写了 ${androidKeysUsed.join(' / ')} 就必须同时写 androidVersionCode（正整数）；` +
          '否则安卓端会退化成拿鸿蒙的 versionCode 比较 → 永远「发现新版本」并指向 HAP 页'
      );
    }
    if (typeof manifest.androidVersionName !== 'string' || manifest.androidVersionName.length === 0) {
      fail('更新清单：写了 android* 就必须写非空的 androidVersionName（安卓端要展示它）');
    }
    if (manifest.apkUrl !== undefined && (typeof manifest.apkUrl !== 'string' || !/^https?:\/\//.test(manifest.apkUrl))) {
      fail('更新清单：apkUrl 必须是 http(s) 链接（安卓侧会直接拿去下载）');
    }
    if (
      isInt(manifest.androidMinVersionCode) &&
      isInt(manifest.androidVersionCode) &&
      manifest.androidMinVersionCode > manifest.androidVersionCode
    ) {
      fail('更新清单：androidMinVersionCode 大于 androidVersionCode');
    }
  }
  for (const k of ['downloadUrl', 'pageUrl']) {
    if (manifest[k] !== undefined && (typeof manifest[k] !== 'string' || !/^https?:\/\//.test(manifest[k]))) {
      fail(`更新清单：${k} 必须是 http(s) 链接（两端都用 Want/Intent 直接打开）`);
    }
  }
}

/**
 * 安卓原生侧的「同一件事写在两个文件里」检查（AGENTS.md 点名的红线）。
 *
 * 这些不是跨端分叉，而是**同一端两份配置**必须对齐，错一处的代价都很大：
 * - 存档快照目录（`Const.SAVE_DIR_NAME` ↔ `backup_rules.xml` / `data_extraction_rules.xml`）：
 *   对不上 → 备份规则把存档排除在外，只有换机/重装时才被发现；
 * - 自更新 APK 目录（`ApkUpdater.DIR_NAME` ↔ `provider_paths.xml`）：对不上 →
 *   FileProvider 抛 `IllegalArgumentException`（安装流程直接断）。
 */
function checkNativePathRules() {
  const saveDirKt = pick(read(P.ktConst), /const val SAVE_DIR_NAME = "([^"]+)"/);
  const saveDirEts = pick(read(P.etsSaveGuard), /const SNAPSHOT_DIR: string = '([^']+)'/);
  if (saveDirKt === undefined || saveDirEts === undefined) {
    fail('存档目录：从 Const.SAVE_DIR_NAME / SaveGuard.SNAPSHOT_DIR 里读不到目录名（解析失败？）');
  } else if (saveDirKt !== saveDirEts) {
    fail(`存档快照目录两端不一致：鸿蒙 files/${saveDirEts}/ ↔ 安卓 filesDir/${saveDirKt}/`);
  }
  if (saveDirKt !== undefined) {
    const rules = [
      [P.ktBackupRules, 1, 'Auto Backup（API 30 及以下）'],
      [P.ktExtractionRules, 2, '数据提取（API 31+，cloud-backup + device-transfer 两块）'],
    ];
    for (const [rel, blocks, what] of rules) {
      const xml = read(rel);
      const includes = [...xml.matchAll(/<include\s+domain="([^"]+)"\s+path="([^"]+)"\s*\/>/g)].map((m) => `${m[1]}:${m[2]}`);
      const allowed = [`sharedpref:.`, `file:${saveDirKt}`];
      const bad = includes.filter((x) => !allowed.includes(x));
      if (bad.length > 0) {
        fail(
          `${rel}：备份白名单里出现不该备的东西（${bad.join(' / ')}）——` +
            '游戏本体在外部存储有 1.5GB、Auto Backup 配额只有 25MB，整次备份会失败并连带丢掉存档。' +
            '只允许 sharedpref + 存档目录。'
        );
      }
      if (includes.length !== allowed.length * blocks) {
        fail(
          `${rel}：期望 ${blocks} 块 × [${allowed.join(', ')}] 共 ${allowed.length * blocks} 条 include，` +
            `实际 ${includes.length} 条（${includes.join(' / ')}）——` +
            '少一条就等于那一块不再备份存档。'
        );
      }
    }
  }

  const apkDirName = pick(read(P.ktApkUpdater), /const val DIR_NAME = "([^"]+)"/);
  if (apkDirName === undefined) {
    fail('自更新目录：从 ApkUpdater.DIR_NAME 里读不到目录名（解析失败？）');
  } else {
    const xml = read(P.ktProviderPaths);
    const entries = [...xml.matchAll(/<(external-files-path|files-path|cache-path|external-path|external-cache-path)\s+name="[^"]*"\s+path="([^"]*)"\s*\/>/g)].map(
      (m) => `${m[1]}:${m[2]}`
    );
    const expected = [`external-files-path:${apkDirName}/`, `files-path:${apkDirName}/`];
    const bad = entries.filter((x) => !expected.includes(x));
    if (bad.length > 0) {
      fail(
        `${P.ktProviderPaths}：FileProvider 白名单里出现了不该放行的路径（${bad.join(' / ')}）——` +
          '授出去的是一条 URI，接收方在授权期内可读该目录下任何文件；游戏资源（1.5GB）与存档都不该在里头。'
      );
    }
    for (const e of expected) {
      if (!entries.includes(e)) {
        fail(
          `${P.ktProviderPaths}：缺少 ${e}（ApkUpdater.DIR_NAME = "${apkDirName}"）——` +
            'FileProvider 只认白名单里的路径，缺了会在安装时抛 IllegalArgumentException。'
        );
      }
    }
  }
}

/** 逐行扫 harmony 源码，返回 `{ rel, text }[]` */
function harmonySources() {
  const out = [];
  const walk = (dir) => {
    for (const e of readdirSync(join(ROOT, dir), { withFileTypes: true })) {
      const rel = `${dir}/${e.name}`;
      if (e.isDirectory()) walk(rel);
      else if (e.name.endsWith('.ets')) out.push({ rel, text: read(rel) });
    }
  };
  walk('entry/src/main/ets');
  return out;
}

/** `interface X { … }` / `class X { … }`（大括号配对） */
function typeBody(text, name) {
  const m = new RegExp(`(?:interface|class)\\s+${name}\\s*(?:extends[^{{]*)?\\{`).exec(text);
  if (!m) return null;
  const open = m.index + m[0].length - 1;
  let depth = 0;
  for (let i = open; i < text.length; i++) {
    if (text[i] === '{') depth++;
    else if (text[i] === '}') {
      depth--;
      if (depth === 0) return text.slice(open + 1, i);
    }
  }
  return null;
}

/**
 * 鸿蒙 release 混淆的 `-keep-property-name` 白名单 ↔ 源码里真正解析的 JSON 键。
 *
 * 为什么：本工程 release 开了 `-enable-property-obfuscation`，`JSON.parse(...) as X` 这类
 * **动态键**必须在 `entry/obfuscation-rules.txt` 里点名保留，否则 release 包会**静默读不到值**
 * （debug 正常、release 一律走默认值，看日志还以为是网络问题）。新增字段解析忘了同步白名单是
 * 最容易犯的错，所以在生成器里拦。
 *
 * 做法：从 `JSON.parse(...) as T` 出发，顺着 T 的属性类型递归收集字段名（含 `assets: ReleaseAsset[]`
 * 这种嵌套），再逐个查白名单；另把 `javaScriptProxy` 的 methodList（也走动态调用）一并纳入。
 */
function checkHarmonyKeepList() {
  const rules = read('entry/obfuscation-rules.txt');
  const block = rules.match(/-keep-property-name[ \t]*\n([\s\S]*)/);
  const kept = new Set(
    (block ? block[1] : '')
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l.length > 0 && !l.startsWith('#') && !l.startsWith('-'))
  );

  const sources = harmonySources();
  const byRel = new Map(sources.map((s) => [s.rel, s.text]));
  const findType = (name) => {
    for (const { rel, text } of sources) {
      if (new RegExp(`(?:interface|class)\\s+${name}\\s*(?:extends[^{{]*)?\\{`).test(text)) {
        return { rel, body: typeBody(text, name) };
      }
    }
    return null;
  };

  // 根：`JSON.parse(...) as T`
  const roots = [];
  for (const { rel, text } of sources) {
    for (const m of text.matchAll(/JSON\.parse\([^;]*?\)\s*as\s+([A-Za-z_$][\w$]*)/g)) {
      roots.push({ type: m[1], rel });
    }
  }
  if (roots.length === 0) {
    fail('混淆白名单：鸿蒙源码里找不到 `JSON.parse(...) as T`（解析写法变了？这条检查会失效）');
  }

  const fields = new Map(); // 字段名 → 出处描述
  const visited = new Set();
  const visit = (type, rel) => {
    if (visited.has(type)) return;
    visited.add(type);
    const decl = findType(type);
    if (decl === null || decl.body === null) {
      fail(`混淆白名单：` + '`JSON.parse(...) as ' + type + '`（' + rel + '）找不到对应的 interface/class 声明');
      return;
    }
    for (const line of decl.body.split('\n')) {
      const f = /^\s*(?:readonly\s+)?([A-Za-z_$][\w$]*)\??\s*:\s*(.+?)\s*;?\s*$/.exec(line);
      if (!f) continue;
      if (!fields.has(f[1])) fields.set(f[1], `${decl.rel} 的 ${type}.${f[1]}`);
      for (const ref of f[2].matchAll(/\b([A-Za-z_$][\w$]*)\b/g)) {
        if (!visited.has(ref[1]) && findType(ref[1]) !== null) visit(ref[1], decl.rel);
      }
    }
  };
  for (const r of roots) visit(r.type, r.rel);

  // javaScriptProxy 的 methodList：动态调用，同样不能改名
  const etsGame = byRel.get('entry/src/main/ets/pages/GamePage.ets') || '';
  const methodList = ((etsGame.match(/methodList:\s*\[([^\]]*)\]/) || [])[1] || '')
    .split(',')
    .map((x) => x.trim().replace(/^'|'$/g, ''))
    .filter((x) => x.length > 0);
  for (const m of methodList) fields.set(m, 'GamePage.ets 的 javaScriptProxy methodList');

  const missing = [...fields.entries()].filter(([name]) => !kept.has(name));
  if (missing.length > 0) {
    const lines = missing.map(([name, from]) => `      ${name}  （${from}）`).join('\n');
    fail(
      '混淆白名单：以下动态属性名不在 entry/obfuscation-rules.txt 的 `-keep-property-name` 里，' +
        `release 包会静默读不到值：\n${lines}`
    );
  }
}

/**
 * 取出 `class X` 的类体（到下一个顶格 `}` 为止）——够用即可，取错会以「找不到桥方法」的形式报错。
 */
function classBody(text, className) {
  const i = text.indexOf(`class ${className}`);
  if (i < 0) return '';
  const end = text.indexOf('\n}\n', i);
  return end < 0 ? text.slice(i) : text.slice(i, end);
}

/** 安卓 Kotlin 里 `@JavascriptInterface` 标注的方法名（顺序即声明顺序） */
function jsInterfaceMethods(body) {
  return [...body.matchAll(/@JavascriptInterface\s+(?:@\w+\s+)*fun\s+([A-Za-z_$][\w$]*)\s*\(/g)].map((m) => m[1]);
}

const sameSet = (a, b) =>
  a.length === b.length && [...a].sort().join(',') === [...b].sort().join(',');

/**
 * JS 桥 / 虚拟端点 / 注入钩子的**跨端契约**对账。
 *
 * 这些东西不是「生成物」，而是「两端 + 共享 JS 三方都写字面量」的接口：桥对象名、桥方法名、
 * 虚拟端点路径、`__CHEAT_PARAMS__` 这类全局名。它们**没有类型**，改一边漏一边的后果是
 * **静默失效**（注入脚本拿到 undefined 就悄悄跳过），只能靠对账拦。
 */
function checkJsBridgeContract() {
  // ---- 1) 存档守卫桥：shared/js/save-guard.js ↔ 鸿蒙 javaScriptProxy ↔ 安卓 @JavascriptInterface
  const sgJs = read(P.saveGuard);
  const nameM = sgJs.match(/window\.([A-Za-z_$][\w$]*)\s*;/);
  const jsName = nameM ? nameM[1] : null;
  const jsMethods = [...sgJs.matchAll(/G\.([A-Za-z_$][\w$]*)\s*\(/g)].map((m) => m[1]);
  if (jsName === null || jsMethods.length === 0) {
    fail('JS 桥：从 shared/js/save-guard.js 里读不到桥对象名或桥方法（`window.X` / `G.m(...)`）');
    return;
  }

  const etsGame = read('entry/src/main/ets/pages/GamePage.ets');
  const etsName = (etsGame.match(/name:\s*'([^']+)'/) || [])[1];
  const etsMethods = ((etsGame.match(/methodList:\s*\[([^\]]*)\]/) || [])[1] || '')
    .split(',')
    .map((x) => x.trim().replace(/^'|'$/g, ''))
    .filter((x) => x.length > 0);
  if (etsName !== jsName) {
    fail(`JS 桥：鸿蒙 javaScriptProxy 的对象名 \`${etsName}\` ≠ 共享 JS 里用的 \`${jsName}\``);
  }
  if (!sameSet(etsMethods, jsMethods)) {
    fail(
      `JS 桥：鸿蒙 methodList [${etsMethods.join(', ')}] 与共享 JS 调用的方法 [${jsMethods.join(', ')}] 不一致` +
        '（methodList 是白名单：少一个 → 该调用静默失败）'
    );
  }

  const ktSaveGuard = read(P.ktSaveGuard);
  const ktName = (ktSaveGuard.match(/const val BRIDGE_NAME\s*=\s*"([^"]+)"/) || [])[1];
  if (ktName !== jsName) {
    fail(`JS 桥：安卓 SaveGuard.BRIDGE_NAME \`${ktName}\` ≠ 共享 JS 里用的 \`${jsName}\``);
  }
  const ktWebView = read('android/app/src/main/java/com/lichtcui/pokelauncher/web/PokeWebView.kt');
  const ktSaveMethods = jsInterfaceMethods(classBody(ktWebView, 'SaveGuardBridge'));
  if (!sameSet(ktSaveMethods, jsMethods)) {
    fail(
      `JS 桥：安卓 SaveGuardBridge 的 @JavascriptInterface 方法 [${ktSaveMethods.join(', ')}] ` +
        `与共享 JS 调用的 [${jsMethods.join(', ')}] 不一致`
    );
  }

  // 混淆：安卓按注解 keep（名字改了也必须有人在 JS 侧调用，见下）
  const proguard = read('android/app/proguard-rules.pro');
  if (!proguard.includes('@android.webkit.JavascriptInterface <methods>;')) {
    fail('JS 桥：android/app/proguard-rules.pro 丢了 `@android.webkit.JavascriptInterface <methods>;`（release 下桥方法会被改名 → 静默失效）');
  }

  // ---- 2) 安卓桥：JS 侧必须有调用方；BRIDGE_METHODS 里不许出现根本没声明的名字
  //        （安卓单测已经锁了「JS 调用 ⊆ BRIDGE_METHODS」，这里补上「BRIDGE_METHODS ⊆ 真的声明了」）
  const others = [];
  const walk = (dir) => {
    for (const e of readdirSync(join(ROOT, dir), { withFileTypes: true })) {
      const rel = `${dir}/${e.name}`;
      if (e.isDirectory()) walk(rel);
      else if (/\.(kt|java)$/.test(e.name) && rel !== 'android/app/src/main/java/com/lichtcui/pokelauncher/web/PokeWebView.kt') {
        others.push(read(rel));
      }
    }
  };
  walk('android/app/src/main/java');
  const allMain = others.join('\n');
  const ktNativeMethods = jsInterfaceMethods(classBody(ktWebView, 'NativeBridge'));

  for (const [what, methods] of [
    ['NativeBridge(PokeNative)', ktNativeMethods],
    ['SaveGuardBridge(PokeSaveGuard)', ktSaveMethods],
  ]) {
    for (const m of methods) {
      if (!new RegExp(`\\.${m}\\s*\\(`).test(allMain)) {
        fail(`JS 桥：${what} 声明了 ${m}()，但 JS 侧（其他源文件里的字符串）没有任何 \`${m}(\` 调用：改名时只改了一边`);
      }
    }
  }

  // BRIDGE_METHODS ↔ 桥类声明（表里的名字必须是真方法名，否则单测锁的是一份幻觉）
  for (const [file, cls] of [
    [P.ktSaveGuard, 'SaveGuardBridge'],
    ['android/app/src/main/java/com/lichtcui/pokelauncher/web/DownloadBridge.kt', 'NativeBridge'],
  ]) {
    const declared = jsInterfaceMethods(classBody(ktWebView, cls));
    const listed = [...read(file).matchAll(/BRIDGE_METHODS[^=]*=\s*listOf\(([^)]*)\)/g)]
      .flatMap((m) => [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]));
    for (const m of listed) {
      if (!declared.includes(m)) {
        fail(`JS 桥：${file.split('/').pop()} 的 BRIDGE_METHODS 里有 ${m}，但 ${cls} 上没有 @JavascriptInterface 方法 ${m}()`);
      }
    }
  }

  // ---- 3) 虚拟端点：两端的「参数调整」脚本路径必须同名（localStorage 以 origin 为键，路径不会换 origin，但注入标签对不上就是空脚本）
  const ktCheatPath = (read(P.ktConst).match(/const val CHEAT_SCRIPT_PATH\s*=\s*"([^"]+)"/) || [])[1];
  const etsCheatPath = (read(P.etsHttpServer).match(/path === '([^']*cheat[^']*)'/) || [])[1];
  if (ktCheatPath !== '/__cheats__.js' || etsCheatPath !== ktCheatPath) {
    fail(`虚拟端点：安卓 CHEAT_SCRIPT_PATH \`${ktCheatPath}\` 与鸿蒙路由 \`${etsCheatPath}\` 不一致（两端都得是 /__cheats__.js）`);
  }

  // ---- 4) 参数调整的数值参数通道：共享脚本读 __CHEAT_PARAMS__，两端平台运行时各写一份
  for (const [what, file] of [['鸿蒙', P.etsCheatState], ['安卓', P.ktCheatScripts]]) {
    if (!/var\s+__CHEAT_PARAMS__\s*=/.test(read(file))) {
      fail(`${what}参数调整运行时没有生成 __CHEAT_PARAMS__（共享脚本会静默退回默认值）：${file}`);
    }
  }

  // ---- 5) 鸿蒙注入链顺序（安卓由 InjectScriptsTest 锁住；鸿蒙这句只是代码里的书写顺序，没有测试）
  const etsHttp = read(P.etsHttpServer);
  const order = /const inject: string = buildSaveGuardScript\(\)\s*\+\s*cheatsTag\s*\+\s*this\.buildInjectScript\(\)/;
  if (!order.test(etsHttp)) {
    fail(
      '注入链：鸿蒙 servePatchedIndex 的拼接不再是 `buildSaveGuardScript() + cheatsTag + this.buildInjectScript()`；' +
        '顺序必须是 存档守卫 → 参数调整 → canvas 补丁（守卫要早于游戏读 localStorage）'
    );
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

const legal = JSON.parse(read(P.legal));
assertLegal(legal);
const mime = JSON.parse(read(P.mime));
const mimeMap = assertMime(mime);
const cases = assertCases({
  paths: JSON.parse(read(P.casesPaths)),
  format: JSON.parse(read(P.casesFormat)),
  version: JSON.parse(read(P.casesVersion)),
});
const mimes = mimeCases(mimeMap, mime.fallback);

const outputs = [
  [P.etsCheats, genEtsCheats(registry)],
  [P.etsSharedJs, genEtsSharedJs(sharedJs)],
  [P.ktCheats, genKtCheats(registry)],
  [P.ktSharedJs, genKtSharedJs(sharedJs)],
  [P.etsLegalText, genEtsLegalText(renderLegal(legal, 'harmony'))],
  [P.ktLegalText, genKtLegalText(renderLegal(legal, 'android'))],
  [P.etsMime, genEtsMime(mimeMap, mime.fallback)],
  [P.ktMime, genKtMime(mimeMap, mime.fallback)],
  [P.etsCases, genEtsCases(cases, mimes)],
  [P.ktCases, genKtCases(cases, mimes)],
];

checkConstParity();
checkNoInline();
checkCaseUsage();
checkJsBridgeContract();
checkHarmonyKeepList();
checkManifestSchema();
checkNativePathRules();
checkHarmonyVectors();
assertKotlinDocs(outputs);

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
