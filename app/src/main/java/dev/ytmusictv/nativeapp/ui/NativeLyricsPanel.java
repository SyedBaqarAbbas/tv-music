package dev.ytmusictv.nativeapp.ui;

import android.content.Context;
import android.animation.ValueAnimator;
import android.provider.Settings;
import androidx.recyclerview.widget.LinearSmoothScroller;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import dev.ytmusictv.LyricsRepository;
import dev.ytmusictv.nativeapp.model.NativeTrack;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recycled native lines. The activity supplies clock events; there is no hidden polling loop. */
public final class NativeLyricsPanel extends LinearLayout {
    public interface Clock { long position(); long duration(); void seek(long position); }
    private static final Pattern TIME=Pattern.compile("\\[(\\d{1,3}):([0-5]\\d)(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET=Pattern.compile("(?im)^\\[offset:([+-]?\\d+)\\]");
    private final Handler main=new Handler(Looper.getMainLooper());
    private final TextView status;
    private RecyclerView list;
    private final Lines adapter=new Lines();
    private final Clock clock;
    private LyricsRepository repository;
    private NativeTrack track;
    private boolean visible,following=true,loading;
    private int active=-1,retry,lookupDuration;
    private long generation;
    private String loadedKey="";
    private final List<Line> lines=new ArrayList<>();

    public NativeLyricsPanel(Context context,Clock clock){
        super(context);this.clock=clock;setOrientation(VERTICAL);
        LinearLayout controls=new LinearLayout(context);
        status=NativeUi.text(context,"Play a track, then open lyrics.",13,NativeUi.MUTED);
        controls.addView(status,new LayoutParams(0,NativeUi.dp(context,42),1));
        Button follow=NativeUi.button(context,"Follow song",()->{following=true;updateClock();if(active>=0)centerActive(true);});
        controls.addView(follow,new LayoutParams(-2,NativeUi.dp(context,38)));
        Button again=NativeUi.button(context,"Retry",()->{retry++;load(true);});
        LayoutParams action=new LayoutParams(-2,NativeUi.dp(context,38));action.setMarginStart(NativeUi.dp(context,8));
        controls.addView(again,action);addView(controls,new LayoutParams(-1,NativeUi.dp(context,44)));
        list=new RecyclerView(context);list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);list.setItemAnimator(null);list.setClipToPadding(false);
        list.setPadding(NativeUi.dp(context,8),NativeUi.dp(context,24),NativeUi.dp(context,16),NativeUi.dp(context,64));
        addView(list,new LayoutParams(-1,0,1));
    }
    public void showTrack(NativeTrack selected,boolean shown){
        String previous=track==null?"":track.videoId;
        track=selected;visible=shown;
        if(track==null||!previous.equals(track.videoId))lookupDuration=0;
        if(!shown){if(list!=null)list.stopScroll();generation++;if(loading){loading=false;loadedKey="";}if(repository!=null){repository.close();repository=null;}return;}
        if(track==null){status.setText("Choose a track to see lyrics.");return;}
        if(!previous.equals(track.videoId)||!loadedKey.equals(lookupKey()))load(false);
        updateClock();
    }
    public void stop(){visible=false;list.stopScroll();generation++;if(loading){loading=false;loadedKey="";}if(repository!=null){repository.close();repository=null;}}
    private String lookupKey(){
        if(track==null)return "";
        int duration=track.durationSeconds>0?track.durationSeconds:(int)Math.round(clock.duration()/1000d);
        if(duration<1||duration>3600)duration=0;
        // Unknown → known is a new recording signature. Ignore tiny container
        // duration corrections rather than repeating network work every tick.
        if(duration>0&&(lookupDuration==0||Math.abs(duration-lookupDuration)>2))lookupDuration=duration;
        return track.videoId+"\n"+track.title+"\n"+track.artist+"\n"+track.album+"\n"+lookupDuration;
    }
    private void load(boolean force){
        if(!visible||track==null)return;
        long request=++generation;String id=track.videoId;loading=true;
        loadedKey=lookupKey();following=true;active=-1;lines.clear();adapter.notifyDataSetChanged();
        status.setText("Looking for lyrics…");
        if(repository==null)repository=new LyricsRepository(main);
        JSONObject metadata=new JSONObject();
        try{
            metadata.put("trackId",id);metadata.put("title",track.title);metadata.put("artist",track.artist);
            metadata.put("album",track.album);metadata.put("duration",lookupDuration);
            metadata.put("retryNonce",force?retry:0);
        }catch(Exception ignored){}
        repository.request(metadata,payload->{
            if(!visible||request!=generation||track==null||!id.equals(track.videoId))return;
            loading=false;String state=payload.optString("status");
            String synced=payload.optString("syncedLyrics","");
            String plain=payload.optString("plainLyrics","");
            if("synced".equals(state)&&!synced.isEmpty())parseSynced(synced);
            else if(!plain.isEmpty())for(String line:plain.split("\\r?\\n"))if(lines.size()<1000&&!line.trim().isEmpty())lines.add(new Line(-1,line.trim()));
            if(lines.isEmpty())status.setText(payload.optString("message","No lyrics found."));
            else status.setText("synced".equals(state)?"Lyrics by LRCLIB · Synchronized"
                    :payload.optBoolean("timingMatched",true)?"Lyrics by LRCLIB · Untimed":"Lyrics by LRCLIB · Untimed: different recording");
            adapter.notifyDataSetChanged();updateClock();
        });
    }
    private void parseSynced(String raw){
        long offset=0;Matcher shift=OFFSET.matcher(raw);
        if(shift.find())try{offset=Long.parseLong(shift.group(1));}catch(NumberFormatException ignored){}
        for(String text:raw.split("\\r?\\n")){
            Matcher times=TIME.matcher(text);List<Long> starts=new ArrayList<>(2);int end=0;
            while(times.find()&&starts.size()<12){
                String fraction=times.group(3);long millis=fraction==null?0:Long.parseLong((fraction+"00").substring(0,3));
                starts.add(Long.parseLong(times.group(1))*60_000+Long.parseLong(times.group(2))*1000+millis+offset);end=times.end();
            }
            String words=text.substring(end).trim();
            if(starts.isEmpty())continue;
            for(long start:starts)if(lines.size()<1000)lines.add(new Line(Math.max(0,start),words.isEmpty()?"♪":words));
        }
        lines.sort(Comparator.comparingLong(line->line.start));
    }
    public void updateClock(){
        if(!visible||lines.isEmpty())return;long at=clock.position();int next=-1;
        for(int i=0;i<lines.size();i++){if(lines.get(i).start<0)continue;if(lines.get(i).start>at)break;next=i;}
        if(next==active)return;int previous=active;active=next;
        if(previous>=0)adapter.notifyItemChanged(previous,Boolean.TRUE);if(next>=0)adapter.notifyItemChanged(next,Boolean.TRUE);
        if(following&&next>=0)centerActive(previous>=0);
    }
    private void centerActive(boolean animate){
        if(active<0||!(list.getLayoutManager() instanceof LinearLayoutManager))return;
        LinearLayoutManager layout=(LinearLayoutManager)list.getLayoutManager();
        float scale=Settings.Global.getFloat(getContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f);
        if(!animate||!ValueAnimator.areAnimatorsEnabled()||scale<=0){
            layout.scrollToPositionWithOffset(active,Math.max(0,list.getHeight()/2-NativeUi.dp(getContext(),34)));return;
        }
        LinearSmoothScroller scroller=new LinearSmoothScroller(getContext()){
            @Override public int calculateDyToMakeVisible(View view,int preference){
                return list.getPaddingTop()+(list.getHeight()-list.getPaddingTop()-list.getPaddingBottom()-view.getHeight())/2-view.getTop();
            }
            @Override protected int calculateTimeForDeceleration(int distance){return Math.max(1,Math.round(220*Math.min(5,scale)));}
        };
        scroller.setTargetPosition(active);layout.startSmoothScroll(scroller);
    }
    private static final class Line {final long start;final String text;Line(long s,String t){start=s;text=t;}}
    private final class Lines extends RecyclerView.Adapter<LineHolder>{
        @Override public LineHolder onCreateViewHolder(ViewGroup parent,int type){
            TextView text=NativeUi.text(getContext(),"",23,NativeUi.MUTED);
            text.setPadding(NativeUi.dp(getContext(),18),NativeUi.dp(getContext(),14),NativeUi.dp(getContext(),18),NativeUi.dp(getContext(),14));
            text.setFocusable(true);text.setDefaultFocusHighlightEnabled(false);
            text.setLayoutParams(new RecyclerView.LayoutParams(-1,-2));return new LineHolder(text);
        }
        @Override public void onBindViewHolder(LineHolder holder,int position){
            Line line=lines.get(position);TextView text=holder.text;text.setText(line.text);
            emphasis(holder,position,false);
            text.setBackground(NativeUi.fill(getContext(),text.hasFocus()||position==active?NativeUi.CARD:NativeUi.BG,text.hasFocus()));
            text.setOnFocusChangeListener((v,focused)->{if(focused)following=false;int index=holder.getBindingAdapterPosition();emphasis(holder,index,false);text.setBackground(NativeUi.fill(getContext(),focused||index==active?NativeUi.CARD:NativeUi.BG,focused));});
            text.setOnKeyListener((v,key,event)->{if(event.getAction()==KeyEvent.ACTION_DOWN&&(key==19||key==20))following=false;return false;});
            text.setOnClickListener(v->{following=false;if(line.start>=0)clock.seek(line.start);});
        }
        @Override public void onBindViewHolder(LineHolder holder,int position,List<Object> payloads){
            if(payloads.isEmpty()){onBindViewHolder(holder,position);return;}
            emphasis(holder,position,true);
            holder.text.setBackground(NativeUi.fill(getContext(),holder.text.hasFocus()||position==active?NativeUi.CARD:NativeUi.BG,holder.text.hasFocus()));
        }
        private void emphasis(LineHolder holder,int position,boolean animate){
            TextView text=holder.text;boolean selected=position==active;
            text.setTextColor(selected?NativeUi.INK:NativeUi.MUTED);
            float alpha=selected||text.hasFocus()?1f:.78f,scale=selected?1.025f:1f;
            text.animate().cancel();
            if(animate&&ValueAnimator.areAnimatorsEnabled())text.animate().alpha(alpha).scaleX(scale).scaleY(scale).setDuration(220).start();
            else{text.setAlpha(alpha);text.setScaleX(scale);text.setScaleY(scale);}
        }
        @Override public int getItemCount(){return lines.size();}
    }
    private static final class LineHolder extends RecyclerView.ViewHolder{
        final TextView text;LineHolder(TextView text){super(text);this.text=text;}
    }
}
