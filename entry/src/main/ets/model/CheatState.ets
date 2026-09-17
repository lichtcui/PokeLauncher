import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { Cheat, CheatParam, CHEATS } from './Cheats';
import { getStore, SETTINGS_STORE } from '../common/Prefs';

const DOMAIN: number = 0x0000;
const TAG: string = 'CheatState';

const KEY_MASTER: string = 'cheatMaster';
const KEY_IDS: string = 'cheatEnabledIds';
const KEY_PARAMS: string = 'cheatParams';

let masterEnabled: boolean = false;
let enabledIds: string[] = [];
let paramValues: Map<string, string> = new Map();
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
  getStore(ctx, SETTINGS_STORE).then((store: preferences.Preferences) => {
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
    const store: preferences.Preferences = await getStore(ctx, SETTINGS_STORE);
    masterEnabled = await store.get(KEY_MASTER, false) as boolean;
    const raw: preferences.ValueType = await store.get(KEY_IDS, '');
    enabledIds = parseIds(raw as string);
    const rawParams: preferences.ValueType = await store.get(KEY_PARAMS, '');
    parseParams(rawParams as string);
  } catch (err) {
    hilog.warn(DOMAIN, TAG, 'loadCheatState failed: %{public}s', JSON.stringify(err));
  }
  hilog.info(DOMAIN, TAG, 'cheat state loaded master=%{public}s count=%{public}d',
    masterEnabled.toString(), enabledIds.length);
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

/** 仅更新内存中的数值参数（拖动中高频调用，不落盘） */
export function setParamLive(id: string, value: number): void {
  const next: string = value.toString();
  if (paramValues.get(id) !== next) {
    paramValues.set(id, next);
  }
}

/** 设置数值参数（内存 + 立即持久化） */
export function setParam(id: string, value: number): void {
  setParamLive(id, value);
  persist();
}

/**
 * 共享「场景捕获」运行时：在游戏启动前 hook Array.prototype.push 捕获 Phaser 场景，
 * 用鸭子类型找到 BattleScene（有 addMoney/getPlayerParty/ui/phaseManager），
 * 之后回调所有 __onScene 注册的函数，并卸载 push 钩子。
 * 依赖游戏内部方法名，游戏更新后可能失效。
 */
const SCENE_RUNTIME: string = `
var __cs=null,__ccb=[];
function __fire(s){__cs=s;try{window.__cheatScene=s;}catch(e){}var a=__ccb;__ccb=[];for(var i=0;i<a.length;i++){try{a[i](s);}catch(e){console.log("CHEAT scene cb "+e);}}}
function __onScene(cb){if(__cs){try{cb(__cs);}catch(e){console.log("CHEAT scene cb "+e);}}else{__ccb.push(cb);}}
function __isBattle(s){try{return !!(s&&typeof s.addMoney==="function"&&typeof s.getPlayerParty==="function"&&s.ui&&s.phaseManager);}catch(e){return false;}}
function __scenes(g){try{if(g&&g.scene&&typeof g.scene.getScenes==="function"){return g.scene.getScenes(false);}if(g&&g.scene&&Array.isArray(g.scene.scenes)){return g.scene.scenes;}}catch(e){}return [];}
function __watch(g){if(!g||!g.scene||g.__cheatWatch){return;}g.__cheatWatch=true;var n=0;var iv=setInterval(function(){if(__cs){clearInterval(iv);return;}var a=__scenes(g);for(var i=0;i<a.length;i++){if(__isBattle(a[i])){clearInterval(iv);console.log("CHEAT scene captured");__fire(a[i]);return;}}if(n===0){console.log("CHEAT watch scenes="+a.length);}if(++n>1200){clearInterval(iv);}},250);}
var __phaseCbs={};
function __onPhase(name,cb){(__phaseCbs[name]=__phaseCbs[name]||[]).push(cb);}
function __installPhaseHook(scene){try{var pm=scene.phaseManager;if(!pm){return;}var proto=Object.getPrototypeOf(pm);if(typeof proto.create!=="function"||proto.__cheatPhaseHook){return;}var orig=proto.create;proto.create=function(name){var inst=orig.apply(this,arguments);var cbs=__phaseCbs[name];if(cbs){for(var i=0;i<cbs.length;i++){try{cbs[i](inst);}catch(e){console.log("CHEAT phase cb "+e);}}}return inst;};proto.__cheatPhaseHook=true;console.log("CHEAT phase hook installed");}catch(e){console.log("CHEAT phase hook err "+e);}}
__onScene(__installPhaseHook);
function __looksScene(x){try{return !!(x&&typeof x==="object"&&x.sys&&x.sys.scene===x&&x.sys.settings&&typeof x.sys.settings.key==="string");}catch(e){return false;}}
var __np=Array.prototype.push;
var __hp=function(){var r=__np.apply(this,arguments);if(!__cs){for(var i=0;i<arguments.length;i++){if(__looksScene(arguments[i])){var s=arguments[i];var n=0;var iv=setInterval(function(){var g=s.sys&&s.sys.game;if(g&&g.scene){clearInterval(iv);if(Array.prototype.push===__hp){Array.prototype.push=__np;}__watch(g);}else if(++n>300){clearInterval(iv);}},60);break;}}}return r;};
Array.prototype.push=__hp;
(function(){try{var _P;Object.defineProperty(window,"Phaser",{configurable:true,get:function(){return _P;},set:function(v){_P=v;try{if(v&&v.Game&&!v.Game.__cheatWrapped){var OG=v.Game;var WG=function(){var inst=Reflect.construct(OG,arguments,new.target||OG);try{console.log("CHEAT game constructed");__watch(inst);}catch(e){}return inst;};WG.prototype=OG.prototype;WG.__cheatWrapped=true;v.Game=WG;console.log("CHEAT phaser Game wrapped");}}catch(e){console.log("CHEAT phaser patch err "+e);}}});}catch(e){console.log("CHEAT defineProperty err "+e);}})();
`;

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
  return '(function(){var __CHEAT_PARAMS__=' + paramsJs + ';' + SCENE_RUNTIME + js + '})();';
}
