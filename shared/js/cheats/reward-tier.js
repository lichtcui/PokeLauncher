__onPhase("SelectModifierPhase", function(phase){
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
});
