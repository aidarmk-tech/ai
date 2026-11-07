// Observe declared modern-player metadata only in the selected provider iframe.
(function(){
'use strict';
if(!window.__lampMetadataFrame||window.__lampModernInstalled)return;
window.__lampModernInstalled=true;
var parse=JSON.parse,stringify=JSON.stringify;
var stats={apis:[],rows:0,declared:0,pending:0},seen={},fileLists=[],byId={},resolvedIds={},factorySeen=false,activeRestrict=null;
function number(value){var s=String(value==null?'':value);return /^[0-9]{1,4}$/.test(s)&&+s>0?+s:0;}
function media(value){
 if(value&&typeof value==='object')value=value.src||value.url;
 if(typeof value!=='string'||value.length>12000)return '';
 if(/^\s*\{/.test(value)){try{return media(parse(value));}catch(e){return '';}}
 try{var u=new URL(value,location.href);return /^https?:$/.test(u.protocol)&&/\.(?:m3u8|mp4|mkv|m4v|webm|mov|avi)$/i.test(u.pathname)?u.href:'';}catch(e){return '';}
}
function sources(source,restrict){
 if(!source||typeof source!=='object')return '';
 var out=[],used={};
 function add(q,value){var parts=typeof value==='string'?value.split(/\s+(?:or|and)\s+(?=(?:https?:)?\/\/)/):[value];
  if(parts.length>4)return;var urls=parts.map(media);if(urls.some(function(u){return !u;}))return;var url=urls.join(' or ');q=+q||0;
  if(!url||q>10000||used[q])return;
  if(q&&typeof restrict==='function'){try{if(restrict(q))return;}catch(e){return;}}
  used[q]=true;out.push((q?'['+q+'p]':'[Авто]')+url);
 }
 if(source.hls)add(0,source.hls);
 if(source.hlsUrl)add(0,source.hlsUrl);
 if(source.file&&typeof source.file==='object')Object.keys(source.file).slice(0,12).forEach(function(q){if(/^[0-9]{3,4}$/.test(q))add(q,source.file[q]);});
 else if(source.file)add(0,source.file);
 if(Array.isArray(source.sources))source.sources.slice(0,12).forEach(function(s){if(s&&typeof s==='object')add(s.size||s.quality||0,s.src);});
 return out.join(',');
}
function voice(value,fallback){return typeof value==='string'&&value.trim()?value.trim().slice(0,200):fallback||'Основная озвучка';}
function blocked(value){return !value||value.blocked===true||value.ad===true||value.isAd===true||value.type==='ad';}
function emit(rows,api,bound){
 rows=rows.filter(function(row){return row.file;}).slice(0,1000);
 if(!rows.length)return;
 var key=(bound?'bound:':'observed:')+stringify(rows);if(!key||key.length>850000||seen[key])return;seen[key]=true;
 if(stats.apis.indexOf(api)<0&&stats.apis.length<6)stats.apis.push(api);
 stats.rows+=rows.length;
 if(window.__lampRecordModern)window.__lampRecordModern(rows,!!bound);
}
function collect(value,api,bound){
 if(!value||typeof value!=='object'||blocked(value))return;
 var list=value.playlist&&typeof value.playlist==='object'?value.playlist:value;
 if(bound){factorySeen=true;activeRestrict=typeof value.restrictQuality==='function'?value.restrictQuality:null;}
 var rows=[],declared=0,current=list.current||{},restrict=value.restrictQuality||activeRestrict;
 function row(ep,season,epNumber,translation){
  if(blocked(ep))return;declared++;
  var file=sources(ep.source||ep,restrict);if(!file)return;
  rows.push({id:String(ep.id||'').slice(0,160),title:String(ep.title||'').slice(0,200),season:number(ep.season)||season||0,
   episode:number(ep.episode)||epNumber||0,voice:voice(ep.translation||ep.voice,translation),file:file});
 }
 if(Array.isArray(list.seasons)){
  list.seasons.slice(0,60).forEach(function(s){if(blocked(s)||!Array.isArray(s.episodes))return;
   s.episodes.slice(0,1000).forEach(function(e){row(e,number(s.season),0,voice(value.translation||value.voice));});
  });
 }else if(bound&&Array.isArray(list.flat)){
  list.flat.slice(0,1000).forEach(function(e){row(e,0,0,voice(value.translation||value.voice));});
 }else if(bound&&(value.source||value.hls||value.file)){
  row(value,number(current.season),number(current.episode),voice(value.translation||value.voice));
 }
 if(declared){stats.declared=Math.max(stats.declared,declared);stats.pending=Math.max(0,declared-rows.length);}
 emit(rows,api,!!bound||factorySeen);
}
function alloha(value){
 if(!value||typeof value!=='object'||!Object.prototype.hasOwnProperty.call(value,'active')||!value.all||typeof value.all!=='object'||!value.translationList)return;
 var key=stringify(value);if(!key||key.length>850000||fileLists.some(function(e){return e.key===key;}))return;
 if(fileLists.length>=3)return;fileLists.push({key:key,value:value});
 var count=0,rows=[];
 function leaf(m,s,e){
  if(!m||typeof m!=='object'||blocked(m))return;
  count++;var id=String(m.id||'');var v=voice(m.translation);
  if(/^[a-zA-Z0-9_-]{1,128}$/.test(id)){
   var context={season:s,episode:e,voice:v,title:String(m.title||'').slice(0,200)};
   if(!Object.prototype.hasOwnProperty.call(byId,id))byId[id]=context;
   else if(!byId[id]||stringify(byId[id])!==stringify(context))byId[id]=null;
  }
  var file=sources(m.source||m);
  if(file)rows.push({season:s,episode:e,voice:v,title:String(m.title||'').slice(0,200),file:file});
 }
 if(value.type==='serial'){
  Object.keys(value.all).slice(0,60).forEach(function(s){if(!number(s))return;var eps=value.all[s];
   if(!eps||typeof eps!=='object')return;Object.keys(eps).slice(0,1000).forEach(function(e){if(!number(e))return;
    var variants=eps[e];if(!variants||typeof variants!=='object')return;
    Object.keys(variants).slice(0,30).forEach(function(v){leaf(variants[v],number(s),number(e));});
   });
  });
 }else if(value.type==='movie'||value.type==='film'){
  Object.keys(value.all).slice(0,12).forEach(function(t){var variants=value.all[t];if(!variants||typeof variants!=='object')return;
   Object.keys(variants).slice(0,30).forEach(function(v){var qualities=variants[v];if(!qualities||typeof qualities!=='object')return;
    Object.keys(qualities).slice(0,12).forEach(function(q){leaf(qualities[q],0,0);});
   });
  });
 }
 stats.declared=Math.max(stats.declared,count);stats.pending=Math.max(0,stats.declared-rows.length);emit(rows,'Alloha.fileList',true);
}
function hlsReply(value,url){
 if(!value||!Array.isArray(value.hlsSource)||typeof url!=='string')return;
 var match=/\/api\/movies\/([a-zA-Z0-9_-]{1,128})(?:[/?#]|$)/.exec(url),context=match&&byId[match[1]];if(!context)return;
 var source=value.hlsSource.find(function(s){return s&&s.default;})||value.hlsSource[0];
 if(!source||source.blocked===true)return;
 var file=sources({file:source.quality,hls:source.src||source.hls});
 if(file){emit([{title:context.title,season:context.season,episode:context.episode,voice:context.voice,file:file}],'Alloha.hlsSource',true);if(!resolvedIds[match[1]]){resolvedIds[match[1]]=true;stats.pending=Math.max(0,stats.pending-1);}}
}
window.__lampObserveModern=function(value,bound,url){
 try{alloha(value);hlsReply(value,url);collect(value,'Venom.playlist',!!bound);}catch(e){}
};
window.__lampModernStats=function(){return stats;};
function allowed(options){
 if(!options||typeof options!=='object'||options.ad===true||options.isAd===true||options.type==='ad')return false;
 var el=options.container;if(typeof el==='string'){try{el=document.querySelector(el);}catch(e){return false;}}
 if(el&&el.nodeType===1){
  var label=(el.id||'')+' '+(el.getAttribute('data-role')||'');
  if(/(?:^|[ _-])(?:ads?|trailer|preroll)(?:$|[ _-])/i.test(label)||el.closest('[data-ad],.advertisement,[id*="trailer"]'))return false;
 }
 return true;
}
var namespaceValue=null,namespaceProxy=null;
function observed(fn,api,receiver){
 function wrapped(options){if(allowed(options)){
  if(options.blocked===true){if(window.__lampBlockModern)window.__lampBlockModern();}
  else collect(options,api,true);
 }return fn.apply(receiver||this,arguments);}
 try{Object.setPrototypeOf(wrapped,fn);wrapped.prototype=fn.prototype;}catch(e){}
 wrapped.__lampModernWrapped=true;return wrapped;
}
function namespace(value){
 if(!value||(typeof value!=='object'&&typeof value!=='function')||typeof value.make!=='function')return value;
 if(value===namespaceProxy||value===namespaceValue)return namespaceProxy;
 var descriptor=Object.getOwnPropertyDescriptor(value,'make');
 if(descriptor&&!descriptor.configurable&&Object.prototype.hasOwnProperty.call(descriptor,'value')&&!descriptor.writable)return value;
 var wrapped=observed(value.make,'VenomPlayer.make',value);
 namespaceValue=value;namespaceProxy=new Proxy(value,{get:function(target,key){return key==='make'?wrapped:Reflect.get(target,key,target);}});
 return namespaceProxy;
}
function wrapFunction(owner,key,api){
 var fn=owner&&owner[key];if(typeof fn!=='function'||fn.__lampModernWrapped)return;
 try{owner[key]=observed(fn,api);}catch(e){}
}
function install(){wrapFunction(window,'makePlayer','Collaps.makePlayer');if(window.VenomPlayer)wrapFunction(window.VenomPlayer,'make','VenomPlayer.make');}
function watch(key){
 try{var descriptor=Object.getOwnPropertyDescriptor(window,key);if(descriptor&&!descriptor.configurable)return;
 var value=key==='VenomPlayer'?namespace(window[key]):window[key];Object.defineProperty(window,key,{configurable:true,enumerable:true,get:function(){return value;},
 set:function(v){value=key==='VenomPlayer'?namespace(v):v;install();}});}catch(e){}
}
// Preserve values: these assignments precede Collaps's synchronous makePlayer call.
watch('VenomPlayer');watch('makePlayer');watch('middleCount');install();setInterval(install,50);
})();
