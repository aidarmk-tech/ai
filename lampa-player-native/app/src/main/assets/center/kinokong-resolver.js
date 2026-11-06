// Reports only the selected card's declared video iframe; metadata mode never clicks play.
(function(){
'use strict';
var cfg=window.__lampKinogoConfig;if(!cfg)return;
if(window!==window.top){setInterval(function(){if(window.__lampSnapshot)window.parent.postMessage({lamp:cfg.session,frameUrl:location.href,data:window.__lampSnapshot()},cfg.origin);},300);return;}
var controls=window.__lampKongControls,records={},index=0,lastSwitch=Date.now();
window.addEventListener('message',function(event){
 if(!event.data||event.data.lamp!==cfg.session||typeof event.data.data!=='string'||event.data.data.length>1000000)return;
 var frame=controls.frames().find(function(f){return f.element.contentWindow===event.source;});if(!frame)return;
 try{var actual=new URL(event.data.frameUrl);if(!/^https?:$/.test(actual.protocol)||actual.origin!==event.origin)return;
 var data=JSON.parse(event.data.data);if(!data||!Array.isArray(data.files)||!Array.isArray(data.lists))return;
 records[frame.url.href]={url:actual.href,snapshot:data};
 }catch(e){}
});
setInterval(function(){var frames=controls.frames();if(!frames.length)controls.select(index);frames.forEach(function(f){var src=f.element.getAttribute('src');if(!src||src==='about:blank')f.element.src=f.url.href;});},300);
window.__lampKinogoTryAlternate=function(){
 if(Date.now()-lastSwitch<6000||!controls.has(index+1))return false;
 index++;lastSwitch=Date.now();return controls.select(index);
};
window.__lampKinogoSnapshot=function(){
 var frames=controls.frames(),count=controls.count(),last=!controls.has(index+1);
 return JSON.stringify({expected:count,alternateAvailable:count>1,alternate:last,alternatePending:!frames.length,candidates:controls.urls().map(function(url){return records[url];}).filter(Boolean)});
};
})();
