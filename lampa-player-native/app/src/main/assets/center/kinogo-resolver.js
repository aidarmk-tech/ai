// Runs inside the metadata-only WebView. cfg binds this session to one KinoGo card.
(function(){
'use strict';
var cfg=window.__lampKinogoConfig;
if(!cfg)return;
var isRoot=window===window.top;
if(!isRoot){
 setInterval(function(){
  if(window.__lampSnapshot)window.parent.postMessage({lamp:cfg.session,frameUrl:location.href,data:window.__lampSnapshot()},cfg.origin);
 },300);
 return;
}
var records={},alternate=false,alternateBaseline=[];
var controls=window.__lampKinogoControls;
function frames(){return controls?controls.frames():[];}
window.__lampKinogoTryAlternate=function(){
 if(alternate)return true;
 alternateBaseline=frames().map(function(f){return f.url.href;});
 if(controls&&controls.select(1)){alternate=true;return true;}
 return false;
};
window.addEventListener('message',function(event){
 if(!event.data||event.data.lamp!==cfg.session||typeof event.data.data!=='string'||event.data.data.length>1000000)return;
 var frame=frames().find(function(f){return f.element.contentWindow===event.source;});
 var actual;
 try{
  actual=new URL(event.data.frameUrl);
  if((actual.protocol!=='https:'&&actual.protocol!=='http:')||actual.origin!==event.origin)return;
 }catch(e){return;}
 if(!frame)return;
 try{
  var data=JSON.parse(event.data.data);
  if(!data||typeof data!=='object'||!Array.isArray(data.files)||!Array.isArray(data.lists))return;
  records[frame.url.href]={url:actual.href,snapshot:data};
 }catch(e){}
});
setInterval(function(){
 frames().forEach(function(f){
  var src=f.element.getAttribute('src');
  if(!src||src==='about:blank'){
   var holder=f.element.parentElement;
   for(var n=0;holder&&holder!==document.body&&n<3;n++,holder=holder.parentElement){
    if(getComputedStyle(holder).display==='none')holder.style.display='block';
   }
   f.element.style.minHeight='360px';
   f.element.src=f.url.href;
  }
 });
},300);
window.__lampKinogoSnapshot=function(){
 var expected=frames();
 return JSON.stringify({expected:expected.length,alternateAvailable:!!(controls&&controls.has(1)),alternate:alternate,alternatePending:alternate&&!expected.some(function(f){return /^walking-as\./i.test(f.url.hostname)||alternateBaseline.indexOf(f.url.href)<0;}),candidates:expected.map(function(f){return records[f.url.href];}).filter(Boolean)});
};
})();