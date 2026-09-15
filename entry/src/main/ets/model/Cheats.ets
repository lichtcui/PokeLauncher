/**
 * 作弊注册表：条目内容集中在此维护，可无限扩展。
 *
 * script 为「加载时注入」的 JS 片段，会在游戏脚本运行前由
 * LocalHttpServer 的 /__cheats__.js 动态下发，用于在启动前改变游戏逻辑。
 *
 * 数值型条目可声明 param，脚本里通过 __CHEAT_PARAMS__.<param.id> 读取当前值。
 *
 * ⚠️ 风险：PokéRogue 官方对作弊有检测机制，Hook 改数据可能被标记/封禁。
 * 开启作弊后的存档不纯净，不建议再导入在线版。
 */
export interface CheatParam {
  id: string;
  label: string;
  min: number;
  max: number;
  default: number;
  step?: number;
  unit?: string;
}

export interface Cheat {
  id: string;
  name: string;
  desc: string;
  script: string;
  param?: CheatParam;
}

export const CHEATS: Cheat[] = [
  {
    id: 'candy',
    name: '糖果获取倍率',
    desc: '捕获/孵化/Boss 应得的糖果 ×N（增量倍率，花费糖果不受影响）',
    param: {
      id: 'candy',
      label: '倍率',
      min: 1,
      max: 100,
      default: 10,
      step: 1,
      unit: 'x'
    },
    script: `(function(){
  var P = (typeof __CHEAT_PARAMS__ !== "undefined" && __CHEAT_PARAMS__) ? __CHEAT_PARAMS__ : {};
  var N = Number(P.candy || 10);
  if (!(N > 1)) { console.log("CHEAT candy disabled N=" + N); return; }
  var CAP = 99;
  var KEY = /^data_/;
  var base = null;
  var uriEnc = false;
  function dec(v){
    var s = atob(v);
    if (s.substring(0,3).toUpperCase() === "%7B") { uriEnc = true; return JSON.parse(decodeURIComponent(s)); }
    uriEnc = false; return JSON.parse(s);
  }
  function enc(o){
    var s = JSON.stringify(o);
    return uriEnc ? btoa(encodeURIComponent(s)) : btoa(s);
  }
  function snap(sd){ var b = {}; for (var id in sd.starterData) { b[id] = sd.starterData[id].candyCount || 0; } return b; }
  var gi = Storage.prototype.getItem;
  Storage.prototype.getItem = function(k){
    var r = gi.apply(this, arguments);
    if (KEY.test(k) && typeof r === "string") {
      try { var sd = dec(r); if (sd && sd.starterData) { base = snap(sd); console.log("CHEAT candy baseline " + Object.keys(base).length); } } catch(e){}
    }
    return r;
  };
  var si = Storage.prototype.setItem;
  Storage.prototype.setItem = function(k, v){
    if (KEY.test(k) && typeof v === "string") {
      try {
        var sd = dec(v);
        if (sd && sd.starterData) {
          if (base === null) { base = snap(sd); }
          else {
            for (var id in sd.starterData) {
              var cur = sd.starterData[id].candyCount || 0;
              var b0 = base[id] || 0;
              var d = cur - b0;
              if (d > 0) { var nv = Math.min(b0 + d * N, CAP); sd.starterData[id].candyCount = nv; base[id] = nv; }
              else { base[id] = cur; }
            }
            v = enc(sd);
          }
        }
      } catch(e){ console.log("CHEAT candy err " + e); }
    }
    return si.call(this, k, v);
  };
  console.log("CHEAT candy x" + N + " installed");
})();`
  },
  {
    id: 'party-shiny',
    name: '队伍红闪',
    desc: '存档时把当前队伍全部标为红闪（shiny + variant 2），读档后生效',
    script: `(function(){
  var KEY = /^sessionData/;
  function dec(v){
    var s = atob(v);
    if (s.substring(0,3).toUpperCase() === "%7B") return JSON.parse(decodeURIComponent(s));
    return JSON.parse(s);
  }
  function enc(o){ return btoa(encodeURIComponent(JSON.stringify(o))); }
  var si = Storage.prototype.setItem;
  Storage.prototype.setItem = function(k, v){
    if (KEY.test(k) && typeof v === "string") {
      try {
        var sd = dec(v);
        if (sd && Array.isArray(sd.party)) {
          for (var i = 0; i < sd.party.length; i++) {
            var p = sd.party[i];
            if (p) {
              p.shiny = true;
              p.variant = 2;
              p.luck = 3;
              if (p.fusionSpecies !== undefined && p.fusionSpecies !== null) {
                p.fusionShiny = true;
                p.fusionVariant = 2;
                p.fusionLuck = 3;
              }
            }
          }
          v = enc(sd);
        }
      } catch(e){ console.log("CHEAT shiny err " + e); }
    }
    return si.call(this, k, v);
  };
  console.log("CHEAT party-shiny installed");
})();`
  },
  {
    id: 'voucher',
    name: '扭蛋券数量',
    desc: '存档时把四种扭蛋券数量设为指定值（读档后生效）',
    param: {
      id: 'voucher',
      label: '数量',
      min: 0,
      max: 99,
      default: 50,
      step: 1
    },
    script: `(function(){
  var P = (typeof __CHEAT_PARAMS__ !== "undefined" && __CHEAT_PARAMS__) ? __CHEAT_PARAMS__ : {};
  var N = Math.floor(Number(P.voucher));
  if (isNaN(N)) { N = 50; }
  if (N < 0) { N = 0; }
  if (N > 99) { N = 99; }
  var KEY = /^data_/;
  function dec(v){
    var s = atob(v);
    if (s.substring(0,3).toUpperCase() === "%7B") return JSON.parse(decodeURIComponent(s));
    return JSON.parse(s);
  }
  function enc(o){ return btoa(encodeURIComponent(JSON.stringify(o))); }
  var si = Storage.prototype.setItem;
  Storage.prototype.setItem = function(k, v){
    if (KEY.test(k) && typeof v === "string") {
      try {
        var sd = dec(v);
        if (sd && sd.voucherCounts) {
          for (var t in sd.voucherCounts) { sd.voucherCounts[t] = N; }
          v = enc(sd);
        }
      } catch(e){ console.log("CHEAT voucher err " + e); }
    }
    return si.call(this, k, v);
  };
  console.log("CHEAT voucher installed N=" + N);
})();`
  }
];
