// Video tabs for the selected KinoKong card. Mobile/desktop copies count once.
(function(){
'use strict';
if(window!==window.top||window.__lampKongControls)return;
function candidate(value){if(!value)return null;try{var u=new URL(value,location.href);
 return /^https?:$/.test(u.protocol)&&!/^\/(?:trailer|trailer-cdn)(?:\/|$)/i.test(u.pathname)?u:null;
}catch(e){return null;}}
function selectedId(){var m=/^\/([0-9]+)-/.exec(location.pathname);return m?m[1]:null;}
function scoped(el){var id=selectedId(),owner=el.closest('[id^="full_"]');
 return !(owner&&id&&owner.id!=='full_'+id)&&!el.closest('[id^="comm-"],[class^="comment"],.comm-item');}
function visible(el){return !!el.getClientRects().length&&getComputedStyle(el).visibility!=='hidden';}
function group(){var groups=Array.prototype.slice.call(document.querySelectorAll('.newTabs')).filter(scoped);
 return groups.find(visible)||groups[0]||null;}
function tabs(){var parent=group();if(!parent)return [];var seen={};
 return Array.prototype.slice.call(parent.children).filter(function(el){
  var url=candidate(el.getAttribute('data-iframe'));
  if(el.tagName!=='LI'||el.classList.contains('trail')||/трейлер|trailer/i.test(el.textContent)||!url||seen[url.href])return false;
  seen[url.href]=true;return true;
 }).slice(0,3);
}
function rawFrames(){return Array.prototype.slice.call(document.querySelectorAll('[id="preroll"] iframe')).filter(scoped);}
function frames(){var allowed=tabs().map(function(t){return candidate(t.getAttribute('data-iframe')).href;});
 var found=rawFrames().map(function(el){var u=candidate(el.getAttribute('src'))||candidate(el.getAttribute('data-src'));
 return u&&allowed.indexOf(u.href)>=0?{element:el,url:u}:null;
 }).filter(Boolean);return found.filter(function(f){return visible(f.element);}).slice(0,1).concat(found).slice(0,1);
}
function select(index){var el=tabs()[index];if(!el)return false;var url=candidate(el.getAttribute('data-iframe'));
 if(!el.classList.contains('current'))el.click();
 var all=rawFrames(),frame=all.find(visible)||all[0];
 if(frame){frame.setAttribute('loading','eager');frame.setAttribute('data-src',url.href);
  if(frame.getAttribute('src')!==url.href)frame.setAttribute('src',url.href);}
 return true;
}
var focusSession=0;
function focus(index,activate){var session=++focusSession;if(activate)select(index);var heading=group();
 if(heading)heading.scrollIntoView({block:'start'});var attempts=0;
 function locate(){if(session!==focusSession)return;var tab=tabs()[index],wanted=tab&&candidate(tab.getAttribute('data-iframe'));
 var f=frames().find(function(e){return wanted&&e.url.href===wanted.href;});
 if(f&&visible(f.element)){var el=f.element;el.style.width='100%';el.style.height=Math.max(240,Math.min(720,Math.round(innerHeight*0.85)))+'px';
 el.setAttribute('tabindex','0');el.scrollIntoView({block:'start'});try{el.focus({preventScroll:true});}catch(e){}return;}
 if(++attempts<24)setTimeout(locate,250);
 }locate();return !!heading;
}
window.__lampKongControls={frames:frames,select:select,focus:focus,has:function(index){return !!tabs()[index];},count:function(){return tabs().length;},
 urls:function(){return tabs().map(function(t){return candidate(t.getAttribute('data-iframe')).href;});},
 inspect:function(){return {ready:document.readyState,card:!!document.getElementById('full_'+selectedId()),groups:document.querySelectorAll('.newTabs').length,
  declared:document.querySelectorAll('.newTabs > li[data-iframe]').length,iframes:rawFrames().length};}};
})();
