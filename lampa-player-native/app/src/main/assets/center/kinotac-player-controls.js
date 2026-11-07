// KinoTac website controls: keep SDK players on the site and never select a trailer.
(function(){
'use strict';
if(window!==window.top||window.__lampTacControls)return;
function excluded(e){return !!e.closest('[id^="comm"],#full-comms,.comm-item,[class^="comment"],.advertisement,.ads,.trailer,.trail');}
function group(){return Array.from(document.querySelectorAll('article.full .pmovie__player')).find(function(e){return !excluded(e);})||null;}
function rawPanels(){var g=group();return g?Array.from(g.querySelectorAll('.tabs-block__content')):[];}
function validFrame(e){var value=e.getAttribute('src');if(!value||value==='about:blank')value=e.getAttribute('data-src');if(!value||excluded(e))return false;
 try{var u=new URL(value,location.href);return /^https?:$/.test(u.protocol)&&!u.pathname.split('/').some(function(p){return /^(trailer|trailer-cdn)$/i.test(p);});}catch(err){return false;}}
function entries(){return rawPanels().map(function(panel,slot){
 if(excluded(panel)||panel.classList.contains('fplay'))return null;
 var frame=panel.classList.contains('fplayer')&&Array.from(panel.querySelectorAll('iframe')).find(validFrame);
 var sdk=panel.querySelector('video-player#cdnvideohubvideoplayer[data-title-id]');
 return frame||sdk?{panel:panel,slot:slot,frame:frame,sdk:sdk}:null;
 }).filter(Boolean).slice(0,3);}
var session=0;
function focus(index,activate){var pass=++session,g=group();if(!g)return false;var attempts=0;
 function locate(){if(pass!==session)return;var entry=entries()[index];
 if(entry){var buttons=Array.from(g.querySelectorAll('.tabs-block__select > button')),button=buttons[entry.slot];
 if(activate&&button&&!button.classList.contains('is-active')&&!button.classList.contains('active'))button.click();
 rawPanels().forEach(function(p){if(p===entry.panel){p.classList.remove('d-none');p.style.display='block';}else{p.classList.add('d-none');p.style.display='none';}});
 buttons.forEach(function(b,i){b.classList.toggle('is-active',i===entry.slot);b.classList.toggle('active',i===entry.slot);});
 var target=entry.frame||entry.sdk;
 if(entry.frame){var src=target.getAttribute('src'),lazy=target.getAttribute('data-src');
 if((!src||src==='about:blank')&&lazy){try{var u=new URL(lazy,location.href);if(/^https?:$/.test(u.protocol))target.src=u.href;}catch(e){}}
 target.loading='eager';}
 if(target){entry.panel.style.width='100%';entry.panel.style.minHeight='240px';target.style.width='100%';target.style.height=Math.max(240,Math.round(innerHeight*.85))+'px';
 target.tabIndex=0;target.scrollIntoView({block:'start'});try{target.focus({preventScroll:true});}catch(e){}return;}
 }
 g.scrollIntoView({block:'start'});if(++attempts<24)setTimeout(locate,250);
 }locate();return true;}
window.__lampTacControls={focus:focus,count:function(){return entries().length;}};
})();
