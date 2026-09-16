import { common } from '@kit.AbilityKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0000;
const TAG: string = 'SaveGuard';

/**
 * 存档快照：把游戏 Web 的 localStorage 镜像一份到 filesDir。
 *
 * 背景：游戏存档存在 Web 的 localStorage，物理位置是 `<沙箱>/cache/web/`。
 * 一旦「清除缓存」（系统设置或 `bm clean -c`）把它清掉，存档就没了，
 * 而这不是用户主动删除数据的预期后果。
 *
 * filesDir 不受清除缓存影响，所以在这里留一份快照；进入游戏页时若发现
 * localStorage 为空，就从快照回填。快照只做兜底，不覆盖任何数据。
 *
 * 覆盖不到的场景（见 README「存档导入 / 导出」）：卸载重装、`bm clean -d` 清数据。
 * 那两种情况下整个沙箱都会被删，只能用游戏内「导出存档」落到公共目录兜底。
 */
const SNAPSHOT_DIR: string = 'saves';
const SNAPSHOT_FILE: string = 'localstorage.json';
const SNAPSHOT_BAK: string = 'localstorage.bak.json';

/** 模块级上下文，与 CheatState / LocalHttpServer 一致，由 EntryAbility 注入 */
let filesDir: string = '';

export function initSaveGuard(ctx: common.UIAbilityContext): void {
  filesDir = ctx.filesDir;
  hilog.info(DOMAIN, TAG, 'init, snapshot dir = %{public}s', dirOf());
}

function dirOf(): string {
  return filesDir + '/' + SNAPSHOT_DIR;
}

function pathOf(): string {
  return dirOf() + '/' + SNAPSHOT_FILE;
}

function bakPathOf(): string {
  return dirOf() + '/' + SNAPSHOT_BAK;
}

function writeText(path: string, data: string): void {
  const fd: fs.File = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
  try {
    fs.writeSync(fd.fd, data);
  } finally {
    fs.closeSync(fd);
  }
}

function readText(path: string): string {
  try {
    if (!fs.accessSync(path)) {
      return '';
    }
    return fs.readTextSync(path);
  } catch (err) {
    hilog.warn(DOMAIN, TAG, 'readText %{public}s failed: %{public}s', path, JSON.stringify(err));
    return '';
  }
}

/**
 * 写入快照。先把上一份滚到 .bak，再写主文件：
 * 写主文件途中被杀也能从 .bak 恢复，避免快照本身变成唯一的坏副本。
 * @returns 是否真的写了（空内容不写，保留上一份）
 */
export function writeSnapshot(data: string): boolean {
  if (filesDir.length === 0) {
    hilog.warn(DOMAIN, TAG, '%{public}s', 'not initialized, skip');
    return false;
  }
  if (data.length <= 2) {
    // "{}" 或空内容：没有可备份的键，保留上一份快照不动
    return false;
  }
  try {
    const dir: string = dirOf();
    if (!fs.accessSync(dir)) {
      fs.mkdirSync(dir, true);
    }
    const main: string = pathOf();
    const previous: string = readText(main);
    if (previous.length > 2) {
      writeText(bakPathOf(), previous);
    }
    writeText(main, data);
    return true;
  } catch (err) {
    hilog.error(DOMAIN, TAG, 'writeSnapshot failed: %{public}s', JSON.stringify(err));
    return false;
  }
}

/** 读取快照；主文件不可用时退回 .bak */
export function readSnapshot(): string {
  const main: string = readText(pathOf());
  if (main.length > 2) {
    return main;
  }
  return readText(bakPathOf());
}

/** 快照是否存在 */
export function hasSnapshot(): boolean {
  return readSnapshot().length > 2;
}

/**
 * 注入到游戏页的存档守卫脚本。
 *
 * 执行时机在 `<head>` 内、游戏脚本之前，因此能保证「先回填再被游戏读取」。
 * 与原生侧通过 `javaScriptProxy` 暴露的 `PokeSaveGuard` 通信。
 */
export function buildSaveGuardScript(): string {
  const js: string =
    // 桥可能比 head 脚本晚一步就绪：轮询等待，避免守卫静默不生效（数据安全功能不能有静默失败）
    'var tries=0;' +
    'function boot(){' +
    'var G=window.PokeSaveGuard;' +
    'if(!G){if(++tries<40){setTimeout(boot,50);}else{console.log("SAVEGUARD bridge missing");}return;}' +
    'if(window.__sgInstalled){return;}window.__sgInstalled=true;' +
    'var LS=window.localStorage,PR=Object.getPrototypeOf(LS),t=null,busy=false;' +
    'function collect(){var o={},i,k;for(i=0;i<LS.length;i++){k=LS.key(i);if(k){o[k]=LS.getItem(k);}}return o;}' +
    'function flush(){if(busy){return;}busy=true;t=null;' +
    'try{var s=JSON.stringify(collect());if(s.length>2){G.save(s);}}catch(e){}busy=false;}' +
    'function schedule(){if(busy){return;}if(t){clearTimeout(t);}t=setTimeout(flush,2000);}' +
    'var _s=PR.setItem,_r=PR.removeItem,_c=PR.clear;' +
    'PR.setItem=function(k,v){var x=_s.call(this,k,v);schedule();return x;};' +
    'PR.removeItem=function(k){var x=_r.call(this,k);schedule();return x;};' +
    'PR.clear=function(){var x=_c.call(this);schedule();return x;};' +
    // 只在 localStorage 被整体清空时回填：避免把用户主动删掉的存档又复活
    'if(LS.length===0){try{var raw=G.load();if(raw&&raw.length>2){' +
    'var o=JSON.parse(raw),n=0,k;' +
    'for(k in o){if(Object.prototype.hasOwnProperty.call(o,k)){_s.call(LS,k,o[k]);n++;}}' +
    'if(n>0){G.notifyRestored(n);console.log("SAVEGUARD restored "+n+" keys");}}}' +
    'catch(e){console.log("SAVEGUARD restore err "+e);}}' +
    'window.addEventListener("pagehide",flush);' +
    'window.addEventListener("beforeunload",flush);' +
    'document.addEventListener("visibilitychange",function(){if(document.hidden){flush();}});' +
    'setInterval(function(){if(!document.hidden){flush();}},60000);' +
    '}' +
    'boot();';

  return '<script>(function(){try{' + js +
    '}catch(e){console.log("SAVEGUARD err "+e);}})();<\/script>';
}

/**
 * 暴露给注入脚本的原生对象（`javaScriptProxy` 的 object）。
 * 方法名与 `buildSaveGuardScript()` 中的调用一一对应。
 */
export class SaveGuardBridge {
  private restoredCount: number = 0;

  /** JS 侧读取快照（localStorage 为空时用于回填） */
  load(): string {
    const data: string = readSnapshot();
    hilog.info(DOMAIN, TAG, 'load snapshot: %{public}d bytes', data.length);
    return data;
  }

  /** JS 侧提交快照 */
  save(data: string): void {
    if (writeSnapshot(data)) {
      hilog.info(DOMAIN, TAG, 'snapshot saved: %{public}d bytes', data.length);
    }
  }

  /** JS 侧回填成功后回报，供页面提示用户 */
  notifyRestored(count: number): void {
    this.restoredCount = count;
    hilog.info(DOMAIN, TAG, 'restored %{public}d keys from snapshot', count);
  }

  /** 本次进入游戏页是否发生过回填（0 表示没有） */
  getRestoredCount(): number {
    return this.restoredCount;
  }
}
