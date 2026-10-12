#!/usr/bin/env node
/**
 * 鸿蒙侧纯函数 × 跨端行为向量（**没有鸿蒙真机时的替代证据**）。
 *
 * 为什么需要它：`shared/cases/*.json` 的期望值由两端共享，鸿蒙侧本该由 ohosTest 在**设备**上跑
 * （`LocalHttpServer.test` / `Format.test` / `GameRepository.test`），但本机常常没有 `hdc` 设备，
 * 于是「鸿蒙侧实现是否满足这套向量」就只能靠人读代码。本脚本把鸿蒙侧的纯函数从 `.ets` 里剪出来，
 * 在 Node 里喂**同一批**向量。
 *
 * 边界（别当成 ohosTest 的等价物）：
 * - 只覆盖无副作用的纯函数；不碰 hypium / Ability / context / UI；
 * - 函数体是「括号配对剪出来的字符串」，再按有限规则去掉 ArkTS 类型标注。规则不够用时会**报错**
 *   （找不到函数 / 语法错误 / 向量不等），不会静默通过；
 * - 真机在手时，**仍然要**跑 `entry@ohosTest` 的 HAP（见 AGENTS.md §2 测试）。
 *
 * 用法：`node scripts/check-harmony-vectors.mjs`（不一致时退出码 1；`--check` 会顺带调用它）
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = (rel) => readFileSync(join(ROOT, rel), 'utf8');

const SRC = {
  http: 'entry/src/main/ets/model/LocalHttpServer.ets',
  repo: 'entry/src/main/ets/model/GameRepository.ets',
  mime: 'entry/src/main/ets/common/Mime.ets',
  format: 'entry/src/main/ets/common/Format.ets',
  cache: 'entry/src/main/ets/common/HttpCache.ets',
};

/**
 * 从 ArkTS 源里剪出 `function NAME(...) { ... }`：按大括号配对，不靠缩进猜。
 *
 * 已知限制：函数体里若出现**字符串形式的大括号**（如 `'}'`）会剪歪。剪歪的后果是语法错误或
 * 向量不等 → 报错退出，不是静默通过（所以这里不追求完备，只保证失败可见）。
 */
function extractFunction(src, name, from) {
  const start = src.indexOf(`function ${name}(`);
  if (start < 0) throw new Error(`找不到函数 ${name}()（${from}）`);
  const open = src.indexOf('{', start);
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) return src.slice(start, i + 1);
    }
  }
  throw new Error(`函数 ${name}() 的大括号不配对（${from}）`);
}

/**
 * 去 ArkTS 类型标注。
 *
 * ⚠️ 这里**故意不用**「把 `: 标识符` 一律删掉」那种写法：三元 `a ? b : c` 与类型标注长得一样，
 * 一刀切会把 `mime !== undefined ? mime : MIME_FALLBACK` 改成 `? mime`（踩过）。所以只认
 * 位置明确的四种：函数声明的参数/返回值、`const|let|var` 声明、`new Map<...>`、`as T`。
 * 遗漏的类型标注 → Node 报语法错误 → 退出码 1（响亮失败，不会静默通过）。
 */
const TYPE = String.raw`[A-Za-z_$][\w$.]*(?:<[\w\s,$.|[\]]*>)?(?:\[\])?(?:\s*\|\s*(?:null|undefined|[A-Za-z_$][\w$.]*))*`;

function stripTypes(text) {
  const noAnn = (params) => params.replace(new RegExp(`:\\s*${TYPE}`, 'g'), '');
  return text
    // 函数声明的参数表 + 返回值
    .replace(new RegExp(`\\bfunction\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)\\s*:\\s*${TYPE}`, 'g'), (_m, name, params) => `function ${name}(${noAnn(params)})`)
    // `const x: T` / `let x: T`（要求声明关键字，避免误伤三元）
    .replace(new RegExp(`\\b(const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*:\\s*${TYPE}`, 'g'), '$1 $2')
    // 泛型实参（只认 `new Map<...>`，别的 `<` 可能是比较运算）
    .replace(/new Map<[^<>]*>/g, 'new Map')
    .replace(/\bas\s+[A-Za-z_$][\w$.]*/g, '')
    .replace(/^export /gm, '');
}

