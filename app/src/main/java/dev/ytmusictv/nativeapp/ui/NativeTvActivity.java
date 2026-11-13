package dev.ytmusictv.nativeapp.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.view.PixelCopy;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
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
import dev.ytmusictv.nativeapp.playback.NativePlaybackCommands;
import dev.ytmusictv.nativeapp.playback.NativePlaybackService;
import dev.ytmusictv.nativeapp.provider.NativeMusicProvider;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Real native catalogue, session controls and recycled lyrics. No Music website is loaded. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
public final class NativeTvActivity extends Activity {
    private enum Screen { HOME, SEARCH, NOW, QUEUE }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Executor uiExecutor=command->main.post(command);
    private final ArrayDeque<Screen> back=new ArrayDeque<>(),forward=new ArrayDeque<>();
    private final EnumMap<Screen,View> retainedFocus=new EnumMap<>(Screen.class);
    private final ArrayList<NativeTrack> results=new ArrayList<>(),library=new ArrayList<>();
    private final ArrayList<Entry> entries=new ArrayList<>(),draft=new ArrayList<>();
    private NativeMusicProvider provider;
    private MediaController controller;
    private ListenableFuture<MediaController> connection;
    private LinearLayout root,nav,mini;
    private FrameLayout content,artFrame,header,mediaStage;
    private Button controlsHandle;
    private boolean chromeExpanded;
    private View homePage,searchPage,nowPage,queuePage;
    private TextView status,miniTitle,time,nowTitle,nowArtist,nowEyebrow,queueHint,playbackState;
    private EditText search;
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
    private RecyclerView resultList,libraryList,queueList;
    private final TrackRows searchRows=new TrackRows(results),libraryRows=new TrackRows(library);
    private final QueueRows queueRows=new QueueRows();
    private Screen screen=Screen.HOME;
    private NativeTrack playing;
    private boolean foreground,videoMode,lyricsShown,shuffle,background,snapshotInFlight,dragging,connected;
    private int repeat;
    private long searchGeneration,libraryGeneration;
    private String moving="",moveCurrent="",lastError="",actionError="";
    private boolean destroyed;
    private boolean seeking;
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
        super.onCreate(state);getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        provider=new NativeMusicProvider(main,()->{
            String cookies=CookieManager.getInstance().getCookie("https://music.youtube.com");
            return cookies==null?"":cookies;
        },(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0);
        buildUi();chromeExpanded=getPreferences(MODE_PRIVATE).getBoolean("nativeChromeExpanded",false);setChrome(chromeExpanded,false);
        if(state!=null){search.setText(state.getString("query",""));lyricsShown=state.getBoolean("lyrics");}
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
        homePage=createHome();searchPage=createSearch();nowPage=createNow();queuePage=createQueue();
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
        page.addView(field,new LinearLayout.LayoutParams(-1,dp(54)));
        TextView hint=NativeUi.text(this,"OK plays a result. Actions offers Play next and Add to queue.",13,NativeUi.MUTED);
        page.addView(hint,new LinearLayout.LayoutParams(-1,dp(35)));
        resultList=recycler(searchRows);page.addView(resultList,new LinearLayout.LayoutParams(-1,0,1));return page;
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
        View focused=getCurrentFocus();if(focused!=null)retainedFocus.put(screen,focused);
        hideKeyboard();
        if(record&&destination!=screen){back.addLast(screen);while(back.size()>32)back.removeFirst();forward.clear();}
        if(screen==Screen.NOW&&destination!=Screen.NOW){lyrics.stop();detachVideo();}
        if(!moving.isEmpty()&&destination!=Screen.QUEUE)cancelMove();
        screen=destination;updateVideoVisibility();mini.setVisibility(destination==Screen.NOW?View.GONE:View.VISIBLE);
        if(destination==Screen.NOW)setChrome(false,false);
        status.setVisibility(destination==Screen.NOW&&lastError.isEmpty()?View.GONE:View.VISIBLE);
        content.removeAllViews();
        View page=destination==Screen.HOME?homePage:destination==Screen.SEARCH?searchPage:destination==Screen.NOW?nowPage:queuePage;
        content.addView(page,new FrameLayout.LayoutParams(-1,-1));
        if(destination==Screen.NOW)updateNow();
        if(destination==Screen.QUEUE)requestSnapshot();
        if(destination==Screen.SEARCH&&results.isEmpty())search.requestFocus();
        else page.post(()->{View restore=retainedFocus.get(destination);if(restore!=null&&restore.isShown())focusTarget(restore);else page.requestFocus(View.FOCUS_DOWN);});
        updateTransport();
    }
    private void runSearch(){
        String query=search.getText().toString().trim();if(query.isEmpty()){search.requestFocus();openKeyboard();return;}
        hideKeyboard();long generation=++searchGeneration;status.setText("Searching “"+query+"”…");
        provider.search(query,new NativeMusicProvider.Callback<List<NativeTrack>>(){
            public void onSuccess(List<NativeTrack> tracks){
                if(destroyed||isFinishing()||generation!=searchGeneration)return;
                results.clear();results.addAll(tracks);searchRows.notifyDataSetChanged();
                status.setText(tracks.isEmpty()?"No matching tracks. Try another search.":tracks.size()+" results · select a track or open Actions");
                if(screen==Screen.SEARCH&&!results.isEmpty())focusRow(resultList,0,true,0);
            }
            public void onError(String message){if(generation==searchGeneration)status.setText(message);}
        });
    }
    private void loadLibrary(){
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
            else{search.setText(which==3&&!track.artist.isEmpty()?track.artist:track.album);show(Screen.SEARCH,true);runSearch();}
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
        updateVideoVisibility();
        nowTitle.setText(playing==null?"Nothing playing yet":playing.title);
        nowArtist.setText(playing==null?"Find a song and make it yours.":playing.artist);
        lyricsButton.setText(lyricsShown?"Hide lyrics":"Lyrics");modeButton.setText(videoMode?"Audio":"Video");
        lyrics.setVisibility(lyricsShown?View.VISIBLE:View.GONE);
        int alignment=lyricsShown?Gravity.LEFT|Gravity.CENTER_VERTICAL:Gravity.CENTER;
        nowTitle.setGravity(alignment);nowArtist.setGravity(alignment);nowEyebrow.setGravity(alignment);playbackState.setGravity(alignment);
        sizeMediaFrame();
        lyrics.showTrack(playing,screen==Screen.NOW&&lyricsShown&&foreground);
        artwork.setVisibility(videoMode?View.GONE:View.VISIBLE);video.setVisibility(videoMode?View.VISIBLE:View.GONE);
        if(screen==Screen.NOW&&foreground&&playing!=null)artwork.load(playing.thumbnailUrl);
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
    private void signIn(){hideKeyboard();startActivityForResult(new Intent(this,SignInActivity.class),74);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==74)loadLibrary();}
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
        int key=event.getKeyCode();
        if(!typing()&&!moving.isEmpty()){
            if(key==19||key==20||key==23||key==66||key==4){
                if(event.getAction()==KeyEvent.ACTION_DOWN){if(key==19||key==20)moveDraft(key==19?-1:1);else if(event.getRepeatCount()==0){if(key==4)cancelMove();else commitMove();}}
                return true;
            }
        }
        if(!typing()&&(key==KeyEvent.KEYCODE_CHANNEL_UP||key==KeyEvent.KEYCODE_CHANNEL_DOWN)){
            if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){if(key==KeyEvent.KEYCODE_CHANNEL_UP)next();else previous();}return true;
        }
        if(!typing()&&key==KeyEvent.KEYCODE_FORWARD){
            if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0&&!forward.isEmpty()){back.addLast(screen);show(forward.removeLast(),false);}return true;
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
        if(!back.isEmpty()){forward.addLast(screen);show(back.removeLast(),false);}
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
                    status.setText("Ready to listen");requestSnapshot();updateTransport();updateNow();scheduleClock();
                }catch(Exception error){status.setText("The player could not connect. Reopen Music to try again.");}
            },uiExecutor);
        }else{send(NativePlaybackCommands.APP_FOREGROUND,NativePlaybackCommands.boolArgs(true),"");requestSnapshot();updateNow();scheduleClock();}
    }
    @Override protected void onStop(){
        foreground=false;videoCaptureGeneration++;main.removeCallbacks(tick);lyrics.stop();detachVideo();updateVideoVisibility();
        if(controller!=null)send(NativePlaybackCommands.APP_FOREGROUND,NativePlaybackCommands.boolArgs(false),"");
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("query",search.getText().toString());state.putBoolean("lyrics",lyricsShown);}
    @Override protected void onDestroy(){
        destroyed=true;videoCaptureGeneration++;main.removeCallbacksAndMessages(null);provider.close();lyrics.stop();artwork.close();
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
            NativeTrack track=tracks.get(position);holder.title.setText(track.title);holder.detail.setText(track.artist+(track.durationSeconds>0?" · "+NativeUi.time(track.durationSeconds*1000L):"")+(track.isVideoSource?" · VIDEO":""));
            holder.main.setOnClickListener(v->queueTrack(track,NativePlaybackCommands.PLAY_NOW));
            holder.actions.setOnClickListener(v->trackActions(track));
            holder.main.setOnLongClickListener(v->{trackActions(track);return true;});
        }
        @Override public int getItemCount(){return tracks.size();}
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
