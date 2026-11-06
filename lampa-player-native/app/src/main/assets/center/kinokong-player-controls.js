// Selected KinoKong video tabs only. Never activate trailers or unrelated iframes.
(function(){
'use strict';
if(window!==window.top||window.__lampKongControls)return;
function tabs(){return Array.prototype.slice.call(document.querySelectorAll('.newTabs > li[data-iframe]')).filter(function(el){
 return !el.classList.contains('trail')&&!/трейлер|trailer/i.test(el.textContent)&&!!candidate(el.getAttribute('data-iframe'));
}).slice(0,3);}
function candidate(value){try{var u=new URL(value,location.href);return /^https?:$/.test(u.protocol)&&!/^\/(?:trailer|trailer-cdn)(?:\/|$)/i.test(u.pathname)?u:null;}catch(e){return null;}}
function frames(){var allowed=tabs().map(function(t){return candidate(t.getAttribute('data-iframe')).href;});
 return Array.prototype.slice.call(document.querySelectorAll('#preroll iframe')).map(function(el){
 var u=candidate(el.getAttribute('src')||el.getAttribute('data-src'));
 return u&&allowed.indexOf(u.href)>=0?{element:el,url:u}:null;
 }).filter(Boolean).slice(0,1);
}
function select(index){var el=tabs()[index];if(!el)return false;var u=candidate(el.getAttribute('data-iframe'));if(!el.classList.contains('current'))el.click();
 var frame=document.querySelector('#preroll iframe');
 if(frame&&(!frame.getAttribute('src')||frame.getAttribute('src')==='about:blank'))frame.src=u.href;
 return true;}
var focusSession=0;
function focus(index,activate){var session=++focusSession;if(activate)select(index);var heading=document.querySelector('.newTabs');if(heading)heading.scrollIntoView({block:'start'});var attempts=0;
 function locate(){if(session!==focusSession)return;var f=frames()[0];if(f&&f.element.getClientRects().length){var el=f.element;el.style.width='100%';el.style.height=Math.max(240,Math.min(720,Math.round(innerHeight*0.85)))+'px';el.setAttribute('tabindex','0');el.scrollIntoView({block:'start'});try{el.focus({preventScroll:true});}catch(e){}return;}if(++attempts<24)setTimeout(locate,250);}
 locate();return !!heading;
}
window.__lampKongControls={frames:frames,select:select,focus:focus,has:function(index){return !!tabs()[index];},count:function(){return tabs().length;},urls:function(){return tabs().map(function(t){return candidate(t.getAttribute('data-iframe')).href;});}};
})();
