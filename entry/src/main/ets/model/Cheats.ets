/**
 * 作弊注册表：条目内容集中在此维护，可无限扩展。
 *
 * 所有条目均为「实时作弊」：注入脚本在游戏启动前捕获运行中的 Phaser 场景
 * （BattleScene），然后 hook 游戏方法或直接修改内存数据，开启后即时生效、无需重启。
 *
 * 数值型条目可声明 param，脚本里通过 __CHEAT_PARAMS__.<param.id> 读取当前值。
 * 实时实现依赖游戏内部方法名，游戏更新后可能失效（详见 README「作弊原理」）。
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
  /** 可选：把数值映射为文字显示（如奖励档位 0=Common … 4=Master） */
  labels?: string[];
}

/** 条目分组：multiplier=倍率修改；other=其他（直接改数据） */
export type CheatGroup = 'multiplier' | 'other';

export interface CheatGroupInfo {
  id: CheatGroup;
  title: string;
  subtitle: string;
}

/** 分组顺序即作弊页的展示顺序 */
export const CHEAT_GROUPS: CheatGroupInfo[] = [
  {
    id: 'multiplier',
    title: '倍率修改',
    subtitle: '放大游戏内收益（实时生效）'
  },
  {
    id: 'other',
    title: '其他',
    subtitle: '直接修改游戏内数据（实时生效）'
  }
];

export interface Cheat {
  id: string;
  name: string;
  desc: string;
  group: CheatGroup;
  script: string;
  param?: CheatParam;
}

