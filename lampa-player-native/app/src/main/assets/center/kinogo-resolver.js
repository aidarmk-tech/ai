// Runs inside the metadata-only WebView. cfg binds this session to one KinoGo card.
(function(){
'use strict';
var cfg=window.__lampKinogoConfig;
if(!cfg)return;
var isRoot=window===window.top;
function candidate(value){
 try{
  var u=new URL(value,location.href);
  if(u.protocol!=='https:'&&u.protocol!=='http:')return null;
  if(!/^\/embed\/movie\/[0-9]+\/?$/.test(u.pathname)&&!/^walking-as\./i.test(u.hostname))return null;
  return u;
 }catch(e){return null;}
}
if(!isRoot){
 setInterval(function(){
  if(window.__lampSnapshot)window.parent.postMessage({lamp:cfg.session,data:window.__lampSnapshot()},cfg.origin);
 },300);
 return;
}
var records={};
function frames(){
 return Array.prototype.slice.call(document.querySelectorAll('iframe')).map(function(el){
  var value=el.getAttribute('src');if(!value||value==='about:blank')value=el.getAttribute('data-src');
  var url=candidate(value);return url?{element:el,url:url}:null;
 }).filter(Boolean).slice(0,2);
}
window.addEventListener('message',function(event){
 if(!event.data||event.data.lamp!==cfg.session||typeof event.data.data!=='string'||event.data.data.length>1000000)return;
 var frame=frames().find(function(f){return f.element.contentWindow===event.source&&f.url.origin===event.origin;});
 if(!frame)return;
 try{
  var data=JSON.parse(event.data.data);
  if(!data||typeof data!=='object'||!Array.isArray(data.files)||!Array.isArray(data.lists))return;
  records[frame.url.href]={url:frame.url.href,snapshot:data};
 }catch(e){}
});
setInterval(function(){
 frames().forEach(function(f){
  var src=f.element.getAttribute('src');
  if(!src||src==='about:blank')f.element.src=f.url.href;
 });
},300);
window.__lampKinogoSnapshot=function(){
 var expected=frames();
 return JSON.stringify({expected:expected.length,candidates:expected.map(function(f){return records[f.url.href];}).filter(Boolean)});
};
})();