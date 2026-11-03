(function(){
'use strict';
if(window!==window.top||window.__lampOnline)return;
HTMLMediaElement.prototype.play=function(){return Promise.reject(new DOMException('Metadata only','NotAllowedError'));};
var state=window.__lampOnline={files:[],lists:[],seen:{},errors:[],cap:480};
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
 this.addEventListener('load',function(){try{if(!this.responseType||this.responseType==='text'){var text=this.responseText;if(text&&text.length<900000)playlist(originalParse(text));}}catch(e){}});
 return send.apply(this,arguments);
};
if(window.fetch){var fetch=window.fetch;window.fetch=function(){return fetch.apply(this,arguments).then(function(response){try{response.clone().text().then(function(text){if(text.length<900000){try{playlist(originalParse(text));}catch(e){}}}).catch(function(){});}catch(e){}return response;});};}
function wrapPlayer(){
 var original=window.Playerjs;
 if(typeof original==='function'&&!original.__lampWrapped){
  var wrapped=function(options){
   if(options&&(options.id==='player'||options.id==='videoplayer'))remember(options.file,'file');
   return Reflect.construct(original,Array.prototype.slice.call(arguments));
  };
  wrapped.prototype=original.prototype;wrapped.__lampWrapped=true;window.Playerjs=wrapped;
 }
 try{
  if(window.player&&typeof window.player.api==='function'){
   playlist(window.player.api('playlist_folders'));
  }
 }catch(e){}
 if(window.user_data){state.cap=(user_data.is_user_pro_plus||user_data.is_user_loyal)?2160:(user_data.is_user_pro?720:480);}
}
setInterval(wrapPlayer,50);
window.__lampSnapshot=function(){
 wrapPlayer();
 var text=document.body?document.body.innerText:'';
 var blocked=/(недоступно[\s\S]{0,70}стране|заблокировано[\s\S]{0,90}правообладателя|нет видео файла)/i.exec(text);
 var result={files:state.files,lists:state.lists,cap:state.cap,blocked:blocked?blocked[0]:null,playerType:typeof window.Playerjs};
 var json=originalStringify(result);return json.length<1000000?json:'{}';
};
})();
