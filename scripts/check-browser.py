#!/usr/bin/env python3
"""Exercise real browser assets with system Firefox; Python stdlib only.

Usage: python3 scripts/check-browser.py [--preview-screenshot /tmp/tv-preview.png]
Requires an installed Firefox executable; no browser download, npm or emulator.
"""
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
import argparse,json,shutil,subprocess,threading,tempfile

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--firefox',default=shutil.which('firefox'),help='Firefox executable (default: system Firefox)')
parser.add_argument('--preview-screenshot',type=Path,help='Optional PNG path; no screenshot is saved by default')
parser.add_argument('--narrow-screenshot',type=Path,help='Optional PNG at the TV viewport of 916 by 454 CSS pixels')
parser.add_argument('--report',type=Path,help='Optional JSON report path')
args=parser.parse_args()
if not args.firefox:
 parser.error('Firefox is required. Install it through your operating system package manager, or pass --firefox /path/to/firefox.')
project=Path(__file__).resolve().parent.parent
root=project/'app/src/main/assets'
results={}
done=threading.Event()
setup=r'''
window.__intervals=new Set();
const originalSetInterval=window.setInterval,originalClearInterval=window.clearInterval;
window.setInterval=function(fn,ms){const id=originalSetInterval(fn,ms);window.__intervals.add(id);return id};
window.clearInterval=function(id){window.__intervals.delete(id);return originalClearInterval(id)};
window.__hidden=false;Object.defineProperty(document,'hidden',{configurable:true,get:()=>window.__hidden});
window.__media={time:3,duration:180,paused:false,ended:false,plays:0,pauses:0};
window.__session={metadata:null};Object.defineProperty(navigator,'mediaSession',{configurable:true,value:window.__session});
const video=document.querySelector('video');
Object.defineProperties(video,{duration:{get:()=>__media.duration},currentTime:{get:()=>__media.time,set:v=>__media.time=v},paused:{get:()=>__media.paused},ended:{get:()=>__media.ended}});
video.play=function(){const changed=__media.paused;__media.paused=false;__media.plays++;if(changed)video.dispatchEvent(new Event('play'));return Promise.resolve()};
video.pause=function(){const changed=!__media.paused;__media.paused=true;__media.pauses++;if(changed)video.dispatchEvent(new Event('pause'))};
Array.prototype.at=undefined;Array.prototype.findLast=undefined;Object.hasOwn=undefined;String.prototype.replaceAll=undefined;
'''
css=(root/'lyrics/core.css').read_text()+'\n'+(root/'tv.css').read_text()
injected='(function(){const s=document.createElement("style");s.textContent='+json.dumps(css)+';document.head.appendChild(s);\n'+(root/'lyrics/core.js').read_text()+'\n'+(root/'tv.js').read_text()+'\n})();'
tests=r'''
(async function(){
 const results=[];
 const check=(name,pass,detail)=>results.push({name,pass:!!pass,detail});
 const delay=ms=>new Promise(r=>setTimeout(r,ms));
 const status=()=>document.querySelector('.tv-lyrics-status').textContent;
 const view=()=>document.querySelector('braccato-lyrics');
 const scroller=()=>document.querySelector('.tv-lyrics-scroll');
 const setScrollInstantly=(element,top)=>{const previous=element.style.scrollBehavior;element.style.scrollBehavior='auto';element.scrollTop=top;element.style.scrollBehavior=previous;};
 const payload=(request,extra)=>Object.assign({},request,extra);
 function track(title,artist,id){document.querySelector('ytmusic-player-bar .title').textContent=title;document.querySelector('ytmusic-player-bar .byline a').textContent=artist;document.querySelector('ytmusic-player-bar .title').setAttribute('href','/watch?v='+id)}
 try {
 const hostReset=document.createElement('style');hostReset.textContent='* { box-sizing: content-box; }';document.head.appendChild(hostReset);
 check('global export survives native-style injection wrapper',window.BraccatoTV&&BraccatoTV.version==='1.21.5');
 check('initial visibility false',!TvMusic.isLyricsVisible());
 check('initial overlay open return',TvMusic.toggleLyrics()===true&&TvMusic.isLyricsVisible());
 const first=TvMusic.getLyricsRequest();
 check('metadata fallback from player bar',first&&first.title==='Test song'&&first.artist==='Test artist'&&first.trackId==='/watch?v=first'&&first.duration===180,first);
 const byline=document.querySelector('ytmusic-player-bar .byline');
 const extraMetadata=document.createElement('span');extraMetadata.textContent=' • Demo album • 2026';byline.appendChild(extraMetadata);
 let snapshot=TvMusic.getLyricsRequest();
 check('artist anchor excludes byline album and year',snapshot&&snapshot.artist==='Test artist',snapshot);
 extraMetadata.remove();
 __session.metadata={title:'   ',artist:'\t',album:' '};snapshot=TvMusic.getLyricsRequest();
 check('whitespace MediaSession fields fall back to player bar',snapshot&&snapshot.title==='Test song'&&snapshot.artist==='Test artist',snapshot);
 __session.metadata={title:{unexpected:true},artist:[],album:{}};snapshot=TvMusic.getLyricsRequest();
 check('malformed MediaSession fields fall back to player bar',snapshot&&snapshot.title==='Test song'&&snapshot.artist==='Test artist',snapshot);
 __session.metadata={};snapshot=TvMusic.getLyricsRequest();
 check('empty MediaSession falls back to player bar',snapshot&&snapshot.title==='Test song'&&snapshot.artist==='Test artist',snapshot);
 const bar=document.querySelector('ytmusic-player-bar'),barParent=bar.parentElement;bar.remove();
 __session.metadata={title:'Session song',artist:'Session artist',album:'Session album'};snapshot=TvMusic.getLyricsRequest();
 check('MediaSession metadata works without player bar',snapshot&&snapshot.title==='Session song'&&snapshot.artist==='Session artist'&&snapshot.album==='Session album',snapshot);
 barParent.appendChild(bar);__session.metadata=null;
 const teaser=document.createElement('video');teaser.style.display='none';document.body.insertBefore(teaser,document.body.firstChild);
 snapshot=TvMusic.getLyricsRequest();
 check('unloaded teaser does not replace loaded main video',snapshot&&snapshot.duration===180,snapshot);
 TvMusic.media('pause');check('media controls use main video after earlier teaser',__media.paused);TvMusic.media('play');teaser.remove();
 const overlayStyle=getComputedStyle(document.querySelector('#tv-lyrics-overlay'));
 check('overlay CSS applied',overlayStyle.position==='fixed'&&(overlayStyle.backgroundImage!=='none'||overlayStyle.backgroundColor!=='rgba(0, 0, 0, 0)'),overlayStyle.backgroundColor);
 check('synced result accepted',TvMusic.receiveLyrics(payload(first,{status:'synced',syncedLyrics:'[00:00.00]First line\n[00:02.00]Second line\n[00:08.00]Third line'}))===true);
 check('actual renderer mounted',view().status==='rendering'&&view().querySelectorAll('.blyrics--line').length===3,view().status);
 check('letter DOM disabled by applied theme',!view().querySelector('.blyrics--letter')&&view().theme.includes('blyrics-letter-wave = false'));
 const engine=view().renderer,originalResume=engine.resumeAutoscroll;let resumed=0;
 engine.resumeAutoscroll=function(){resumed++;};document.querySelector('[aria-label="Follow current lyric"]').click();engine.resumeAutoscroll=originalResume;
 check('Follow song resumes renderer autoscroll',resumed===1,resumed);
 __media.time=3;await delay(320);
 check('clock follows video playback',view().currentTime===3&&view().playing&&window.__intervals.size===1,{time:view().currentTime,playing:view().playing,intervals:window.__intervals.size});
 const stableView=view(),stableContainer=stableView.renderer.container;
 const elementProto=Object.getPrototypeOf(stableView),timeProperty=Object.getOwnPropertyDescriptor(elementProto,'currentTime'),playingProperty=Object.getOwnPropertyDescriptor(elementProto,'playing');
 let timeWrites=0,playingWrites=0;
 Object.defineProperty(stableView,'currentTime',{configurable:true,get(){return timeProperty.get.call(this)},set(value){timeWrites++;timeProperty.set.call(this,value)}});
 Object.defineProperty(stableView,'playing',{configurable:true,get(){return playingProperty.get.call(this)},set(value){playingWrites++;playingProperty.set.call(this,value)}});
 TvMusic.media('pause');const pausedWrites=[timeWrites,playingWrites];await delay(320);
 check('paused overlay has no active clock timer',window.__intervals.size===0,window.__intervals.size);
 check('paused overlay does not rewrite renderer properties',timeWrites===pausedWrites[0]&&playingWrites===pausedWrites[1],{before:pausedWrites,after:[timeWrites,playingWrites]});
 TvMusic.media('play');const unchangedWrites=[timeWrites,playingWrites];await delay(550);
 check('playing unchanged clock does not rewrite renderer properties',timeWrites===unchangedWrites[0]&&playingWrites===unchangedWrites[1],{before:unchangedWrites,after:[timeWrites,playingWrites]});
 __media.time=3.25;const changedWrites=timeWrites;await delay(320);
 check('changed playback time produces only one renderer time write',timeWrites===changedWrites+1,{before:changedWrites,after:timeWrites});
 TvMusic.media('pause');__media.time=5;video.dispatchEvent(new Event('seeked'));
 check('paused seek updates lyrics without restarting clock',stableView.currentTime===5&&!stableView.playing&&window.__intervals.size===0,{time:stableView.currentTime,playing:stableView.playing,intervals:window.__intervals.size});
 TvMusic.media('play');
 check('pause and resume preserve renderer and lyric DOM',view()===stableView&&view().renderer.container===stableContainer);
 const oldClock=view().currentTime;
 check('explicit pause handled',TvMusic.media('pause')===true&&__media.paused);
 check('explicit play handled',TvMusic.media('play')===true&&!__media.paused);
 check('explicit play remains play',TvMusic.media('play')===true&&!__media.paused);
 check('explicit pause remains pause',TvMusic.media('pause')===true&&TvMusic.media('pause')===true&&__media.paused);
 TvMusic.media('play');
 view().querySelectorAll('.blyrics--line')[2].click();
 check('line event seeks native media',__media.time===8,__media.time);
 const stale=payload(first,{status:'synced',syncedLyrics:'[00:00]Stale'});
 track('New song','New artist','second');
 const second=TvMusic.getLyricsRequest();
 check('track change clears prior lyrics',view().querySelectorAll('.blyrics--line').length===0);
 check('stale lyrics rejected',TvMusic.receiveLyrics(stale)===false&&status()==='Finding lyrics…',status());
 TvMusic.receiveLyrics(payload(second,{status:'synced',syncedLyrics:'[00:00]Sync line\n[00:02]Other line'}));
 TvMusic.receiveLyrics(payload(second,{status:'plain',plainLyrics:'Plain only\nAnother plain line'}));
 check('plain response replaces timed content',scroller().querySelectorAll('.blyrics--line').length===0&&scroller().textContent.includes('Plain only'),scroller().textContent);
 check('plain status before close',status()==='Lyrics by LRCLIB · Unsynced',status());
 check('toggle close return',TvMusic.toggleLyrics()===false&&!TvMusic.isLyricsVisible());
 check('clock stopped after close',window.__intervals.size===0);
 check('toggle reopen return',TvMusic.toggleLyrics()===true&&TvMusic.isLyricsVisible());
 check('plain status preserved after reopen',status()==='Lyrics by LRCLIB · Unsynced',status());
 check('plain text preserved after reopen',scroller().textContent.includes('Plain only'));
 TvMusic.receiveLyrics(payload(second,{status:'synced',syncedLyrics:'[00:00]Replacement timed\n[00:02]Next timed'}));
 check('synced response removes plain content',!scroller().querySelector('.tv-plain-lyrics'),scroller().textContent);
 TvMusic.receiveLyrics(payload(second,{status:'plain',timingMatched:false,plainLyrics:'Different recording words'}));
 check('different recording is explicitly untimed',status().includes('Untimed: different recording'),status());
 TvMusic.receiveLyrics(payload(second,{status:'missing',message:'No lyrics found for this recording.'}));
 let retry=document.querySelector('.tv-lyrics-retry');
 check('missing result offers retry',!retry.hidden&&status()==='No lyrics found for this recording.',status());
 const beforeRetry=TvMusic.getLyricsRequest();
 check('retry action accepted',TvMusic.retryLyrics()===true);
 const afterRetry=TvMusic.getLyricsRequest();
 check('retry nonce changes native request identity',Number(afterRetry.retryNonce)===Number(beforeRetry.retryNonce)+1,{before:beforeRetry.retryNonce,after:afterRetry.retryNonce});
 check('stale same-track retry response is rejected',TvMusic.receiveLyrics(payload(beforeRetry,{status:'error',message:'Stale failure'}))===false);
 check('retry preserves finding status while pending',status()==='Finding lyrics…'&&retry.hidden,status());
 TvMusic.receiveLyrics(payload(afterRetry,{status:'error',message:'Connection unavailable.'}));
 check('network error keeps message and retry',status()==='Connection unavailable.'&&!retry.hidden,status());
 TvMusic.receiveLyrics(payload(afterRetry,{status:'synced',syncedLyrics:'malformed lyrics without timestamps'}));
 check('invalid synced response offers retry',!retry.hidden&&status().includes('could not be displayed'),status());
 TvMusic.receiveLyrics(payload(afterRetry,{status:'instrumental',message:'Instrumental track'}));
 check('instrumental result has no retry prompt',status()==='Instrumental track'&&retry.hidden,status());
 const transport=document.querySelector('#tv-transport');
 check('component roots override host content-box reset',getComputedStyle(transport).boxSizing==='border-box'&&getComputedStyle(document.querySelector('#tv-lyrics-overlay')).boxSizing==='border-box');
 check('transport total height stays exactly 78 pixels',Math.abs(transport.getBoundingClientRect().height-78)<0.1,transport.getBoundingClientRect().height);
 check('play button keeps the warm-white visual hierarchy',getComputedStyle(transport.querySelector('.tv-play-button')).backgroundColor==='rgb(242, 238, 230)',getComputedStyle(transport.querySelector('.tv-play-button')).backgroundColor);
 const controls=Array.from(transport.querySelectorAll('.tv-transport-controls button'));
 check('transport icons expose accessible labels',controls.length===3&&controls.every(b=>b.getAttribute('aria-label')&&b.querySelector('svg[aria-hidden="true"]')));
 const previous=document.createElement('button'),next=document.createElement('button');
 previous.className='previous-button';next.className='next-button';let previousCount=0,nextCount=0;
 previous.onclick=()=>previousCount++;next.onclick=()=>nextCount++;
 document.querySelector('ytmusic-player-bar').append(previous,next);
 transport.querySelector('[aria-label="Previous"]').click();transport.querySelector('[aria-label="Next"]').click();
 check('previous and next icon buttons reach official controls',previousCount===1&&nextCount===1,{previousCount,nextCount});previous.remove();next.remove();
 const range=transport.querySelector('input[type="range"]');
 range.value='2000';range.dispatchEvent(new Event('input'));check('seek upper bound is recording duration',__media.time===__media.duration,__media.time);
 range.value='-20';range.dispatchEvent(new Event('input'));check('seek lower bound is zero',__media.time===0,__media.time);
 range.dispatchEvent(new KeyboardEvent('keydown',{key:'End',bubbles:true,cancelable:true}));check('End seeks recording end',__media.time===__media.duration,__media.time);
 range.dispatchEvent(new KeyboardEvent('keydown',{key:'Home',bubbles:true,cancelable:true}));check('Home seeks recording start',__media.time===0,__media.time);
 check('seek range exposes spoken time',range.getAttribute('aria-valuetext')==='0:00 of 3:00',range.getAttribute('aria-valuetext'));
 const artFrames=()=>Array.from(document.querySelectorAll('.tv-artwork'));
 check('artwork has visible local fallback',artFrames().length===2&&artFrames().every(frame=>frame.classList.contains('tv-artwork-empty')&&frame.querySelector('img').hidden));
 const sourcePlayer=document.createElement('div');sourcePlayer.id='movie_player';
 sourcePlayer.getVideoData=()=>({video_id:'actual-movie-player-id'});
 sourcePlayer.getPlayerResponse=()=>({videoDetails:{thumbnail:{thumbnails:[{url:'https://example.invalid/tiny-thumb.jpg',width:120},{url:'https://example.invalid/medium-thumb.jpg',width:480},{url:'https://example.invalid/huge-thumb.jpg',width:1920}]}}});
 document.body.appendChild(sourcePlayer);video.dispatchEvent(new Event('loadedmetadata'));
 check('artwork falls back to a modest movie-player thumbnail',artFrames().every(frame=>frame.querySelector('img').getAttribute('src')==='https://example.invalid/medium-thumb.jpg'));
 const titleLink=document.querySelector('ytmusic-player-bar .title'),titleHref=titleLink.getAttribute('href');titleLink.removeAttribute('href');
 check('track identity can fall back to movie-player video data',TvMusic.getLyricsRequest().trackId==='actual-movie-player-id');
 titleLink.setAttribute('href',titleHref);sourcePlayer.remove();video.dispatchEvent(new Event('loadedmetadata'));TvMusic.getLyricsRequest();
 const emptyBarImage=document.createElement('img');document.querySelector('ytmusic-player-bar').appendChild(emptyBarImage);video.dispatchEvent(new Event('loadedmetadata'));
 check('an empty source image never requests the current page as artwork',artFrames().every(frame=>!frame.querySelector('img').getAttribute('src')));emptyBarImage.remove();
 __session.metadata={artwork:[{src:'javascript:alert(1)'}]};video.dispatchEvent(new Event('loadedmetadata'));
 check('non-HTTPS artwork is rejected',artFrames().every(frame=>!frame.querySelector('img').getAttribute('src')));
 __session.metadata={artwork:[{src:'http://example.invalid/art.png'}]};video.dispatchEvent(new Event('loadedmetadata'));
 check('insecure HTTP artwork is rejected',artFrames().every(frame=>!frame.querySelector('img').getAttribute('src')));
 __session.metadata={artwork:[{src:'https://example.invalid/art=w1920-h1920-l90-rj'}]};video.dispatchEvent(new Event('loadedmetadata'));
 check('HTTPS artwork is sized for modest decoding',artFrames().every(frame=>frame.querySelector('img').getAttribute('src')==='https://example.invalid/art=w480-h480-l90-rj'));
 artFrames().forEach(frame=>frame.querySelector('img').dispatchEvent(new Event('load')));
 check('loaded artwork replaces local fallback',artFrames().every(frame=>!frame.classList.contains('tv-artwork-empty')&&!frame.querySelector('img').hidden));
 artFrames().forEach(frame=>frame.querySelector('img').dispatchEvent(new Event('error')));
 check('failed artwork restores local fallback',artFrames().every(frame=>frame.classList.contains('tv-artwork-empty')&&frame.querySelector('img').hidden));
 __session.metadata=null;video.dispatchEvent(new Event('loadedmetadata'));
 TvMusic.receiveLyrics(payload(TvMusic.getLyricsRequest(),{status:'unavailable',message:'No lyrics for this recording.'}));
 TvMusic.back();TvMusic.toggleLyrics();
 check('unavailable status preserved after reopen',status()==='No lyrics for this recording.',status());
 check('single clock after multiple reopen',window.__intervals.size===1,window.__intervals.size);
 __hidden=true;document.dispatchEvent(new Event('visibilitychange'));
 const hiddenView=view(),hiddenTime=hiddenView.currentTime;
 __media.time=44;await delay(320);
 check('hidden stops clock and animation',window.__intervals.size===0&&!hiddenView.playing&&hiddenView.currentTime===hiddenTime,{intervals:window.__intervals.size,playing:hiddenView.playing,time:hiddenView.currentTime});
 TvMusic.resume();
 check('resume while hidden keeps clock stopped',window.__intervals.size===0,window.__intervals.size);
 __hidden=false;document.dispatchEvent(new Event('visibilitychange'));
 check('visible restarts single clock',window.__intervals.size===1);
 TvMusic.suspend();const suspendedView=view(),suspendedTime=suspendedView.currentTime;__media.time=70;await delay(320);
 check('suspend pauses media and stops clock',__media.paused&&window.__intervals.size===0&&!suspendedView.playing&&suspendedView.currentTime===suspendedTime,{paused:__media.paused,intervals:window.__intervals.size,playing:suspendedView.playing});
 TvMusic.resume();check('resume while media remains paused keeps clock stopped',window.__intervals.size===0,window.__intervals.size);
 setScrollInstantly(scroller(),0);
 check('overlay D-pad up at top yields toolbar',TvMusic.move('up')===false);
 __media.time=5;TvMusic.move('left');check('left seek clamps to zero',__media.time===0);
 TvMusic.move('right');check('right seek advances ten seconds',__media.time===10);
 TvMusic.back();check('back on closed overlay returns false',TvMusic.back()===false);
 check('request unavailable while closed',TvMusic.getLyricsRequest()===null);
 check('browse first move focuses candidate',TvMusic.move('down')===true&&document.querySelector('.tv-focused'));
 check('browse D-pad up at top yields toolbar',TvMusic.move('up')===false&&!document.querySelector('.tv-focused'));
 check('no clock after returning to Browse',window.__intervals.size===0);
 const nested=document.createElement('div');nested.style.cssText='position:absolute;left:30px;top:180px;width:260px;height:140px;overflow:auto;background:#345';
 const cursorButton=document.createElement('button');cursorButton.textContent='Cursor target';cursorButton.style.cssText='width:220px;height:48px';
 let clicks=0;cursorButton.onclick=()=>clicks++;
 const filler=document.createElement('div');filler.style.height='700px';nested.append(cursorButton,filler);document.body.appendChild(nested);
 const targetBox=cursorButton.getBoundingClientRect();document.elementFromPoint(targetBox.left+20,targetBox.top+20).click();
 check('page buttons still receive coordinate-selected clicks',clicks===1,clicks);
 const box=nested.getBoundingClientRect(),xf=(box.left+box.width/2)/innerWidth,yf=(box.top+box.height/2)/innerHeight;
 check('cursor scroll API exists',typeof TvMusic.scrollAt==='function');
 check('cursor scroll changes nearest nested scroll area',TvMusic.scrollAt(xf,yf,0,0.25)===true&&nested.scrollTop>0,nested.scrollTop);
 nested.scrollTop=nested.scrollHeight-nested.clientHeight;
 check('cursor scroll reports lower boundary',TvMusic.scrollAt(xf,yf,0,0.25)===false,nested.scrollTop);
 nested.remove();
 TvMusic.toggleLyrics();
 const current=TvMusic.getLyricsRequest();
 TvMusic.receiveLyrics(payload(current,{status:'plain',plainLyrics:Array.from({length:80},(_,i)=>'Original scroll fixture line '+i).join('\n')}));
 setScrollInstantly(scroller(),0);
 const lyricBox=scroller().getBoundingClientRect();
 const scrollEngine=view().renderer,originalNote=scrollEngine.noteUserScroll;let manualScrollNotes=0;
 scrollEngine.noteUserScroll=function(){manualScrollNotes++;};
 const lyricScroll=TvMusic.scrollAt((lyricBox.left+lyricBox.width/2)/innerWidth,(lyricBox.top+lyricBox.height/2)/innerHeight,0,0.3);
 check('cursor scroll reaches lyrics scroller',lyricScroll===true&&scroller().scrollTop>0,scroller().scrollTop);
 check('actual cursor scrolling pauses renderer autoscroll',manualScrollNotes===1,manualScrollNotes);
 setScrollInstantly(scroller(),scroller().scrollHeight-scroller().clientHeight);
 const boundaryScroll=TvMusic.scrollAt((lyricBox.left+lyricBox.width/2)/innerWidth,(lyricBox.top+lyricBox.height/2)/innerHeight,0,0.3);
 check('scrolling at a boundary does not pause autoscroll again',boundaryScroll===false&&manualScrollNotes===1,{boundaryScroll,manualScrollNotes});
 scrollEngine.noteUserScroll=originalNote;
 const timedFixture=Array.from({length:24},(_,i)=>'['+String(Math.floor(i*3/60)).padStart(2,'0')+':'+String(i*3%60).padStart(2,'0')+']Original timed fixture line '+i).join('\n');
 TvMusic.receiveLyrics(payload(TvMusic.getLyricsRequest(),{status:'synced',syncedLyrics:timedFixture}));
 TvMusic.media('play');__media.time=18;video.dispatchEvent(new Event('seeked'));
 const forcedEngine=view().renderer,forcedContainer=forcedEngine.container;
 forcedEngine.noteUserScroll();
 check('default renderer scroll note retains upstream event-budget behavior',!forcedContainer.classList.contains('blyrics-user-scrolling'));
 const timedBox=scroller().getBoundingClientRect(),beforeFirstManual=scroller().scrollTop;
 const firstManual=TvMusic.scrollAt((timedBox.left+timedBox.width/2)/innerWidth,(timedBox.top+timedBox.height/2)/innerHeight,0,0.2);
 check('first explicit scroll immediately pauses following after recent auto-follow',firstManual===true&&scroller().scrollTop!==beforeFirstManual&&forcedContainer.classList.contains('blyrics-user-scrolling'),{firstManual,before:beforeFirstManual,after:scroller().scrollTop,userScrolling:forcedContainer.classList.contains('blyrics-user-scrolling')});
 document.querySelector('[aria-label="Follow current lyric"]').click();
 check('Follow clears manual-scroll state immediately',!forcedContainer.classList.contains('blyrics-user-scrolling'));
 __media.time=24;video.dispatchEvent(new Event('timeupdate'));await delay(320);
 check('automatic lyric following continues after Follow',view().currentTime===24&&view().querySelector('.blyrics--active').textContent.includes('fixture line 8'));
 __media.duration=NaN;
 check('unready recording produces no lyric request',TvMusic.getLyricsRequest()===null);
 check('unready recording clears old lyrics and identity',view().querySelectorAll('.blyrics--line').length===0&&!scroller().querySelector('.tv-plain-lyrics')&&document.querySelector('.tv-song-identity h1').textContent==='Nothing playing');
 __media.duration=180;const recovered=TvMusic.getLyricsRequest();
 check('recording metadata recovers with a fresh request',recovered&&recovered.title==='New song'&&Number(recovered.retryNonce)===0,recovered);
 check('recovered recording accepts fresh synchronized lyrics',TvMusic.receiveLyrics(payload(recovered,{status:'synced',syncedLyrics:'[00:00]Recovered recording\n[00:05]Fresh words'}))===true&&view().querySelectorAll('.blyrics--line').length===2);
 TvMusic.back();
 check('search reports absence of site navigation API',TvMusic.searchFor('Fixture song')===false);
 const searchBox=document.createElement('ytmusic-search-box'),searchInput=document.createElement('input');searchBox.appendChild(searchInput);document.body.appendChild(searchBox);
 check('search reports unsupported site search method',TvMusic.searchFor('Fixture song')===false);
 let searched='',inputEvents=0;searchInput.addEventListener('input',()=>inputEvents++);searchBox.navigateToQueryResults=function(){searched=this.querySelector('input').value;};
 const searchPlayer=video,searchTime=__media.time,searchUrl=location.href;
 check('SPA search uses the site query navigation API',TvMusic.searchFor('  aespa Lemonade  ')===true&&searched==='aespa Lemonade'&&inputEvents===1,{searched,inputEvents});
 check('SPA search preserves media node and playback position',document.querySelector('video')===searchPlayer&&__media.time===searchTime&&location.href===searchUrl,{time:__media.time,url:location.href});
 check('empty SPA search does not navigate',TvMusic.searchFor('   ')===false&&searched==='aespa Lemonade');searchBox.remove();
 const app=document.createElement('ytmusic-app');let homeRoute='';app.navigate=route=>homeRoute=route;document.body.appendChild(app);
 check('Home uses the site single-page route',TvMusic.home()===true&&homeRoute==='FEmusic_home',homeRoute);
 check('SPA Home preserves media node and playback position',document.querySelector('video')===searchPlayer&&__media.time===searchTime&&location.href===searchUrl);app.remove();
 }catch(error){results.push({name:'uncaught integration error',pass:false,detail:error.stack})}
 document.body.innerHTML='<pre>'+JSON.stringify(results,null,2)+'</pre>';
 await fetch('/results/integration',{method:'POST',body:JSON.stringify(results)});
 window.location.href='/__legacy-effect';
})();
'''
html='''<!doctype html><html><head><meta charset="utf-8"><style>body{margin:0;background:#111;color:white;font:24px sans-serif} .cards{position:absolute;top:20px;left:30px;display:flex;gap:40px}.cards a{display:block;width:180px;height:100px;background:#344;padding:20px;color:white} ytmusic-player-bar{position:absolute;left:30px;top:500px}video{width:2px;height:2px;position:absolute;right:0;bottom:0}</style></head><body><div class="cards"><a href="#one">First card</a><a href="#two">Second card</a></div><ytmusic-player-bar><a class="title" href="/watch?v=first">Test song</a><span class="byline"><a>Test artist</a></span></ytmusic-player-bar><video class="html5-main-video"></video><script src="/setup.js"></script><script src="/injected.js"></script><script src="/tests.js"></script></body></html>'''
legacy_effect_setup=r'''
window.__legacyEffects=0;
const originalAnimate=Element.prototype.animate;
Element.prototype.animate=function(keyframes,options){
 const animation=originalAnimate.call(this,keyframes,options),effect=animation.effect;
 const partialEffect={target:effect.target,getKeyframes:effect.getKeyframes.bind(effect),getTiming:effect.getTiming.bind(effect)};
 Object.defineProperty(animation,'effect',{configurable:true,value:partialEffect});
 window.__legacyEffects++;return animation;
};
'''
legacy_effect_tests=r'''
(async function(){
 const results=[],errors=[],check=(name,pass,detail)=>results.push({name,pass:!!pass,detail});
 try{
  TvMusic.toggleLyrics();const view=document.querySelector('braccato-lyrics');
  view.host={...view.host,log(...args){if(args.some(value=>value instanceof Error)||String(args[0]).includes('Error in lyrics tick'))errors.push(args.map(String).join(' '))}};
  const request=TvMusic.getLyricsRequest();
  const syncedLyrics=Array.from({length:24},(_,i)=>'['+String(Math.floor(i*3/60)).padStart(2,'0')+':'+String(i*3%60).padStart(2,'0')+']Legacy effect fixture line '+i).join('\n');
  TvMusic.receiveLyrics({...request,status:'synced',syncedLyrics});
  __media.time=18;video.dispatchEvent(new Event('seeked'));TvMusic.media('pause');
  check('fixture provides animation effects without getComputedTiming',window.__legacyEffects>0,window.__legacyEffects);
  check('partial animation effects do not break paused seeks',view.currentTime===18&&view.querySelector('.blyrics--active').textContent.includes('fixture line 6'));
  const scroller=document.querySelector('.tv-lyrics-scroll'),box=scroller.getBoundingClientRect();
  check('partial-effect renderer accepts manual scrolling',TvMusic.scrollAt((box.left+box.width/2)/innerWidth,(box.top+box.height/2)/innerHeight,0,0.2)===true&&view.renderer.container.classList.contains('blyrics-user-scrolling'));
  document.querySelector('[aria-label="Follow current lyric"]').click();
  check('Follow clears manual state despite partial animation effects',!view.renderer.container.classList.contains('blyrics-user-scrolling'));
  __media.time=24;video.dispatchEvent(new Event('seeked'));
  check('later paused seeks still activate correct lyric',view.currentTime===24&&view.querySelector('.blyrics--active').textContent.includes('fixture line 8'));
  check('partial-effect paused flow keeps clock stopped',window.__intervals.size===0,window.__intervals.size);
  check('renderer reports no hidden tick exceptions for partial effects',errors.length===0,errors);
  TvMusic.back();
 }catch(error){results.push({name:'uncaught partial-effect error',pass:false,detail:error.stack})}
 await fetch('/results/legacy-effect',{method:'POST',body:JSON.stringify(results)});
 location.href='/__capability?unsupported';
})();
'''
legacy_effect_html=html.replace('<script src="/injected.js">','<script src="/legacy-effect-setup.js"></script><script src="/injected.js">').replace('/tests.js','/legacy-effect-tests.js')
capability_setup=r'''
if(location.search.includes('unsupported')){
 const original=CSS.supports.bind(CSS);
 CSS.supports=function(property,value){return property==='scroll-behavior'?false:original(property,value)};
}else{
 const original=window.matchMedia.bind(window);
 window.matchMedia=function(query){
  if(!query.includes('prefers-reduced-motion'))return original(query);
  return {matches:true,media:query,addEventListener(){},removeEventListener(){},addListener(){},removeListener(){}};
 };
}
'''
capability_tests=r'''
(async function(){
 const results=[],check=(name,pass,detail)=>results.push({name,pass:!!pass,detail});
 const mode=location.search.includes('unsupported')?'unsupported':'reduced';
 try{
  TvMusic.toggleLyrics();const request=TvMusic.getLyricsRequest();
  TvMusic.receiveLyrics({...request,status:'synced',syncedLyrics:'[00:00]Opening line\n[00:02]Current line\n[00:08]Following line'});
  const view=document.querySelector('braccato-lyrics');
  check('fallback disables smooth-capability class',!document.documentElement.classList.contains('tv-smooth-lyrics'));
  check('fallback keeps instant native scrolling',getComputedStyle(document.querySelector('.tv-lyrics-scroll')).scrollBehavior==='auto');
  check('fallback disables per-line scroll animations',getComputedStyle(view.renderer.container).getPropertyValue('--blyrics-animate-scroll').trim()==='0');
  check('fallback still renders timed active lyrics',view.status==='rendering'&&view.querySelector('.blyrics--active'));
  check('fallback does not build per-letter animation DOM',!view.querySelector('.blyrics--letter'));
  TvMusic.back();check('fallback clock stops when lyrics close',window.__intervals.size===0,window.__intervals.size);
 }catch(error){results.push({name:'uncaught capability error',pass:false,detail:error.stack})}
 await fetch('/results/'+mode,{method:'POST',body:JSON.stringify(results)});
 location.href=mode==='unsupported'?'/__capability?reduced':'/__preview';
})();
'''
capability_html=html.replace('<script src="/injected.js">','<script src="/capability-setup.js"></script><script src="/injected.js">').replace('/tests.js','/capability-tests.js')
preview_tests=r'''
(async function(){
 const results=[];
 const check=(name,pass,detail)=>results.push({name,pass:!!pass,detail});
 try {
  const hostReset=document.createElement('style');hostReset.textContent='* { box-sizing: content-box; }';document.head.appendChild(hostReset);
  check('preview starts in Browse',window.TvMusic&&!TvMusic.isLyricsVisible());
  document.querySelector('video').dispatchEvent(new Event('loadedmetadata'));
  document.querySelector('#lyrics').click();
  const view=document.querySelector('braccato-lyrics');
  check('Lyrics button opens actual renderer',TvMusic.isLyricsVisible()&&view.status==='rendering');
  const capable=CSS.supports('scroll-behavior','smooth')&&!matchMedia('(prefers-reduced-motion: reduce)').matches;
  check('supported browser selects native smooth scrolling',getComputedStyle(document.querySelector('.tv-lyrics-scroll')).scrollBehavior===(capable?'smooth':'auto')&&document.documentElement.classList.contains('tv-smooth-lyrics')===capable);
  check('renderer per-line scroll animation path stays disabled',view.tickOptions.smoothScroll===false&&getComputedStyle(view.renderer.container).getPropertyValue('--blyrics-animate-scroll').trim()==='0');
  check('line timing stays honest without generated word staggering',view.theme.includes('blyrics-line-synced-animation-delay = 0'));
  check('full lyric lines do not run opacity fades',getComputedStyle(view.querySelector('.blyrics--line')).transitionProperty.split(',').every(property=>property.trim()!=='opacity')||getComputedStyle(view.querySelector('.blyrics--line')).transitionDuration.split(',').every(duration=>parseFloat(duration)===0));
  check('demo contains six timed lines',view.lyrics.length===6,view.lyrics.length);
  check('first demo line has separate original text',view.lyrics[0].words==='Open skies above the room',view.lyrics[0].words);
  check('demo origin and simulated clock are visible',document.querySelector('.tv-lyrics-status').textContent==='Original demo text · Simulated clock');
  TvMusic.media('pause');
  document.querySelector('video').currentTime=12;
  view.playing=false;view.currentTime=12;
  check('demo highlights third line at twelve seconds',view.querySelector('.blyrics--active').textContent.includes('The evening finds a slower pace'));
  document.querySelector('video').dispatchEvent(new Event('timeupdate'));
  const transport=document.querySelector('#tv-transport');
  check('video events show transport footer',transport&&!transport.hidden);
  check('transport has explicit border-box independent of page reset',getComputedStyle(transport).boxSizing==='border-box'&&Math.abs(transport.getBoundingClientRect().height-78)<0.1,{sizing:getComputedStyle(transport).boxSizing,height:transport.getBoundingClientRect().height});
  const fits=element=>{
   const r=element.getBoundingClientRect();
   return r.width>0&&r.height>0&&r.left>=0&&r.top>=0&&r.right<=innerWidth+1&&r.bottom<=innerHeight+1;
  };
  const transportTime=transport.querySelector('.tv-transport-time');
  check('timestamps fit viewport',fits(transportTime),{text:transportTime.textContent,viewport:[innerWidth,innerHeight],box:transportTime.getBoundingClientRect().toJSON()});
  check('timestamps show current time and duration',transportTime.textContent==='0:12 / 0:32',transportTime.textContent);
  check('all transport buttons fit viewport',Array.from(transport.querySelectorAll('button')).every(fits));
  const overlayFooter=document.querySelector('#tv-lyrics-overlay footer');
  check('lyrics hint fits above transport',fits(overlayFooter)&&overlayFooter.getBoundingClientRect().bottom<=transport.getBoundingClientRect().top);
  check('lyrics scrolling region has visible height',document.querySelector('.tv-lyrics-scroll').clientHeight>40);
  const identity=document.querySelector('.tv-song-identity'),scroll=document.querySelector('.tv-lyrics-scroll'),cover=document.querySelector('.tv-cover-art');
  const identityBox=identity.getBoundingClientRect(),scrollBox=scroll.getBoundingClientRect();
  check('song identity and lyrics share a split layout',identityBox.right<=scrollBox.left+2&&Math.abs(identityBox.top-scrollBox.top)<40,{identity:identityBox.toJSON(),lyrics:scrollBox.toJSON()});
  check('cover artwork fits viewport',fits(cover),cover.getBoundingClientRect().toJSON());
  check('split title and artist retain separate hierarchy',identity.querySelector('h1').textContent==='Open skies'&&identity.querySelector('.tv-song-artist').textContent==='TV Music Demo');
  check('lyrics pane stays above transport',scrollBox.bottom<=transport.getBoundingClientRect().top&&scrollBox.height>=100,scrollBox.toJSON());
  const overlay=document.querySelector('#tv-lyrics-overlay'),restingTop=identity.getBoundingClientRect().top;
  check('expanded chrome API returns state',TvMusic.setChromeExpanded(true)===true&&document.documentElement.classList.contains('tv-chrome-expanded'));
  const expandedTop=identity.getBoundingClientRect().top;
  check('expanded native chrome reserves header space',expandedTop>restingTop,{restingTop,expandedTop});
  check('collapsed chrome restores available room',TvMusic.setChromeExpanded(false)===false&&Math.abs(identity.getBoundingClientRect().top-restingTop)<1);
  if(location.search.includes('narrow'))check('TV viewport is exactly 916 by 454 CSS pixels',innerWidth===916&&innerHeight===454,[innerWidth,innerHeight]);
  check('preview reports no browser errors',!window.__previewErrors.length,window.__previewErrors);
 }catch(error){results.push({name:'uncaught preview error',pass:false,detail:error.stack})}
 const narrow=location.search.includes('narrow');
 await fetch('/results/'+(narrow?'narrow':'preview'),{method:'POST',body:JSON.stringify(results)});
 if(!narrow&&!location.search.includes('capture'))window.location.href='/__narrow';
})();
'''
preview_html=(project/'dev/preview.html').read_text()
preview_html=preview_html.replace('<head>','<head><base href="/dev/">') if '<head>' in preview_html else preview_html.replace('<meta charset="utf-8">','<base href="/dev/"><meta charset="utf-8">')
preview_html=preview_html.replace('<title>','<script src="/__preview-errors.js"></script><title>',1)
preview_html=preview_html.replace('</html>','<script src="/__preview-tests.js"></script></html>')

