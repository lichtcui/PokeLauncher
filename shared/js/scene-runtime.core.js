var __cs=null,__ccb=[];
function __fire(s){__cs=s;try{window.__cheatScene=s;}catch(e){}var a=__ccb;__ccb=[];for(var i=0;i<a.length;i++){try{a[i](s);}catch(e){console.log("CHEAT scene cb "+e);}}}
function __onScene(cb){if(__cs){try{cb(__cs);}catch(e){console.log("CHEAT scene cb "+e);}}else{__ccb.push(cb);}}
function __isBattle(s){try{return !!(s&&typeof s.addMoney==="function"&&typeof s.getPlayerParty==="function"&&s.ui&&s.phaseManager);}catch(e){return false;}}
function __scenes(g){try{if(g&&g.scene&&typeof g.scene.getScenes==="function"){return g.scene.getScenes(false);}if(g&&g.scene&&Array.isArray(g.scene.scenes)){return g.scene.scenes;}}catch(e){}return [];}
function __watch(g){if(!g||!g.scene||g.__cheatWatch){return;}g.__cheatWatch=true;var n=0;var iv=setInterval(function(){if(__cs){clearInterval(iv);return;}var a=__scenes(g);for(var i=0;i<a.length;i++){if(__isBattle(a[i])){clearInterval(iv);console.log("CHEAT scene captured");__fire(a[i]);return;}}if(n===0){console.log("CHEAT watch scenes="+a.length);}if(++n>1200){clearInterval(iv);}},250);}
var __phaseCbs={};
function __onPhase(name,cb){(__phaseCbs[name]=__phaseCbs[name]||[]).push(cb);}
function __installPhaseHook(scene){try{var pm=scene.phaseManager;if(!pm){return;}var proto=Object.getPrototypeOf(pm);if(typeof proto.create!=="function"||proto.__cheatPhaseHook){return;}var orig=proto.create;proto.create=function(name){var inst=orig.apply(this,arguments);var cbs=__phaseCbs[name];if(cbs){for(var i=0;i<cbs.length;i++){try{cbs[i](inst);}catch(e){console.log("CHEAT phase cb "+e);}}}return inst;};proto.__cheatPhaseHook=true;console.log("CHEAT phase hook installed");}catch(e){console.log("CHEAT phase hook err "+e);}}
__onScene(__installPhaseHook);
function __looksScene(x){try{return !!(x&&typeof x==="object"&&x.sys&&x.sys.scene===x&&x.sys.settings&&typeof x.sys.settings.key==="string");}catch(e){return false;}}
var __np=Array.prototype.push;
var __hp=function(){var r=__np.apply(this,arguments);if(!__cs){for(var i=0;i<arguments.length;i++){if(__looksScene(arguments[i])){var s=arguments[i];var n=0;var iv=setInterval(function(){var g=s.sys&&s.sys.game;if(g&&g.scene){clearInterval(iv);if(Array.prototype.push===__hp){Array.prototype.push=__np;}__watch(g);}else if(++n>300){clearInterval(iv);}},60);break;}}}return r;};
Array.prototype.push=__hp;
