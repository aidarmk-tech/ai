(function(){
'use strict';
if((window!==window.top&&!window.__lampMetadataFrame)||window.__lampOnline)return;
HTMLMediaElement.prototype.play=function(){return Promise.reject(new DOMException('Metadata only','NotAllowedError'));};
var state=window.__lampOnline={files:[],lists:[],playerFiles:[],playerLists:[],seen:{},errors:[],cap:480,ids:[],instances:[],xhr:[],shapes:[],jsonXhr:0};
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
   }else if(!this.responseType||this.responseType==='text'){
    var text=this.responseText;
    if(state.xhr.length<12)state.xhr.push([this.status,text?text.length:0]);
    if(text&&text.length<900000)playlist(originalParse(text));
   }
  }catch(e){}
 });
 return send.apply(this,arguments);
};
if(window.fetch){var fetch=window.fetch;window.fetch=function(){return fetch.apply(this,arguments).then(function(response){try{response.clone().text().then(function(text){if(text.length<900000){try{playlist(originalParse(text));}catch(e){}}}).catch(function(){});}catch(e){}return response;});};}
function wrapPlayer(){
 var original=window.Playerjs;
 if(typeof original==='function'&&!original.__lampWrapped){
  var wrapped=function(options){
   var id=typeof options==='string'?options:options&&options.id;
   if(state.ids.length<12&&id)state.ids.push(String(id));
   var main=typeof id==='string'&&(/^(?:video)?player$/i.test(id)||(window.__lampMetadataFrame&&/^player(?:js|[12])?$/i.test(id))),voice=main?activeVoice():'';
   if(main&&options&&typeof options==='object')playerFile(options.file,voice);
   var instance=Reflect.construct(original,Array.prototype.slice.call(arguments));
   if(main&&instance&&typeof instance.api==='function'){
    if(state.instances.length<4)state.instances.push({player:instance,voice:voice});
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
 state.instances.forEach(function(entry){try{playlist(entry.player.api('playlist_folders'),true,entry.voice);}catch(e){}});
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
 // Once a player is observed, unrelated page JSON cannot stand in for its playlist.
 var bound=state.ids.length||state.playerFiles.length||state.playerLists.length;
 var result={files:bound?state.playerFiles:state.files,lists:bound?state.playerLists:state.lists,cap:state.cap,blocked:blocked?blocked[0]:null,playerType:typeof window.Playerjs,ids:state.ids,xhr:state.xhr,shapes:state.shapes,jsonXhr:state.jsonXhr};
 var json=originalStringify(result);return json.length<1000000?json:'{}';
};
})();