class Handler(SimpleHTTPRequestHandler):
 def __init__(self,*args,**kwargs):super().__init__(*args,directory=str(project),**kwargs)
 def log_message(self,*args): pass
 def do_GET(self):
  path=urlsplit(self.path).path
  content={'/':html,'/setup.js':setup,'/injected.js':injected,'/tests.js':tests,
    '/__legacy-effect':legacy_effect_html,'/legacy-effect-setup.js':legacy_effect_setup,'/legacy-effect-tests.js':legacy_effect_tests,
    '/__capability':capability_html,'/capability-setup.js':capability_setup,'/capability-tests.js':capability_tests,
    '/__preview':preview_html,'/__preview-tests.js':preview_tests,
    '/__narrow':'<!doctype html><html><body style="margin:0"><iframe src="/__preview?narrow" style="display:block;width:916px;height:454px;border:0"></iframe></body></html>',
    '/__preview-errors.js':"window.__previewErrors=[];window.addEventListener('error',e=>window.__previewErrors.push(e.message));"}.get(path)
  if content is None:return super().do_GET()
  self.send_response(200);self.send_header('Content-Type','text/html' if path in ['/', '/__preview', '/__narrow', '/__capability', '/__legacy-effect'] else 'text/javascript');self.end_headers();self.wfile.write(content.encode())
 def do_POST(self):
  name=self.path.removeprefix('/results/')
  if name not in ['integration','preview','narrow','unsupported','reduced','legacy-effect']:self.send_error(404);return
  results[name]=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
  self.send_response(200);self.end_headers()
  if name=='narrow':done.set()
