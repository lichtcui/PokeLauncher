var tries=0;
function boot(){
var G=window.PokeSaveGuard;
if(!G){if(++tries<40){setTimeout(boot,50);}else{console.log("SAVEGUARD bridge missing");}return;}
if(window.__sgInstalled){return;}window.__sgInstalled=true;
var LS=window.localStorage,PR=Object.getPrototypeOf(LS),t=null,busy=false,dirty=false;
function collect(){var o={},i,k;for(i=0;i<LS.length;i++){k=LS.key(i);if(k){o[k]=LS.getItem(k);}}return o;}
function flush(force){if(busy){return;}if(!force&&!dirty){return;}busy=true;dirty=false;t=null;
try{var s=JSON.stringify(collect());if(s.length>2){G.save(s);}}catch(e){dirty=true;}busy=false;}
function schedule(){dirty=true;if(busy){return;}if(t){clearTimeout(t);}t=setTimeout(function(){flush(false);},2000);}
var _s=PR.setItem,_r=PR.removeItem,_c=PR.clear;
PR.setItem=function(k,v){var x=_s.call(this,k,v);schedule();return x;};
PR.removeItem=function(k){var x=_r.call(this,k);schedule();return x;};
PR.clear=function(){var x=_c.call(this);schedule();return x;};
if(LS.length===0){try{var raw=G.load();if(raw&&raw.length>2){
var o=JSON.parse(raw),n=0,k;
for(k in o){if(Object.prototype.hasOwnProperty.call(o,k)){_s.call(LS,k,o[k]);n++;}}
if(n>0){G.notifyRestored(n);console.log("SAVEGUARD restored "+n+" keys");}}}
catch(e){console.log("SAVEGUARD restore err "+e);}}
window.addEventListener("pagehide",function(){flush(true);});
window.addEventListener("beforeunload",function(){flush(true);});
document.addEventListener("visibilitychange",function(){if(document.hidden){flush(true);}});
setInterval(function(){if(!document.hidden){flush(false);}},60000);
}
boot();
