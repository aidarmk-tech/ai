// Controls for the two video tabs on the selected KinoGo card; trailers are never selected.
(function(){
'use strict';
if(window!==window.top||window.__lampKinogoControls)return;
function candidate(value){
 if(!value)return null;
 try{
  var u=new URL(value,location.href);
  if(!/^https?:$/.test(u.protocol))return null;
  if(!/^\/embed\/movie\/[0-9]+\/?$/.test(u.pathname)&&!/^walking-as\./i.test(u.hostname))return null;
  return u;
 }catch(e){return null;}
}
function frames(){
 return Array.prototype.slice.call(document.querySelectorAll('iframe')).map(function(el){
  var value=el.getAttribute('src');if(!value||value==='about:blank')value=el.getAttribute('data-src');
  var url=candidate(value);return url?{element:el,url:url}:null;
 }).filter(Boolean).slice(0,2);
}
function tab(index){
 var label=index===0?'Смотреть онлайн':index===1?'Плеер 2':null;
 if(!label)return null;
 return Array.prototype.slice.call(document.querySelectorAll('.player-tabs > li')).find(function(el){
  return el.textContent.replace(/\s+/g,' ').trim()===label;
 })||null;
}
function select(index){
 var el=tab(index);if(!el)return false;
 if(!el.classList.contains('current'))el.click();
 return true;
}
var focusSession=0;
function focus(index,activate){
 var session=++focusSession;
 if(activate)select(index);
 var heading=document.querySelector('.player-tabs');
 if(heading)heading.scrollIntoView({block:'start'});
 var tries=0;
 function locate(){
  if(session!==focusSession)return;
  var visible=frames().filter(function(f){return f.element.getClientRects().length&&getComputedStyle(f.element).visibility!=='hidden';});
  var target=visible.find(function(f){return index===1?/^walking-as\./i.test(f.url.hostname):/^\/embed\/movie\/[0-9]+\/?$/.test(f.url.pathname);})||(visible.length===1?visible[0]:null);
  if(target&&target.element.getClientRects().length&&getComputedStyle(target.element).visibility!=='hidden'){
   var el=target.element;
   el.style.width='100%';el.style.maxWidth='100%';
   el.style.height=Math.max(240,Math.min(720,Math.round(innerHeight*0.85)))+'px';
   el.setAttribute('tabindex','0');el.scrollIntoView({block:'start'});
   try{el.focus({preventScroll:true});}catch(e){}
   return;
  }
  if(++tries<24)setTimeout(locate,250);
 }
 locate();
 return !!heading;
}
window.__lampKinogoControls={frames:frames,has:function(index){return !!tab(index);},select:select,focus:focus};
})();
