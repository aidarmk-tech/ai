(function(){
'use strict';
if((window!==window.top&&!window.__lampMetadataFrame)||window.__lampOnline)return;
HTMLMediaElement.prototype.play=function(){return Promise.reject(new DOMException('Metadata only','NotAllowedError'));};
var state=window.__lampOnline={files:[],lists:[],playerFiles:[],playerLists:[],seen:{},errors:[],cap:480,ids:[],instances:[],xhr:[],shapes:[],jsonXhr:0};
window.__lampBlockModern=function(){state.modernBlocked=true;};
window.__lampRecordModern=function(rows,bound){
 var list=rows.map(function(row){return {title:row.voice||"Основная озвучка",folder:[{id:row.id||"",title:row.title||"",season:row.season||0,episode:row.episode||0,file:row.file}]};});
 remember(list,"list",!!bound);
};
var originalParse=JSON.parse,originalStringify=JSON.stringify;
function remember(value,kind,bound){
 try{
  if(value==null)return;
  var data=originalStringify(value);if(!data||data.length>900000)return;
  var key=(bound?'player:':'')+kind+data;
  if(state.seen[key])return;
  state.seen[key]=1;
  var target=bound?(kind==='file'?state.playerFiles:state.playerLists):(kind==='file'?state.files:state.lists);
  if(target.length<16)target.push(value);
 }catch(e){}
}
function playlist(value,bound,voice,depth){
 if(window.__lampObserveModern)window.__lampObserveModern(value,!!bound);
 depth=depth||0;if(depth>5)return;
 if(typeof value==='string'&&value.length<900000&&/^\s*[\[{]/.test(value)){
  try{playlist(originalParse(value),bound,voice,depth+1);}catch(e){}
  return;
 }
 if(value&&typeof value==='object'&&!Array.isArray(value)){
  if(state.shapes.length<8){var shape=Object.keys(value).slice(0,16).sort().join(',');if(state.shapes.indexOf(shape)<0)state.shapes.push(shape);}
  if(value.file||value.folder)remember(voice?[{title:voice,folder:[value]}]:[value],'list',bound);
  ['data','result','playlist'].forEach(function(key){if(value[key]!=null)playlist(value[key],bound,voice,depth+1);});
  return;
 }
 if(!Array.isArray(value)||!value.length||value.length>1000)return;
 if(value.some(function(v){return v&&typeof v==='object'&&(v.file||v.folder)}))remember(voice?[{title:voice,folder:value}]:value,'list',bound);
}
function playerFile(value,voice){
 if(value==null||value==='')return;
 remember(voice&&!Array.isArray(value)?{title:voice,file:value}:value,'file',true);
 playlist(value,true,voice);
 if(typeof value==='string'&&value.length<900000&&/^\s*\[/.test(value)){try{playlist(originalParse(value),true,voice);}catch(e){}}
}
function activeVoice(){
 try{var label=document.querySelector('.player-main-block .translations li.active span[data-name]');return label?label.getAttribute('data-name')||'':'';}catch(e){return '';}
}
JSON.parse=function(){var result=originalParse.apply(JSON,arguments);playlist(result);return result;};
var open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send;
XMLHttpRequest.prototype.open=function(method,url){this.__lampUrl=String(url);return open.apply(this,arguments);};
XMLHttpRequest.prototype.send=function(){
 this.addEventListener('load',function(){
  try{
   if(this.responseType==='json'){
    state.jsonXhr++;
    if(state.xhr.length<12)state.xhr.push([this.status,-1]);
    playlist(this.response);
    if(window.__lampObserveModern)window.__lampObserveModern(this.response,false,this.__lampUrl);
   }else if(!this.responseType||this.responseType==='text'){
    var text=this.responseText;
    if(state.xhr.length<12)state.xhr.push([this.status,text?text.length:0]);
    if(text&&text.length<900000){var value=originalParse(text);playlist(value);if(window.__lampObserveModern)window.__lampObserveModern(value,false,this.__lampUrl);}
   }
  }catch(e){}
 });
 return send.apply(this,arguments);
};
if(window.fetch){var fetch=window.fetch;window.fetch=function(){return fetch.apply(this,arguments).then(function(response){try{response.clone().text().then(function(text){if(text.length<900000){try{var value=originalParse(text);playlist(value);if(window.__lampObserveModern)window.__lampObserveModern(value,false,response.url);}catch(e){}}}).catch(function(){});}catch(e){}return response;});};}

function mainPlayerId(id){
 if(typeof id!=='string'||/trailer|preroll|(?:^|[_-])ads?(?:$|[_-])/i.test(id))return false;
 if(/^(?:video)?player$/i.test(id))return true;
 if(!window.__lampMetadataFrame)return false;
 if(/^(?:video)?player(?:js|[12]|[-_][a-z0-9_-]{1,160})?$/i.test(id))return true;
 try{return typeof playerConfigs!=='undefined'&&playerConfigs&&String(playerConfigs.id)===id;}catch(e){return false;}
}
function adElement(element){
 for(var el=element;el&&el.nodeType===1;el=el.parentElement){
  var label=(el.id||'')+' '+(typeof el.className==='string'?el.className:'');
  if(/(?:^|[\s_-])(?:ads?|advert|advertisement|preroll|trailer)(?:$|[\s_-])/i.test(label)||el.hasAttribute('data-ad'))return true;
 }
 return false;
}
function validMedia(value){
 if(typeof value!=='string'||value.length>12000)return '';
 try{var u=new URL(value,location.href);return /^https?:$/.test(u.protocol)&&/\.(?:m3u8|mp4|mkv|m4v|webm|mov|avi)$/i.test(u.pathname)?u.href:'';}catch(e){return '';}
}
function knownInstance(instance,id,voice,recovered){
 if(!instance||typeof instance.api!=='function'||!mainPlayerId(id))return null;
 var existing=state.instances.find(function(entry){return entry.player===instance;});
 if(existing){existing.id=existing.id||id;return existing;}
 if(state.instances.length>=4)return null;
 var entry={player:instance,id:id,voice:voice||''};state.instances.push(entry);
 if(state.ids.indexOf(id)<0&&state.ids.length<12)state.ids.push(id);
 if(recovered)state.recovered=(state.recovered||0)+1;
 return entry;
}
function discoverInstance(instance){
 try{
  if(!instance||typeof instance.api!=='function')return;
  if(state.instances.some(function(entry){return entry.player===instance;}))return;
  var id=instance.api('id'),container=typeof id==='string'?document.getElementById(id):null;
  if(!container||adElement(container))return;
  knownInstance(instance,id,'',true);
 }catch(e){}
}
function discoverPlayers(){
 if(!window.__lampMetadataFrame)return;
 // Known PlayerJS bindings include global lexical declarations, not just window properties.
 try{if(typeof player!=='undefined')discoverInstance(player);}catch(e){}
 try{if(typeof videoPlayer!=='undefined')discoverInstance(videoPlayer);}catch(e){}
 try{if(typeof pljssglobal!=='undefined'&&pljssglobal&&typeof pljssglobal==='object'){
  Object.keys(pljssglobal).slice(0,12).forEach(function(key){discoverInstance(pljssglobal[key]);});
 }}catch(e){}
}
function readMedia(entry){
 if(!window.__lampMetadataFrame||!mainPlayerId(entry.id))return;
 var container=document.getElementById(entry.id);if(!container||adElement(container))return;
 try{if(typeof playerConfigs!=='undefined'&&playerConfigs&&String(playerConfigs.id)===entry.id){playerFile(playerConfigs.file,entry.voice);state.configRead=true;}}catch(e){}
 try{
  var hls=entry.player.api('hls'),url=hls&&validMedia(hls.url);
  if(url&&(!hls.media||container.contains(hls.media)&&!adElement(hls.media))){
   playerFile(url,entry.voice);state.hlsRead=true;
  }
 }catch(e){}
 // A direct MP4 is read only from the sole non-advertising video in this bound container.
 try{
  var videos=Array.prototype.slice.call(container.querySelectorAll('video')).filter(function(video){return !adElement(video);});
  if(videos.length===1){
   var direct=validMedia(videos[0].currentSrc||videos[0].getAttribute('src'));
   if(direct){playerFile(direct,entry.voice);state.domRead=true;}
  }
 }catch(e){}
}

function wrapPlayer(){
 var original=window.Playerjs;
 if(typeof original==='function'&&!original.__lampWrapped){
  var wrapped=function(options){
   var id=typeof options==='string'?options:options&&options.id;
   if(state.ids.length<12&&id)state.ids.push(String(id));
   var main=mainPlayerId(id),voice=main?activeVoice():'';
   if(main&&options&&typeof options==='object')playerFile(options.file,voice);
   var instance=Reflect.construct(original,Array.prototype.slice.call(arguments));
   if(main&&instance&&typeof instance.api==='function'){
    knownInstance(instance,id,voice,false);
    var api=instance.api;
    try{instance.api=function(command,value){
     if(arguments.length>1&&/^(file|playlist|preload|play)$/.test(command)){
      playerFile(value,voice);
     }
     return api.apply(instance,arguments);
    };}catch(e){}
   }
   return instance;
  };
  wrapped.prototype=original.prototype;Object.setPrototypeOf(wrapped,original);
  wrapped.__lampWrapped=true;window.Playerjs=wrapped;
 }
 discoverPlayers();
 state.instances.forEach(function(entry){try{playlist(entry.player.api('playlist_folders'),true,entry.voice);}catch(e){}readMedia(entry);});
 if(window.user_data){state.cap=(user_data.is_user_pro_plus||user_data.is_user_loyal)?2160:(user_data.is_user_pro?720:480);}
}
// Catch assignment before the page immediately constructs its first player.
try {
 var playerValue=window.Playerjs;
 Object.defineProperty(window,'Playerjs',{configurable:true,enumerable:true,
  get:function(){return playerValue;},
  set:function(value){playerValue=value;if(typeof value==='function'&&!value.__lampWrapped)wrapPlayer();}
 });
 if(playerValue)wrapPlayer();
}catch(e){}
window.__lampInstallPlayer=wrapPlayer;
setInterval(wrapPlayer,50);
function providerMessage(){
 if(!document.body)return '';
 // Providers can replace the whole player container with an availability notice.
 // Read visible source text while excluding comments and form/template content.
 var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),node,parts=[],count=0;
 while((node=walker.nextNode())&&count++<6000){
  var parent=node.parentElement;
  if(!parent||parent.closest('script,style,noscript,template,textarea,input,[id^="comment"],[class*="comment"]'))continue;
  if(!parent.getClientRects().length||getComputedStyle(parent).visibility==='hidden')continue;
  parts.push(node.nodeValue);
 }
 return parts.join(' ');
}
window.__lampSnapshot=function(){
 wrapPlayer();
 var text=providerMessage();
 var blocked=/(недоступ(?:но|ен)[\s\S]{0,90}(?:стране|регион[ае])|заблокировано[\s\S]{0,90}правообладателя|нет видео файла)/i.exec(text);
 if(window.__lampMetadataFrame&&state.ids.length===0&&/^\s*video\s+not\s+found\s*!?\s*[0-9]*\s*$/i.test(text))blocked=['Источник сообщает, что видео не найдено'];
 // Once a player is observed, unrelated page JSON cannot stand in for its playlist.
 var bound=state.ids.length||state.playerFiles.length||state.playerLists.length;
 var result={files:state.modernBlocked?[]:bound?state.playerFiles:state.files,lists:state.modernBlocked?[]:bound?state.playerLists:state.lists,cap:state.cap,blocked:state.modernBlocked?"Видео заблокировано источником":blocked?blocked[0]:null,playerType:typeof window.Playerjs,ids:state.ids,xhr:state.xhr,shapes:state.shapes,jsonXhr:state.jsonXhr,modern:window.__lampModernStats?window.__lampModernStats():null,recovered:state.recovered||0,hlsRead:!!state.hlsRead,domRead:!!state.domRead,configRead:!!state.configRead};
 var json=originalStringify(result);return json.length<1000000?json:'{}';
};
})();