server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
threading.Thread(target=server.serve_forever,daemon=True).start()
temporary=tempfile.TemporaryDirectory(prefix='ytmtv-firefox-check-')
profile=Path(temporary.name)/'profile';profile.mkdir()
url=f'http://127.0.0.1:{server.server_port}'
version=subprocess.run([args.firefox,'--version'],capture_output=True,text=True,check=True).stdout.strip()
print(version+' (headless; this does not verify Chrome 80 or Android WebView)')
p=subprocess.Popen([args.firefox,'--headless','--profile',str(profile),'--width','1280','--height','720',url+'/'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
exit_code=1
try:
 if not done.wait(25): print('ERROR: browser did not return results')
 else:
  for name,checks in results.items():
   print(name+': '+str(sum(x['pass'] for x in checks))+'/'+str(len(checks))+' checks passed')
   for check in checks:
    if not check['pass']:print('FAIL '+check['name']+': '+str(check.get('detail','')))
  exit_code=0 if all(check['pass'] for checks in results.values() for check in checks) else 1
  if args.report:
   args.report.parent.mkdir(parents=True,exist_ok=True)
   args.report.write_text(json.dumps({'browser':version,'suites':results},indent=2)+'\n')
finally:
 p.terminate()
 try: p.wait(timeout=5)
 except subprocess.TimeoutExpired:p.kill();p.wait()

for screenshot,size,query in [(args.preview_screenshot,'1280,720','capture'),
    (args.narrow_screenshot,'916,454','narrow&capture')]:
 if screenshot and done.is_set():
  screenshot.parent.mkdir(parents=True,exist_ok=True)
  screenshot_profile=Path(temporary.name)/('screenshot-'+size);screenshot_profile.mkdir()
  try:
   subprocess.run([args.firefox,'--headless','--profile',str(screenshot_profile),
     '--screenshot',str(screenshot.resolve()),'--window-size',size,url+'/__preview?'+query],
     check=True,timeout=25,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
   print('Preview screenshot: '+str(screenshot.resolve()))
  except (subprocess.CalledProcessError,subprocess.TimeoutExpired) as error:
   print('Screenshot failed: '+str(error));exit_code=1

server.shutdown()
temporary.cleanup()
raise SystemExit(exit_code)
