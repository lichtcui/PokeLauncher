__onScene(function(scene){
  var N = Number(__CHEAT_PARAMS__.money || 10);
  if (!(N > 1)) { console.log("CHEAT money off"); return; }
  var proto = Object.getPrototypeOf(scene);
  if (proto && typeof proto.addMoney === "function" && !proto.__cheatMoney) {
    var orig = proto.addMoney;
    proto.addMoney = function(amount){ return orig.call(this, amount * N); };
    proto.__cheatMoney = true;
    console.log("CHEAT money live x" + N);
  }
});