/**
 * 要**连带剪出模块级字面量常量**的源文件（键同 [SRC]）。
 *
 * 被剪出来的函数可能引用模块级常量（例如 `cacheControl` 用的 `NO_CACHE`），
 * 只剪函数就会 ReferenceError。这里只取「初值是字面量」的常量——它们不可能引用 import，
 * 所以连带剪出来是安全的；不是字面量（数组 / Map / 调用）的常量故意不取，
 * 需要时再把整个文件喂进来（像 `Mime.ets` 那样）。
 */
const CONST_SOURCES = ['cache'];

/**
 * 剪出模块级字面量常量声明（行首 + 以 `;` 结尾 + 初值是字符串/数字/布尔）。
 *
 * 已知限制同 [extractFunction]：剪歪 = 语法错误 = 退出码 1，不会静默通过。
 */
function extractConsts(src) {
  const re = /^(?:export\s+)?const\s+[A-Za-z_$][\w$]*\s*(?::[^=]*)?=\s*(?:'[^']*'|"[^"]*"|-?[\d.]+|true|false)\s*;/gm;
  return [...src.matchAll(re)].map((m) => m[0]).join('\n');
}

/** 被测函数（键 = 全局名，值 = [源文件, 函数名]） */
const FUNCS = {
  resolveRequestPath: ['http', 'resolveRequestPath'],
  normalizeVersion: ['repo', 'normalizeVersion'],
  sameVersion: ['repo', 'sameVersion'],
  formatBytes: ['format', 'formatBytes'],
  formatSpeed: ['format', 'formatSpeed'],
  formatErrorDetail: ['format', 'formatErrorDetail'],
  formatDuration: ['format', 'formatDuration'],
  cacheControl: ['cache', 'cacheControl'],
};

function buildContext() {
  const src = Object.fromEntries(Object.entries(SRC).map(([k, rel]) => [k, read(rel)]));
  const pieces = Object.entries(FUNCS).map(([, [file, name]]) =>
    extractFunction(src[file], name, SRC[file])
  );
  for (const key of CONST_SOURCES) pieces.push(extractConsts(src[key]));
  // Mime.ets 是生成物、整份喂进去（MIME_MAP / MIME_FALLBACK / mimeOf 都在里面，且无 import）
  pieces.push(src.mime);
  const context = { console, Math, Number, isFinite, Map };
  vm.createContext(context);
  vm.runInContext(stripTypes(pieces.join('\n\n')), context, { filename: 'harmony-pure-functions.js' });
  for (const name of [...Object.keys(FUNCS), 'mimeOf']) {
    if (typeof context[name] !== 'function') {
      throw new Error(`剪出来的代码里没有 ${name}()：类型标注的剥离规则需要更新（scripts/check-harmony-vectors.mjs）`);
    }
  }
  return context;
}

