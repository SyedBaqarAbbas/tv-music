package dev.ytmusictv.nativeapp.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.speech.SpeechRecognizer;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.view.PixelCopy;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionResult;
import androidx.media3.session.SessionCommand;
import com.google.common.util.concurrent.Futures;
import java.util.EnumMap;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.common.util.concurrent.ListenableFuture;
import dev.ytmusictv.SignInActivity;
import dev.ytmusictv.nativeapp.model.NativeTrack;
import dev.ytmusictv.nativeapp.model.NativeCollection;
import dev.ytmusictv.nativeapp.model.NativeArtistPage;
import dev.ytmusictv.nativeapp.model.NativeAlbumPage;
import dev.ytmusictv.nativeapp.playback.NativePlaybackCommands;
import dev.ytmusictv.nativeapp.playback.NativePlaybackService;
import dev.ytmusictv.nativeapp.provider.NativeMusicProvider;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/** Real native catalogue, session controls and recycled lyrics. No Music website is loaded. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
public final class NativeTvActivity extends Activity {
    private enum Screen { HOME, SEARCH, ARTIST, ALBUM, NOW, QUEUE }
    private enum SearchKind { SONGS, ARTISTS, ALBUMS }
    private static final int REQUEST_SIGN_IN=74,REQUEST_VOICE_PERMISSION=76;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Executor uiExecutor=command->main.post(command);
    private final ArrayDeque<NavigationState> back=new ArrayDeque<>(),forward=new ArrayDeque<>();
    private final EnumMap<Screen,View> retainedFocus=new EnumMap<>(Screen.class);
    private final ArrayList<NativeTrack> results=new ArrayList<>(),library=new ArrayList<>(),albumTracks=new ArrayList<>();
    private final ArrayList<NativeCollection> searchCollections=new ArrayList<>(),artistAlbums=new ArrayList<>();
    private NativeCollection selectedArtist,selectedAlbum;
    private SearchKind searchKind=SearchKind.SONGS;
    private boolean searchLoaded,artistLoaded,albumLoaded,albumComplete=true,artistComplete=true,restoringNavigation;
    private long browseGeneration;
    private final ArrayList<Entry> entries=new ArrayList<>(),draft=new ArrayList<>();
    private NativeMusicProvider provider;
    private MediaController controller;
    private ListenableFuture<MediaController> connection;
    private LinearLayout root,nav,mini;
    private FrameLayout content,artFrame,header,mediaStage;
    private Button controlsHandle;
    private boolean chromeExpanded;
    private View homePage,searchPage,artistPage,albumPage,nowPage,queuePage;
    private TextView status,miniTitle,time,nowTitle,nowArtist,nowEyebrow,queueHint,playbackState;
    private EditText search;
    private TextView searchHint,artistTitle,artistSubtitle,albumTitle,albumSubtitle,albumNotice;
    private Button songsFilter,artistsFilter,albumsFilter,playAlbum,nextAlbum,addAlbum,voiceButton;
    private boolean voiceInFlight,libraryNeedsRefresh,resumeAfterVoice,voiceResumeReady;
    private String voiceEntryId="";
    private NativeVoiceSearch voiceSearch;
    private AlertDialog voiceDialog;
    private TextView voiceStatus;
    private ProgressBar voiceLevel;
    private long voiceLevelAt;
    private SeekBar seek;
    private Button play,miniPlay,modeButton,lyricsButton,undoButton,shuffleButton,repeatButton;
    private SurfaceView video;
    private NativeArtworkView artwork;
    private boolean surfaceAttached;
    private Boolean lastSentVideoVisible;
    private final Handler captureHandler=new Handler(Looper.getMainLooper());
    private boolean videoCapturePending;
    private int videoCaptureGeneration;
    private NativeLyricsPanel lyrics;
    private RecyclerView resultList,libraryList,queueList,artistList,albumList;
    private final TrackRows searchRows=new TrackRows(results),libraryRows=new TrackRows(library),albumRows=new TrackRows(albumTracks);
    private final CollectionRows collectionSearchRows=new CollectionRows(searchCollections),artistAlbumRows=new CollectionRows(artistAlbums);
    private final QueueRows queueRows=new QueueRows();
    private Screen screen=Screen.HOME;
    private NativeTrack playing;
    private boolean foreground,videoMode,lyricsShown,shuffle,background,snapshotInFlight,dragging,connected;
    private int repeat;
    private long searchGeneration,libraryGeneration;
    private String moving="",moveCurrent="",lastError="",actionError="";
    private boolean destroyed;
    private boolean seeking,debugRemote;
    private final Set<Long> heldShortcuts=new HashSet<>();
    private final LinkedHashSet<String> artworkRepairAttempts=new LinkedHashSet<>();
    private String pendingArtworkRepair="";
    private long artworkRepairGeneration;
    private final Runnable tick=new Runnable(){@Override public void run(){
        if(!foreground)return;updateTransport();if(screen==Screen.NOW&&lyricsShown)lyrics.updateClock();
        if(controller!=null&&controller.isPlaying())main.postDelayed(this,500);
    }};
    private final Player.Listener playerListener=new Player.Listener(){
        @Override public void onEvents(Player player,Player.Events events){
            if(!foreground||destroyed)return;
            updateTransport();requestSnapshot();scheduleClock();
        }
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);debugRemote=(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0;getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        provider=new NativeMusicProvider(main,()->{
            String cookies=CookieManager.getInstance().getCookie("https://music.youtube.com");
            return cookies==null?"":cookies;
        },(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0);
        buildUi();chromeExpanded=getPreferences(MODE_PRIVATE).getBoolean("nativeChromeExpanded",false);setChrome(chromeExpanded,false);
        if(state!=null){
            search.setText(state.getString("query",""));lyricsShown=state.getBoolean("lyrics");
            try{searchKind=SearchKind.valueOf(state.getString("searchKind","SONGS"));}catch(IllegalArgumentException ignored){}
            resultList.setAdapter(searchKind==SearchKind.SONGS?searchRows:collectionSearchRows);styleSearchFilters();
        }
        show(Screen.HOME,false);loadLibrary();
    }
    private void buildUi(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(NativeUi.BG);
        root.setPadding(dp(24),dp(14),dp(24),dp(14));setContentView(root);
        nav=new LinearLayout(this);nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=NativeUi.text(this,"MUSIC / TV",20,NativeUi.INK);NativeUi.heading(brand);
        nav.addView(brand,new LinearLayout.LayoutParams(0,dp(46),1));
        add(nav,"Home",()->show(Screen.HOME,true));add(nav,"Search",()->{show(Screen.SEARCH,true);search.requestFocus();});
        add(nav,"Now playing",()->show(Screen.NOW,true));add(nav,"Queue",()->show(Screen.QUEUE,true));
        add(nav,"Account",this::account);add(nav,"More",this::settings);
        add(nav,"Hide ↑",()->setChrome(false,true));
        header=new FrameLayout(this);header.addView(nav,new FrameLayout.LayoutParams(-1,dp(48)));
        controlsHandle=NativeUi.button(this,"☰  Controls",()->setChrome(true,true));
        header.addView(controlsHandle,new FrameLayout.LayoutParams(-2,dp(34),Gravity.LEFT|Gravity.TOP));
        root.addView(header,new LinearLayout.LayoutParams(-1,dp(48)));
        status=NativeUi.text(this,"Getting ready…",12,NativeUi.MUTED);
        root.addView(status,new LinearLayout.LayoutParams(-1,dp(29)));
        content=new FrameLayout(this);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        homePage=createHome();searchPage=createSearch();artistPage=createArtist();albumPage=createAlbum();nowPage=createNow();queuePage=createQueue();
        mini=new LinearLayout(this);mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setPadding(dp(12),dp(8),dp(8),dp(8));mini.setBackground(NativeUi.fill(this,NativeUi.CARD,false));
        miniTitle=NativeUi.text(this,"Choose your next listen",14,NativeUi.INK);miniTitle.setSingleLine(true);
        mini.addView(miniTitle,new LinearLayout.LayoutParams(0,dp(42),1));
        add(mini,"Previous",this::previous);miniPlay=add(mini,"Play",this::togglePlay);add(mini,"Next",this::next);
        add(mini,"Lyrics",()->{lyricsShown=true;show(Screen.NOW,true);updateNow();});
        add(mini,"Queue",()->show(Screen.QUEUE,true));
        LinearLayout.LayoutParams miniLayout=new LinearLayout.LayoutParams(-1,dp(58));miniLayout.topMargin=dp(12);root.addView(mini,miniLayout);
    }
    private View createHome(){
        LinearLayout page=vertical();TextView title=NativeUi.text(this,"A little space for your music.",30,NativeUi.INK);NativeUi.heading(title);
        page.addView(title,new LinearLayout.LayoutParams(-1,dp(56)));
        TextView hint=NativeUi.text(this,"Search for something new, or choose from your liked songs.",15,NativeUi.MUTED);
        page.addView(hint,new LinearLayout.LayoutParams(-1,dp(36)));
        LinearLayout actions=new LinearLayout(this);add(actions,"Find music",()->{show(Screen.SEARCH,true);search.requestFocus();openKeyboard();});
        add(actions,"Refresh liked songs",this::loadLibrary);add(actions,"Sign in",this::signIn);
        add(actions,"Resume saved queue",()->send(NativePlaybackCommands.RESTORE,Bundle.EMPTY,"Saved queue restored. Press Play when ready."));
        page.addView(actions,new LinearLayout.LayoutParams(-1,dp(48)));
        libraryList=recycler(libraryRows);page.addView(libraryList,new LinearLayout.LayoutParams(-1,0,1));return page;
    }
    private View createSearch(){
        LinearLayout page=vertical();LinearLayout field=new LinearLayout(this);field.setGravity(Gravity.CENTER_VERTICAL);
        search=new EditText(this);search.setSingleLine(true);search.setTextSize(19);search.setTextColor(NativeUi.INK);
        search.setHintTextColor(NativeUi.MUTED);search.setHint("Song, artist or album");
        search.setInputType(InputType.TYPE_CLASS_TEXT);search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(16),0,dp(16),0);search.setBackground(NativeUi.fill(this,NativeUi.CARD,false));
        search.setOnEditorActionListener((view,action,event)->{
            if(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER){
                if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)runSearch();
                return true;
            }
            if(action==EditorInfo.IME_ACTION_SEARCH){runSearch();return true;}
            return false;
        });
        field.addView(search,new LinearLayout.LayoutParams(0,dp(48),1));add(field,"Search",this::runSearch);
        voiceButton=add(field,"Voice",this::startVoiceSearch);
        page.addView(field,new LinearLayout.LayoutParams(-1,dp(54)));
        LinearLayout filters=new LinearLayout(this);
        songsFilter=add(filters,"Songs",()->selectSearchKind(SearchKind.SONGS,true));
        artistsFilter=add(filters,"Artists",()->selectSearchKind(SearchKind.ARTISTS,true));
        albumsFilter=add(filters,"Albums",()->selectSearchKind(SearchKind.ALBUMS,true));
        for(Button filter:new Button[]{songsFilter,artistsFilter,albumsFilter})filter.setOnFocusChangeListener((v,f)->styleSearchFilters());
        page.addView(filters,new LinearLayout.LayoutParams(-1,dp(46)));
        searchHint=NativeUi.text(this,"OK plays a result. Actions offers Play next and Add to queue.",13,NativeUi.MUTED);
        page.addView(searchHint,new LinearLayout.LayoutParams(-1,dp(30)));
        styleSearchFilters();
        resultList=recycler(searchRows);page.addView(resultList,new LinearLayout.LayoutParams(-1,0,1));return page;
    }
    private View createArtist(){
        LinearLayout page=vertical(),heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        artistTitle=NativeUi.text(this,"Artist",28,NativeUi.INK);NativeUi.heading(artistTitle);
        heading.addView(artistTitle,new LinearLayout.LayoutParams(0,dp(48),1));
        add(heading,"Back",this::onBackPressed);add(heading,"Refresh",this::loadArtist);
        page.addView(heading,new LinearLayout.LayoutParams(-1,dp(50)));
        artistSubtitle=NativeUi.text(this,"",14,NativeUi.MUTED);page.addView(artistSubtitle,new LinearLayout.LayoutParams(-1,dp(30)));
        TextView section=NativeUi.text(this,"ALBUMS & RELEASES",12,NativeUi.GOLD);page.addView(section,new LinearLayout.LayoutParams(-1,dp(28)));
        artistList=recycler(artistAlbumRows);page.addView(artistList,new LinearLayout.LayoutParams(-1,0,1));return page;
    }
    private View createAlbum(){
        LinearLayout page=vertical(),heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        albumTitle=NativeUi.text(this,"Album",28,NativeUi.INK);NativeUi.heading(albumTitle);albumTitle.setSingleLine(true);
        heading.addView(albumTitle,new LinearLayout.LayoutParams(0,dp(48),1));add(heading,"Back",this::onBackPressed);
        page.addView(heading,new LinearLayout.LayoutParams(-1,dp(50)));
        albumSubtitle=NativeUi.text(this,"",14,NativeUi.MUTED);page.addView(albumSubtitle,new LinearLayout.LayoutParams(-1,dp(30)));
        LinearLayout actions=new LinearLayout(this);
        playAlbum=add(actions,"Play all",()->queueAlbum(NativePlaybackCommands.PLAY_COLLECTION));
        nextAlbum=add(actions,"Play next",()->queueAlbum(NativePlaybackCommands.PLAY_NEXT_COLLECTION));
        addAlbum=add(actions,"Add all to queue",()->queueAlbum(NativePlaybackCommands.ADD_COLLECTION));
        add(actions,"Refresh",this::loadAlbum);page.addView(actions,new LinearLayout.LayoutParams(-1,dp(46)));
        albumNotice=NativeUi.text(this,"Play all replaces the queue. Next and Add keep your current track.",12,NativeUi.MUTED);
        page.addView(albumNotice,new LinearLayout.LayoutParams(-1,dp(32)));
        albumList=recycler(albumRows);page.addView(albumList,new LinearLayout.LayoutParams(-1,0,1));updateAlbumPage();return page;
    }
    private View createNow(){
        LinearLayout page=vertical();LinearLayout room=new LinearLayout(this);room.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout identity=vertical();identity.setPadding(dp(10),dp(14),dp(26),dp(14));
        nowEyebrow=NativeUi.text(this,"NOW PLAYING",11,NativeUi.GOLD);identity.addView(nowEyebrow,new LinearLayout.LayoutParams(-1,dp(28)));
        nowTitle=NativeUi.text(this,"Nothing playing yet",26,NativeUi.INK);NativeUi.heading(nowTitle);nowTitle.setMaxLines(2);
        identity.addView(nowTitle,new LinearLayout.LayoutParams(-1,-2));
        nowArtist=NativeUi.text(this,"Find a song and make it yours.",18,NativeUi.MUTED);nowArtist.setMaxLines(1);
        identity.addView(nowArtist,new LinearLayout.LayoutParams(-1,dp(42)));
        artFrame=new FrameLayout(this);artFrame.setBackground(NativeUi.fill(this,NativeUi.CARD,false));
        artwork=new NativeArtworkView(this);
        artFrame.addView(artwork,new FrameLayout.LayoutParams(-1,-1));
        video=new SurfaceView(this);video.setVisibility(View.GONE);artFrame.addView(video,new FrameLayout.LayoutParams(-1,-1));
        mediaStage=new FrameLayout(this);
        mediaStage.addView(artFrame,new FrameLayout.LayoutParams(dp(200),dp(200),Gravity.CENTER));
        mediaStage.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,oright,ob)->sizeMediaFrame());
        identity.addView(mediaStage,new LinearLayout.LayoutParams(-1,0,1));
        playbackState=NativeUi.text(this,"",13,NativeUi.MUTED);identity.addView(playbackState,new LinearLayout.LayoutParams(-1,dp(32)));
        room.addView(identity,new LinearLayout.LayoutParams(0,-1,2));
        lyrics=new NativeLyricsPanel(this,new NativeLyricsPanel.Clock(){
            public long position(){return controller==null?0:controller.getCurrentPosition();}
            public long duration(){return durationMs();}
            public void seek(long position){if(controller!=null){controller.seekTo(position);updateTransport();lyrics.updateClock();}}
        });
        room.addView(lyrics,new LinearLayout.LayoutParams(0,-1,3));page.addView(room,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);
        add(controls,"Previous",this::previous);play=add(controls,"Play",this::togglePlay);add(controls,"Next",this::next);
        lyricsButton=add(controls,"Lyrics",()->{lyricsShown=!lyricsShown;updateNow();});
        add(controls,"Queue",()->show(Screen.QUEUE,true));
        modeButton=add(controls,"Video",()->send(NativePlaybackCommands.SET_VIDEO,NativePlaybackCommands.boolArgs(!videoMode),""));
        add(controls,"Restart",()->{if(controller!=null)controller.seekTo(0);});
        add(controls,"Retry",()->send(NativePlaybackCommands.RETRY,Bundle.EMPTY,"Retrying playback…"));
        page.addView(controls,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout progress=new LinearLayout(this);progress.setGravity(Gravity.CENTER_VERTICAL);progress.setBaselineAligned(false);
        time=NativeUi.text(this,"0:00 / —",13,NativeUi.MUTED);
        time.setSingleLine(true);time.setHorizontallyScrolling(false);time.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        time.setTextDirection(View.TEXT_DIRECTION_LTR);time.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        progress.addView(time,new LinearLayout.LayoutParams(dp(115),dp(38)));
        seek=new SeekBar(this);seek.setMax(1000);seek.setProgressTintList(android.content.res.ColorStateList.valueOf(NativeUi.GOLD));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(NativeUi.GOLD));
        seek.setOnKeyListener((view,key,event)->{
            if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){
                if(event.getAction()==KeyEvent.ACTION_DOWN)seekRelative(key==21?-10_000:10_000);return true;
            }return false;
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){seeking=true;}
            public void onStopTrackingTouch(SeekBar bar){seeking=false;if(controller!=null&&durationMs()>0)controller.seekTo(durationMs()*bar.getProgress()/1000);}
            public void onProgressChanged(SeekBar bar,int value,boolean fromUser){}
        });
        progress.addView(seek,new LinearLayout.LayoutParams(0,dp(38),1));page.addView(progress,new LinearLayout.LayoutParams(-1,dp(38)));return page;
    }
    private View createQueue(){
        LinearLayout page=vertical();LinearLayout tools=new LinearLayout(this);tools.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=NativeUi.text(this,"Up next",28,NativeUi.INK);NativeUi.heading(title);
        tools.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        undoButton=add(tools,"Undo remove",()->send(NativePlaybackCommands.UNDO,Bundle.EMPTY,"Removal undone."));
        shuffleButton=add(tools,"Shuffle off",()->send(NativePlaybackCommands.SET_SHUFFLE,NativePlaybackCommands.boolArgs(!shuffle),""));
        repeatButton=add(tools,"Repeat off",()->send(NativePlaybackCommands.SET_REPEAT,NativePlaybackCommands.repeatArgs((repeat+1)%3),""));
        add(tools,"Clear upcoming",this::clearUpcoming);page.addView(tools,new LinearLayout.LayoutParams(-1,dp(50)));
        queueHint=NativeUi.text(this,"Your current track stays pinned while you arrange what follows.",13,NativeUi.MUTED);
        page.addView(queueHint,new LinearLayout.LayoutParams(-1,dp(34)));
        queueList=recycler(queueRows);page.addView(queueList,new LinearLayout.LayoutParams(-1,0,1));return page;
    }
    private LinearLayout vertical(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private RecyclerView recycler(RecyclerView.Adapter<?> adapter){
        RecyclerView v=new RecyclerView(this);v.setLayoutManager(new LinearLayoutManager(this));v.setAdapter(adapter);
        v.setItemAnimator(null);v.setPadding(0,dp(6),0,dp(18));v.setClipToPadding(false);return v;
    }
    private Button add(LinearLayout row,String label,Runnable action){
        Button b=NativeUi.button(this,label,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(40));
        p.setMarginStart(dp(7));
        b.setNextFocusLeftId(b.getId());b.setNextFocusRightId(b.getId());
        for(int index=row.getChildCount()-1;index>=0;index--){
            View previous=row.getChildAt(index);if(!previous.isFocusable())continue;
            if(previous.getId()==View.NO_ID)previous.setId(View.generateViewId());
            previous.setNextFocusRightId(b.getId());b.setNextFocusLeftId(previous.getId());break;
        }
        row.addView(b,p);return b;
    }
    private void show(Screen destination,boolean record){
        if(destination!=Screen.SEARCH&&(voiceInFlight||voiceResumeReady||voiceDialog!=null))cancelNativeVoice(false,false);
        View focused=getCurrentFocus();if(focused!=null)retainedFocus.put(screen,focused);
        hideKeyboard();
        if(record&&destination!=screen)rememberNavigation();
        if(screen==Screen.NOW&&destination!=Screen.NOW){cancelArtworkRepair();lyrics.stop();artwork.stopLoading();detachVideo();}
        if(!moving.isEmpty()&&destination!=Screen.QUEUE)cancelMove();
        screen=destination;updateVideoVisibility();mini.setVisibility(destination==Screen.NOW?View.GONE:View.VISIBLE);
        if(destination==Screen.NOW)setChrome(false,false);
        status.setVisibility(destination==Screen.NOW&&lastError.isEmpty()?View.GONE:View.VISIBLE);
        content.removeAllViews();
        View page=destination==Screen.HOME?homePage:destination==Screen.SEARCH?searchPage:destination==Screen.ARTIST?artistPage:destination==Screen.ALBUM?albumPage:destination==Screen.NOW?nowPage:queuePage;
        content.addView(page,new FrameLayout.LayoutParams(-1,-1));
        if(destination==Screen.NOW)updateNow();
        if(destination==Screen.QUEUE)requestSnapshot();
        if(destination==Screen.HOME&&libraryNeedsRefresh)loadLibrary();
        if(!restoringNavigation&&destination==Screen.SEARCH&&!searchLoaded)search.requestFocus();
        else if(!restoringNavigation)page.post(()->{View restore=retainedFocus.get(destination);if(restore!=null&&restore.isShown())focusTarget(restore);else page.requestFocus(View.FOCUS_DOWN);});
        updateTransport();
    }
    private void styleSearchFilters(){
        if(songsFilter==null)return;
        Button[] buttons={songsFilter,artistsFilter,albumsFilter};SearchKind[] kinds=SearchKind.values();
        for(int index=0;index<buttons.length;index++){
            Button button=buttons[index];boolean selected=searchKind==kinds[index],focused=button.hasFocus();
            button.setSelected(selected);button.setTextColor(focused?NativeUi.BG:selected?NativeUi.GOLD:NativeUi.INK);
            button.setBackground(NativeUi.fill(this,focused?NativeUi.GOLD:NativeUi.CARD,selected||focused));
        }
        if(searchHint!=null)searchHint.setText(searchKind==SearchKind.SONGS?"OK plays. Actions offers Play next and Add to queue."
                :searchKind==SearchKind.ARTISTS?"Open an artist to browse their albums and releases.":"Open an album to see every loaded track and queue it together.");
    }
    private void selectSearchKind(SearchKind kind,boolean performSearch){
        if(searchKind==kind){if(performSearch&&!search.getText().toString().trim().isEmpty())runSearch();return;}
        searchKind=kind;searchLoaded=false;searchGeneration++;
        if(kind==SearchKind.SONGS){results.clear();searchRows.notifyDataSetChanged();resultList.setAdapter(searchRows);}
        else{searchCollections.clear();collectionSearchRows.notifyDataSetChanged();resultList.setAdapter(collectionSearchRows);}
        styleSearchFilters();
        if(performSearch&&!search.getText().toString().trim().isEmpty())runSearch();
    }
    private void runSearch(){
        String query=search.getText().toString().trim();if(query.isEmpty()){search.requestFocus();openKeyboard();return;}
        hideKeyboard();long generation=++searchGeneration;searchLoaded=false;status.setVisibility(View.VISIBLE);
        status.setText("Searching "+searchKind.name().toLowerCase(java.util.Locale.ROOT)+" for “"+query+"”…");
        if(searchKind==SearchKind.SONGS){
            results.clear();searchRows.notifyDataSetChanged();resultList.setAdapter(searchRows);
            provider.search(query,new NativeMusicProvider.Callback<List<NativeTrack>>(){
                public void onSuccess(List<NativeTrack> tracks){
                    if(destroyed||isFinishing()||generation!=searchGeneration)return;
                    results.clear();results.addAll(tracks);searchLoaded=true;searchRows.notifyDataSetChanged();
                    status.setText(tracks.isEmpty()?"No matching songs. Try another search.":tracks.size()+" songs · select a track or open Actions");
                    if(screen==Screen.SEARCH&&!results.isEmpty())focusRow(resultList,0,true,0);
                }
                public void onError(String message){if(generation==searchGeneration){searchLoaded=true;status.setText(message);}}
            });
        }else{
            searchCollections.clear();collectionSearchRows.notifyDataSetChanged();resultList.setAdapter(collectionSearchRows);
            NativeMusicProvider.Callback<List<NativeCollection>> callback=new NativeMusicProvider.Callback<List<NativeCollection>>(){
                public void onSuccess(List<NativeCollection> items){
                    if(destroyed||isFinishing()||generation!=searchGeneration)return;
                    searchCollections.clear();searchCollections.addAll(items);searchLoaded=true;collectionSearchRows.notifyDataSetChanged();
                    status.setText(items.isEmpty()?"No matches. Try an artist name or album title.":items.size()+" results · OK opens");
                    if(screen==Screen.SEARCH&&!items.isEmpty())focusRow(resultList,0,true,0);
                }
                public void onError(String message){if(generation==searchGeneration){searchLoaded=true;status.setText(message);}}
            };
            if(searchKind==SearchKind.ARTISTS)provider.searchArtists(query,callback);else provider.searchAlbums(query,callback);
        }
    }
    private void navigateSearch(String query,SearchKind kind){
        rememberNavigation();selectSearchKind(kind,false);search.setText(query);show(Screen.SEARCH,false);runSearch();
    }
    private void openCollection(NativeCollection collection){
        if(collection==null)return;rememberNavigation();
        if(collection.kind==NativeCollection.Kind.ARTIST){
            selectedArtist=collection;artistAlbums.clear();artistLoaded=false;artistAlbumRows.notifyDataSetChanged();
            artistTitle.setText(collection.title);artistSubtitle.setText(collection.subtitle);
            show(Screen.ARTIST,false);loadArtist();
        }else{
            selectedAlbum=collection;albumTracks.clear();albumLoaded=false;albumComplete=true;albumRows.notifyDataSetChanged();
            updateAlbumPage();show(Screen.ALBUM,false);loadAlbum();
        }
    }
    private void loadArtist(){
        if(selectedArtist==null)return;
        final String id=selectedArtist.id;long generation=++browseGeneration;artistLoaded=false;status.setText("Loading albums…");
        provider.artist(selectedArtist,new NativeMusicProvider.Callback<NativeArtistPage>(){
            public void onSuccess(NativeArtistPage page){
                if(destroyed||generation!=browseGeneration||screen!=Screen.ARTIST||selectedArtist==null||!id.equals(selectedArtist.id))return;
                if(page.artist!=null)selectedArtist=page.artist;
                artistAlbums.clear();artistAlbums.addAll(page.albums);artistLoaded=true;artistComplete=page.complete;
                artistTitle.setText(selectedArtist.title);artistSubtitle.setText(selectedArtist.subtitle);artistAlbumRows.notifyDataSetChanged();
                status.setText(artistAlbums.isEmpty()?"No albums found for this artist.":page.complete?artistAlbums.size()+" releases":"Showing a selection of this artist’s releases.");
                if(!artistAlbums.isEmpty())focusRow(artistList,0,true,0);
            }
            public void onError(String message){if(generation==browseGeneration&&screen==Screen.ARTIST){artistLoaded=true;status.setText(message);}}
        });
    }
    private void loadAlbum(){
        if(selectedAlbum==null)return;
        final String id=selectedAlbum.id;long generation=++browseGeneration;albumLoaded=false;updateAlbumPage();status.setText("Loading album tracks…");
        provider.album(selectedAlbum,new NativeMusicProvider.Callback<NativeAlbumPage>(){
            public void onSuccess(NativeAlbumPage page){
                if(destroyed||generation!=browseGeneration||screen!=Screen.ALBUM||selectedAlbum==null||!id.equals(selectedAlbum.id))return;
                if(page.album!=null)selectedAlbum=page.album;
                albumTracks.clear();albumTracks.addAll(page.tracks);albumLoaded=true;albumComplete=page.complete;
                albumRows.notifyDataSetChanged();updateAlbumPage();
                refreshQueueArtwork(albumTracks);
                status.setText(albumTracks.isEmpty()?"No playable tracks found in this album.":page.complete?albumTracks.size()+" album tracks":"Partial album · "+albumTracks.size()+" loaded tracks · maximum 200");
                if(!albumTracks.isEmpty())focusTarget(playAlbum);
            }
            public void onError(String message){if(generation==browseGeneration&&screen==Screen.ALBUM){albumLoaded=true;status.setText(message);updateAlbumPage();}}
        });
    }
    private static String artworkAlbumKey(String album,String artist){
        return album.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+"," ")+"\n"
                +artist.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+"," ");
    }
    private boolean artworkRepairCurrent(long generation,String key){
        return generation==artworkRepairGeneration&&key.equals(pendingArtworkRepair)
                &&foreground&&!destroyed&&screen==Screen.NOW&&!videoMode&&playing!=null
                &&key.equals(artworkAlbumKey(playing.album,playing.artist));
    }
    private void cancelArtworkRepair(){
        artworkRepairGeneration++;
        if(!pendingArtworkRepair.isEmpty())artworkRepairAttempts.remove(pendingArtworkRepair);
        pendingArtworkRepair="";
    }
    private void maybeRepairMissingArtwork(){
        if(!foreground||destroyed||screen!=Screen.NOW||videoMode||controller==null
                ||!controller.isPlaying()||playing==null||!playing.thumbnailUrl.isEmpty()
                ||playing.album.isEmpty()||playing.artist.isEmpty()){
            if(!pendingArtworkRepair.isEmpty())cancelArtworkRepair();
            return;
        }
        final String album=playing.album,artist=playing.artist,key=artworkAlbumKey(album,artist);
        if(key.equals(pendingArtworkRepair))return;
        if(!pendingArtworkRepair.isEmpty())cancelArtworkRepair();
        if(artworkRepairAttempts.contains(key))return;
        artworkRepairAttempts.add(key);
        while(artworkRepairAttempts.size()>16)artworkRepairAttempts.remove(artworkRepairAttempts.iterator().next());
        pendingArtworkRepair=key;final long generation=++artworkRepairGeneration;
        provider.searchAlbums(album+" "+artist,new NativeMusicProvider.Callback<List<NativeCollection>>(){
            public void onSuccess(List<NativeCollection> candidates){
                if(!artworkRepairCurrent(generation,key))return;
                NativeCollection matching=null;
                String expected=album.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+"," ");
                String expectedArtist=artist.trim().toLowerCase(java.util.Locale.ROOT);
                for(NativeCollection candidate:candidates){
                    if(candidate.kind==NativeCollection.Kind.ALBUM
                            &&candidate.title.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+"," ").equals(expected)
                            &&candidate.subtitle.toLowerCase(java.util.Locale.ROOT).contains(expectedArtist)){
                        matching=candidate;break;
                    }
                }
                if(matching==null){pendingArtworkRepair="";return;}
                provider.album(matching,new NativeMusicProvider.Callback<NativeAlbumPage>(){
                    public void onSuccess(NativeAlbumPage page){
                        if(!artworkRepairCurrent(generation,key))return;
                        pendingArtworkRepair="";refreshQueueArtwork(page.tracks);
                    }
                    public void onError(String message){if(artworkRepairCurrent(generation,key))pendingArtworkRepair="";}
                });
            }
            public void onError(String message){if(artworkRepairCurrent(generation,key))pendingArtworkRepair="";}
        });
    }
    private void refreshQueueArtwork(List<NativeTrack> tracks){
        if(controller==null||tracks.isEmpty())return;
        boolean hasArtwork=false;for(NativeTrack track:tracks)if(!track.thumbnailUrl.isEmpty()){hasArtwork=true;break;}
        if(!hasArtwork)return;
        try{
            controller.sendCustomCommand(NativePlaybackCommands.command(NativePlaybackCommands.REFRESH_ARTWORK),
                    NativePlaybackCommands.collectionArgs(tracks));
        }catch(IllegalArgumentException ignored){/* Optional metadata repair must not interrupt album browsing. */}
    }
    private void updateAlbumPage(){
        if(albumTitle==null)return;
        albumTitle.setText(selectedAlbum==null?"Album":selectedAlbum.title);
        albumSubtitle.setText(selectedAlbum==null?"":selectedAlbum.subtitle);
        if(playAlbum==null)return;
        boolean ready=albumLoaded&&!albumTracks.isEmpty();
        playAlbum.setEnabled(ready);nextAlbum.setEnabled(ready);addAlbum.setEnabled(ready);
        playAlbum.setText(albumComplete?"Play all":"Play loaded");
        nextAlbum.setText(albumComplete?"Play next":"Play next (loaded)");
        addAlbum.setText(albumComplete?"Add all to queue":"Add loaded tracks");
        albumNotice.setText(!albumComplete?"Partial album: only loaded tracks will be queued (maximum 200)."
                :"Play all replaces the queue. Next and Add keep your current track.");
    }
    private void queueAlbum(String action){
        if(controller==null){status.setText("Player is connecting. Try again shortly.");return;}
        if(!albumLoaded||albumTracks.isEmpty()){status.setText("Wait for the album to load, then try again.");return;}
        try{
            Bundle args=NativePlaybackCommands.collectionArgs(new ArrayList<>(albumTracks));
            send(action,args,action.equals(NativePlaybackCommands.PLAY_COLLECTION)?"Playing "+selectedAlbum.title
                    :action.equals(NativePlaybackCommands.PLAY_NEXT_COLLECTION)?"Album will play next.":"Album added to your queue.");
            if(action.equals(NativePlaybackCommands.PLAY_COLLECTION))show(Screen.NOW,true);
        }catch(IllegalArgumentException invalid){status.setText("This album cannot be queued together. Choose individual tracks instead.");}
    }
    private void loadLibrary(){
        libraryNeedsRefresh=false;
        long generation=++libraryGeneration;status.setText("Loading your liked songs…");
        provider.library(new NativeMusicProvider.Callback<List<NativeTrack>>(){
            public void onSuccess(List<NativeTrack> tracks){
                if(destroyed||isFinishing()||generation!=libraryGeneration)return;
                library.clear();library.addAll(tracks);libraryRows.notifyDataSetChanged();
                status.setText(tracks.isEmpty()?"No liked songs yet. Search for your next listen.":"Liked songs · "+tracks.size()+" tracks");
            }
            public void onError(String message){if(generation==libraryGeneration)status.setText(message);}
        });
    }
    private void trackActions(NativeTrack track){
        ArrayList<String> names=new ArrayList<>();names.add("Play now");names.add("Play next");names.add("Add to queue");
        if(!track.artist.isEmpty())names.add("Search artist");if(!track.album.isEmpty())names.add("Search album");
        dialog(track.title,names.toArray(new String[0]),which->{
            if(which<3)queueTrack(track,new String[]{NativePlaybackCommands.PLAY_NOW,NativePlaybackCommands.PLAY_NEXT,NativePlaybackCommands.ADD_END}[which]);
            else if(which==3&&!track.artist.isEmpty())navigateSearch(track.artist,SearchKind.ARTISTS);
            else navigateSearch(track.album,SearchKind.ALBUMS);
        });
    }
    private void queueTrack(NativeTrack track,String action){
        if(controller==null){status.setText("Player is connecting. Try again shortly.");return;}
        send(action,NativePlaybackCommands.trackArgs(track),action.equals(NativePlaybackCommands.PLAY_NEXT)?"Playing next: "+track.title
                :action.equals(NativePlaybackCommands.ADD_END)?"Added to queue: "+track.title:"");
        if(action.equals(NativePlaybackCommands.PLAY_NOW))show(Screen.NOW,true);
    }
    private void requestSnapshot(){
        if(!foreground||destroyed||controller==null||snapshotInFlight)return;snapshotInFlight=true;
        ListenableFuture<SessionResult> future=controller.sendCustomCommand(NativePlaybackCommands.command(NativePlaybackCommands.SNAPSHOT),Bundle.EMPTY);
        future.addListener(()->{
            snapshotInFlight=false;if(!foreground||destroyed||controller==null||isFinishing())return;
            try{applySnapshot(future.get().extras);}catch(Exception ignored){}
        },uiExecutor);
    }
    private void applySnapshot(Bundle snapshot){
        ArrayList<Bundle> data=snapshot.getParcelableArrayList("entries");
        if(data!=null){
            String previousCurrent=currentEntryId();entries.clear();
            for(Bundle item:data){NativeTrack track=NativeTrack.fromBundle(item.getBundle("track"));if(track!=null)entries.add(new Entry(item.getString("entryId",""),track,item.getBoolean("current"),item.getBoolean("past")));}
            if(!moving.isEmpty()&&!previousCurrent.equals(currentEntryId())){cancelMove();status.setText("The queue advanced. Move cancelled; choose the item again.");}
            if(moving.isEmpty())queueRows.notifyDataSetChanged();
            playing=null;for(Entry entry:entries)if(entry.current){playing=entry.track;break;}
        }
        undoButton.setEnabled(snapshot.getBoolean("canUndo",false));
        videoMode=snapshot.getBoolean("video",videoMode);shuffle=snapshot.getBoolean("shuffle",shuffle);
        repeat=snapshot.getInt("repeat",repeat);background=snapshot.getBoolean("background",background);
        String error=snapshot.getString("error","");if(error.isEmpty())error=actionError;lastError=error;
        if(!error.isEmpty()){status.setText(error);status.setVisibility(View.VISIBLE);}
        else if(screen==Screen.NOW)status.setVisibility(View.GONE);
        shuffleButton.setText(shuffle?"Shuffle on":"Shuffle off");
        repeatButton.setText(repeat==Player.REPEAT_MODE_ONE?"Repeat one":repeat==Player.REPEAT_MODE_ALL?"Repeat all":"Repeat off");
        updateNow();updateTransport();
    }
    private void send(String action,Bundle args,String success){
        if(controller==null){status.setText("Player is connecting…");return;}
        ListenableFuture<SessionResult> future=controller.sendCustomCommand(NativePlaybackCommands.command(action),args);
        future.addListener(()->{
            if(!foreground||destroyed||isFinishing())return;
            try{SessionResult result=future.get();
                String message=result.extras.getString("error","");
                if(result.resultCode!=SessionResult.RESULT_SUCCESS){actionError=message.isEmpty()?"That action is unavailable.":message;status.setText(actionError);status.setVisibility(View.VISIBLE);}
                else{actionError="";if(!success.isEmpty())status.setText(success);}
            }catch(Exception error){status.setText("The player could not complete that action.");}
            requestSnapshot();
        },uiExecutor);
    }
    private void updateNow(){
        if(nowTitle==null)return;
        updateVideoVisibility();maybeRepairMissingArtwork();
        nowTitle.setText(playing==null?"Nothing playing yet":playing.title);
        nowArtist.setText(playing==null?"Find a song and make it yours.":playing.artist);
        lyricsButton.setText(lyricsShown?"Hide lyrics":"Lyrics");modeButton.setText(videoMode?"Audio":"Video");
        lyrics.setVisibility(lyricsShown?View.VISIBLE:View.GONE);
        int alignment=lyricsShown?Gravity.LEFT|Gravity.CENTER_VERTICAL:Gravity.CENTER;
        nowTitle.setGravity(alignment);nowArtist.setGravity(alignment);nowEyebrow.setGravity(alignment);playbackState.setGravity(alignment);
        sizeMediaFrame();
        lyrics.showTrack(playing,screen==Screen.NOW&&lyricsShown&&foreground);
        artwork.setVisibility(videoMode?View.GONE:View.VISIBLE);video.setVisibility(videoMode?View.VISIBLE:View.GONE);
        if(screen==Screen.NOW&&foreground&&!videoMode&&playing!=null)artwork.load(playing.thumbnailUrl);
        else if(videoMode)artwork.stopLoading();
        if(controller!=null&&screen==Screen.NOW&&videoMode&&foreground){if(!surfaceAttached){controller.setVideoSurfaceView(video);surfaceAttached=true;}}
        else detachVideo();
    }
    private void sizeMediaFrame(){
        if(mediaStage==null||mediaStage.getWidth()<1||mediaStage.getHeight()<1)return;
        int width=videoMode?mediaStage.getWidth():Math.min(dp(300),Math.min(mediaStage.getWidth(),mediaStage.getHeight()));
        int height=videoMode?mediaStage.getHeight():width;
        FrameLayout.LayoutParams bounds=(FrameLayout.LayoutParams)artFrame.getLayoutParams();
        if(bounds.width!=width||bounds.height!=height){bounds.width=width;bounds.height=height;bounds.gravity=Gravity.CENTER;artFrame.setLayoutParams(bounds);}
    }
    private void updateTransport(){
        if(controller==null)return;
        String label=controller.isPlaying()?"Pause":"Play";textChanged(miniPlay,label);textChanged(play,label);
        textChanged(miniTitle,playing==null?"Choose your next listen":playing.title+"  ·  "+playing.artist);
        long at=controller.getCurrentPosition(),duration=durationMs();
        String progressText=NativeUi.time(at)+" / "+(duration>0?NativeUi.time(duration):"—");
        if(!progressText.contentEquals(time.getText())){time.setText(progressText);time.scrollTo(0,0);}
        if(!seeking)seek.setProgress(duration>0?(int)Math.min(1000,at*1000/duration):0);
        if(videoMode&&controller.getVideoSize().width>0&&controller.getVideoSize().height>0){
            float aspect=controller.getVideoSize().width*controller.getVideoSize().pixelWidthHeightRatio/controller.getVideoSize().height;
            int availableWidth=artFrame.getWidth(),availableHeight=artFrame.getHeight();
            if(availableWidth>0&&availableHeight>0){int width=availableWidth,height=Math.round(width/aspect);
                if(height>availableHeight){height=availableHeight;width=Math.round(height*aspect);}
                FrameLayout.LayoutParams bounds=(FrameLayout.LayoutParams)video.getLayoutParams();
                if(bounds.width!=width||bounds.height!=height){bounds.width=width;bounds.height=height;bounds.gravity=Gravity.CENTER;video.setLayoutParams(bounds);}}
        }
        textChanged(playbackState,!lastError.isEmpty()?lastError:controller.getPlayerError()!=null?"Playback interrupted · Retry or choose another track."
                :controller.getPlaybackState()==Player.STATE_BUFFERING?"Getting your music ready…"
                :playing==null?"":controller.isPlaying()?(videoMode?"VIDEO":"AUDIO"):"PAUSED");
        if(screen==Screen.NOW&&lyricsShown)lyrics.updateClock();
    }
    private long durationMs(){if(controller==null)return 0;long d=controller.getDuration();return d==C.TIME_UNSET||d<0?0:d;}
    private void scheduleClock(){main.removeCallbacks(tick);if(foreground&&controller!=null&&controller.isPlaying())main.post(tick);}
    private void togglePlay(){if(controller!=null){if(controller.isPlaying())controller.pause();else controller.play();}}
    private void next(){if(controller!=null&&controller.hasNextMediaItem())controller.seekToNextMediaItem();}
    private void previous(){if(controller!=null&&controller.hasPreviousMediaItem())controller.seekToPreviousMediaItem();}
    private void seekRelative(long delta){if(controller!=null)controller.seekTo(Math.max(0,Math.min(durationMs()>0?durationMs():Long.MAX_VALUE,controller.getCurrentPosition()+delta)));}
    private void updateVideoVisibility(){
        if(controller==null)return;
        boolean shown=foreground&&screen==Screen.NOW&&videoMode;
        if(lastSentVideoVisible!=null&&lastSentVideoVisible==shown)return;
        lastSentVideoVisible=shown;
        controller.sendCustomCommand(NativePlaybackCommands.command(NativePlaybackCommands.VIDEO_VISIBLE),
                NativePlaybackCommands.boolArgs(shown));
    }
    private void detachVideo(){if(surfaceAttached&&controller!=null&&video!=null)controller.clearVideoSurfaceView(video);surfaceAttached=false;}
    private void queueActions(Entry entry){
        if(entry.current){dialog(entry.track.title,new String[]{"Restart","Next track"},which->{if(which==0&&controller!=null)controller.seekTo(0);else next();});return;}
        dialog(entry.track.title,new String[]{"Play this entry","Move","Remove"},which->{
            if(which==0)playEntry(entry.id);else if(which==1)startMove(entry);else send(NativePlaybackCommands.REMOVE,NativePlaybackCommands.entryArgs(entry.id),"Removed · Undo is available in Queue.");
        });
    }
    private void playEntry(String id){
        if(controller==null)return;for(int i=0;i<controller.getMediaItemCount();i++){
            MediaItem item=controller.getMediaItemAt(i);if(id.equals(item.mediaId)){controller.seekToDefaultPosition(i);controller.play();show(Screen.NOW,true);return;}
        }
        status.setText("This queue entry is no longer available.");
    }
    private List<Entry> shownEntries(){
        ArrayList<Entry> shown=new ArrayList<>();for(Entry entry:moving.isEmpty()?entries:draft)if(!entry.past)shown.add(entry);return shown;
    }
    private String currentEntryId(){for(Entry entry:entries)if(entry.current)return entry.id;return "";}
    private void startMove(Entry entry){
        if(entry.current||entry.past)return;moving=entry.id;moveCurrent=currentEntryId();draft.clear();draft.addAll(entries);
        queueHint.setText("Moving "+entry.track.title+" · Up/Down chooses a place · OK saves · Back cancels");queueRows.notifyDataSetChanged();queueList.postDelayed(()->focusMovingRow(),32);
    }
    private void moveDraft(int delta){
        int at=-1;for(int i=0;i<draft.size();i++)if(moving.equals(draft.get(i).id)){at=i;break;}
        int target=at+delta;
        if(at<0||target<0||target>=draft.size()||draft.get(target).current||draft.get(target).past)return;
        Entry entry=draft.remove(at);draft.add(target,entry);queueRows.notifyDataSetChanged();
        List<Entry> shown=shownEntries();for(int i=0;i<shown.size();i++)if(moving.equals(shown.get(i).id)){focusRow(queueList,i,false,0);break;}
    }
    private void focusMovingRow(){
        if(moving.isEmpty()||screen!=Screen.QUEUE)return;
        List<Entry> shown=shownEntries();for(int index=0;index<shown.size();index++)
            if(moving.equals(shown.get(index).id)){focusRow(queueList,index,false,0);return;}
    }
    private void rememberNavigation(){
        back.addLast(captureNavigation());while(back.size()>32)back.removeFirst();forward.clear();
    }
    private RecyclerView visibleList(){
        return screen==Screen.HOME?libraryList:screen==Screen.SEARCH?resultList:screen==Screen.ARTIST?artistList:screen==Screen.ALBUM?albumList:screen==Screen.QUEUE?queueList:null;
    }
    private NavigationState captureNavigation(){
        NavigationState state=new NavigationState();state.screen=screen;state.kind=searchKind;state.query=search.getText().toString();
        state.artist=selectedArtist;state.album=selectedAlbum;state.loaded=screen==Screen.SEARCH?searchLoaded:screen==Screen.ARTIST?artistLoaded:screen==Screen.ALBUM?albumLoaded:true;
        state.complete=screen==Screen.ARTIST?artistComplete:albumComplete;
        if(screen==Screen.SEARCH){state.tracks=new ArrayList<>(results);state.collections=new ArrayList<>(searchCollections);}
        if(screen==Screen.ARTIST)state.collections=new ArrayList<>(artistAlbums);
        if(screen==Screen.ALBUM)state.tracks=new ArrayList<>(albumTracks);
        state.focus=getCurrentFocus();RecyclerView list=visibleList();
        if(list!=null&&list.getLayoutManager()!=null){
            state.scroll=list.getLayoutManager().onSaveInstanceState();
            RecyclerView.ViewHolder holder=state.focus==null?null:list.findContainingViewHolder(state.focus);
            if(holder!=null){state.position=holder.getBindingAdapterPosition();state.action=holder instanceof TrackHolder&&state.focus==((TrackHolder)holder).actions;}
        }
        return state;
    }
    private void restoreNavigation(NavigationState state){
        searchGeneration++;browseGeneration++;restoringNavigation=true;
        if(state.screen==Screen.SEARCH){
            searchKind=state.kind;search.setText(state.query);searchLoaded=state.loaded;
            results.clear();results.addAll(state.tracks);searchCollections.clear();searchCollections.addAll(state.collections);
            searchRows.notifyDataSetChanged();collectionSearchRows.notifyDataSetChanged();
            resultList.setAdapter(searchKind==SearchKind.SONGS?searchRows:collectionSearchRows);styleSearchFilters();
        }else if(state.screen==Screen.ARTIST){
            selectedArtist=state.artist;artistAlbums.clear();artistAlbums.addAll(state.collections);artistLoaded=state.loaded;artistComplete=state.complete;
            artistAlbumRows.notifyDataSetChanged();if(selectedArtist!=null){artistTitle.setText(selectedArtist.title);artistSubtitle.setText(selectedArtist.subtitle);}
        }else if(state.screen==Screen.ALBUM){
            selectedAlbum=state.album;albumTracks.clear();albumTracks.addAll(state.tracks);albumLoaded=state.loaded;albumComplete=state.complete;
            albumRows.notifyDataSetChanged();updateAlbumPage();
        }
        show(state.screen,false);restoringNavigation=false;
        if(state.loaded&&lastError.isEmpty()){
            if(screen==Screen.SEARCH)status.setText((searchKind==SearchKind.SONGS?results.size():searchCollections.size())+" "+searchKind.name().toLowerCase(java.util.Locale.ROOT)+" · "+state.query);
            else if(screen==Screen.ARTIST)status.setText(artistComplete?artistAlbums.size()+" releases":"Showing a selection of this artist’s releases.");
            else if(screen==Screen.ALBUM)status.setText(albumComplete?albumTracks.size()+" album tracks":"Partial album · "+albumTracks.size()+" loaded tracks · maximum 200");
        }
        RecyclerView list=visibleList();
        if(list!=null&&state.scroll!=null&&list.getLayoutManager()!=null)list.getLayoutManager().onRestoreInstanceState(state.scroll);
        if(state.position>=0&&list!=null)restoreRowFocus(list,state.position,state.action,0);
        else if(state.focus!=null)content.post(()->{if(state.focus.isShown())focusTarget(state.focus);else content.requestFocus(View.FOCUS_DOWN);});
        if(!state.loaded){if(screen==Screen.SEARCH&&!state.query.trim().isEmpty())runSearch();else if(screen==Screen.ARTIST)loadArtist();else if(screen==Screen.ALBUM)loadAlbum();}
    }
    private void restoreRowFocus(RecyclerView list,int position,boolean actions,int attempt){
        list.post(()->{
            if(destroyed||!foreground||!list.isShown())return;
            RecyclerView.ViewHolder holder=list.findViewHolderForAdapterPosition(position);
            if(holder instanceof TrackHolder)focusTarget(actions?((TrackHolder)holder).actions:((TrackHolder)holder).main);
            else if(holder instanceof QueueHolder)focusTarget(((QueueHolder)holder).name);
            else if(attempt<2)list.postDelayed(()->restoreRowFocus(list,position,actions,attempt+1),32);
        });
    }
    private static final class NavigationState {
        Screen screen;SearchKind kind;String query="";NativeCollection artist,album;
        List<NativeTrack> tracks=new ArrayList<>();List<NativeCollection> collections=new ArrayList<>();
        boolean loaded,complete,action;int position=-1;Parcelable scroll;View focus;
    }
    private static void focusTarget(View target){
        target.setFocusableInTouchMode(true);
        if(!target.requestFocusFromTouch())target.requestFocus();
    }
    private void focusRow(RecyclerView list,int position,boolean trackRow,int attempt){
        list.post(()->{
            if(destroyed||!foreground||!list.isShown())return;
            list.scrollToPosition(position);
            RecyclerView.ViewHolder holder=list.findViewHolderForAdapterPosition(position);
            View target=trackRow&&holder instanceof TrackHolder?((TrackHolder)holder).main
                    :!trackRow&&holder instanceof QueueHolder?((QueueHolder)holder).name:null;
            if(target!=null)focusTarget(target);
            else if(attempt<2)list.postDelayed(()->focusRow(list,position,trackRow,attempt+1),32);
        });
    }
    private void commitMove(){
        if(!moveCurrent.equals(currentEntryId())){cancelMove();status.setText("The queue changed. Choose the item again.");return;}
        String entryId=moving,before="";
        for(int i=0;i<draft.size();i++)if(entryId.equals(draft.get(i).id)&&i+1<draft.size()){before=draft.get(i+1).id;break;}
        cancelMove();send(NativePlaybackCommands.MOVE,NativePlaybackCommands.moveArgs(entryId,before),"Queue updated.");
    }
    private void cancelMove(){moving="";draft.clear();if(queueHint!=null)queueHint.setText("Your current track stays pinned while you arrange what follows.");queueRows.notifyDataSetChanged();}
    private void clearUpcoming(){
        ArrayList<Entry> removing=new ArrayList<>();for(Entry entry:entries)if(!entry.current&&!entry.past)removing.add(entry);
        if(removing.isEmpty()){status.setText("No upcoming tracks to clear.");return;}
        new AlertDialog.Builder(this,dev.ytmusictv.R.style.AppDialogTheme).setTitle("Clear upcoming music?").setMessage("The current track keeps playing.")
                .setNegativeButton("Keep queue",null).setPositiveButton("Clear", (d,w)->{
                    send(NativePlaybackCommands.CLEAR_UPCOMING,Bundle.EMPTY,"Upcoming music cleared. Undo restores it.");
                }).show();
    }
    private void account(){
        new AlertDialog.Builder(this,dev.ytmusictv.R.style.AppDialogTheme).setTitle("Your account")
                .setMessage("Sign in to see your liked songs on this TV. Your music keeps its place when you return.")
                .setNegativeButton("Keep listening",null).setNeutralButton("Refresh liked songs",(d,w)->loadLibrary())
                .setPositiveButton("Sign in",(d,w)->signIn()).show();
    }
    private void signIn(){hideKeyboard();startActivityForResult(new Intent(this,SignInActivity.class),REQUEST_SIGN_IN);}
    private void startVoiceSearch(){
        if(voiceInFlight||screen!=Screen.SEARCH||destroyed||!foreground)return;
        hideKeyboard();
        if(!NativeVoiceSearch.isAvailable(this)){showVoiceFallback("Voice search is unavailable on this TV. Use Keyboard instead.");return;}
        voiceInFlight=true;voiceButton.setEnabled(false);
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_VOICE_PERMISSION);
            return;
        }
        beginNativeVoice();
    }
    private void beginNativeVoice(){
        if(!voiceInFlight||!foreground||destroyed||screen!=Screen.SEARCH)return;
        MediaItem current=controller==null?null:controller.getCurrentMediaItem();
        resumeAfterVoice=current!=null&&controller.getPlayWhenReady();voiceEntryId=current==null?"":current.mediaId;voiceResumeReady=false;
        // Quiet this player even when the recognition UI is a translucent window.
        if(controller!=null&&resumeAfterVoice)controller.pause();
        LinearLayout panel=vertical();panel.setPadding(dp(24),dp(12),dp(24),dp(12));
        voiceStatus=NativeUi.text(this,"Getting the microphone ready…",18,NativeUi.INK);
        panel.addView(voiceStatus,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView hint=NativeUi.text(this,"Say an artist, album or song name.",13,NativeUi.MUTED);
        panel.addView(hint,new LinearLayout.LayoutParams(-1,dp(32)));
        voiceLevel=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);voiceLevel.setMax(100);voiceLevel.setIndeterminate(false);
        voiceLevel.setProgressTintList(android.content.res.ColorStateList.valueOf(NativeUi.GOLD));
        panel.addView(voiceLevel,new LinearLayout.LayoutParams(-1,dp(8)));
        voiceDialog=new AlertDialog.Builder(this,dev.ytmusictv.R.style.AppDialogTheme).setTitle("Voice search").setView(panel)
                .setNegativeButton("Cancel",(dialog,which)->cancelNativeVoice(true,true))
                .setPositiveButton("Keyboard",(dialog,which)->{cancelNativeVoice(true,false);openVoiceKeyboard();}).create();
        voiceDialog.setOnCancelListener(dialog->cancelNativeVoice(true,true));voiceDialog.show();
        voiceSearch=new NativeVoiceSearch(this,new NativeVoiceSearch.Callback(){
            public void onReady(){if(voiceInFlight&&voiceStatus!=null)voiceStatus.setText("Listening…");}
            public void onSpeech(){if(voiceInFlight&&voiceStatus!=null)voiceStatus.setText("Hearing your voice…");}
            public void onLevel(float level){
                long now=android.os.SystemClock.uptimeMillis();
                if(voiceInFlight&&voiceLevel!=null&&now-voiceLevelAt>=100){voiceLevelAt=now;voiceLevel.setProgress(Math.max(0,Math.min(100,Math.round((level+2)*8))),false);}
            }
            public void onResult(List<String> candidates){finishNativeVoice(candidates,0);}
            public void onError(int error){finishNativeVoice(null,error);}
        });
        voiceSearch.start();
    }
    private static String firstVoiceText(List<String> candidates){
        if(candidates!=null)for(String candidate:candidates){
            if(candidate==null)continue;candidate=candidate.trim();if(candidate.isEmpty())continue;
            int end=Math.min(256,candidate.length());
            if(end<candidate.length()&&end>0&&Character.isHighSurrogate(candidate.charAt(end-1)))end--;
            return candidate.substring(0,end);
        }
        return "";
    }
    private void stopVoiceResources(){
        NativeVoiceSearch active=voiceSearch;voiceSearch=null;if(active!=null)active.close();
        AlertDialog dialog=voiceDialog;voiceDialog=null;voiceStatus=null;voiceLevel=null;
        if(dialog!=null){dialog.setOnCancelListener(null);dialog.dismiss();}
        voiceInFlight=false;if(voiceButton!=null)voiceButton.setEnabled(true);
    }
    private void cancelNativeVoice(boolean resume,boolean focusButton){
        boolean wasActive=voiceInFlight||voiceSearch!=null;
        stopVoiceResources();
        if(resume&&wasActive){voiceResumeReady=true;resumeVoicePlayback();}
        else{resumeAfterVoice=false;voiceResumeReady=false;voiceEntryId="";}
        if(focusButton&&foreground&&screen==Screen.SEARCH){
            status.setVisibility(View.VISIBLE);status.setText("Voice search cancelled. Your search is unchanged.");
            voiceButton.post(()->{if(!destroyed&&screen==Screen.SEARCH)focusTarget(voiceButton);});
        }
    }
    private void finishNativeVoice(List<String> candidates,int error){
        if(!voiceInFlight)return;
        String spoken=firstVoiceText(candidates);stopVoiceResources();voiceResumeReady=true;resumeVoicePlayback();
        if(!foreground||destroyed||screen!=Screen.SEARCH)return;
        if(!spoken.isEmpty()){search.setText(spoken);search.setSelection(spoken.length());runSearch();return;}
        String message=error==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS?"Microphone access was not allowed. Use Keyboard or allow microphone access before trying Voice again."
                :error==SpeechRecognizer.ERROR_AUDIO?"The TV could not read the microphone. Use your TV keyboard’s microphone, or type your search."
                :error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT||error==SpeechRecognizer.ERROR_NO_MATCH||error==0?"No speech was heard. Try Voice again, or use Keyboard."
                :error==SpeechRecognizer.ERROR_NETWORK||error==SpeechRecognizer.ERROR_NETWORK_TIMEOUT?"Voice search could not connect. Use Keyboard or try again."
                :"Voice search could not finish. Use Keyboard or try again.";
        showVoiceFallback(message);
    }
    private void showVoiceFallback(String message){
        if(!foreground||destroyed||screen!=Screen.SEARCH)return;
        status.setVisibility(View.VISIBLE);status.setText(message);
        voiceDialog=new AlertDialog.Builder(this,dev.ytmusictv.R.style.AppDialogTheme).setTitle("Voice search")
                .setMessage(message).setNegativeButton("Back",(dialog,which)->{
                    voiceDialog=null;voiceButton.post(()->focusTarget(voiceButton));
                }).setPositiveButton("Keyboard",(dialog,which)->{voiceDialog=null;openVoiceKeyboard();}).create();
        voiceDialog.setOnCancelListener(dialog->{voiceDialog=null;voiceButton.post(()->focusTarget(voiceButton));});voiceDialog.show();
    }
    private void openVoiceKeyboard(){
        search.postDelayed(()->{
            if(foreground&&!destroyed&&screen==Screen.SEARCH){focusTarget(search);openKeyboard();}
        },80);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=REQUEST_VOICE_PERMISSION)return;
        boolean allowed=results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED;
        if(!voiceInFlight||!foreground||destroyed||screen!=Screen.SEARCH){
            cancelNativeVoice(false,false);
            if(allowed&&foreground&&!destroyed&&screen==Screen.SEARCH){status.setVisibility(View.VISIBLE);status.setText("Microphone access is ready. Press Voice to search.");}
            return;
        }
        if(allowed)beginNativeVoice();
        else{cancelNativeVoice(false,false);showVoiceFallback("Microphone access was not allowed. You can search with Keyboard instead.");}
    }
    private void resumeVoicePlayback(){
        if(!voiceResumeReady||!foreground||controller==null)return;
        voiceResumeReady=false;boolean resume=resumeAfterVoice;resumeAfterVoice=false;
        String expected=voiceEntryId;voiceEntryId="";
        MediaItem current=controller.getCurrentMediaItem();
        if(resume&&current!=null&&expected.equals(current.mediaId))controller.play();
    }
    @Override protected void onResume(){super.onResume();resumeVoicePlayback();}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==REQUEST_SIGN_IN){
            libraryNeedsRefresh=true;
            if(screen==Screen.HOME)loadLibrary();
            else if(screen==Screen.ARTIST&&selectedArtist!=null)loadArtist();
            else if(screen==Screen.ALBUM&&selectedAlbum!=null)loadAlbum();
            else if(screen==Screen.SEARCH&&!searchLoaded&&!search.getText().toString().trim().isEmpty())runSearch();
        }
    }
    private void settings(){
        dialog("Listening preferences",new String[]{background?"Background audio: on":"Background audio: off","Resume saved queue","Retry playback","Remote guide"},which->{
            if(which==0)send(NativePlaybackCommands.SET_BACKGROUND,NativePlaybackCommands.boolArgs(!background),"Listening preference saved.");
            else if(which==1)send(NativePlaybackCommands.RESTORE,Bundle.EMPTY,"Saved queue restored. Press Play when ready.");
            else if(which==2)send(NativePlaybackCommands.RETRY,Bundle.EMPTY,"Retrying…");
            else new AlertDialog.Builder(this,dev.ytmusictv.R.style.AppDialogTheme).setTitle("Made for your remote").setMessage("Arrows move focus. OK activates. Search uses the TV keyboard.\n\nActions beside every result offers Play next and Add to queue. Queue → Actions → Move uses Up/Down and OK; Back cancels.\n\nChannel +/− and media Next/Previous change tracks. Left/Right on the seek rail skips 10 seconds. Menu shows or hides Controls. Back returns to the previous screen.").setPositiveButton("Got it",null).show();
        });
    }
    private interface Choice {void select(int which);}
    private void dialog(String title,String[] labels,Choice choice){
        new AlertDialog.Builder(this,dev.ytmusictv.R.style.AppDialogTheme).setTitle(title).setItems(labels,(d,which)->choice.select(which)).setNegativeButton("Back",null).show();
    }
    private void openKeyboard(){InputMethodManager manager=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(manager!=null)manager.showSoftInput(search,InputMethodManager.SHOW_IMPLICIT);}
    private void hideKeyboard(){if(search==null)return;InputMethodManager manager=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(manager!=null)manager.hideSoftInputFromWindow(search.getWindowToken(),0);}
    private void setChrome(boolean expanded,boolean transferFocus){
        chromeExpanded=expanded;nav.setVisibility(expanded?View.VISIBLE:View.GONE);
        controlsHandle.setVisibility(expanded?View.GONE:View.VISIBLE);
        header.getLayoutParams().height=dp(expanded?48:34);header.requestLayout();
        getPreferences(MODE_PRIVATE).edit().putBoolean("nativeChromeExpanded",expanded).apply();
        if(transferFocus){if(expanded)nav.getChildAt(1).requestFocus();else content.requestFocus(View.FOCUS_DOWN);}
    }
    private boolean typing(){return getCurrentFocus() instanceof EditText;}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int key=event.getKeyCode(),scan=event.getScanCode();
        boolean relevant=key==KeyEvent.KEYCODE_INFO||key==KeyEvent.KEYCODE_CAPTIONS
                ||key==KeyEvent.KEYCODE_SEARCH||key==KeyEvent.KEYCODE_EXPLORER
                ||scan==235||scan==358||scan==651;
        if(relevant&&debugRemote)Log.d("NativeRemote","code="+key+" scan="+scan+" action="+event.getAction()+" repeat="+event.getRepeatCount());
        long press=((long)event.getDeviceId()<<32)^((long)key<<16)^(scan&0xffffL);
        if(event.getAction()==KeyEvent.ACTION_UP&&heldShortcuts.remove(press))return true;
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()>0&&heldShortcuts.contains(press))return true;
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)heldShortcuts.remove(press);
        if(!typing()&&!moving.isEmpty()){
            if(key==19||key==20||key==23||key==66||key==4){
                if(event.getAction()==KeyEvent.ACTION_DOWN){if(key==19||key==20)moveDraft(key==19?-1:1);else if(event.getRepeatCount()==0){if(key==4)cancelMove();else commitMove();}}
                return true;
            }
        }
        if(relevant&&foreground&&!destroyed&&hasWindowFocus()&&!typing()&&moving.isEmpty()&&voiceDialog==null){
            InputDevice device=event.getDevice();boolean tcl=device!=null&&"BT_RC813_MA".equals(device.getName());
            boolean keyboard=key==KeyEvent.KEYCODE_SEARCH||(tcl&&scan==235);
            boolean openLyrics=key==KeyEvent.KEYCODE_INFO||key==KeyEvent.KEYCODE_CAPTIONS||(tcl&&scan==358);
            boolean openSearch=key==KeyEvent.KEYCODE_EXPLORER||(tcl&&scan==651);
            if((keyboard||openLyrics||openSearch)&&event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){
                heldShortcuts.add(press);
                if(openLyrics){lyricsShown=true;show(Screen.NOW,true);updateNow();}
                else{show(Screen.SEARCH,true);if(keyboard){focusTarget(search);openKeyboard();}}
                return true;
            }
        }
        if(!typing()&&(key==KeyEvent.KEYCODE_CHANNEL_UP||key==KeyEvent.KEYCODE_CHANNEL_DOWN)){
            if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){if(key==KeyEvent.KEYCODE_CHANNEL_UP)next();else previous();}return true;
        }
        if(!typing()&&key==KeyEvent.KEYCODE_FORWARD){
            if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0&&!forward.isEmpty()){back.addLast(captureNavigation());restoreNavigation(forward.removeLast());}return true;
        }
        if(!typing()&&(key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD||key==KeyEvent.KEYCODE_MEDIA_REWIND)){
            if(event.getAction()==KeyEvent.ACTION_DOWN)seekRelative(key==KeyEvent.KEYCODE_MEDIA_REWIND?-10_000:10_000);return true;
        }
        if(!typing()&&(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||key==KeyEvent.KEYCODE_MEDIA_PLAY||key==KeyEvent.KEYCODE_MEDIA_PAUSE
                ||key==KeyEvent.KEYCODE_MEDIA_NEXT||key==KeyEvent.KEYCODE_MEDIA_PREVIOUS||key==KeyEvent.KEYCODE_MEDIA_STOP)){
            if(controller!=null&&event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){
                if(key==KeyEvent.KEYCODE_MEDIA_NEXT)next();
                else if(key==KeyEvent.KEYCODE_MEDIA_PREVIOUS)previous();
                else if(key==KeyEvent.KEYCODE_MEDIA_PLAY)controller.play();
                else if(key==KeyEvent.KEYCODE_MEDIA_PAUSE)controller.pause();
                else if(key==KeyEvent.KEYCODE_MEDIA_STOP)controller.pause();
                else togglePlay();
            }return true;
        }
        if(!typing()&&key==KeyEvent.KEYCODE_MENU){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)setChrome(!chromeExpanded,true);return true;}
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed(){
        if(!moving.isEmpty()){cancelMove();return;}
        if(!back.isEmpty()){forward.addLast(captureNavigation());restoreNavigation(back.removeLast());}
        else super.onBackPressed();
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        if(intent!=null&&intent.getBooleanExtra("native_debug_capture_video",false))captureDebugVideoFrame();
    }
    /** One-shot developer diagnostic of the video surface, never account/UI pixels. */
    private void captureDebugVideoFrame(){
        if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0
                ||!foreground||destroyed||screen!=Screen.NOW||!videoMode||videoCapturePending)return;
        int sourceWidth=video.getWidth(),sourceHeight=video.getHeight();
        if(sourceWidth<1||sourceHeight<1||!video.getHolder().getSurface().isValid()){
            Log.d("NativeVideoCapture","result=-1 width=0 height=0");return;
        }
        float ratio=Math.min(1f,320f/Math.max(sourceWidth,sourceHeight));
        int width=Math.max(1,Math.round(sourceWidth*ratio)),height=Math.max(1,Math.round(sourceHeight*ratio));
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        videoCapturePending=true;final int generation=videoCaptureGeneration;
        try{
            PixelCopy.request(video,bitmap,result->{
                if(result!=PixelCopy.SUCCESS||generation!=videoCaptureGeneration||!foreground||destroyed){
                    bitmap.recycle();videoCapturePending=false;
                    Log.d("NativeVideoCapture","result="+(result==PixelCopy.SUCCESS?-3:result)+" width="+width+" height="+height);return;
                }
                File temporary=new File(getFilesDir(),"native-video-frame.pending.png");
                File target=new File(getFilesDir(),"native-video-frame.png");
                new Thread(()->{
                    boolean written=false;
                    try(FileOutputStream output=new FileOutputStream(temporary)){
                        written=bitmap.compress(Bitmap.CompressFormat.PNG,100,output);
                    }catch(Exception ignored){}finally{bitmap.recycle();}
                    final boolean ready=written;
                    captureHandler.post(()->{
                        boolean current=generation==videoCaptureGeneration&&foreground&&!destroyed;
                        boolean saved=current&&ready&&temporary.renameTo(target);
                        if(!saved)temporary.delete();
                        videoCapturePending=false;
                        Log.d("NativeVideoCapture","result="+(saved?0:current?-2:-3)+" width="+width+" height="+height);
                    });
                },"NativeVideoCapture").start();
            },captureHandler);
        }catch(IllegalArgumentException invalid){
            bitmap.recycle();videoCapturePending=false;
            Log.d("NativeVideoCapture","result=-4 width="+width+" height="+height);
        }
    }
    @Override protected void onStart(){
        super.onStart();foreground=true;
        if(controller==null&&connection==null){
            SessionToken token=new SessionToken(this,new ComponentName(this,NativePlaybackService.class));
            connection=new MediaController.Builder(this,token).setListener(new MediaController.Listener(){
                @Override public ListenableFuture<SessionResult> onCustomCommand(MediaController source,SessionCommand command,Bundle args){
                    if(foreground&&NativePlaybackCommands.QUEUE_CHANGED.equals(command.customAction)&&!destroyed&&!isFinishing())applySnapshot(args);
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                }
            }).buildAsync();
            connection.addListener(()->{
                if(destroyed||isFinishing())return;
                try{controller=connection.get();connected=true;controller.addListener(playerListener);
                    send(NativePlaybackCommands.APP_FOREGROUND,NativePlaybackCommands.boolArgs(foreground),"");
                    if(!foreground)return;
                    status.setText("Ready to listen");requestSnapshot();updateTransport();updateNow();scheduleClock();resumeVoicePlayback();
                }catch(Exception error){status.setText("The player could not connect. Reopen Music to try again.");}
            },uiExecutor);
        }else{send(NativePlaybackCommands.APP_FOREGROUND,NativePlaybackCommands.boolArgs(true),"");requestSnapshot();updateNow();scheduleClock();}
    }
    @Override protected void onStop(){
        foreground=false;cancelArtworkRepair();heldShortcuts.clear();cancelNativeVoice(false,false);videoCaptureGeneration++;main.removeCallbacks(tick);lyrics.stop();artwork.stopLoading();detachVideo();updateVideoVisibility();
        if(controller!=null)send(NativePlaybackCommands.APP_FOREGROUND,NativePlaybackCommands.boolArgs(false),"");
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("query",search.getText().toString());state.putBoolean("lyrics",lyricsShown);state.putString("searchKind",searchKind.name());}
    @Override protected void onDestroy(){
        destroyed=true;cancelArtworkRepair();cancelNativeVoice(false,false);videoCaptureGeneration++;main.removeCallbacksAndMessages(null);provider.close();lyrics.stop();artwork.close();
        if(controller!=null)controller.removeListener(playerListener);
        if(connection!=null)MediaController.releaseFuture(connection);
        controller=null;super.onDestroy();
    }
    private static void textChanged(TextView view,String value){if(!value.contentEquals(view.getText()))view.setText(value);}
    private int dp(float value){return NativeUi.dp(this,value);}
    private static final class Entry{
        final String id;final NativeTrack track;final boolean current,past;
        Entry(String id,NativeTrack track,boolean current,boolean past){this.id=id;this.track=track;this.current=current;this.past=past;}
    }
    private final class TrackRows extends RecyclerView.Adapter<TrackHolder>{
        private final List<NativeTrack> tracks;TrackRows(List<NativeTrack> tracks){this.tracks=tracks;}
        @Override public TrackHolder onCreateViewHolder(ViewGroup parent,int type){return new TrackHolder();}
        @Override public void onBindViewHolder(TrackHolder holder,int position){
            NativeTrack track=tracks.get(position);holder.actions.setText("Actions");holder.title.setText(track.title);holder.detail.setText(track.artist+(track.durationSeconds>0?" · "+NativeUi.time(track.durationSeconds*1000L):"")+(track.isVideoSource?" · VIDEO":""));
            holder.main.setOnClickListener(v->queueTrack(track,NativePlaybackCommands.PLAY_NOW));
            holder.actions.setOnClickListener(v->trackActions(track));
            holder.main.setOnLongClickListener(v->{trackActions(track);return true;});
        }
        @Override public int getItemCount(){return tracks.size();}
    }
    private final class CollectionRows extends RecyclerView.Adapter<TrackHolder>{
        private final List<NativeCollection> items;CollectionRows(List<NativeCollection> items){this.items=items;}
        @Override public TrackHolder onCreateViewHolder(ViewGroup parent,int type){return new TrackHolder();}
        @Override public void onBindViewHolder(TrackHolder holder,int position){
            NativeCollection item=items.get(position);holder.title.setText(item.title);
            holder.detail.setText(item.subtitle.isEmpty()?(item.kind==NativeCollection.Kind.ARTIST?"Artist":"Album"):item.subtitle);
            holder.actions.setText("Open");holder.main.setOnLongClickListener(null);holder.main.setLongClickable(false);
            holder.main.setOnClickListener(v->openCollection(item));holder.actions.setOnClickListener(v->openCollection(item));
        }
        @Override public int getItemCount(){return items.size();}
    }
    private final class TrackHolder extends RecyclerView.ViewHolder{
        final LinearLayout main;final TextView title,detail;final Button actions;
        TrackHolder(){super(new LinearLayout(NativeTvActivity.this));LinearLayout row=(LinearLayout)itemView;row.setGravity(Gravity.CENTER_VERTICAL);
            RecyclerView.LayoutParams p=new RecyclerView.LayoutParams(-1,dp(74));p.bottomMargin=dp(7);row.setLayoutParams(p);
            main=vertical();main.setGravity(Gravity.CENTER_VERTICAL);main.setPadding(dp(16),0,dp(16),0);main.setFocusable(true);main.setDefaultFocusHighlightEnabled(false);
            main.setBackground(NativeUi.fill(NativeTvActivity.this,NativeUi.CARD,false));
            main.setOnFocusChangeListener((v,f)->main.setBackground(NativeUi.fill(NativeTvActivity.this,NativeUi.CARD,f)));
            title=NativeUi.text(NativeTvActivity.this,"",17,NativeUi.INK);title.setSingleLine(true);NativeUi.heading(title);
            detail=NativeUi.text(NativeTvActivity.this,"",12,NativeUi.MUTED);detail.setSingleLine(true);
            main.addView(title,new LinearLayout.LayoutParams(-1,dp(29)));main.addView(detail,new LinearLayout.LayoutParams(-1,dp(23)));
            row.addView(main,new LinearLayout.LayoutParams(0,-1,1));actions=add(row,"Actions",()->{});
        }
    }
    private final class QueueRows extends RecyclerView.Adapter<QueueHolder>{
        @Override public QueueHolder onCreateViewHolder(ViewGroup parent,int type){return new QueueHolder();}
        @Override public void onBindViewHolder(QueueHolder holder,int position){
            Entry entry=shownEntries().get(position);holder.name.setText((entry.current?"PLAYING  ·  ":moving.equals(entry.id)?"MOVING  ·  ":"")+entry.track.title+"\n"+entry.track.artist+"  ·  "+(entry.track.isVideoSource?"Music video":"Song"));
            holder.name.setTextColor(entry.current||moving.equals(entry.id)?NativeUi.GOLD:NativeUi.INK);
            holder.name.setOnClickListener(v->{if(moving.isEmpty())playEntry(entry.id);else commitMove();});
            holder.actions.setOnClickListener(v->queueActions(entry));
        }
        @Override public int getItemCount(){return shownEntries().size();}
    }
    private final class QueueHolder extends RecyclerView.ViewHolder{
        final Button name,actions;
        QueueHolder(){super(new LinearLayout(NativeTvActivity.this));LinearLayout row=(LinearLayout)itemView;row.setGravity(Gravity.CENTER_VERTICAL);
            RecyclerView.LayoutParams p=new RecyclerView.LayoutParams(-1,dp(62));p.bottomMargin=dp(5);row.setLayoutParams(p);
            name=NativeUi.button(NativeTvActivity.this,"",()->{});name.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);name.setSingleLine(false);name.setMaxLines(2);
            row.addView(name,new LinearLayout.LayoutParams(0,dp(52),1));actions=add(row,"Actions",()->{});
        }
    }
}