export const CHEATS: Cheat[] = [
  {
    id: 'candy',
    name: '糖果获取倍率',
    desc: '捕获 / 孵化 / Boss 获得的糖果 ×N（消耗糖果不受影响）',
    group: 'multiplier',
    param: {
      id: 'candy',
      label: '倍率',
      min: 1,
      max: 100,
      default: 10,
      step: 1,
      unit: 'x'
    },
    script: `__onScene(function(scene){
  var N = Number(__CHEAT_PARAMS__.candy || 10);
  if (!(N > 1)) { console.log("CHEAT candy off"); return; }
  var gd = scene.gameData;
  if (!gd) { return; }
  var proto = Object.getPrototypeOf(gd);
  if (proto && typeof proto.addStarterCandy === "function" && !proto.__cheatCandy) {
    var orig = proto.addStarterCandy;
    proto.addStarterCandy = function(a, b){ return orig.call(this, a, (typeof b === "number" ? b : 1) * N); };
    proto.__cheatCandy = true;
    console.log("CHEAT candy live x" + N);
  }
});`
  },
  {
    id: 'exp',
    name: '经验获取倍率',
    desc: '战斗获得的经验 ×N（实时，升级立即结算）',
    group: 'multiplier',
    param: {
      id: 'exp',
      label: '倍率',
      min: 1,
      max: 100,
      default: 10,
      step: 1,
      unit: 'x'
    },
    script: `__onScene(function(scene){
  var N = Number(__CHEAT_PARAMS__.exp || 10);
  if (!(N > 1)) { console.log("CHEAT exp off"); return; }
  var tries = 0;
  function hook(){
    if (tries++ > 600) { return; }
    var party = scene.getPlayerParty ? scene.getPlayerParty() : null;
    var p = party && party[0];
    if (!p) { setTimeout(hook, 500); return; }
    var proto = Object.getPrototypeOf(p);
    if (proto && typeof proto.addExp === "function" && !proto.__cheatExp) {
      var orig = proto.addExp;
      proto.addExp = function(exp, ign){ return orig.call(this, exp * N, ign); };
      proto.__cheatExp = true;
      console.log("CHEAT exp live x" + N);
    }
  }
  hook();
});`
  },
  {
    id: 'money',
    name: '金币获取倍率',
    desc: '战斗获得的金币 ×N（消费金币不受影响）',
    group: 'multiplier',
    param: {
      id: 'money',
      label: '倍率',
      min: 1,
      max: 100,
      default: 10,
      step: 1,
      unit: 'x'
    },
    script: `__onScene(function(scene){
  var N = Number(__CHEAT_PARAMS__.money || 10);
  if (!(N > 1)) { console.log("CHEAT money off"); return; }
  var proto = Object.getPrototypeOf(scene);
  if (proto && typeof proto.addMoney === "function" && !proto.__cheatMoney) {
    var orig = proto.addMoney;
    proto.addMoney = function(amount){ return orig.call(this, amount * N); };
    proto.__cheatMoney = true;
    console.log("CHEAT money live x" + N);
  }
});`
  },
  {
    id: 'luck',
    name: '幸运值拉满',
    desc: '全队幸运值拉满（SSS），提升战后奖励升级与稀有遭遇',
    group: 'other',
    script: `__onScene(function(scene){
  function apply(){
    var party = scene.getPlayerParty ? scene.getPlayerParty() : null;
    if (!party) { return; }
    for (var i = 0; i < party.length; i++) {
      var p = party[i];
      if (!p) { continue; }
      p.luck = 14;
      if (p.fusionSpecies) { p.fusionLuck = 14; }
    }
  }
  apply();
  var proto = Object.getPrototypeOf(scene);
  if (proto && typeof proto.addPlayerPokemon === "function" && !proto.__cheatLuck) {
    var orig = proto.addPlayerPokemon;
    proto.addPlayerPokemon = function(){
      var r = orig.apply(this, arguments);
      try {
        if (r) { r.luck = 14; if (r.fusionSpecies) { r.fusionLuck = 14; } }
        apply();
      } catch(e){}
      return r;
    };
    proto.__cheatLuck = true;
    console.log("CHEAT luck SSS live");
  }
});`
  },
  {
    id: 'catch',
    name: '100% 捕获率',
    desc: '投球必定捕获野生宝可梦（训练师战无法投球，不受影响）',
    group: 'other',
    script: `__onPhase("AttemptCapturePhase", function(phase){
  var proto = Object.getPrototypeOf(phase);
  if (proto && typeof proto.failCatch === "function" && !proto.__cheatCatch) {
    proto.failCatch = function(){ return this.catch(); };
    proto.__cheatCatch = true;
    console.log("CHEAT catch 100% live");
  }
});`
  },
  {
    id: 'free-egg',
    name: '免费抽蛋',
    desc: '抽蛋不消耗扭蛋券；券数不足时会保底显示为 25（仅显示、不扣减）',
    group: 'other',
    script: `__onScene(function(scene){
  var gd = scene.gameData;
  if (gd && gd.voucherCounts) {
    for (var t in gd.voucherCounts) { if (gd.voucherCounts[t] < 25) { gd.voucherCounts[t] = 25; } }
  }
  var handlers = (scene.ui && Array.isArray(scene.ui.handlers)) ? scene.ui.handlers : [];
  for (var i = 0; i < handlers.length; i++) {
    var h = handlers[i];
    if (h && typeof h.pull === "function" && typeof h.setGachaCursor === "function" && ("gachaCursor" in h)) {
      var proto = Object.getPrototypeOf(h);
      if (typeof proto.consumeVouchers === "function" && !proto.__cheatFreeEgg) {
        proto.consumeVouchers = function(){};
        proto.__cheatFreeEgg = true;
        console.log("CHEAT free eggs live");
      }
      break;
    }
  }
});`
  },
  {
    id: 'reward-tier',
    name: '强制奖励稀有度',
    desc: '战后奖励固定为指定稀有度（重掷仍保持）',
    group: 'other',
    param: {
      id: 'rewardTier',
      label: '档位',
      min: 0,
      max: 4,
      default: 4,
      step: 1,
      labels: ['Common', 'Great', 'Ultra', 'Rogue', 'Master']
    },
    script: `__onPhase("SelectModifierPhase", function(phase){
  var proto = Object.getPrototypeOf(phase);
  if (!proto || typeof proto.getModifierTypeOptions !== "function" || proto.__cheatReward) { return; }
  var TIER = Math.floor(Number(__CHEAT_PARAMS__.rewardTier));
  if (isNaN(TIER)) { TIER = 4; }
  if (TIER < 0) { TIER = 0; }
  if (TIER > 4) { TIER = 4; }
  var orig = proto.getModifierTypeOptions;
  proto.getModifierTypeOptions = function(count){
    var scene = window.__cheatScene;
    var savedTiers = this.modifierTiers;
    var savedLock = scene ? scene.lockModifierTiers : false;
    var arr = [];
    for (var i = 0; i < count; i++) { arr.push(TIER); }
    this.modifierTiers = arr;
    if (scene) { scene.lockModifierTiers = true; }
    var r;
    try { r = orig.call(this, count); }
    finally {
      this.modifierTiers = savedTiers;
      if (scene) { scene.lockModifierTiers = savedLock; }
    }
    return r;
  };
  proto.__cheatReward = true;
  console.log("CHEAT reward tier " + TIER + " live");
});`
  }
];
