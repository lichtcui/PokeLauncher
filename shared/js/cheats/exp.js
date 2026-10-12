__onScene(function(scene){
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
});
