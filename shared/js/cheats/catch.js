__onPhase("AttemptCapturePhase", function(phase){
  var proto = Object.getPrototypeOf(phase);
  if (proto && typeof proto.failCatch === "function" && !proto.__cheatCatch) {
    proto.failCatch = function(){ return this.catch(); };
    proto.__cheatCatch = true;
    console.log("CHEAT catch 100% live");
  }
});
