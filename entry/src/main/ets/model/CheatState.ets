import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { Cheat, CheatParam, CHEATS } from './Cheats';

const DOMAIN: number = 0x0000;
const TAG: string = 'CheatState';

const STORE: string = 'pokerogue_settings';
const KEY_MASTER: string = 'cheatMaster';
const KEY_IDS: string = 'cheatEnabledIds';
const KEY_PARAMS: string = 'cheatParams';

let masterEnabled: boolean = false;
let enabledIds: string[] = [];
let paramValues: Map<string, string> = new Map();
let revision: number = 0;
let loaded: boolean = false;
let ctxRef: common.UIAbilityContext | null = null;

function idsToString(): string {
  return enabledIds.join(',');
}

function parseIds(raw: string): string[] {
  const out: string[] = [];
  if (raw.length === 0) {
    return out;
  }
  const parts: string[] = raw.split(',');
  for (let i = 0; i < parts.length; i++) {
    const p: string = parts[i].trim();
    if (p.length > 0) {
      out.push(p);
    }
  }
  return out;
}

/** 数值参数持久化格式：id=value,id=value（与 enabledIds 的逗号串风格一致） */
function paramsToString(): string {
  const parts: string[] = [];
  paramValues.forEach((v: string, k: string) => {
    parts.push(k + '=' + v);
  });
  return parts.join(',');
}

function parseParams(raw: string): void {
  paramValues = new Map();
  if (raw.length === 0) {
    return;
  }
  const parts: string[] = raw.split(',');
  for (let i = 0; i < parts.length; i++) {
    const p: string = parts[i];
    const eq: number = p.indexOf('=');
    if (eq > 0) {
      paramValues.set(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
    }
  }
}

function persist(): void {
  const ctx: common.UIAbilityContext | null = ctxRef;
  if (ctx === null) {
    return;
  }
  preferences.getPreferences(ctx, STORE).then((store: preferences.Preferences) => {
    store.put(KEY_MASTER, masterEnabled).then(() => {
      return store.put(KEY_IDS, idsToString());
    }).then(() => {
      return store.put(KEY_PARAMS, paramsToString());
    }).then(() => {
      return store.flush();
    }).catch((err: Error) => {
      hilog.warn(DOMAIN, TAG, 'flush cheat state failed: %{public}s', JSON.stringify(err));
    });
  }).catch((err: Error) => {
    hilog.warn(DOMAIN, TAG, 'getPreferences failed: %{public}s', JSON.stringify(err));
  });
}

/** App 启动时调用：从偏好载入作弊状态到内存单例 */
export async function loadCheatState(ctx: common.UIAbilityContext): Promise<void> {
  ctxRef = ctx;
  try {
    const store: preferences.Preferences = await preferences.getPreferences(ctx, STORE);
    masterEnabled = await store.get(KEY_MASTER, false) as boolean;
    const raw: preferences.ValueType = await store.get(KEY_IDS, '');
    enabledIds = parseIds(raw as string);
    const rawParams: preferences.ValueType = await store.get(KEY_PARAMS, '');
    parseParams(rawParams as string);
  } catch (err) {
    hilog.warn(DOMAIN, TAG, 'loadCheatState failed: %{public}s', JSON.stringify(err));
  }
  loaded = true;
  hilog.info(DOMAIN, TAG, 'cheat state loaded master=%{public}s count=%{public}d',
    masterEnabled.toString(), enabledIds.length);
}

export function isLoaded(): boolean {
  return loaded;
}

export function isMasterEnabled(): boolean {
  return masterEnabled;
}

export function isCheatEnabled(id: string): boolean {
  return enabledIds.indexOf(id) >= 0;
}

/** 总开关（内存 + 持久化） */
export function setMaster(v: boolean): void {
  if (masterEnabled === v) {
    return;
  }
  masterEnabled = v;
  revision++;
  persist();
}

/** 切换单条作弊（内存 + 持久化） */
export function toggleCheat(id: string): void {
  const at: number = enabledIds.indexOf(id);
  if (at >= 0) {
    enabledIds.splice(at, 1);
  } else {
    enabledIds.push(id);
  }
  revision++;
  persist();
}

/** 读取数值参数（未设置返回 fallback） */
export function getParamNumber(id: string, fallback: number): number {
  const raw: string | undefined = paramValues.get(id);
  if (raw === undefined) {
    return fallback;
  }
  const n: number = Number(raw);
  return isNaN(n) ? fallback : n;
}

/** 设置数值参数（内存 + 持久化） */
export function setParam(id: string, value: number): void {
  const next: string = value.toString();
  if (paramValues.get(id) === next) {
    return;
  }
  paramValues.set(id, next);
  revision++;
  persist();
}

/** 配置版本号：变化后游戏页需重载以重新注入 */
export function getRevision(): number {
  return revision;
}

/** 生成加载时注入的作弊脚本（总开关关闭或无条目时返回空串） */
export function buildBootScript(): string {
  if (!masterEnabled || enabledIds.length === 0) {
    return '';
  }
  // 已启用且声明了数值参数的条目 → __CHEAT_PARAMS__ = { id: value }
  const paramParts: string[] = [];
  let js: string = '';
  for (let i = 0; i < CHEATS.length; i++) {
    const c: Cheat = CHEATS[i];
    if (enabledIds.indexOf(c.id) < 0) {
      continue;
    }
    const p: CheatParam | undefined = c.param;
    if (p !== undefined) {
      let n: number = getParamNumber(p.id, p.default);
      if (n < p.min) {
        n = p.min;
      }
      if (n > p.max) {
        n = p.max;
      }
      paramParts.push(JSON.stringify(p.id) + ':' + n);
    }
    if (c.script.length > 0) {
      js += 'try{' + c.script + '}catch(e){console.log("CHEAT err ' + c.id + ' "+e);}';
    }
  }
  if (js.length === 0) {
    return '';
  }
  const paramsJs: string = '{' + paramParts.join(',') + '}';
  return '(function(){var __CHEAT_PARAMS__=' + paramsJs + ';' + js + '})();';
}
