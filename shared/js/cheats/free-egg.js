__onScene(function(scene){
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
});
