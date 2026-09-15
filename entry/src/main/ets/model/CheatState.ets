import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { Cheat, CHEATS } from './Cheats';

const DOMAIN: number = 0x0000;
const TAG: string = 'CheatState';

const STORE: string = 'pokerogue_settings';
const KEY_MASTER: string = 'cheatMaster';
const KEY_IDS: string = 'cheatEnabledIds';

let masterEnabled: boolean = false;
let enabledIds: string[] = [];
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

function persist(): void {
  const ctx: common.UIAbilityContext | null = ctxRef;
  if (ctx === null) {
    return;
  }
  preferences.getPreferences(ctx, STORE).then((store: preferences.Preferences) => {
    store.put(KEY_MASTER, masterEnabled).then(() => {
      return store.put(KEY_IDS, idsToString());
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

/** 配置版本号：变化后游戏页需重载以重新注入 */
export function getRevision(): number {
  return revision;
}

/** 生成加载时注入的作弊脚本（总开关关闭或无条目时返回空串） */
export function buildBootScript(): string {
  if (!masterEnabled || enabledIds.length === 0) {
    return '';
  }
  let js: string = '';
  for (let i = 0; i < CHEATS.length; i++) {
    const c: Cheat = CHEATS[i];
    if (enabledIds.indexOf(c.id) < 0 || c.script.length === 0) {
      continue;
    }
    js += 'try{' + c.script + '}catch(e){console.log("CHEAT err ' + c.id + ' "+e);}';
  }
  if (js.length === 0) {
    return '';
  }
  return '(function(){' + js + '})();';
}
