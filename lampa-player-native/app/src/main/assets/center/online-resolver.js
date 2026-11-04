(function(){
'use strict';
if(window!==window.top||window.__lampOnline)return;
HTMLMediaElement.prototype.play=function(){return Promise.reject(new DOMException('Metadata only','NotAllowedError'));};
var state=window.__lampOnline={files:[],lists:[],seen:{},errors:[],cap:480,ids:[],instances:[],xhr:[]};
var originalParse=JSON.parse,originalStringify=JSON.stringify;
function remember(value,kind){
 try{
  if(value==null)return;
  var data=originalStringify(value);if(!data||data.length>900000)return;
  if(state.seen[kind+data])return;
  state.seen[kind+data]=1;
  var target=kind==='file'?state.files:state.lists;
  if(target.length<16)target.push(value);
 }catch(e){}
}
function playlist(value){
 if(!Array.isArray(value)||!value.length||value.length>1000)return;
 if(value.some(function(v){return v&&typeof v==='object'&&(v.file||v.folder)}))remember(value,'list');
}
JSON.parse=function(){var result=originalParse.apply(JSON,arguments);playlist(result);return result;};
var open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send;
XMLHttpRequest.prototype.open=function(method,url){this.__lampUrl=String(url);return open.apply(this,arguments);};
XMLHttpRequest.prototype.send=function(){
 this.addEventListener('load',function(){try{if(!this.responseType||this.responseType==='text'){var text=this.responseText;if(state.xhr.length<12)state.xhr.push([this.status,text?text.length:0]);if(text&&text.length<900000)playlist(originalParse(text));}}catch(e){}});
 return send.apply(this,arguments);
};
if(window.fetch){var fetch=window.fetch;window.fetch=function(){return fetch.apply(this,arguments).then(function(response){try{response.clone().text().then(function(text){if(text.length<900000){try{playlist(originalParse(text));}catch(e){}}}).catch(function(){});}catch(e){}return response;});};}
function wrapPlayer(){
 var original=window.Playerjs;
 if(typeof original==='function'&&!original.__lampWrapped){
  var wrapped=function(options){
   var id=typeof options==='string'?options:options&&options.id;
   if(state.ids.length<12&&id)state.ids.push(String(id));
   var main=typeof id==='string'&&/^(?:video)?player(?:[_-][a-z0-9]+)?$/i.test(id);
   if(main&&options&&typeof options==='object')remember(options.file,'file');
   var instance=Reflect.construct(original,Array.prototype.slice.call(arguments));
   if(main&&instance&&typeof instance.api==='function'){
    if(state.instances.length<4)state.instances.push(instance);
    var api=instance.api;
    try{instance.api=function(command,value){
     if(arguments.length>1&&/^(file|playlist|preload|play)$/.test(command)){
      remember(value,'file');playlist(value);
     }
     return api.apply(instance,arguments);
    };}catch(e){}
   }
   return instance;
  };
  wrapped.prototype=original.prototype;Object.setPrototypeOf(wrapped,original);
  wrapped.__lampWrapped=true;window.Playerjs=wrapped;
 }
 var players=state.instances.slice();if(window.player)players.push(window.player);
 players.forEach(function(player){try{if(typeof player.api==='function')playlist(player.api('playlist_folders'));}catch(e){}});
 if(window.user_data){state.cap=(user_data.is_user_pro_plus||user_data.is_user_loyal)?2160:(user_data.is_user_pro?720:480);}
}
window.__lampInstallPlayer=wrapPlayer;
setInterval(wrapPlayer,50);
window.__lampSnapshot=function(){
 wrapPlayer();
 var text=document.body?document.body.innerText:'';
 var blocked=/(недоступно[\s\S]{0,70}стране|заблокировано[\s\S]{0,90}правообладателя|нет видео файла)/i.exec(text);
 var result={files:state.files,lists:state.lists,cap:state.cap,blocked:blocked?blocked[0]:null,playerType:typeof window.Playerjs,ids:state.ids,xhr:state.xhr};
 var json=originalStringify(result);return json.length<1000000?json:'{}';
};
})();
