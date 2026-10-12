try{
  var _gc=HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext=function(t,a){
    if(t==="2d"){var n={};if(a){for(var k in a){n[k]=a[k];}}n.willReadFrequently=true;return _gc.call(this,t,n);}
    return _gc.apply(this,arguments);
  };
  console.log("PK canvas willReadFrequently patch applied");
}catch(e){console.log("PK canvas patch err "+e)}
