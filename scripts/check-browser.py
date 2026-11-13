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
window.__media={time:3,paused:false,ended:false,plays:0,pauses:0};
window.__session={metadata:null};Object.defineProperty(navigator,'mediaSession',{configurable:true,value:window.__session});
const video=document.querySelector('video');
Object.defineProperties(video,{duration:{get:()=>180},currentTime:{get:()=>__media.time,set:v=>__media.time=v},paused:{get:()=>__media.paused},ended:{get:()=>__media.ended}});
video.play=function(){__media.paused=false;__media.plays++;return Promise.resolve()};
video.pause=function(){__media.paused=true;__media.pauses++};
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
 const payload=(request,extra)=>Object.assign({},request,extra);
 function track(title,artist,id){document.querySelector('ytmusic-player-bar .title').textContent=title;document.querySelector('ytmusic-player-bar .byline a').textContent=artist;document.querySelector('ytmusic-player-bar .title').setAttribute('href','/watch?v='+id)}
 try {
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
 check('overlay CSS applied',getComputedStyle(document.querySelector('#tv-lyrics-overlay')).backgroundColor==='rgb(16, 27, 32)');
 check('synced result accepted',TvMusic.receiveLyrics(payload(first,{status:'synced',syncedLyrics:'[00:00.00]First line\n[00:02.00]Second line\n[00:08.00]Third line'}))===true);
 check('actual renderer mounted',view().status==='rendering'&&view().querySelectorAll('.blyrics--line').length===3,view().status);
 check('letter DOM disabled by applied theme',!view().querySelector('.blyrics--letter')&&view().theme.includes('blyrics-letter-wave = false'));
 __media.time=3;await delay(320);
 check('clock follows video playback',view().currentTime===3&&view().playing&&window.__intervals.size===1,{time:view().currentTime,playing:view().playing,intervals:window.__intervals.size});
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
 TvMusic.receiveLyrics(payload(second,{status:'unavailable',message:'No lyrics for this recording.'}));
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
 TvMusic.resume();check('resume restarts one clock',window.__intervals.size===1);
 scroller().scrollTop=0;
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
 scroller().scrollTop=0;
 const lyricBox=scroller().getBoundingClientRect();
 const lyricScroll=TvMusic.scrollAt((lyricBox.left+lyricBox.width/2)/innerWidth,(lyricBox.top+lyricBox.height/2)/innerHeight,0,0.3);
 check('cursor scroll reaches lyrics scroller',lyricScroll===true&&scroller().scrollTop>0,scroller().scrollTop);
 TvMusic.back();
 }catch(error){results.push({name:'uncaught integration error',pass:false,detail:error.stack})}
 document.body.innerHTML='<pre>'+JSON.stringify(results,null,2)+'</pre>';
 await fetch('/results/integration',{method:'POST',body:JSON.stringify(results)});
 window.location.href='/__preview';
})();
'''
html='''<!doctype html><html><head><meta charset="utf-8"><style>body{margin:0;background:#111;color:white;font:24px sans-serif} .cards{position:absolute;top:20px;left:30px;display:flex;gap:40px}.cards a{display:block;width:180px;height:100px;background:#344;padding:20px;color:white} ytmusic-player-bar{position:absolute;left:30px;top:500px}video{width:2px;height:2px;position:absolute;right:0;bottom:0}</style></head><body><div class="cards"><a href="#one">First card</a><a href="#two">Second card</a></div><ytmusic-player-bar><a class="title" href="/watch?v=first">Test song</a><span class="byline"><a>Test artist</a></span></ytmusic-player-bar><video class="html5-main-video"></video><script src="/setup.js"></script><script src="/injected.js"></script><script src="/tests.js"></script></body></html>'''
preview_tests=r'''
(async function(){
 const results=[];
 const check=(name,pass,detail)=>results.push({name,pass:!!pass,detail});
 try {
  check('preview starts in Browse',window.TvMusic&&!TvMusic.isLyricsVisible());
  document.querySelector('video').dispatchEvent(new Event('loadedmetadata'));
  document.querySelector('#lyrics').click();
  const view=document.querySelector('braccato-lyrics');
  check('Lyrics button opens actual renderer',TvMusic.isLyricsVisible()&&view.status==='rendering');
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
    '/__preview':preview_html,'/__preview-tests.js':preview_tests,
    '/__narrow':'<!doctype html><html><body style="margin:0"><iframe src="/__preview?narrow" style="display:block;width:916px;height:454px;border:0"></iframe></body></html>',
    '/__preview-errors.js':"window.__previewErrors=[];window.addEventListener('error',e=>window.__previewErrors.push(e.message));"}.get(path)
  if content is None:return super().do_GET()
  self.send_response(200);self.send_header('Content-Type','text/html' if path in ['/', '/__preview', '/__narrow'] else 'text/javascript');self.end_headers();self.wfile.write(content.encode())
 def do_POST(self):
  name=self.path.removeprefix('/results/')
  if name not in ['integration','preview','narrow']:self.send_error(404);return
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

if args.preview_screenshot and done.is_set():
 args.preview_screenshot.parent.mkdir(parents=True,exist_ok=True)
 screenshot_profile=Path(temporary.name)/'screenshot-profile';screenshot_profile.mkdir()
 try:
  subprocess.run([args.firefox,'--headless','--profile',str(screenshot_profile),
    '--screenshot',str(args.preview_screenshot.resolve()),'--window-size','1280,720',url+'/__preview?capture'],
    check=True,timeout=25,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
  print('Preview screenshot: '+str(args.preview_screenshot.resolve()))
 except (subprocess.CalledProcessError,subprocess.TimeoutExpired) as error:
  print('Screenshot failed: '+str(error));exit_code=1

server.shutdown()
temporary.cleanup()
raise SystemExit(exit_code)
