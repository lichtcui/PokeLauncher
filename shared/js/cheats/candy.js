__onScene(function(scene){
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
});
