__onScene(function(scene){
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
});