function main() {
  const context = buildContext();
  const cases = {
    paths: JSON.parse(read('shared/cases/paths.json')),
    format: JSON.parse(read('shared/cases/format.json')),
    version: JSON.parse(read('shared/cases/version.json')),
    http: JSON.parse(read('shared/cases/http.json')),
    mime: JSON.parse(read('shared/mime.json')),
  };

  let total = 0;
  const failures = [];
  const check = (what, got, want) => {
    total++;
    if (got !== want) {
      failures.push(`${what}\n      实际: ${JSON.stringify(got)}\n      期望: ${JSON.stringify(want)}`);
    }
  };

  // 路径：向量里 `null` = 应当拒绝，鸿蒙侧的约定是返回空串（与 ohosTest 里的换算一致）
  let rejected = 0;
  for (const c of cases.paths.cases) {
    if (c.expect === null) rejected++;
    check(`resolveRequestPath(${JSON.stringify(c.input)})`, context.resolveRequestPath(c.input), c.expect === null ? '' : c.expect);
  }
  if (rejected < 8) {
    failures.push(`拒绝项只剩 ${rejected} 条（<8）：向量里的安全边界被删过头了`);
  }

  for (const [name, arr] of [
    ['formatBytes', cases.format.bytes],
    ['formatSpeed', cases.format.speed],
    ['formatDuration', cases.format.duration],
  ]) {
    for (const c of arr) check(`${name}(${c.in})`, context[name](c.in), c.out);
  }
  // NaN / Infinity 在 JSON 里表达不了，这几条各端自己手写（鸿蒙 Format.test 与安卓 FormatTest 里也有）
  check('formatDuration(NaN)', context.formatDuration(Number.NaN), '');
  check('formatDuration(Infinity)', context.formatDuration(Number.POSITIVE_INFINITY), '');
  check('formatSpeed(NaN)', context.formatSpeed(Number.NaN), '');
  check('formatSpeed(Infinity)', context.formatSpeed(Number.POSITIVE_INFINITY), '');
  check('formatBytes(NaN)', context.formatBytes(Number.NaN), context.formatBytes(0));
  check('formatBytes(Infinity)', context.formatBytes(Number.POSITIVE_INFINITY), context.formatBytes(0));
  for (const c of cases.format.errorDetail) {
    const message = c.message === null ? undefined : c.message;
    const code = c.code === null ? undefined : c.code;
    check(`formatErrorDetail(${JSON.stringify(c.message)}, ${JSON.stringify(c.code)})`, context.formatErrorDetail(message, code), c.out);
  }

  // 缓存语义：入参是已解析的游戏内路径（query 已由 resolveRequestPath 去掉）
  for (const c of cases.http.cacheControl) check(`cacheControl(${JSON.stringify(c.path)})`, context.cacheControl(c.path), c.expect);

  for (const c of cases.version.normalize) check(`normalizeVersion(${JSON.stringify(c.in)})`, context.normalizeVersion(c.in), c.out);
  for (const c of cases.version.same) {
    check(`sameVersion(${JSON.stringify(c.local)}, ${JSON.stringify(c.remote)})`, context.sameVersion(c.local, c.remote), c.out);
  }

  // MIME：与生成器 mimeCases() 同一组用例（每条映射 + 三条退化）
  for (const [ext, mime] of Object.entries(cases.mime.map)) check(`mimeOf('/x.${ext}')`, context.mimeOf(`/x.${ext}`), mime);
  check("mimeOf('/no-extension')", context.mimeOf('/no-extension'), cases.mime.fallback);
  check("mimeOf('/x.unknownext')", context.mimeOf('/x.unknownext'), cases.mime.fallback);
  check("mimeOf('/UPPER.PNG')", context.mimeOf('/UPPER.PNG'), cases.mime.map.png);

  if (failures.length > 0) {
    console.error(`✗ 鸿蒙侧纯函数不满足 shared/ 向量（${failures.length}/${total}）：`);
    for (const f of failures) console.error(`    ${f}`);
    console.error('  ⚠️ 注意：这**只是**无设备时的替代证据，真机在手时要跑 entry@ohosTest 的 HAP。');
    process.exit(1);
  }
  console.log(`✓ 鸿蒙侧纯函数满足 shared/ 向量：${total} 条（paths / format / version / cache / mime）`);
  console.log('  ⚠️ 无设备时的替代证据，不能替代 entry@ohosTest 在真机上的运行结果。');
}

try {
  main();
} catch (err) {
  console.error(`✗ 鸿蒙侧向量复跑失败：${err instanceof Error ? err.message : String(err)}`);
  process.exit(1);
}
