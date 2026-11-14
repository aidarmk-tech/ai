// Zona's declared video panels only; no trailer, comments or advertising frames.
(function(){
'use strict';
if(window!==window.top||window.__lampZonaControls)return;
function group(){return Array.from(document.querySelectorAll('.plays > .tabs-box2')).find(function(e){
 return !e.closest('[id^="comm-"],.comm-item,[class^="comment"]');})||null;}
function panels(){var g=group();return g?Array.from(g.children).filter(function(e){return e.classList.contains('tabs-b2');}).slice(0,2):[];}
function tabs(){var g=group(),s=g&&g.querySelector('.tabs-sel2');return s?Array.from(s.children).filter(function(e){return e.tagName==='SPAN';}).slice(0,2):[];}
function video(panel){return panel&&Array.from(panel.querySelectorAll('iframe')).find(function(e){
 if(e.closest('.advertisement,.trailer,.trail,.tmdbview'))return false;
 var value=e.getAttribute('src');if(!value||value==='about:blank')value=e.getAttribute('data-src');if(!value)return false;
 try{var u=new URL(value,location.href);return /^https?:$/.test(u.protocol)&&!/^\/trailer(?:-cdn)?\//i.test(u.pathname);}catch(err){return false;}
 });}
var session=0;
function focus(index,activate){var pass=++session,g=group();if(!g)return false;var tries=0;
 function locate(){if(pass!==session)return;var all=panels(),panel=all[index],tab=tabs()[index];
 if(activate&&tab&&!tab.classList.contains('current')){tab.click();tabs().forEach(function(e,i){e.classList.toggle('current',i===index);});}
 if(panel){all.forEach(function(e,i){e.style.display=i===index?'block':'none';});}
 var f=video(panel);
 if(f){var src=f.getAttribute('src'),lazy=f.getAttribute('data-src');
 if((!src||src==='about:blank')&&lazy){try{var u=new URL(lazy,location.href);if(/^https?:$/.test(u.protocol))f.src=u.href;}catch(e){}}
 f.loading='eager';f.style.width='100%';f.style.height=Math.max(240,Math.round(innerHeight*.85))+'px';f.tabIndex=0;
 f.scrollIntoView({block:'start'});try{f.focus({preventScroll:true});}catch(e){}return;}
 g.scrollIntoView({block:'start'});if(++tries<24)setTimeout(locate,250);
 }locate();return true;}
window.__lampZonaControls={focus:focus};
})();
